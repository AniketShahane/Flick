package com.flick.sender.net

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import com.flick.sender.CastServerService
import com.flick.sender.CastTransportCommands
import com.flick.sender.CastTransportSnapshot
import com.flick.sender.CastTransportState
import com.flick.sender.MediaMeta
import com.flick.sender.R
import com.flick.sender.ServerStateHolder
import com.flick.sender.ServerStatus
import com.flick.sender.SourceFault
import com.flick.sender.SourceServerTerminalKind
import com.flick.sender.preferredTerminalCode
import com.flick.sender.SubtitleServingState
import com.flick.sender.TransferTelemetry
import com.flick.sender.media.AudioDelayMemoryStore
import com.flick.sender.media.AudioDelayRecorder
import com.flick.sender.media.AudioDelayWrite
import com.flick.sender.media.LibraryFolder
import com.flick.sender.media.LibraryFolderChoice
import com.flick.sender.media.LibraryFolderId
import com.flick.sender.media.LibraryFolderStore
import com.flick.sender.media.LibraryFolders
import com.flick.sender.media.LibraryScope
import com.flick.sender.media.LibrarySort
import com.flick.sender.media.LibrarySortController
import com.flick.sender.media.LibrarySortStore
import com.flick.sender.media.MediaAccess
import com.flick.sender.media.MediaLibrary
import com.flick.sender.media.MediaLibraryLoadGate
import com.flick.sender.media.PlaybackMediaFingerprint
import com.flick.sender.media.PlaybackProgressMutation
import com.flick.sender.media.PlaybackProgressRecorder
import com.flick.sender.media.PlaybackProgressState
import com.flick.sender.media.PlaybackProgressStore
import com.flick.sender.media.PlaybackProgressWrite
import com.flick.sender.media.PlaybackResumePolicy
import com.flick.sender.media.SubtitleFiles
import com.flick.sender.media.SubtitleMemoryStore
import com.flick.sender.media.collectSettledAudioDelay
import com.flick.sender.media.rememberedAudioDelayMs
import com.flick.sender.media.rememberedSubtitle
import com.flick.sender.media.resumePositionMs
import com.flick.sender.media.VideoNamePreferenceController
import com.flick.sender.media.VideoNamePreferenceStore
import com.flick.sender.media.VideoNames
import com.flick.sender.media.labelResource
import com.flick.sender.model.CastErrorKind
import com.flick.sender.model.CastFailure
import com.flick.sender.model.ConnectionStatus
import com.flick.sender.model.DiscoveredTv
import com.flick.sender.model.MediaItem
import com.flick.sender.model.TerminalOrigin
import com.flick.sender.model.TvAvailability
import com.flick.sender.model.VideoRotation
import com.flick.sender.support.SupportCatalog
import com.flick.sender.support.SupportPromptStore
import com.flick.sender.util.FlickLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

sealed interface Route { data object Connect : Route; data object Library : Route; data object Settings : Route; data class Detail(val item: MediaItem) : Route; data object Connecting : Route; data object NowPlaying : Route; data class Failure(val kind: CastErrorKind, val failure: CastFailure) : Route }
data class PairedTv(val name: String, val host: String, val port: Int, val tvId: String)
enum class PairErrorKind {
    CODE_MISMATCH, UNREACHABLE, INVALID_QR, UPDATE_REQUIRED, INVALID_ENTRY, PAIRING_REQUIRED, LOCAL_STORAGE,
    TIMED_OUT, REJECTED, CODE_EXPIRED, TV_SURFACE, LOCKED, TV_STORAGE, REPAIR_NEEDED, ENDPOINT_CHANGED,
    REFUSED, NO_ROUTE, NO_ANSWER, NO_NETWORK, ADDRESS_UNSUPPORTED,
}

/**
 * What a finished dial leaves the pairing form able to say.
 *
 * The four dial faults are separated here rather than in [ControlTransportFailure],
 * which decides absorb-vs-rethrow for a live socket and must not carry a UI taxonomy.
 * [sameSubnet] reaches exactly one arm: silence from a TV on this phone's own /24 rules
 * the Wi-Fi out, and that is the one thing the shipped copy got backwards.
 *
 * [offNetwork] is the evidence for the opposite claim, and it is required for the same
 * reason: "this phone isn't on a Wi-Fi network" is a statement about the phone, so it may
 * be made only where the phone was actually asked.
 */
internal fun pairErrorFor(
    result: ControlClient.Result,
    sameSubnet: Boolean,
    offNetwork: Boolean,
): PairErrorKind = when (result) {
    is ControlClient.Result.Unreachable -> pairErrorForFault(result.fault, sameSubnet, offNetwork)
    // A code that left the phone means the receiver was reached and a person did not
    // answer — a different fact from a dial that never landed, and the only one of the
    // two the shipped TIMED_OUT copy is about.
    is ControlClient.Result.TimedOut ->
        if (result.pairCodeSent) PairErrorKind.TIMED_OUT else pairErrorForFault(result.fault, sameSubnet, offNetwork)
    is ControlClient.Result.RejectedByTv -> PairErrorKind.REJECTED
    // Past the upgrade every typed field is demonstrably correct, so asking the user to
    // retype three of them is the textbook wrong instruction.
    is ControlClient.Result.ProtocolError -> PairErrorKind.UPDATE_REQUIRED
    ControlClient.Result.UpdateRequired -> PairErrorKind.UPDATE_REQUIRED
    else -> PairErrorKind.INVALID_ENTRY
}

/**
 * One fault, two vocabularies: the pairing form's, and the cast error face's.
 *
 * [DialFault.NO_NETWORK] is the arm [offNetwork] exists for. It is mapped from a bare
 * `SocketException`, which also covers a VPN refusing LAN traffic, a torn-down socket and
 * an exhausted descriptor table — states a phone reaches while sitting on the Wi-Fi its
 * TV is on. Telling that user to join the network they are already on is the instruction
 * this whole taxonomy was built to stop giving.
 */
internal fun pairErrorForFault(
    fault: DialFault,
    sameSubnet: Boolean,
    offNetwork: Boolean,
): PairErrorKind = when (fault) {
    DialFault.REFUSED -> PairErrorKind.REFUSED
    DialFault.NO_ROUTE -> PairErrorKind.NO_ROUTE
    DialFault.NO_NETWORK -> if (offNetwork) PairErrorKind.NO_NETWORK else PairErrorKind.UNREACHABLE
    // Silence is the one outcome the phone cannot resolve on its own. A shared /24 is the
    // only thing that lets it rule the Wi-Fi out rather than send the user to rejoin it.
    DialFault.NO_ANSWER -> if (sameSubnet) PairErrorKind.NO_ANSWER else PairErrorKind.UNREACHABLE
    DialFault.REJECTED -> PairErrorKind.REJECTED
}

/**
 * The same fault as a local terminal code. None of these is on the wire —
 * `ControlFrameSchema.failureCodes` is an inbound allow-list — so they cost nothing
 * there and `castErrorFace` is their only consumer.
 */
internal fun controlFaultCode(fault: DialFault, offNetwork: Boolean): String = when (fault) {
    DialFault.REFUSED -> "control_refused"
    DialFault.NO_ROUTE -> "control_no_route"
    DialFault.NO_ANSWER -> "control_no_answer"
    // Same evidence rule as the pairing vocabulary above: the no-Wi-Fi face may not be
    // drawn for a `SocketException` raised on a phone that holds a link and an address.
    DialFault.NO_NETWORK -> if (offNetwork) "control_no_network" else "control_unreachable"
    // The upgrade completed and the receiver then closed. That proves the TV is awake and
    // its Flick is running, which is the one thing the residual "unreachable" face — "check
    // the TV is awake" — states the opposite of.
    DialFault.REJECTED -> "control_rejected"
}

/** A secret-free, monotonic result signal for a manual pairing form. */
internal data class ManualPairAttemptEvent(
    val startedGeneration: Long = 0L,
    val terminalGeneration: Long? = null,
)

/**
 * Keeps a manual form from mistaking a cancelled older attempt for its own result.
 *
 * The published [ManualPairAttemptEvent] changes even when the user sees the same
 * [PairErrorKind] twice, so a StateFlow collector cannot conflate away that outcome.
 */
internal class ManualPairAttemptLedger {
    private var nextGeneration = 0L
    var event: ManualPairAttemptEvent = ManualPairAttemptEvent()
        private set

    fun begin(): Long {
        val generation = ++nextGeneration
        event = ManualPairAttemptEvent(startedGeneration = generation)
        return generation
    }

    fun complete(generation: Long): Boolean {
        if (event.startedGeneration != generation) return false
        event = event.copy(terminalGeneration = generation)
        return true
    }
}
/**
 * [host]/[port] are the QR's untrusted prefill hint — never dialed without a code.
 *
 * [codeInHand] says the scanner also read the four digits off that QR, so the user
 * confirms a TV instead of typing them. The code itself is deliberately NOT a field here:
 * this value is published state that any screen may render and any dump may print, and
 * the secret lives in the coordinator's own memory until a confirmation spends it.
 */
data class PendingPairLaunch(
    val eventId: Long,
    val host: String? = null,
    val port: Int? = null,
    val codeInHand: Boolean = false,
)

/**
 * The library and everything the folder scope derives from it, published as one value.
 *
 * They travel together because they describe each other: the selected folder and its
 * scoped list must come from one read, or a screen could show one folder name above
 * another folder's media while the library refreshes.
 *
 * [items] is the whole library and stays that way — the access-level empty states are
 * decided from it, so a chosen folder can never be mistaken for a gallery Flick was
 * never let into.
 */
data class LibraryView(
    val items: List<MediaItem> = emptyList(),
    val folders: List<LibraryFolder> = emptyList(),
    val scope: LibraryScope = LibraryScope.All,
    val scoped: List<MediaItem> = emptyList(),
)
/**
 * A sideloaded subtitle the user picked, either by a one-shot SAF document pick or by
 * a filename match inside a folder they granted. [displayName] is already normalized
 * to the label that goes on the wire; [language] is a validated BCP-47 tag, or null
 * when it is genuinely unknown so the field is omitted rather than guessed.
 */
data class SelectedSubtitle(val uri: Uri, val displayName: String, val language: String?)
sealed interface CastStartState { data object Idle : CastStartState; data class ConnectingControl(val castId: String) : CastStartState; data class StartingSource(val castId: String) : CastStartState; data class AwaitingAcceptance(val castId: String) : CastStartState; data class AwaitingFirstFrame(val castId: String) : CastStartState; data class Active(val castId: String) : CastStartState; data class Failed(val castId: String, val code: String) : CastStartState }
internal data class CastRequest(val item: MediaItem, val startMs: Long, val startOver: Boolean = false)
internal data class RetryStart(val startMs: Long, val startOver: Boolean)

internal object CastRetryPolicy {
    /**
     * [durationMs] is the TV-confirmed container duration, which decides eligibility;
     * [wireDurationMs] is MediaStore's, which is what the retry's `loadMedia` actually
     * carries. The two disagree on a mis-scanned or VFR file, and the receiver answers
     * startMs > durationMs by closing the control socket — a non-retryable disconnect
     * that would cost the user the whole cast. The start is clamped to what the
     * receiver will see, never to the value that merely decided it.
     */
    fun start(
        originalStartMs: Long,
        originalStartOver: Boolean,
        active: Boolean,
        confirmedMs: Long,
        durationMs: Long,
        wireDurationMs: Long,
    ): RetryStart = if (active) {
        val resumed = PlaybackResumePolicy.eligiblePosition(confirmedMs, durationMs) ?: 0L
        RetryStart(
            if (wireDurationMs > 0L) resumed.coerceAtMost(wireDurationMs) else resumed,
            startOver = false,
        )
    } else {
        RetryStart(originalStartMs, originalStartOver)
    }
}

/**
 * Whether a transport verb sent right now would reach a TV player. An Active cast on a
 * connected socket is the only state in which one does: before that the receiver has
 * adopted no media, and after a terminal there is no cast left to command.
 *
 * The cast id is compared, not merely present: an `Active` published for a cast this
 * phone has already superseded would otherwise keep answering yes for its successor.
 */
internal fun castCommandable(
    castStart: CastStartState,
    castId: String?,
    connection: ConnectionStatus,
): Boolean = castId != null && (castStart as? CastStartState.Active)?.castId == castId &&
    connection == ConnectionStatus.CONNECTED

/**
 * Whether a failure may carry a Retry action. [retryable] is the receiver's verdict on
 * the code, but a retry is re-cast of a specific item: a terminal that lands after the
 * cast record was cleared has nothing to hand [CastCoordinator.flickToTv], and offering
 * the button then leaves the user tapping the only control on the screen for no effect.
 */
internal fun castRetryOffered(retryable: Boolean, hasCastRecord: Boolean): Boolean =
    retryable && hasCastRecord

/**
 * Terminal codes that indict the FILE rather than the link, the TV's state or this
 * phone's server — the only ones allowed to mark an item unplayable. Every member is a
 * verdict a receiver reached with the bytes in front of it: the badge they raise says a
 * TV refused this file, so nothing may enter that a TV cannot emit.
 *
 * `decoder_init` is deliberately absent: the TV has the decoder and failed to start it,
 * which is usually another app still holding it, and a file marked on that evidence would
 * be libelled for something that was never about it. `http_rejected`, `media_unreachable`
 * and their neighbours are the network having a bad minute. `source_unavailable` is not a
 * receiver code at all — `CastFailureCode` has no such constant and this phone raises it in
 * `startCast` before a byte leaves, so marking on it would badge a file for a TV that was
 * never asked.
 */
private val FileFaultCodes = setOf(
    "unsupported_container",
    "unsupported_video_codec",
    "unsupported_video_format",
    "unsupported_hdr_profile",
    "malformed_media",
)

/**
 * The one code that indicts neither the file nor the TV on its own, and both together on
 * the second try. Named because three places now have to agree on it.
 */
internal const val DecoderFaultCode = "decoder_init"

/**
 * [repeatedDecoderFault] is the second consecutive `decoder_init` for the same file with
 * no first frame in between.
 *
 * One is not evidence about the file, for the reason [FileFaultCodes] gives. Two running
 * is: a decoder another app was holding is released when that app lets go, so it does not
 * survive a fresh attempt, while a decoder this TV cannot stand up for this file's format
 * fails identically every time. Verified on the Google TV Streamer with a 4K H.264 file —
 * it failed, the TV was rebooted so nothing could still be holding a codec, and it failed
 * again the same way, while a 1080p file cast immediately afterwards. The TV's own codec
 * table declares that file supported, so no capability query would have caught it; only
 * trying it twice does.
 */
internal fun marksFileUnplayable(code: String, repeatedDecoderFault: Boolean = false): Boolean =
    code in FileFaultCodes || (repeatedDecoderFault && code == DecoderFaultCode)

/**
 * Files that have failed to find a decoder once, waiting to see whether it happens again.
 *
 * This exists because the sheet used to promise "will direct-play at full quality" on
 * every visit to a file that had never once played: `decoder_init` marks nothing, so
 * there was no evidence for the sheet to change its mind with, and the promise repeated
 * forever. A file in here has broken that promise once — enough to stop making it, not
 * enough to call the file unplayable.
 *
 * Bounded and process-lived, for the reasons [UnplayableMemory] gives about persistence.
 */
internal class DecoderFaultLedger(private val limit: Int = 32) {
    private val seen = LinkedHashSet<String>()

    /** Records a fault and answers whether this file has now failed twice running. */
    fun record(key: String): Boolean {
        if (!seen.add(key)) return true
        while (seen.size > limit) seen.remove(seen.first())
        return false
    }

    /** A frame reached the screen, so whatever was holding the decoder is gone. */
    fun forget(key: String) {
        seen.remove(key)
    }

    fun suspects(): Set<String> = seen.toSet()
}

/** A completed first-frame cast may earn the one-time invitation only from an Active session. */
internal fun supportInvitationEligibleForNormalCompletion(
    castId: String,
    currentCastId: String?,
    state: CastStartState,
): Boolean = currentCastId == castId && (state as? CastStartState.Active)?.castId == castId

/**
 * Files a receiver refused, held for the life of the process and no longer.
 *
 * Persisting this would be a much larger promise than the evidence supports: one bad
 * container read, one TV, one moment — and the file would wear the badge on every launch
 * forever, including after the user remuxed it or bought a different TV. A relaunch is
 * the cheapest amnesty there is. [limit] bounds a library the user could cast at all day;
 * the oldest verdict is the one worth forgetting first.
 */
internal class UnplayableMemory(private val limit: Int = 64) {
    private val marks = LinkedHashMap<String, String>()

    fun mark(key: String, code: String): Map<String, String> {
        marks.remove(key)
        marks[key] = code
        while (marks.size > limit) marks.remove(marks.keys.first())
        return snapshot()
    }

    /** Proof the verdict is stale — a cast of this file that reached the first frame. */
    fun clear(key: String): Map<String, String> {
        marks.remove(key)
        return snapshot()
    }

    fun snapshot(): Map<String, String> = LinkedHashMap(marks)
}

/**
 * Files a receiver played without sound, against the audio format it could not decode.
 *
 * Held for the life of the process and no longer, for the reason [UnplayableMemory] gives
 * and one more of its own: this is one file on one TV in one moment, and the sound is the
 * half of it most easily changed by something outside the app — a soundbar plugged in, a
 * Bluetooth speaker left behind, a different TV entirely. A relaunch is the cheapest
 * amnesty there is, and this fact deserves it sooner than a refusal does.
 *
 * The value is the mime the receiver named, which is `unknown` when it could not name one.
 */
internal class SilentAudioMemory(private val limit: Int = 64) {
    private val marks = LinkedHashMap<String, String>()

    fun mark(key: String, mime: String): Map<String, String> {
        marks.remove(key)
        marks[key] = mime
        while (marks.size > limit) marks.remove(marks.keys.first())
        return snapshot()
    }

    /** A fresh cast of this file, which will say so again if it is still silent. */
    fun clear(key: String): Map<String, String> {
        marks.remove(key)
        return snapshot()
    }

    fun snapshot(): Map<String, String> = LinkedHashMap(marks)
}

/** Application-scoped owner of pairing, control, service state and cast generations. */
class CastCoordinator(private val appContext: Context, private val scope: CoroutineScope) {
    val nsd = NsdDiscovery(appContext)
    val control = ControlClient(scope)
    val session = PlaybackSession(control, scope, appContext.getString(R.string.media_title_generic))
    private val haptics = FlickHaptics(appContext)
    private val store = PairingStore(appContext)
    private val supportPromptStore = SupportPromptStore(appContext)
    private val playbackProgressStore = PlaybackProgressStore(appContext, scope)
    private val playbackProgressRecorder = PlaybackProgressRecorder()
    private val audioDelayStore = AudioDelayMemoryStore(appContext, scope)
    private val audioDelayRecorder = AudioDelayRecorder()
    private val subtitleMemoryStore = SubtitleMemoryStore(appContext, scope)
    private val libraryFolderStore = LibraryFolderStore(appContext)
    private val librarySortStore = LibrarySortStore(appContext)
    private val librarySortPreference =
        LibrarySortController(librarySortStore.order(), librarySortStore::save)
    private val videoNameStore = VideoNamePreferenceStore(appContext)
    private val videoNamePreference =
        VideoNamePreferenceController(videoNameStore.simplified(), videoNameStore::save)
    private val deviceLabel = ControlProtocolV2.normalizedLabel(Build.MODEL, 80)
        ?: appContext.getString(R.string.sender_device_generic)
    private var pairingJob: Job? = null
    private var castJob: Job? = null
    private var libraryJob: Job? = null
    private var subtitleJob: Job? = null
    private var progressResolutionJob: Job? = null
    private var subtitleRecallJob: Job? = null
    private val _subtitleOwnerKey = MutableStateFlow<String?>(null)

    /**
     * Which film [selectedSubtitle] belongs to, as that film's identity and nothing else.
     *
     * The selection is one per app and not one per film: a live cast owns it, so browsing
     * to another film mid-cast leaves the casting film's subtitle attached while the screen
     * in front of the viewer is showing something else entirely. A screen that names the
     * selection has to answer that before it draws anything, and this key is all it needs
     * to — never the owning item, which is more than the question asks for.
     *
     * A flow because a recall lands after the sheet is already drawn: the copy is proven on
     * disk while the user is still reading it.
     */
    val subtitleOwnerKey: StateFlow<String?> = _subtitleOwnerKey.asStateFlow()

    // The item and not its Uri: a Uri cannot produce a fingerprint, and the subtitle
    // memory is filed under the same identity the resume checkpoint is.
    private var subtitleOwner: MediaItem? = null
        set(value) {
            field = value
            // Published from the setter rather than written beside each assignment: a key
            // that had drifted from the owner would let one film's screen name another
            // film's subtitle, which is the one thing the owner exists to prevent.
            _subtitleOwnerKey.value = value?.uriKey
        }
    private val pairingGate = PairingAttemptGate()
    private val tvNameRefreshGate = TvNameRefreshGate()
    private val manualPairAttemptLedger = ManualPairAttemptLedger()
    private val pairCodeReset = PairCodeReset()
    private val castGate = CastGenerationGate()
    private val libraryGate = MediaLibraryLoadGate()
    private var currentCastId: String? = null
    private var accepted: CompletableDeferred<JSONObject>? = null
    private var ready: CompletableDeferred<JSONObject>? = null
    private var pendingCast: CastRequest? = null
    private var retryItem: CastRequest? = null
    private var currentRequest: CastRequest? = null
    private var loadSentCastId: String? = null

    /**
     * The last address this phone actually dialed, held so a failure can be measured
     * against this phone's own /24. The authenticated endpoint is torn down by the time
     * a face composes, and the paired record's host is where the TV was last SEEN — a
     * resume may have reached it somewhere else.
     *
     * Provenance rides with the address because the measurement is worthless without it:
     * see [LanProximity.sameSubnetClaim].
     */
    private var dialedHost: DialedHost? = null
    // The four digits a scanned v4 QR carried, held here rather than in the published
    // launch so the only way to reach them is to spend them. Cleared with the launch.
    private var pendingPairCode: String? = null
    private val unplayableMemory = UnplayableMemory()
    private val silentAudioMemory = SilentAudioMemory()
    private val decoderFaults = DecoderFaultLedger()
    private val linkMonitor = LinkCapacityMonitor { SystemClock.elapsedRealtime() }

    // The last moment this phone provably put bytes on the media socket, off the same
    // 1 Hz readings the capacity monitor measures on. It is the second witness a control
    // link that has gone quiet is judged against — see [ControlRecoveryPolicy].
    private var lastServedByteAtMs = 0L
    private var controlRecoveries = 0
    private var lastControlRecoveryAtMs = 0L

    private val _route = MutableStateFlow<Route>(if (store.last() == null) Route.Connect else Route.Library)
    val route: StateFlow<Route> = _route.asStateFlow()
    private val _pendingPairLaunch = MutableStateFlow<PendingPairLaunch?>(null)
    val pendingPairLaunch: StateFlow<PendingPairLaunch?> = _pendingPairLaunch.asStateFlow()
    private val _showQualitySheet = MutableStateFlow(false); val showQualitySheet = _showQualitySheet.asStateFlow()
    private val _showDiagnostics = MutableStateFlow(false); val showDiagnostics = _showDiagnostics.asStateFlow()
    private val _showSupportSheet = MutableStateFlow(false); val showSupportSheet = _showSupportSheet.asStateFlow()
    private val _showSupportInvitation = MutableStateFlow(false); val showSupportInvitation = _showSupportInvitation.asStateFlow()
    val devices = nsd.devices
    // Read here, at construction, rather than by the screen: the first library this
    // publishes is already narrowed, so a scoped library never opens on everything and
    // then visibly drops the files the user scoped away.
    private var libraryFolder: LibraryFolderChoice? = libraryFolderStore.choice()
    private var libraryResolved = false
    private val _library = MutableStateFlow(
        LibraryView(scope = LibraryFolders.scope(libraryFolder, emptyList(), resolved = false)),
    )
    val library = _library.asStateFlow()

    /**
     * Whether the last MediaStore walk reached the end of its cursor, or null where no
     * walk has answered yet.
     *
     * The empty state and the grid's advisory both need it, and neither may infer it from
     * the list: a read that stopped partway looks exactly like a short library. Null is
     * the seed rather than false because false is itself a finding — "Android stopped
     * answering" — and a read still in flight has found nothing of the sort.
     */
    private val _libraryComplete = MutableStateFlow<Boolean?>(null)
    val libraryComplete = _libraryComplete.asStateFlow()
    /**
     * The order the grid deals its tiles in. Read at construction like the folder above, so
     * the first grid a launch paints is already in the order this phone's owner chose — a
     * library that opened newest-first and then visibly re-dealt itself would be a worse
     * answer than not remembering at all.
     *
     * The list itself is NOT sorted here: applying it is the grid's, because the scoped list
     * this publishes is what the library's search index is keyed on, and re-dealing it would
     * refold every name in the library on the tap that changed the order.
     */
    val librarySort = librarySortPreference.order
    private val _libraryLoading = MutableStateFlow(false); val libraryLoading = _libraryLoading.asStateFlow()
    private val _mediaAccess = MutableStateFlow(MediaAccess.NONE); val mediaAccess = _mediaAccess.asStateFlow()
    val connection = control.connection
    private val _connectedTv = MutableStateFlow(store.last()?.let { PairedTv(it.name, it.host, it.port, it.tvId) }); val connectedTv = _connectedTv.asStateFlow()

    /**
     * Whether this phone had already paired with some TV before this launch — read once,
     * because it is a fact about the past and nothing in this process can change it.
     *
     * The v1 half is the whole point. [connectedTv] is seeded from the v2 record alone,
     * so a phone that paired under the old host-keyed scheme and has not re-paired since
     * reads as never-paired — which would be a lie told to the one user who most
     * demonstrably knows better. A v1 record is retired only by a visible v2 pair at the
     * same host (see [PairingStore.save]), so consulting both is what makes this durable
     * across that migration rather than only after it.
     */
    val pairedBeforeThisLaunch: Boolean =
        _connectedTv.value != null || store.legacyLast() != null
    private val _pairTarget = MutableStateFlow<DiscoveredTv?>(null); val pairTarget = _pairTarget.asStateFlow()
    private val _pairError = MutableStateFlow<PairErrorKind?>(null); val pairError = _pairError.asStateFlow()
    private val _manualPairAttempt = MutableStateFlow(ManualPairAttemptEvent())
    internal val manualPairAttempt: StateFlow<ManualPairAttemptEvent> = _manualPairAttempt.asStateFlow()
    private val _connectFromLibrary = MutableStateFlow(false); val connectFromLibrary = _connectFromLibrary.asStateFlow()
    private val _castingItem = MutableStateFlow<MediaItem?>(null); val castingItem = _castingItem.asStateFlow()
    private val _castStart = MutableStateFlow<CastStartState>(CastStartState.Idle); val castStart = _castStart.asStateFlow()
    private val _castFailure = MutableStateFlow<CastFailure?>(null); val castFailure = _castFailure.asStateFlow()
    // The failed cast's item outlives the cast record the terminal tears down: the error
    // face offers to play it here, and both the tile and the detail sheet remember it.
    private val _failureItem = MutableStateFlow<MediaItem?>(null); val failureItem = _failureItem.asStateFlow()
    private val _unplayableFiles = MutableStateFlow<Map<String, String>>(emptyMap()); val unplayableFiles = _unplayableFiles.asStateFlow()
    /** Files the TV played silently, each against the audio mime it could not decode. */
    private val _silentAudioFiles = MutableStateFlow<Map<String, String>>(emptyMap()); val silentAudioFiles = _silentAudioFiles.asStateFlow()
    /** Files that have failed to find a decoder once. The sheet stops promising for these. */
    private val _decoderSuspects = MutableStateFlow<Set<String>>(emptySet()); val decoderSuspects = _decoderSuspects.asStateFlow()
    private val _selectedSubtitle = MutableStateFlow<SelectedSubtitle?>(null); val selectedSubtitle = _selectedSubtitle.asStateFlow()
    val simplifiedVideoNames = videoNamePreference.simplified
    val playback = session.state; val pulses = session.pulses

    /**
     * Whether the TV can be driven right now. The same guard the media notification
     * arms its transport with, published so an in-app surface can decide whether to
     * OFFER a control at all rather than leave one that silently does nothing.
     *
     * Written from [publishTransport], not derived from the two flows it reads: the
     * current cast id is a plain field, and a `combine` would keep answering for a
     * cast the coordinator has already torn down.
     */
    private val _commandable = MutableStateFlow(false); val commandable = _commandable.asStateFlow()

    /** The A/V nudge in force for this cast. Nothing on the wire reports it back. */
    val audioDelayMs = session.audioDelayMs
    internal val playbackProgress = playbackProgressStore.state

    /** What this phone has proven about the link carrying the live cast. Never terminal. */
    val linkVerdict: StateFlow<LinkVerdict> = linkMonitor.verdict

    /** The stalling card: raised by the rebuffer count alone, dismissible, never blocking. */
    val linkStall: StateFlow<LinkStall> = linkMonitor.stall

    // Captured at the terminal, before teardown resets the monitor: the error face has to
    // read the link as it was when the cast died, not as an idle phone reads it after.
    private val _failureLinkVerdict = MutableStateFlow<LinkVerdict>(LinkVerdict.Unknown)
    val failureLinkVerdict: StateFlow<LinkVerdict> = _failureLinkVerdict.asStateFlow()

    /**
     * Whether this phone and the TV it was talking to shared a /24 when the cast died.
     *
     * Null is the honest third answer and not a default: this phone could not place
     * itself next to the TV — no address of its own, no Wi-Fi link for the copy to make
     * its claim about, or an address it only remembered rather than met on the network it
     * is on now — so neither "you are on the same network" nor its opposite may be
     * claimed. See [sameSubnetAs]. Captured at the terminal for
     * [failureLinkVerdict]'s reason — by the time the face composes the endpoint is gone.
     */
    private val _failureSameSubnet = MutableStateFlow<Boolean?>(null)
    val failureSameSubnet: StateFlow<Boolean?> = _failureSameSubnet.asStateFlow()

    /**
     * The phone's media notification, spending the same verbs the remote does.
     *
     * Every one of these republishes synchronously, because [CastRemotePlayer] reads the
     * published snapshot back the instant a hook returns: a value that only arrived a
     * dispatch later would hand the platform the pre-command state and flicker the
     * play/pause button back to what it was.
     *
     * The guard is the same one that decides what the notification offers, restated here
     * because a hardware media key can arrive at any moment and does not consult a button.
     */
    private val transportCommands = object : CastTransportCommands {
        override fun togglePlaying() {
            if (transportCommandable()) session.togglePlayPause()
            publishTransport()
        }

        override fun setPlaying(play: Boolean) {
            if (transportCommandable() && session.state.value.playing != play) {
                session.togglePlayPause()
            }
            publishTransport()
        }

        override fun skip(deltaMs: Long) {
            if (transportCommandable()) session.skip(deltaMs)
            publishTransport()
        }

        override fun seekTo(positionMs: Long) {
            if (transportCommandable()) session.seekTo(positionMs)
            publishTransport()
        }

        override fun stop() {
            // Deliberately ungated: Stop is the source service's own teardown and has to
            // work whatever the TV is doing, which is why the notification offers it in
            // every state the others are withheld in.
            stopCast()
            publishTransport()
        }
    }

    init {
        CastTransportState.attach(transportCommands)
        scope.launch { control.frames.collect(::onFrame) }
        scope.launch { session.haptics.collect { haptics.play(it) } }
        // The offset the viewer stopped on, and none of the ones the blade or the walk
        // passed through getting there. The recorder decides whether it is worth a write;
        // this only decides when one is worth considering.
        scope.launch {
            collectSettledAudioDelay(session.audioDelayMs) { delayMs ->
                audioDelayRecorder.settled(delayMs)?.let(::enqueueAudioDelay)
            }
        }
        scope.launch {
            TransferTelemetry.samples.collect { sample ->
                // A sample that carried nothing says only that the TV's buffer was full;
                // one that carried bytes is the TV proving it is still there.
                if (sample.bytes > 0L) lastServedByteAtMs = SystemClock.elapsedRealtime()
                linkMonitor.onSample(sample)
            }
        }
        scope.launch { session.state.collect(linkMonitor::onPlayback) }
        // The TV's own frames are what move the notification's scrubber and flip its
        // play/pause face; the service re-posts only when what it renders actually changed.
        scope.launch { session.state.collect { publishTransport() } }
        // Only the transitions: Marginal republishes every second with a fresh measurement,
        // and 200 lines of ring buffer is the whole phone-side diagnostics channel.
        scope.launch {
            var lastKind: String? = null
            linkMonitor.verdict.collect { verdict ->
                val kind = verdict.javaClass.simpleName
                if (kind == lastKind) return@collect
                lastKind = kind
                val detail = when (verdict) {
                    is LinkVerdict.Proven -> " peakBps=${verdict.peakBps}"
                    is LinkVerdict.Marginal -> " measuredBps=${verdict.measuredBps} requiredBps=${verdict.requiredBps}"
                    is LinkVerdict.Starved -> " measuredBps=${verdict.measuredBps} requiredBps=${verdict.requiredBps}"
                    LinkVerdict.Unknown -> ""
                }
                FlickLog.i("cast", "link verdict=$kind$detail")
            }
        }
        scope.launch {
            ServerStateHolder.terminalEvent.collect { event ->
                event?.takeIf { it.castId == currentCastId }?.let(::onSourceTerminal)
            }
        }
        // A file that stopped being readable mid-play. It is a terminal in its own right:
        // the bytes have stopped and the TV will run out of buffer, but only this phone
        // knows why, and waiting for the receiver's guess would spend that knowledge.
        scope.launch {
            ServerStateHolder.sourceFault.collect { event ->
                event?.takeIf { it.castId == currentCastId }?.let { terminal(it.castId, it.code) }
            }
        }
        scope.launch { control.connection.collect { status ->
            if (status == ConnectionStatus.CONNECTED) session.onConnected()
            if (status == ConnectionStatus.DISCONNECTED) currentCastId?.let(::onControlLost)
            publishTransport()
        } }
        // A rename on the TV is the one fact about the receiver that reaches this phone
        // over neither channel it holds: the control wire is frozen and carries no name
        // change, and its session survives the app being backgrounded, so it is never
        // re-established on its own. An advertisement is the only cue left — a cue and
        // never the value, which is what [TvNameRefreshGate] is for.
        //
        // Combined with the cast stage and the route because a cue met while this phone is
        // busy is deferred rather than dropped, and every reason to defer it ends on one of
        // those two rather than on another advertisement. mDNS need not re-resolve a record
        // it already holds, so without them a hint met mid-cast — or met while the viewer
        // was reading the Connect screen — could wait for a rename that never comes.
        scope.launch {
            combine(nsd.devices, _castStart, _route) { discovered, _, _ -> discovered }
                .collect(::considerTvNameRefresh)
        }
    }

    /**
     * The control socket went down under a live cast.
     *
     * It used to be a terminal on sight, and that cost a viewer a film it had no business
     * costing them: Ktor's ping watchdog reports a lost link after 30 s of missing pong,
     * and the phone was still serving the TV tens of megabits a second the whole time.
     * A WebSocket that went quiet is one witness; the media socket is the other, and
     * [ControlRecoveryPolicy] is where the two are weighed. Where the bytes stopped too,
     * the TV really has gone and the terminal below is unchanged.
     *
     * Recovery is a re-cast rather than a bare reconnect, and it has to be: the receiver
     * tears its own session down the moment this socket closes
     * (`SessionController.onControlLost`), so there is no playback left on the far end for
     * a re-established link to re-adopt. What it costs the viewer is the re-buffer a seek
     * costs; what it saves them is the film.
     */
    private fun onControlLost(castId: String) {
        val nowMs = SystemClock.elapsedRealtime()
        val serving = ControlRecoveryPolicy.mediaPathServing(lastServedByteAtMs, nowMs)
        val request = currentRequest
        val pairing = _connectedTv.value?.let { store.get(it.tvId) }?.takeIf { !it.needsRepair }
        val attempt = ControlRecoveryPolicy.attempt(controlRecoveries, lastControlRecoveryAtMs, nowMs)
        val recovers = ControlRecoveryPolicy.recovers(
            reachedActive = (_castStart.value as? CastStartState.Active)?.castId == castId,
            mediaServing = serving,
            canDial = request != null && pairing != null,
            attempt = attempt,
        )
        FlickLog.w("cast", "control lost castIdFp=${FlickLog.fp(castId)} serving=$serving attempt=$attempt recovering=$recovers")
        if (!recovers || request == null) {
            // Retryable now, whatever the reason. A link that dropped says nothing about
            // the file, and the error face has to be able to offer the film back — which
            // for this code it could not, so the cast simply ended.
            terminal(castId, "control_disconnected", retryable = true)
            return
        }
        controlRecoveries = attempt
        lastControlRecoveryAtMs = nowMs
        val playback = session.state.value
        val resumed = CastRetryPolicy.start(
            originalStartMs = request.startMs,
            originalStartOver = request.startOver,
            active = true,
            confirmedMs = playback.confirmedMs,
            durationMs = playback.durationMs,
            wireDurationMs = request.item.durationMs,
        )
        // The face before the dial: this cast is being re-established rather than failing,
        // and the connecting screen is the only one that says so.
        publishCastStart(CastStartState.ConnectingControl(castId))
        _route.value = Route.Connecting
        // Then the dead cast, in full, BEFORE the re-dial. Every failure path inside a
        // resume assumes this coordinator holds no cast, and one left standing would keep
        // a foreground server and a media notification alive behind an error face. No
        // remote stop is sent: the socket that would carry it is the one that just went.
        cleanup(castId, clearStart = false, stopRemoteIfLoaded = false)
        flickToTv(request.copy(startMs = resumed.startMs, startOver = resumed.startOver))
    }

    private fun transportCommandable(): Boolean =
        castCommandable(_castStart.value, currentCastId, control.connection.value)

    /**
     * This phone's own site-local address, or null.
     *
     * Null is proof there is no LAN to cast over. A non-null value proves the opposite
     * only in the weak sense [LanProximity] documents — a cellular rmnet 10/8 address
     * satisfies it — so nothing here may turn a non-null into a claim about Wi-Fi.
     */
    private fun ownLanIpv4(): String? = com.flick.sender.NetworkUtils.getSiteLocalIpv4()

    /**
     * Whether this phone and [dialed] sit on one /24, or null where that cannot be claimed.
     *
     * The Wi-Fi test is [LanProximity]'s documented limit rather than belt-and-braces: a
     * cellular rmnet 10/8 address satisfies the /24 comparison against an ISP-default TV
     * address, and every body this feeds rules the Wi-Fi out BY NAME. A phone that is not
     * on Wi-Fi at all is exactly the phone for which "join the right network" is the fix,
     * so it must reach the hedged copy rather than the copy that rules the network out.
     *
     * It cannot rule out a second network that happens to share the same /24 — a hotspot
     * or a neighbour's AP on the same consumer-router default range — because nothing
     * readable without a location permission distinguishes those. The claim stays "on the
     * same network", never "the network is fine". A remembered address is not even that
     * much, which is the gate [LanProximity.sameSubnetClaim] holds.
     */
    private fun sameSubnetAs(dialed: DialedHost?): Boolean? {
        if (com.flick.sender.NetworkUtils.getWifiLinkInfo(appContext) == null) return null
        return LanProximity.sameSubnetClaim(ownLanIpv4(), dialed)
    }

    /**
     * Whether this phone provably holds neither a Wi-Fi link nor a LAN address — the only
     * state in which "this phone isn't on a Wi-Fi network" is a fact rather than a guess
     * about whatever a bare `SocketException` meant.
     */
    private fun offNetwork(): Boolean =
        com.flick.sender.NetworkUtils.getWifiLinkInfo(appContext) == null && ownLanIpv4() == null

    /**
     * Republish what the media notification may say and do. Cheap and idempotent — a
     * `MutableStateFlow` drops an equal value — so every edge that could change it calls
     * this rather than each of them deciding whether it needed to.
     */
    private fun publishTransport() {
        val castId = currentCastId
        val item = _castingItem.value
        _commandable.value = transportCommandable()
        if (castId == null || item == null) {
            CastTransportState.publish(null)
            return
        }
        val playback = session.state.value
        CastTransportState.publish(
            CastTransportSnapshot(
                castId = castId,
                // The name the library shows, under the live preference — never the
                // release filename the wire and the sidecar matcher still use.
                title = displayedVideoName(item.name).ifBlank {
                    appContext.getString(R.string.media_title_generic)
                },
                deviceName = _connectedTv.value?.name,
                // MediaStore's duration is all there is until the TV confirms the
                // container's, and a scrubber needs one before the first frame lands.
                durationMs = if (playback.durationMs > 0L) playback.durationMs else item.durationMs.coerceAtLeast(0L),
                positionMs = playback.targetMs,
                bufferedMs = playback.bufferedMs,
                playing = playback.playing,
                phase = playback.phase,
                headHeld = playback.skipping || playback.scrubbing,
                commandable = transportCommandable(),
            ),
        )
    }

    fun onStart() { nsd.start() }
    fun selectSimplifiedVideoNames(simplified: Boolean) =
        videoNamePreference.select(simplified)

    fun onMediaAccess(access: MediaAccess) {
        val load = libraryGate.begin(access)
        libraryJob?.cancel()
        libraryJob = null
        _mediaAccess.value = access
        if (access == MediaAccess.NONE) {
            // No query is run here, so the empty list that follows is not evidence about
            // this phone's storage and must not be allowed to convict the stored folder.
            libraryResolved = false
            _libraryComplete.value = null
            publishLibrary(emptyList())
            _libraryLoading.value = false
            return
        }
        _libraryLoading.value = true
        libraryJob = scope.launch {
            val read = MediaLibrary.query(appContext)
            libraryGate.runIfLatest(load) {
                // Assigned from this read, never latched: "that folder is gone" is a claim
                // about the folders published alongside it, and a read that failed partway
                // is missing exactly the rows the newest-first sort put last. The rows it
                // did get are still published — withheld is the verdict, not the library.
                libraryResolved = read.complete
                _libraryComplete.value = read.complete
                publishLibrary(read.items)
                _libraryLoading.value = false
                libraryJob = null
            }
        }
    }
    fun refreshMediaLibrary() {
        _mediaAccess.value.takeIf { it != MediaAccess.NONE }?.let(::onMediaAccess)
    }

    /**
     * Narrow the library to one folder, or to everything when [folder] is null — which
     * is also the repair offered when the chosen folder has gone.
     *
     * A view concern and nothing more: no cast, no served file and no resume is keyed
     * on it, and the subtitle sidecar folder is a separate grant this never reads.
     */
    fun chooseLibraryFolder(folder: LibraryFolder?) {
        val choice = folder?.let { LibraryFolderChoice(LibraryFolderId.Path(it.id), it.name) }
        if (choice == libraryFolder) return
        libraryFolder = choice
        // Applied whether or not the write lands: a preference file that refused the
        // commit costs the choice at the next launch, which is not a reason to ignore
        // the tap the user just made.
        if (choice == null) libraryFolderStore.clear() else libraryFolderStore.save(choice)
        publishLibrary(_library.value.items)
    }

    /**
     * Re-deal the grid. A view concern like the folder scope, and a smaller one: nothing is
     * re-read, nothing is re-derived, and no cast, served file or resume is keyed on it.
     */
    fun chooseLibrarySort(order: LibrarySort) = librarySortPreference.select(order)

    private fun publishLibrary(items: List<MediaItem>) {
        val folders = LibraryFolders.derive(items, MediaItem::relativePath, MediaItem::bucketId)
        val folderScope = LibraryFolders.scope(libraryFolder, folders, libraryResolved)
        // A choice stored before the chooser had a tree names a MediaStore bucket and
        // nothing else. This is the one moment the phone can prove which folder that was,
        // so the record is rewritten here rather than resolved through the bucket for
        // ever — the folder the user picked may itself be a parent, which has no bucket.
        LibraryFolders.migration(libraryFolder, folderScope)?.let { migrated ->
            libraryFolder = migrated
            libraryFolderStore.save(migrated)
        }
        _library.value = LibraryView(
            items = items,
            folders = folders,
            scope = folderScope,
            scoped = LibraryFolders.scoped(items, folderScope, MediaItem::relativePath, MediaItem::bucketId),
        )
    }
    fun openConnect() { nsd.start(); _connectFromLibrary.value = true; _route.value = Route.Connect }
    fun openLibrary() { _route.value = Route.Library }
    fun openSettings() { _route.value = Route.Settings }
    fun openDetail(item: MediaItem) {
        // A sideloaded subtitle belongs to the title it was picked for. Browsing to a
        // different one drops it, but only while nothing is casting: a live cast owns
        // the served subtitle and must not lose it because the user opened Library.
        if (currentCastId == null && subtitleOwner?.uri != item.uri) {
            // Dropped before the new owner is published, which is the order startCast
            // already keeps: the selection and its owner are two flows, so a reader that
            // catches them mid-swap has to find no selection rather than the previous
            // film's under this film's name.
            _selectedSubtitle.value = null
            subtitleOwner = item
            recallSubtitle(item)
        }
        _route.value = Route.Detail(item)
    }

    /**
     * Put back the subtitle this film was last watched with, while the sheet is being read.
     *
     * The half of the recall that costs nothing: the copy is proven on disk while the user
     * is still looking at the sheet, so a film opened with nothing casting reaches the
     * Flick button already carrying its cues. It deliberately does not run while a cast is
     * live, because that cast owns the selection — which is why [recalledSubtitleFor]
     * exists to answer for the film that is about to replace it.
     *
     * The record is read WITHOUT waiting, for the audio delay's reason: the store starts
     * reading at launch and fails open to Ready, and a recall that lost a race with the
     * disk on one launch is a subtitle the viewer re-attaches in one tap — while holding
     * a screen transition for a decoration is a stutter they see every time.
     */
    private fun recallSubtitle(item: MediaItem) {
        subtitleRecallJob?.cancel()
        val fingerprint = PlaybackMediaFingerprint.of(item)
        val record = rememberedSubtitle(subtitleMemoryStore.state.value, fingerprint) ?: return
        subtitleRecallJob = scope.launch {
            val uri = subtitleMemoryStore.recall(fingerprint, record) ?: return@launch
            // The film may have been navigated away from, or a cast started, while the
            // copy was being proven — a stale recall must never land on either.
            if (currentCastId != null || subtitleOwner?.uri != item.uri) return@launch
            if (_selectedSubtitle.value != null) return@launch
            _selectedSubtitle.value = SelectedSubtitle(uri, record.displayName, record.language)
            FlickLog.i("cast", "subtitle recalled lang=${record.language ?: "unknown"}")
        }
    }

    /**
     * The subtitle [item] was last watched with, for a cast that is starting right now.
     *
     * [openDetail] cannot answer for this one. A live cast owns the served selection, so
     * browsing to another film mid-cast leaves that film's memory unread — which is the
     * ordinary way of switching films, not a corner: cast one, open Library, open the next,
     * press Flick. Without this it would be cast bare and its subtitle would only come back
     * on some later launch that happened to open its sheet first.
     *
     * The record is read from the StateFlow WITHOUT waiting, exactly as the remembered
     * audio delay in [startCast] is. What this does add to the startup path is a stat and a
     * length on an app-private file, and not the provider round-trip that rule exists to
     * keep out of it.
     */
    private suspend fun recalledSubtitleFor(
        item: MediaItem,
        castId: String,
        generation: Long,
    ): SelectedSubtitle? {
        val fingerprint = PlaybackMediaFingerprint.of(item)
        val record = rememberedSubtitle(subtitleMemoryStore.state.value, fingerprint) ?: return null
        val uri = subtitleMemoryStore.recall(fingerprint, record) ?: return null
        // The cast may have been superseded while the copy was being proven, and a stale
        // recall must land on neither the file being served nor the selection the sheet
        // shows — including one the viewer picked themselves in the meantime.
        if (!castGate.isCurrent(castId, generation) || currentCastId != castId) return null
        if (_selectedSubtitle.value != null) return null
        val selection = SelectedSubtitle(uri, record.displayName, record.language)
        // Published as well as served: this is what the sheet names and what clearSubtitle
        // takes back off, so a recall the viewer cannot see is one they cannot refuse.
        _selectedSubtitle.value = selection
        FlickLog.i("cast", "subtitle recalled lang=${record.language ?: "unknown"}")
        return selection
    }

    /**
     * Adopt [uri] as the external subtitle for the next (or current) cast. While a
     * cast is Active this re-arms the `/s/{token}` capability and re-issues the load
     * so the TV attaches the track; the load resumes at the position the TV last
     * confirmed, so the swap costs a re-buffer and nothing else.
     */
    fun selectSubtitle(uri: Uri, displayName: String, language: String?) {
        // A pick outranks a recall that is still proving its copy on disk.
        subtitleRecallJob?.cancel()
        val selection = SelectedSubtitle(
            uri,
            ControlProtocolV2.normalizedLabel(displayName, ControlProtocolV2.SUBTITLE_LABEL_MAX)
                ?: appContext.getString(R.string.subtitle_label_generic),
            ControlProtocolV2.languageTag(language),
        )
        // The sheet names the casting item as what it is matching against, so that is
        // the title this pick belongs to; openDetail cannot bind it while a cast owns
        // the selection.
        _castingItem.value?.let { subtitleOwner = it }
        _selectedSubtitle.value = selection
        // Neither the URI nor the file name is ever logged: a subtitle path names the
        // film and the user's storage layout.
        FlickLog.i("cast", "subtitle selected lang=${selection.language ?: "unknown"}")
        retargetSubtitle(selection)
        rememberSubtitle(uri, displayName, selection)
    }

    /**
     * Keep a copy of this pick so the film can be opened with it again.
     *
     * [uri] is handed to the serving path above untouched and this copies it a second
     * time, rather than the two sharing one file. The working path carries a 4K
     * direct-play and it already reads a source both the picker and OpenSubtitles proved
     * readable; making it depend on a copy that could still be running would put an IO
     * step this feature owns in front of the one thing the app exists to do.
     *
     * The extension comes from the RAW name and not from the stored label: the label went
     * through `normalizedLabel`, which truncates, and a name long enough to lose its
     * extension there would be remembered as a file Media3 cannot parse.
     */
    private fun rememberSubtitle(uri: Uri, displayName: String, selection: SelectedSubtitle) {
        val owner = subtitleOwner ?: return
        val extension = SubtitleFiles.extensionOf(displayName)
            ?.takeIf { it in SubtitleFiles.SubtitleExtensions } ?: return
        // Launched and not held: the store serialises its own mutations in call order, and
        // cancelling one from here is what would let a later removal overtake it. The write
        // waits on the copy — a copy that fails writes no record — and neither outcome is
        // ever visible to the film being served.
        scope.launch {
            subtitleMemoryStore.remember(
                fingerprint = PlaybackMediaFingerprint.of(owner),
                source = uri,
                displayName = selection.displayName,
                language = selection.language,
                extension = extension,
            )
        }
    }

    /** Drop the external subtitle and revoke its token so the old `/s/{token}` 404s. */
    fun clearSubtitle() {
        subtitleRecallJob?.cancel()
        if (_selectedSubtitle.value == null) return
        _selectedSubtitle.value = null
        FlickLog.i("cast", "subtitle cleared")
        retargetSubtitle(null)
        forgetSubtitle()
    }

    /**
     * Detaching is the audio delay's in-sync: the absence of a record, not a record of
     * an absence. A viewer who takes the subtitle off is saying do not bring it back.
     *
     * Only this path forgets. [openDetail] and [startCast] drop `_selectedSubtitle` by
     * direct assignment rather than through [clearSubtitle], which is exactly why browsing
     * to another film — or casting one that owns no selection — leaves the memory alone.
     */
    private fun forgetSubtitle() {
        val owner = subtitleOwner ?: return
        scope.launch { subtitleMemoryStore.forget(PlaybackMediaFingerprint.of(owner)) }
    }

    private fun retargetSubtitle(selection: SelectedSubtitle?) {
        val castId = currentCastId ?: return
        val item = _castingItem.value ?: return
        // Only a cast that already reached Active has a load to re-issue; a startup
        // still in flight picks the selection up from startCast instead.
        if (_castStart.value !is CastStartState.Active) return
        subtitleJob?.cancel()
        subtitleJob = scope.launch {
            val before = SubtitleServingState.revision()
            CastServerService.setSubtitle(appContext, castId, selection?.uri)
            val served = withTimeoutOrNull(SUBTITLE_RETARGET_MS) {
                SubtitleServingState.state.first { it != null && it.castId == castId && it.revision > before }
            }
            if (currentCastId != castId || _castStart.value !is CastStartState.Active) return@launch
            val server = ServerStateHolder.state.value
            val videoUrl = server.videoUrl?.takeIf { server.castId == castId } ?: return@launch
            // A retarget that never landed leaves the load with no subtitle rather than
            // a URL that would 404 on the TV; the video is never at risk either way.
            val subUrl = subtitleUrlFor(videoUrl, served?.url)
            val title = ControlProtocolV2.normalizedLabel(displayedVideoName(item.name), 200)
                ?: appContext.getString(R.string.media_title_generic)
            // MediaStore's duration and the container's can disagree by a frame, so near
            // the end of a film the TV-confirmed position can exceed the value that goes
            // on the wire as durationMs. The receiver answers startMs > durationMs by
            // closing the control socket, which would cost the user the whole cast.
            val resumeMs = session.state.value.confirmedMs.coerceAtLeast(0L)
                .let { if (item.durationMs > 0L) it.coerceAtMost(item.durationMs) else it }
            control.armLoadSubtitle(castId, subUrl, selection?.displayName, selection?.language)
            linkMonitor.onReload()
            session.loadMedia(castId, videoUrl, title, item.durationMs, resumeMs)
        }
    }

    /** The subtitle URL is only ever emitted when it shares the media URL's origin. */
    private fun subtitleUrlFor(videoUrl: String, subUrl: String?): String? {
        if (subUrl == null) return null
        if (ControlProtocolV2.sameHttpOrigin(videoUrl, subUrl)) return subUrl
        FlickLog.w("cast", "subtitle dropped reason=origin")
        return null
    }
    fun back() {
        when (SenderNavigationPolicy.backDisposition(_route.value, _connectFromLibrary.value)) {
            BackDisposition.SYSTEM -> Unit
            BackDisposition.CANCEL_CAST -> cancelCast()
            BackDisposition.CLOSE_PAIRING -> {
                cancelPairing()
                _route.value = Route.Library
            }
            BackDisposition.SHOW_LIBRARY -> _route.value = Route.Library
        }
    }
    fun minimizeNowPlaying() {
        if (_route.value == Route.NowPlaying && canRestoreNowPlaying()) _route.value = Route.Library
    }
    fun restoreNowPlaying() {
        if (canRestoreNowPlaying()) _route.value = Route.NowPlaying
    }
    fun toggleQualitySheet(show: Boolean) {
        _showQualitySheet.value = show
        if (show) {
            _showDiagnostics.value = false
            _showSupportSheet.value = false
        }
    }
    fun toggleDiagnostics(show: Boolean) {
        _showDiagnostics.value = show
        if (show) {
            _showQualitySheet.value = false
            _showSupportSheet.value = false
        }
    }
    fun toggleSupportSheet(show: Boolean) {
        _showSupportSheet.value = show
        if (show) {
            _showQualitySheet.value = false
            _showDiagnostics.value = false
            dismissSupportInvitation()
        }
    }
    fun dismissSupportInvitation() { _showSupportInvitation.value = false }

    /**
     * A launch this app did not read itself — a deep link from any installed app, or a
     * browser hand-off. [IncomingPairEvent] has no field a code could arrive in, which is
     * how this ingress stays incapable of auto-pairing: whatever a v4 QR carried, the
     * endpoint is a prefill and the user still types the digits off the TV.
     */
    fun acceptPairLaunch(event: IncomingPairEvent) =
        applyPairLaunch(event.eventId, event.result, code = null)

    /**
     * The in-app scanner's ingress, and the only one that may arrive holding a code: the
     * camera is in this process, so the payload came off a QR that was in front of the
     * user rather than out of an Intent any app on the phone can fire.
     */
    fun acceptScannedPair(eventId: Long, scanned: ScannedPairLaunch) =
        applyPairLaunch(eventId, scanned.result, scanned.code)

    private fun applyPairLaunch(eventId: Long, result: PairLaunchParseResult, code: String?) {
        invalidatePairingAttempt()
        val launch = result as? PairLaunchParseResult.Valid
        _pairError.value = if (launch != null) null else PairErrorKind.INVALID_QR
        // A code is only ever held alongside the endpoint it was printed with, so a stale
        // one cannot outlive its launch and be spent against the next TV.
        pendingPairCode = code?.takeIf { launch?.host != null && launch.port != null }
        _pendingPairLaunch.value = launch?.let {
            PendingPairLaunch(eventId, it.host, it.port, codeInHand = pendingPairCode != null)
        }
        FlickLog.i("pair", "launch result=${result.javaClass.simpleName} hasEndpoint=${launch?.host != null} hasCode=${pendingPairCode != null} eventId=$eventId")
        _pairTarget.value = null
        // invalidatePairingAttempt() is already a no-op while authenticated, so a QR
        // scanned mid-cast must not yank the user out of playback either.
        if (_route.value != Route.NowPlaying && _route.value != Route.Connecting && currentCastId == null) {
            _route.value = Route.Connect
        }
    }

    /**
     * Spend the code the scanned QR carried, against the endpoint it was printed beside.
     * The confirmation the user tapped is the authorisation, so this goes through the
     * same submission every typed pairing does rather than around it.
     *
     * A failed attempt leaves both in place: the card stays up carrying the reason, and
     * the one thing that answers "that code is no longer current" is scanning the TV
     * again — which arrives as a new launch and replaces this one wholesale.
     */
    fun confirmScannedPair(eventId: Long) {
        val launch = _pendingPairLaunch.value?.takeIf { it.eventId == eventId } ?: return
        val host = launch.host ?: return
        val port = launch.port ?: return
        val code = pendingPairCode ?: return
        submitTvDisplayedPair(eventId, host, port.toString(), code)
    }

    fun dismissPairLaunch(eventId: Long) {
        if (_pendingPairLaunch.value?.eventId == eventId) {
            invalidatePairingAttempt()
            clearPendingLaunch()
        }
    }

    /** The launch and the code it arrived with are one value; neither outlives the other. */
    private fun clearPendingLaunch() {
        pendingPairCode = null
        _pendingPairLaunch.value = null
    }

    /** Discovery is advisory until a stored key completes a proof. */
    fun selectDevice(tv: DiscoveredTv) {
        if ((tv.protocolVersion ?: 0) < ControlProtocolV2.VERSION) { _pairError.value = PairErrorKind.UPDATE_REQUIRED; _route.value = Route.Connect; return }
        val paired = store.last()?.takeIf { it.tvId == tv.tvId && !it.needsRepair }
        if (paired != null) { resume(paired); return }
        // The tapped row is the only component that knows the TV's live control
        // endpoint; keep it so the code sheet can dial it without the user retyping.
        if (tv.tvId != null) {
            _pairTarget.value = tv; _pairError.value = null
        } else {
            _pairTarget.value = null; _pairError.value = PairErrorKind.PAIRING_REQUIRED
        }
        _route.value = Route.Connect
    }
    fun cancelPairing() {
        invalidatePairingAttempt(); clearPendingLaunch(); _pairTarget.value = null; _pairError.value = null
    }

    /**
     * Pairs with the exact TV record the user confirmed in the code sheet. Discovery is
     * re-snapshotted immediately before dialing so a receiver that rebound between the
     * tap and the typed code is still reached — but only the PORT may move. mDNS is
     * unauthenticated, so re-deriving the host from it would let a LAN advertiser that
     * claims the same service name / tvId collect a code the user authorized for a
     * different address; the confirmed host is the only one ever dialed.
     */
    fun submitDiscoveredPair(tv: DiscoveredTv, code: String) {
        val tvId = tv.tvId
        val reject = when {
            tvId == null -> "device"
            !PairLaunch.isCanonicalIpv4(tv.host) || tv.port !in 1..65535 -> "endpoint"
            !PairLaunch.isCode(code) -> "code"
            else -> null
        }
        if (reject != null || tvId == null) {
            FlickLog.w("pair", "submit rejected reason=$reject")
            // Nothing was reached because nothing was dialed. An advertised endpoint this
            // build cannot use is a fact about the address, never about the TV's presence.
            _pairError.value = when (reject) {
                "code" -> PairErrorKind.INVALID_ENTRY
                "endpoint" -> PairErrorKind.ADDRESS_UNSUPPORTED
                else -> PairErrorKind.PAIRING_REQUIRED
            }
            return
        }
        val attempt = beginPairingAttempt(); _pairError.value = null
        pairingJob = scope.launch {
            val fresh = nsd.refresh(tvId)
            if (fresh != null && fresh.host != tv.host) {
                // The advertisement moved hosts under the open sheet. Re-present it at the
                // new address for an explicit second confirmation instead of dialing it.
                FlickLog.w("pair", "submit aborted reason=host_changed")
                if (pairingGate.isCurrent(attempt)) {
                    if (PairLaunch.isCanonicalIpv4(fresh.host) && fresh.port in 1..65535) _pairTarget.value = fresh
                    _pairError.value = PairErrorKind.ENDPOINT_CHANGED
                }
                return@launch
            }
            // Only a record still at the confirmed host may supply a rebound port.
            val port = fresh?.port?.takeIf { it in 1..65535 } ?: tv.port
            FlickLog.i("pair", "submit host=${tv.host} port=$port codeLen=${code.length}")
            runPairing(attempt, tv.host, port, code)
        }
    }

    fun submitTvDisplayedPair(
        eventId: Long,
        host: String,
        port: String,
        code: String,
        manualSubmission: Boolean = false,
    ): Long? {
        if (eventId != 0L && _pendingPairLaunch.value?.eventId != eventId) return null
        // The code itself is never logged, at any level: a 4-digit secret is trivially
        // recovered from a hash, so only its length is recorded.
        FlickLog.i("pair", "submit host=$host port=$port codeLen=${code.length}")
        val reject = when {
            !PairLaunch.isCanonicalIpv4(host) -> "host"
            !PairLaunch.isCanonicalPort(port) -> "port"
            !PairLaunch.isCode(code) -> "code"
            else -> null
        }
        if (reject != null) {
            FlickLog.w("pair", "submit rejected reason=$reject")
            _pairError.value = PairErrorKind.INVALID_ENTRY
            return null
        }
        val attempt = beginPairingAttempt()
        val manualGeneration = if (manualSubmission) beginManualPairAttempt() else null
        _pairError.value = null
        pairingJob = scope.launch { runPairing(attempt, host, port.toInt(), code, manualGeneration) }
        return manualGeneration
    }

    private suspend fun runPairing(
        attempt: Long,
        host: String,
        port: Int,
        code: String,
        manualGeneration: Long? = null,
    ) {
        val legacyAtExactHost = store.legacyForHost(host)
        // Live either way: a first pair reaches here off an advertisement this phone just
        // re-resolved, or off an address a person is reading from the TV's own screen as
        // they spend it. Neither is a memory of where the TV used to be.
        dialedHost = DialedHost(host, liveVerified = true)
        val sameSubnet = sameSubnetAs(dialedHost) == true
        val first = control.pair(host, port, deviceLabel, code)
        // Refused before a single byte of the code left the phone is the dead-port
        // fingerprint of a receiver that rebound. Retry the SAME host only — a rogue
        // advertiser must never be able to redirect a first pair to another address.
        val result = if (first is ControlClient.Result.Unreachable && !first.pairCodeSent) {
            retryAtSameHost(host, port, code) ?: first
        } else first
        FlickLog.i("pair", "result=${result.javaClass.simpleName}")
        if (!pairingGate.isCurrent(attempt)) return
        when (result) {
            is ControlClient.Result.Paired -> {
                persistPaired(result.key, result.endpoint, host, result.endpoint.port, legacyAtExactHost?.host)?.let { pairing ->
                    _connectedTv.value = PairedTv(pairing.name, host, pairing.port, pairing.tvId)
                    clearPendingLaunch(); _pairTarget.value = null; _route.value = Route.Library
                }
                finishManualPairAttempt(manualGeneration)
            }
            is ControlClient.Result.PairedBusy -> {
                val pairing = persistPaired(result.key, result.endpoint, host, result.endpoint.port, legacyAtExactHost?.host)
                if (pairing != null) {
                    _connectedTv.value = PairedTv(pairing.name, host, pairing.port, pairing.tvId)
                    clearPendingLaunch(); _pairTarget.value = null
                    if (PairResultPolicy.clearCode(result)) clearEnteredCode()
                    publishBusyFailure()
                }
                finishManualPairAttempt(manualGeneration)
            }
            is ControlClient.Result.Denied -> {
                if (PairResultPolicy.clearCode(result)) clearEnteredCode()
                applyDenied(result.reason)
                finishManualPairAttempt(manualGeneration)
            }
            ControlClient.Result.UpdateRequired -> {
                _pairError.value = PairErrorKind.UPDATE_REQUIRED
                finishManualPairAttempt(manualGeneration)
            }
            is ControlClient.Result.Unreachable,
            is ControlClient.Result.TimedOut,
            is ControlClient.Result.RejectedByTv,
            is ControlClient.Result.ProtocolError,
            -> {
                if (PairResultPolicy.clearCode(result)) clearEnteredCode()
                _pairError.value = pairErrorFor(result, sameSubnet, offNetwork())
                finishManualPairAttempt(manualGeneration)
            }
            ControlClient.Result.Busy -> {
                if (PairResultPolicy.clearCode(result)) clearEnteredCode()
                publishBusyFailure()
                finishManualPairAttempt(manualGeneration)
            }
            else -> {
                _pairError.value = PairErrorKind.INVALID_ENTRY
                finishManualPairAttempt(manualGeneration)
            }
        }
    }

    /**
     * Same-host-only rebind sweep, bounded in both candidate count and wall time.
     *
     * The budget gates STARTING another candidate rather than wrapping the whole sweep,
     * and the difference is load-bearing now that a first pair can stop on a person: a
     * candidate that reached the receiver is no longer a search, it is the attempt, and
     * an enclosing wall clock would cancel it mid-confirmation and report the TV as
     * unreachable while its own screen was still asking about this phone. Each candidate
     * carries its own six-second dial bound inside `ControlClient.open`, so the sweep
     * stays bounded either way — it just no longer cuts across an attempt that landed.
     */
    private suspend fun retryAtSameHost(host: String, typedPort: Int, code: String): ControlClient.Result? {
        val deadlineMs = System.nanoTime() / 1_000_000L + PAIR_REBIND_BUDGET_MS
        var ports = rebindPorts(host, typedPort)
        if (ports.isEmpty()) {
            withTimeoutOrNull(NSD_RESNAPSHOT_MS) {
                devices.first { list -> list.any { it.host == host && it.port != typedPort } }
            }
            ports = rebindPorts(host, typedPort)
        }
        var last: ControlClient.Result? = null
        for (candidate in ports) {
            if (System.nanoTime() / 1_000_000L >= deadlineMs) break
            val outcome = control.pair(host, candidate, deviceLabel, code)
            FlickLog.i("ws", "pair candidate $host:$candidate -> ${outcome.javaClass.simpleName}")
            last = outcome
            if (outcome !is ControlClient.Result.Unreachable || outcome.pairCodeSent) return outcome
        }
        return last
    }

    private fun rebindPorts(host: String, typedPort: Int): List<Int> =
        devices.value.filter { it.host == host && it.port != typedPort && it.port in 1..65535 }
            .map { it.port }.distinct().sorted().take(MAX_PAIR_REBIND_CANDIDATES)

    private fun applyDenied(reason: String?) {
        if (reason == "busy") { publishBusyFailure(); return }
        _pairError.value = when (reason) {
            "expired" -> PairErrorKind.CODE_EXPIRED
            "surface" -> PairErrorKind.TV_SURFACE
            "locked" -> PairErrorKind.LOCKED
            "storage" -> PairErrorKind.TV_STORAGE
            "proof", "unknown" -> PairErrorKind.REPAIR_NEEDED
            // "code" and a receiver that sends no reason at all.
            else -> PairErrorKind.CODE_MISMATCH
        }
    }

    private fun resume(pairing: PairingStore.Pairing, afterResume: (() -> Unit)? = null) {
        val attempt = beginPairingAttempt()
        pairingJob = scope.launch {
            var sawUntrustedFailure = false
            var sawTransportFailure = false
            // The LAST candidate's answer, not the first: the queue walks from the stored
            // endpoint outwards, so the address the phone tried most recently is the one
            // whose kernel answer the user is owed an explanation of.
            //
            // Null rather than a NO_ANSWER seed, because the seed was itself a claim: a
            // sweep whose every candidate came back DENIED for a transient reason met a TV
            // that answered, and reporting "got no answer at all" about it is the exact
            // false sentence this taxonomy exists to stop.
            var lastFault: DialFault? = null
            val candidates = ResumeCandidateQueue(pairing.host, pairing.port, pairing.tvId, MAX_RESUME_CANDIDATES)
            var awaitedNsd = false
            while (true) {
                var candidate = candidates.next(devices.value)
                if (candidate == null && sawTransportFailure && candidates.hasCapacity() && !awaitedNsd) {
                    awaitedNsd = true
                    withTimeoutOrNull(NSD_RESNAPSHOT_MS) {
                        devices.first(candidates::hasNext)
                    }
                    candidate = candidates.next(devices.value)
                }
                if (candidate == null) break
                dialedHost = DialedHost(candidate.host, candidate.discovered)
                val result = control.resume(pairing, candidate.host, candidate.port)
                FlickLog.i("ws", "resume candidate ${candidate.host}:${candidate.port} -> ${result.javaClass.simpleName}")
                when (result) {
                    is ControlClient.Result.Resumed -> if (pairingGate.isCurrent(attempt)) {
                        if (!PairingPersistence.commit { store.commitVerifiedEndpoint(pairing.tvId, result.endpoint.tv, candidate.host, candidate.port) }) {
                            control.close()
                            // The dial succeeded end to end and a write to this phone
                            // failed. `persistPaired` has said so honestly for years; this
                            // path used to report the TV as unreachable instead.
                            failPendingResume(
                                "pairing_store_failed",
                                pairError = PairErrorKind.LOCAL_STORAGE,
                            )
                            return@launch
                        }
                        _connectedTv.value = PairedTv(result.endpoint.tv, candidate.host, candidate.port, pairing.tvId)
                        _route.value = Route.Library
                        afterResume?.invoke()
                        return@launch
                    }
                    ControlClient.Result.Busy -> if (pairingGate.isCurrent(attempt)) { publishBusyFailure(); return@launch }
                    // A policy close is not proof the stored key is bad; only a
                    // credential-shaped denial may cost the pairing its trust.
                    is ControlClient.Result.Denied ->
                        if (result.reason in TRANSIENT_DENIALS) sawTransportFailure = true else sawUntrustedFailure = true
                    is ControlClient.Result.ProtocolError -> sawUntrustedFailure = true
                    is ControlClient.Result.Unreachable -> {
                        lastFault = result.fault
                        sawTransportFailure = true
                    }
                    is ControlClient.Result.TimedOut -> {
                        lastFault = result.fault
                        sawTransportFailure = true
                    }
                    is ControlClient.Result.RejectedByTv -> {
                        lastFault = DialFault.REJECTED
                        sawTransportFailure = true
                    }
                    else -> sawUntrustedFailure = true
                }
                if (!pairingGate.isCurrent(attempt)) return@launch
            }
            if (pairingGate.isCurrent(attempt)) {
                // A single spoofed/expired candidate must not poison a durable pairing.
                if (sawUntrustedFailure) {
                    FlickLog.w("pair", "marked needs_repair tvId=${pairing.tvId}")
                    store.markNeedsRepair(pairing.tvId)
                    // The user typed no code here, so "that code didn't match" names a
                    // field they never filled in. `markNeedsRepair` on the line above is
                    // the app stating this same diagnosis to itself.
                    _pairError.value = PairErrorKind.REPAIR_NEEDED
                    _route.value = Route.Connect
                } else if (sawTransportFailure) {
                    val fault = lastFault
                    if (fault != null) {
                        failPendingResume(controlFaultCode(fault, offNetwork()), fault)
                    } else {
                        // Nothing but transient denials: every candidate ANSWERED, and
                        // said why. That is the same fact the post-upgrade close carries.
                        failPendingResume("control_rejected", DialFault.REJECTED)
                    }
                }
            }
        }
    }

    /**
     * The stale name a rename on the TV leaves on every surface that names the receiver —
     * the library pill, the detail CTA, the media notification, the error copy — because
     * [_connectedTv] is written only where a proof lands and nothing re-proves an idle link.
     *
     * Acting on the advertisement is a re-handshake and NOT a name change: the name that
     * arrives is the one the receiver signed, which is why an unauthenticated cue can
     * safely drive it.
     */
    private fun considerTvNameRefresh(discovered: List<DiscoveredTv>) {
        val shown = _connectedTv.value ?: return
        // Read before the gate, so a record that could not be resumed at all never spends
        // the single hint this advertised name gets.
        val pairing = store.get(shown.tvId)?.takeIf { !it.needsRepair } ?: return
        if (!tvNameRefreshGate.refreshes(pairing.tvId, shown.name, idleForNameRefresh(), discovered)) return
        FlickLog.i("nsd", "tv name refresh tvId=${pairing.tvId}")
        refreshTvName(pairing)
    }

    /**
     * Whether a re-handshake would cost this phone nothing it is in the middle of. A resume
     * closes the control session before it dials, so a live cast, a cast queued behind a
     * dial, a pairing already running and a code sheet waiting on the receiver each outrank
     * a cosmetic name.
     *
     * [Route.Connect] outranks it too, and not because anything there is in flight. That
     * screen is the app's only reader of the control connection status, so a dial nobody
     * asked for arrives on it as a pairing that is: the manual sheet raises its progress
     * state and refuses submit, the connected row drops its tick, and the reconnect fires
     * the confirm haptic with no press behind it. A phone that buzzes at nobody is worse
     * than the stale name this exists to correct.
     */
    private fun idleForNameRefresh(): Boolean =
        currentCastId == null && pendingCast == null && pairingJob?.isActive != true &&
            _pairTarget.value == null && _pendingPairLaunch.value == null &&
            _route.value != Route.Connect && _route.value != Route.Connecting

    /**
     * Re-proves the pairing at the endpoint this phone last authenticated at, and takes the
     * name out of the answer.
     *
     * That endpoint alone, rather than [ResumeCandidates]' sweep: nothing but an
     * unauthenticated advertisement asked for this, so it may not choose which address the
     * phone dials. A LAN advertiser claiming the paired id can then cost at most one
     * re-handshake with the TV this phone already trusts, and can never redirect it.
     *
     * Every failure is silent for the same reason. A resume the user asked for may mark the
     * pairing for repair, raise an error face and move the route; a refresh nobody asked for
     * may do none of those — breaking a working pairing on an unauthenticated cue would be
     * a far worse bug than the stale name it set out to fix.
     */
    private fun refreshTvName(pairing: PairingStore.Pairing) {
        val attempt = beginPairingAttempt()
        pairingJob = scope.launch {
            val endpoint = (control.resume(pairing) as? ControlClient.Result.Resumed)?.endpoint
            if (endpoint == null || !pairingGate.isCurrent(attempt)) return@launch
            val committed = PairingPersistence.commit {
                store.commitVerifiedEndpoint(pairing.tvId, endpoint.tv, pairing.host, pairing.port)
            }
            if (!committed) return@launch
            _connectedTv.value = PairedTv(endpoint.tv, pairing.host, pairing.port, pairing.tvId)
            publishTransport()
        }
    }

    /**
     * Delegated rather than spelt out here: the library tile's progress line resolves
     * through the same rule, and the two must be one decision. See [resumePositionMs].
     */
    internal fun resumePosition(item: MediaItem, state: PlaybackProgressState): Long? =
        resumePositionMs(state, PlaybackMediaFingerprint.of(item), item.durationMs)

    fun flickToTv(item: MediaItem) {
        progressResolutionJob?.cancel()
        when (val progress = playbackProgress.value) {
            PlaybackProgressState.Loading -> {
                progressResolutionJob = scope.launch {
                    val ready = playbackProgress.first { it is PlaybackProgressState.Ready }
                    progressResolutionJob = null
                    beginUserCast(CastRequest(item, resumePosition(item, ready) ?: 0L))
                }
            }
            is PlaybackProgressState.Ready ->
                beginUserCast(CastRequest(item, resumePosition(item, progress) ?: 0L))
        }
    }

    fun startOver(item: MediaItem) {
        progressResolutionJob?.cancel()
        progressResolutionJob = null
        beginUserCast(CastRequest(item, 0L, startOver = true))
    }

    /**
     * A cast the user asked for, which is what separates this from the re-cast
     * [onControlLost] performs: a viewing they started themselves is a fresh run, and the
     * recovery budget the previous one may have spent is not theirs to inherit.
     */
    private fun beginUserCast(request: CastRequest) {
        controlRecoveries = 0
        lastControlRecoveryAtMs = 0L
        flickToTv(request)
    }

    private fun flickToTv(request: CastRequest) {
        val tv = _connectedTv.value ?: run {
            // Bounced to Connect with nothing said. The Connect screen already draws
            // PairErrorCard from this flow, so naming the reason is one assignment.
            _pairError.value = PairErrorKind.PAIRING_REQUIRED
            openConnect()
            return
        }
        if (control.authenticatedEndpoint() == null) {
            val pairing = store.get(tv.tvId)
            if (pairing == null || pairing.needsRepair) {
                _pairError.value =
                    if (pairing == null) PairErrorKind.PAIRING_REQUIRED else PairErrorKind.REPAIR_NEEDED
                openConnect()
                return
            }
            pendingCast = request
            resume(pairing) { pendingCast?.takeIf { control.authenticatedEndpoint() != null }?.let(::startCast) }
            return
        }
        startCast(request)
    }
    private fun startCast(request: CastRequest) {
        val item = request.item
        pendingCast = null
        cancelCast(silent = true); val castId = ControlProtocolV2.randomId(); val thisGeneration = castGate.begin(castId); currentCastId = castId
        currentRequest = request
        _castFailure.value = null
        _failureItem.value = null
        _failureLinkVerdict.value = LinkVerdict.Unknown
        // openDetail cannot drop a selection while a cast is live — that cast owns the
        // file it is serving — so a film browsed to mid-cast reaches here still carrying
        // the previous film's subtitle. Casting it must not inherit those cues, which
        // the receiver would auto-enable under SELECTION_FLAG_DEFAULT.
        val owned = _selectedSubtitle.value?.takeIf { subtitleOwner?.uri == item.uri }
        if (owned == null) _selectedSubtitle.value = null
        subtitleOwner = item
        // Cleared as the cast starts and not when a first frame proves it, which is the
        // one thing this cannot borrow from the refusal above: the receiver decides
        // silence from the track selection, so its frame arrives BEFORE `loadReady` and a
        // clear at readiness would erase the mark this same cast had just earned. Ahead of
        // the load it is only an amnesty — a TV that is still silent says so again in
        // seconds, and a TV that has since found a decoder never does.
        _silentAudioFiles.value = silentAudioMemory.clear(item.uriKey)
        _castingItem.value = item; _route.value = Route.Connecting; publishCastStart(CastStartState.ConnectingControl(castId))
        castJob = scope.launch {
            var readyCommit = false
            try {
                // Proof, not a guess: `getSiteLocalIpv4` returns null only when this phone
                // holds no site-local address at all. It is also the commonest no-LAN case
                // — Wi-Fi simply off — and the one no wire code could ever report, because
                // nothing is dialed to report it.
                if (ownLanIpv4() == null) throw CastStartupFailure(SourceFault.NO_LAN_ADDRESS)
                val endpoint = control.authenticatedEndpoint() ?: throw CastStartupFailure("control_unreachable")
                // The authenticated socket is the strongest witness there is: this phone
                // completed a keyed exchange with that address moments ago.
                dialedHost = DialedHost(endpoint.host, liveVerified = true)
                if (!com.flick.sender.NetworkUtils.isOwnedLanIpv4(endpoint.peerIp)) throw CastStartupFailure("no_compatible_lan")
                if (item.uri.scheme != "content") throw CastStartupFailure("source_unavailable")
                // MediaStore leaves SIZE null for files its scanner never finished, and
                // MediaHttpServer stats the descriptor for its own range math regardless —
                // so refusing on the missing column would kill a cast of a file that is
                // sitting right there. Only a URI that will not open is genuinely gone,
                // which is the one thing `source_unavailable` is allowed to mean.
                val sizeBytes = item.sizeBytes.takeIf { it > 0L }
                    ?: withContext(Dispatchers.IO) { MediaMeta.resolveSize(appContext.contentResolver, item.uri) }
                if (sizeBytes <= 0L) throw CastStartupFailure("source_unavailable")
                // Container bitrate, from the only two numbers that are known before a byte
                // moves. A null here is a file this feature will never have an opinion about.
                linkMonitor.beginCast(castId, LinkCapacityPolicy.requiredBitrateBps(sizeBytes, item.durationMs))
                publishCastStart(CastStartState.StartingSource(castId))
                // Before the server is told what to serve, so a remembered subtitle rides
                // the first loadMedia rather than arriving as a retarget the TV pays for
                // with a re-buffer.
                val subtitle = owned ?: recalledSubtitleFor(item, castId, thisGeneration)
                ServerStateHolder.beginStarting(castId)
                CastServerService.start(appContext, castId, item.uri, item.name, sizeBytes, endpoint.peerIp, subtitle?.uri)
                // Not `startup_timeout`: this throw is strictly before `loadSentCastId` is
                // written below, so the shipped copy's claim that the TV accepted the cast
                // is provably false here — nothing was ever asked of it.
                val server = withTimeoutOrNull(9_000) { ServerStateHolder.state.first { it.castId == castId && (it.status == ServerStatus.RUNNING || it.status == ServerStatus.ERROR) } }
                    ?: throw CastStartupFailure("source_start_timeout")
                val videoUrl = server.videoUrl
                if (server.status != ServerStatus.RUNNING || videoUrl == null) {
                    // The service names its own fault where it has one; the bind failure
                    // is only the floor for a RUNNING state with no URL to serve from.
                    throw CastStartupFailure(
                        ServerStateHolder.terminalEvent.value
                            ?.takeIf { it.castId == castId }?.errorCode
                            ?: SourceFault.BIND_FAILED,
                    )
                }
                // The subtitle capability is published before RUNNING, so it is already
                // visible on the state the wait above returned.
                val subUrl = subtitleUrlFor(
                    videoUrl,
                    SubtitleServingState.state.value?.takeIf { it.castId == castId }?.url,
                )
                // endpoint() strips the path: the /v/{token} segment IS the capability.
                FlickLog.i("cast", "source ready ${FlickLog.endpoint(videoUrl)} subtitle=${subUrl != null}")
                accepted = CompletableDeferred(); ready = CompletableDeferred(); publishCastStart(CastStartState.AwaitingAcceptance(castId))
                loadSentCastId = castId
                val title = ControlProtocolV2.normalizedLabel(displayedVideoName(item.name), 200)
                    ?: appContext.getString(R.string.media_title_generic)
                control.armLoadSubtitle(castId, subUrl, subtitle?.displayName, subtitle?.language)
                val fingerprint = PlaybackMediaFingerprint.of(item)
                // Read without waiting. This store starts reading at launch, fails open to
                // Ready on an unreadable file, and has had the whole of pairing, the
                // library and this startup to resolve — and a nudge is a decoration on a
                // cast, so it may not hold one up. A memory missed on the one cast that
                // outran the disk is a memory the viewer re-dials in one gesture.
                val rememberedDelayMs = rememberedAudioDelayMs(audioDelayStore.state.value, fingerprint)
                // A false here is certainty: the frame provably never left this phone.
                // Letting the two-second `accepted` wait below expire instead would file
                // that certainty as the TV having stayed silent.
                if (!session.loadMedia(castId, videoUrl, title, item.durationMs, request.startMs)) {
                    throw CastStartupFailure("load_not_sent")
                }
                // Back in force before the first frame is decoded, and in one frame rather
                // than a walk — see PlaybackSession.applyRememberedAudioDelay for why a
                // cast with nothing on screen yet is the one move that needs no walking.
                // The recorder is armed with the same value, so a film that is watched at
                // the offset it was left at rewrites nothing.
                rememberedDelayMs?.let(session::applyRememberedAudioDelay)
                audioDelayRecorder.activate(
                    castId,
                    fingerprint,
                    rememberedDelayMs ?: AudioDelayPolicy.IN_SYNC_MS,
                )
                withTimeoutOrNull(2_000) { accepted?.await() } ?: throw CastStartupFailure("startup_timeout")
                publishCastStart(CastStartState.AwaitingFirstFrame(castId))
                withTimeoutOrNull(18_000) { ready?.await() } ?: throw CastStartupFailure("startup_timeout")
                if (!castGate.isCurrent(castId, thisGeneration) || currentCastId != castId) return@launch
                readyCommit = true
                supportPromptStore.recordSuccess()
                publishCastStart(CastStartState.Active(castId))
                playbackProgressRecorder.activate(
                    castId,
                    fingerprint,
                    startOver = request.startOver,
                )?.let(::enqueueProgress)
                _route.value = Route.NowPlaying
                // A first frame is the only thing that can outrank a previous refusal.
                // It also clears the decoder suspicion: this file just found a decoder,
                // so whatever was holding one before has let go.
                _unplayableFiles.value = unplayableMemory.clear(item.uriKey)
                decoderFaults.forget(item.uriKey)
                _decoderSuspects.value = decoderFaults.suspects()
            } catch (failure: CastStartupFailure) { terminal(castId, failure.code) }
              catch (e: Exception) {
                  // The only cast terminal that used to leave no diagnostic trace at all.
                  // The class name and nothing else: a message can carry a URI or a path.
                  FlickLog.w("cast", "cast start failed ${e.javaClass.simpleName}")
                  terminal(castId, "unknown")
              }
            finally { if (!readyCommit) cleanup(castId) }
        }
    }

    private fun displayedVideoName(rawName: String): String {
        if (!simplifiedVideoNames.value) return VideoNames.safeFileName(rawName)
        val parsed = VideoNames.parse(rawName)
        val edition = parsed.edition?.let { appContext.getString(it.labelResource()) }
        return VideoNames.format(parsed, edition)
    }

    fun cancelCast() = cancelCast(silent = false)

    /**
     * Cancels the cast that is running AND the one queued behind a resume, because during
     * a control recovery the second exists without the first: the connecting screen is up,
     * its Cancel button is under the viewer's thumb, and the only thing to cancel is a
     * dial in flight. Leaving that running would restart the film after they said stop.
     *
     * The silent path is [startCast]'s own supersede, which clears [pendingCast] before
     * calling this — so a cast starting normally never reaches the pairing invalidation
     * below and the sequence it has always run is unchanged.
     */
    private fun cancelCast(silent: Boolean) {
        val queued = pendingCast != null
        pendingCast = null
        val live = currentCastId
        if (live != null) { castJob?.cancel(); cleanup(live, stopRemoteIfLoaded = true) }
        if (queued) invalidatePairingAttempt()
        if (!silent && (queued || live != null)) _route.value = Route.Library
    }
    fun stopCast() {
        currentCastId?.let { castId ->
            control.send(ControlProtocolV2.command("stop", castId))
            completeCastToLibrary(castId)
        } ?: run {
            // Stop during a control recovery finds no cast to command — the dial is still
            // in flight — and the film it would start must not arrive after the viewer
            // said stop. With nothing queued this is the no-op it has always been.
            cancelCast()
            _route.value = Route.Library
        }
    }
    private fun cleanup(castId: String, clearStart: Boolean = true, stopRemoteIfLoaded: Boolean = false) {
        if (stopRemoteIfLoaded && CastCleanupPolicy.shouldSendStop(castId, loadSentCastId)) requestRemoteStop(castId)
        castGate.invalidate(castId); accepted?.cancel(); ready?.cancel(); accepted = null; ready = null
        if (loadSentCastId == castId) loadSentCastId = null
        CastServerService.stop(appContext, castId)
        // Both are cast-scoped: a stale cleanup must not cancel a newer cast's
        // retarget or disarm the subtitle its load is about to carry.
        if (currentCastId == castId) {
            playbackProgressRecorder.finish(castId)?.let(::enqueueProgress)
            // Before session.clear() below, which republishes in-sync: that value belongs
            // to no film, and a recorder still active when it arrived would read it as the
            // viewer cancelling the nudge on the one that just ended.
            audioDelayRecorder.finish(castId, session.audioDelayTargetMs)?.let(::enqueueAudioDelay)
            subtitleJob?.cancel(); subtitleJob = null; control.disarmLoadSubtitle(); currentCastId = null
            currentRequest = null; _castingItem.value = null; session.clear(); linkMonitor.reset()
            // Nothing one cast's socket proved may be read as evidence about the next.
            lastServedByteAtMs = 0L
            if (clearStart) publishCastStart(CastStartState.Idle)
            // The terminal path keeps the Failed state, so it never reaches publishCastStart
            // — and the notification has to go with the cast either way.
            publishTransport()
        }
    }
    /** Normal terminals deliberately capture their Active ownership before [cleanup] clears it. */
    private fun completeCastToLibrary(castId: String) {
        val eligibleForInvitation = supportInvitationEligibleForNormalCompletion(
            castId = castId,
            currentCastId = currentCastId,
            state = _castStart.value,
        )
        cleanup(castId)
        _route.value = Route.Library
        if (eligibleForInvitation && SupportCatalog.configured() != null && supportPromptStore.consumeIfEligible()) {
            _showSupportInvitation.value = true
        }
    }
    /** Cast ids are fingerprinted, never printed: they address a live media session. */
    private fun publishCastStart(state: CastStartState) {
        val castId = when (state) {
            is CastStartState.ConnectingControl -> state.castId
            is CastStartState.StartingSource -> state.castId
            is CastStartState.AwaitingAcceptance -> state.castId
            is CastStartState.AwaitingFirstFrame -> state.castId
            is CastStartState.Active -> state.castId
            is CastStartState.Failed -> state.castId
            CastStartState.Idle -> null
        }
        FlickLog.i("cast", "state=${state.javaClass.simpleName} castIdFp=${FlickLog.fp(castId)}")
        // Called rather than collected: a conflating StateFlow can hand a collector a value
        // the coordinator has already moved past, and a stale terminal must never stop the
        // monitor measuring the cast that replaced it.
        linkMonitor.onCastStart(state)
        _castStart.value = state
        // Reaching Active is what arms the notification's transport, and leaving it is
        // what disarms it, so the stage transition is one of the edges that republishes.
        publishTransport()
    }
    /**
     * [reportedBeforeStart] is what the raising side claims about the phase. It is a claim
     * rather than the answer: this phone holds its own record of whether the TV ever
     * reported a first frame for this cast, and the copy may say "the film was playing"
     * only where both agree. The default is the honest floor for every local raise, all of
     * which happen before or during startup unless the Active record says otherwise.
     */
    private fun terminal(
        castId: String,
        reportedCode: String,
        retryable: Boolean = false,
        httpStatus: Int? = null,
        origin: TerminalOrigin = TerminalOrigin.LOCAL,
        reportedBeforeStart: Boolean = true,
    ) {
        if (currentCastId != castId) return
        val reachedActive = (_castStart.value as? CastStartState.Active)?.castId == castId
        // `streamSlice` is the only place in the system that knows why a body stopped, so
        // where it recorded a reason for THIS cast it outranks the receiver's guess about
        // the same silence. Every other receiver verdict was reached with the file in
        // front of it and is better evidence than anything this phone holds.
        val preferred = preferredTerminalCode(
            reportedCode,
            ServerStateHolder.sourceFault.value?.takeIf { it.castId == castId }?.code,
        )
        // A control link that went quiet under a phone holding no LAN address of its own
        // is a phone that left the network, and that is a different diagnosis with a
        // different fix — one this side can prove, and the only one of the two with a
        // control behind it.
        val code = if (preferred == "control_disconnected" && ownLanIpv4() == null) {
            "control_disconnected_no_lan"
        } else {
            preferred
        }
        // The origin follows the code it ends up carrying: a receiver frame whose verdict
        // was replaced by this phone's own record is no longer the receiver speaking, and
        // the copy that varies on origin must not say it was.
        val resolvedOrigin = if (code == reportedCode) origin else TerminalOrigin.LOCAL
        _failureLinkVerdict.value = linkMonitor.verdict.value
        _failureSameSubnet.value = sameSubnetAs(dialedHost)
        val item = _castingItem.value
        val request = currentRequest
        val offerRetry = castRetryOffered(retryable, item != null)
        retryItem = request?.takeIf { offerRetry }?.let { original ->
            val playback = session.state.value
            val retryStart = CastRetryPolicy.start(
                originalStartMs = original.startMs,
                originalStartOver = original.startOver,
                active = reachedActive,
                confirmedMs = playback.confirmedMs,
                durationMs = playback.durationMs,
                wireDurationMs = original.item.durationMs,
            )
            original.copy(startMs = retryStart.startMs, startOver = retryStart.startOver)
        }
        _failureItem.value = item
        // Only ever the file's own fault, and only ever for the file that was on the
        // wire: a marked item stays castable, so this is a memory, not a gate.
        //
        // `decoder_init` is recorded before it is judged. The first one only makes the
        // sheet stop promising; the second marks the file, because by then the TV has
        // failed the same way twice with nothing left to blame.
        if (item != null) {
            val repeatedDecoderFault = code == DecoderFaultCode && decoderFaults.record(item.uriKey)
            if (code == DecoderFaultCode) _decoderSuspects.value = decoderFaults.suspects()
            if (marksFileUnplayable(code, repeatedDecoderFault)) {
                _unplayableFiles.value = unplayableMemory.mark(item.uriKey, code)
            }
        }
        // Both witnesses have to agree before the copy may describe a film that was
        // playing: a receiver frame carries the receiver's own phase, and this phone's
        // Active record is what proves a frame ever reached the screen.
        val beforeStart = reportedBeforeStart || !reachedActive
        FlickLog.w("cast", "terminal castIdFp=${FlickLog.fp(castId)} code=$code origin=$resolvedOrigin retryable=$offerRetry beforeStart=$beforeStart httpStatus=$httpStatus")
        _castFailure.value = CastFailure(code, offerRetry, httpStatus, resolvedOrigin, beforeStart)
        publishCastStart(CastStartState.Failed(castId, code))
        cleanup(castId, clearStart = false, stopRemoteIfLoaded = true)
        _route.value = Route.Failure(errorKind(code), _castFailure.value!!)
    }
    private fun onFrame(frame: JSONObject) {
        // busy is deliberately session-level and has no castId. It must win before
        // stale-cast filtering so a second controller never appears to prepare.
        if (frame.optString("t") == "busy") {
            currentCastId?.let { terminal(it, "active_cast_busy", retryable = true) } ?: run {
                publishBusyFailure()
            }
            return
        }
        session.onFrame(frame); val id = frame.optString("castId", ""); if (id != currentCastId) return
        if (frame.optString("t") == "state" && (_castStart.value as? CastStartState.Active)?.castId == id) {
            val state = session.state.value
            playbackProgressRecorder.onConfirmed(
                id,
                state.confirmedMs,
                state.durationMs,
                state.phase,
            )?.let(::enqueueProgress)
        }
        when (frame.optString("t")) {
            "loadAccepted" -> accepted?.complete(frame)
            "loadReady" -> ready?.complete(frame)
            // The one arm that speaks for the receiver. Everything else that reaches
            // `terminal` was raised by this phone about itself.
            //
            // The frame type IS the phase: the receiver encodes a failure before its first
            // frame as `loadFailed` and one after as `error`, so the same code arrives
            // twice over meaning two different things about when it happened. Read here
            // rather than inferred, because a mid-film code that degrades to the generic
            // face would otherwise be described as a cast that never started.
            "loadFailed", "error" -> terminal(
                id,
                frame.getString("code"),
                frame.getBoolean("retryable"),
                if (frame.has("httpStatus")) frame.getInt("httpStatus") else null,
                TerminalOrigin.RECEIVER,
                reportedBeforeStart = frame.optString("t") == "loadFailed",
            )
            // The film is playing and stays playing: this ends no cast and fails nothing.
            // It is filed against the item the cast is serving, which the id check above
            // has already proven is this one.
            "audio_silent" -> _castingItem.value?.let { item ->
                _silentAudioFiles.value = silentAudioMemory.mark(item.uriKey, frame.getString("mime"))
            }
            "stopped" -> completeCastToLibrary(id)
        }
    }
    private fun onSourceTerminal(event: com.flick.sender.SourceServerEvent) {
        val id = event.castId
        requestRemoteStop(id)
        when (event.kind) {
            SourceServerTerminalKind.STOPPED -> {
                completeCastToLibrary(id)
            }
            // The service already names which of its own faults fired; the hardcoded
            // bind failure it used to be reported a port collision for a phone that had
            // simply left the Wi-Fi.
            SourceServerTerminalKind.FAILED -> terminal(id, event.errorCode ?: SourceFault.BIND_FAILED)
        }
    }
    /**
     * The coarse taxonomy, and no longer the driver of any face that has a code of its
     * own: `sender_not_serving`, `http_rejected`, `media_bind_failed`, `control_disconnected`
     * and `media_unreachable` are all matched in `castErrorFace` before this is consulted.
     * The last of those is why the kind below still says UNREACHABLE and the screen does
     * not — the TV saying it could not fetch from this phone arrives over a live socket,
     * so the only unreachable thing is this phone's own film server.
     */
    private fun errorKind(code: String) = when (code) { "no_compatible_lan", "host_mismatch", "no_lan_address", "control_no_network", "control_disconnected_no_lan" -> CastErrorKind.NO_LAN; "control_unreachable", "control_disconnected", "media_unreachable" -> CastErrorKind.UNREACHABLE; else -> CastErrorKind.GENERIC }
    fun playPause() = session.togglePlayPause(); fun skip(deltaMs: Long) = session.skip(deltaMs); fun commitPendingSkip() = session.commitPendingSkip(); fun scrubStart() = session.scrubStart(); fun scrubTo(fraction: Float) = session.scrubTo(fraction); fun scrubEnd() = session.scrubEnd(); fun setVolume(level: Float) = session.setVolume(level)

    /**
     * Gated by the same predicate that dims the control offering it, because the
     * optimistic selection is recorded downstream: a choice kept for a verb that could
     * not leave would draw a turn the picture never took.
     *
     * The drop is logged because it is otherwise the one outcome of this verb that
     * leaves no trace anywhere — the receiver never acknowledges a rotation and no
     * `state` frame carries one back, so a phone log that recorded only the sends could
     * not tell a dropped press from a TV that ignored one.
     */
    fun setRotation(choice: VideoRotation) {
        if (transportCommandable()) session.setRotation(choice)
        else FlickLog.w("cast", "setRotation drop reason=not_commandable choice=$choice")
    }
    fun setAudioDelay(delayMs: Int) = session.setAudioDelay(delayMs); fun nudgeAudioDelay(later: Boolean) = session.nudgeAudioDelay(later); fun resetAudioDelay() = session.resetAudioDelay()
    fun retryCast() { retryItem?.let { request -> retryItem = null; beginUserCast(request) } }
    /** "Keep watching": the card goes and stays gone for this cast. Playback never stopped. */
    fun dismissLinkStall() = linkMonitor.dismissStall()
    private fun requestRemoteStop(castId: String) { control.send(ControlProtocolV2.command("stop", castId)) }
    private fun enqueueProgress(write: PlaybackProgressWrite) {
        playbackProgressStore.enqueue(write.fingerprint, write.mutation) { success ->
            playbackProgressRecorder.acknowledge(write, success)
        }
    }
    private fun enqueueAudioDelay(write: AudioDelayWrite) {
        audioDelayStore.enqueue(write.fingerprint, write.mutation) { success ->
            audioDelayRecorder.acknowledge(write, success)
        }
    }
    /**
     * [pairError] is for the faults that are not a dial fault at all — a resume that
     * reached the TV and then failed on this phone's own storage has no [DialFault] to
     * translate, and the residual UNREACHABLE would report a disk as a missing TV.
     */
    private fun failPendingResume(
        code: String,
        fault: DialFault? = null,
        pairError: PairErrorKind? = null,
    ) {
        val request = pendingCast
        pendingCast = null
        // Frozen here for the same reason `terminal` freezes it: the addresses are gone by
        // the time the face composes, and null stays the honest third answer.
        _failureSameSubnet.value = sameSubnetAs(dialedHost)
        if (request != null) {
            retryItem = request
            _failureItem.value = request.item
            _castFailure.value = CastFailure(code, retryable = true)
            _route.value = Route.Failure(errorKind(code), _castFailure.value!!)
        } else {
            _pairError.value = pairError
                ?: fault?.let { pairErrorForFault(it, _failureSameSubnet.value == true, offNetwork()) }
                ?: PairErrorKind.UNREACHABLE
        }
    }
    private fun persistPaired(
        key: String,
        endpoint: ControlClient.AuthenticatedEndpoint,
        host: String,
        port: Int,
        legacyHost: String?,
    ): PairingStore.Pairing? {
        val old = store.get(endpoint.tvId)
        val pairing = PairingStore.Pairing(endpoint.tvId, endpoint.keyId, endpoint.tv, host, port, key)
        if (PairingPersistence.commit { store.save(pairing, old, legacyHost) }) return pairing
        control.close()
        clearEnteredCode()
        _pairError.value = PairErrorKind.LOCAL_STORAGE
        return null
    }
    private fun publishBusyFailure() {
        retryItem = pendingCast
        _failureItem.value = pendingCast?.item ?: _castingItem.value
        pendingCast = null
        _castFailure.value = CastFailure("active_cast_busy", retryable = retryItem != null)
        _route.value = Route.Failure(errorKind("active_cast_busy"), _castFailure.value!!)
    }
    private fun canRestoreNowPlaying(): Boolean {
        val active = _castStart.value as? CastStartState.Active ?: return false
        return SenderNavigationPolicy.canRestoreNowPlaying(active, _castingItem.value != null) &&
            active.castId == currentCastId
    }
    private val _pairCodeRevision = MutableStateFlow(0L); val pairCodeRevision = _pairCodeRevision.asStateFlow()
    private fun clearEnteredCode() { _pairCodeRevision.value = pairCodeReset.clear() }
    private fun beginPairingAttempt(): Long { invalidatePairingAttempt(); return pairingGate.begin() }
    private fun beginManualPairAttempt(): Long = manualPairAttemptLedger.begin().also {
        _manualPairAttempt.value = manualPairAttemptLedger.event
    }
    private fun finishManualPairAttempt(generation: Long?) {
        if (generation != null && manualPairAttemptLedger.complete(generation)) {
            _manualPairAttempt.value = manualPairAttemptLedger.event
        }
    }
    private fun invalidatePairingAttempt() { pairingGate.invalidate(); pairingJob?.cancel(); pairingJob = null; control.cancelUnauthenticated() }
    private class CastStartupFailure(val code: String) : RuntimeException()

    private companion object {
        const val NSD_RESNAPSHOT_MS = 1_500L
        const val MAX_RESUME_CANDIDATES = 4
        const val MAX_PAIR_REBIND_CANDIDATES = 3
        const val PAIR_REBIND_BUDGET_MS = 4_000L
        const val SUBTITLE_RETARGET_MS = 3_000L
        /** Denials that describe the TV's momentary state, not the stored credential. */
        val TRANSIENT_DENIALS = setOf("surface", "locked", "busy", "storage")
    }
}

/** Compatibility source alias for screens while all ownership is application scoped. */
typealias FlickController = CastCoordinator
