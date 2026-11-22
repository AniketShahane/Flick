package com.flick.sender.library

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.flick.sender.R
import com.flick.sender.ServerStateHolder
import com.flick.sender.ServerStatus
import com.flick.sender.currentProcessImportance
import com.flick.sender.foregroundStartAllowed
import com.flick.sender.media.FilmPosterPolicy
import com.flick.sender.media.MediaAccess
import com.flick.sender.media.MediaProbe
import com.flick.sender.media.PlaybackMediaFingerprint
import com.flick.sender.media.PlaybackProgressState
import com.flick.sender.media.VideoNames
import com.flick.sender.media.labelResource
import com.flick.sender.media.resumePositionMs
import com.flick.sender.model.ConnectionStatus
import com.flick.sender.model.MediaItem
import com.flick.sender.model.PlaybackPhase
import com.flick.sender.net.ControlClient
import com.flick.sender.net.FilmDetailsStore
import com.flick.sender.net.FilmPosters
import com.flick.sender.net.LibraryView
import com.flick.sender.net.Liveness
import com.flick.sender.net.PairingStore
import com.flick.sender.util.FlickLog
import com.flick.sender.util.IncidentJournal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the library channel needs of the phone. The coordinator implements it, so the channel
 * reaches the cast path only through the same entries a tap does.
 */
internal interface LibraryPhone {
    val control: ControlClient
    val library: StateFlow<LibraryView>
    val libraryLoading: StateFlow<Boolean>

    /** Null until a MediaStore read has answered at all; see the coordinator's own field. */
    val libraryComplete: StateFlow<Boolean?>
    val mediaAccess: StateFlow<MediaAccess>
    val playbackProgress: StateFlow<PlaybackProgressState>
    val unplayableFiles: StateFlow<Map<String, String>>
    val silentAudioFiles: StateFlow<Map<String, String>>
    val simplifiedVideoNames: StateFlow<Boolean>
    val filmPosters: StateFlow<Boolean>

    /** The verified pairing for [tvId], or null when it is gone or needs repair. */
    fun pairing(tvId: String): PairingStore.Pairing?
    fun tvName(): String?

    /** Whether the activity is started, as the coordinator tracks it. */
    fun visible(): Boolean

    /** A phone-side cast start is between its first step and its first frame. */
    fun castStartInFlight(): Boolean

    /** A cast exists, or is queued behind a check: its own foreground service holds the process. */
    fun castLive(): Boolean

    /** Exactly what a tap runs: the resume path, or Start over. */
    fun play(item: MediaItem, fromStart: Boolean)
    fun refreshLibrary()

    /** The label of the subtitle a cast of [item] would attach now, or null. */
    fun subtitleLabel(item: MediaItem): String?

    /** The pairing ready mode serves: the last TV, verified and not needing repair. */
    fun readyPairing(): PairingStore.Pairing?

    /** Media access as the platform grants it now, whether or not anything has read it yet. */
    fun mediaAccessGranted(): Boolean

    /** Evaluate media access if nothing in this process has yet, so a library can load. */
    fun ensureMediaAccess()

    /** A dial, a pairing or a block wait owns control v2 right now. */
    fun dialing(): Boolean

    /**
     * A connection attempt is actually running (a dial, a resume including a knock's, or a block
     * wait). Unlike [dialing], an open pair sheet or scanned-code card alone does not count.
     */
    fun dialInFlight(): Boolean

    /**
     * The tvId a silent resume ([silentResume], of any kind) is still dialing, or null. A knock
     * from that TV is answered by it; it releases the knock's wake lock if it fails.
     */
    fun knockResumingTv(): String?

    /**
     * The quiet, non-user resume to [pairing]'s stored endpoint, then [hintHost] (where a
     * knock came from) behind it. False when it did not start.
     */
    fun silentResume(pairing: PairingStore.Pairing, hintHost: String?, kind: SilentResumeKind): Boolean

    /**
     * The tvId of the silent resume still dialing, or null. Unlike [knockResumingTv], safe to
     * read off main: the doorbell's listener thread asks it before answering a knock.
     */
    fun silentResumeTv(): String?

    /**
     * A knock's address for the OPEN or PROMPT resume dialing [tvId], tried once after its own
     * candidates. Ignored when no such resume is running.
     */
    fun lateResumeHint(tvId: String, host: String?)

    /** The kind of the silent resume still dialing (as [knockResumingTv]), or null. On main. */
    fun silentResumeKind(): SilentResumeKind?

    /** A dial, pairing, block wait or pair sheet owns control v2 besides any silent resume. */
    fun dialingBesidesSilentResume(): Boolean

    /** Cancel the silent resume still dialing; it journals itself as superseded. */
    fun supersedeSilentResume()

    /** Close control v2 with no face, no haptic and no redial. */
    fun hangUpControl()
}

/**
 * The phone's side of library channel v1 (`docs/design/library-channel.md`): when the socket
 * is open, what the TV is sent, how its requests are answered, the watched ledger, and the
 * ready window. Main-confined, like the coordinator that owns it.
 *
 * Nothing that names a file — title, name, folder, TMDB path, item id or picture — is logged.
 */
internal class TvLibrary(
    private val context: Context,
    private val scope: CoroutineScope,
    private val phone: LibraryPhone,
) {
    private val preferenceStore = TvLibraryPreferenceStore(context)
    private val preference = TvLibraryPreferenceController(preferenceStore.enabled(), preferenceStore::save)

    /** "Show my library on my TV". */
    val enabled: StateFlow<Boolean> = preference.enabled

    private val connectModePreference =
        TvConnectModeController(preferenceStore.connectMode(), preferenceStore::saveConnectMode)

    /** "When your TV opens Flick". Read off main by [knockReply]: a StateFlow's value is safe there. */
    val connectMode: StateFlow<TvConnectMode> = connectModePreference.mode

    // "Not now" per TV, read on the doorbell's listener thread.
    private val promptSnooze = PromptSnooze()

    // TVs with a prompt up, each with the address its knock came from (a hint only).
    private val promptHints = HashMap<String, String?>()

    private val watchedStore = WatchedStore(context, scope)
    private val watchedRecorder = WatchedRecorder()

    /** Contract §9, for the catalog's `watched` field and nothing on the phone's own screens. */
    val watched: StateFlow<Map<String, Long>> = watchedStore.state

    private val link = LibraryLink(scope, object : LibraryLink.Handler {
        override fun onOpened() = onLinkOpened()
        override fun onFrame(frame: TvFrame) = onTvFrame(frame)
        override fun onClosed(reason: String) = onLinkClosed()
    })
    private val backoff = LibraryDialBackoff()

    // TVs whose control server has no /library route. Process lifetime, per the contract.
    private val unsupported = HashSet<String>()
    private var dialJob: Job? = null
    private var dialFor: ControlClient.AuthenticatedEndpoint? = null
    private val opening = AttemptSlot<ControlClient.AuthenticatedEndpoint>()
    private var linkFor: ControlClient.AuthenticatedEndpoint? = null
    private var linkOpenedAtMs = 0L

    // Moves on every socket, so an answer computed for one never lands on its successor.
    private var generation = 0L
    private var catalogAsked = false
    private var playOutstanding = false
    private val casting = CastingMemory()

    private val catalogLock = Mutex()
    private val names = CatalogNameFacts.Cache()
    private var built: Built? = null
    private val freshness = CatalogFreshness()
    private var pushJob: Job? = null

    // What the TV is known to hold, for patches; kept across reconnects to the same TV
    // because the TV keeps its catalog in memory across them too.
    private var held: EncodedCatalog? = null
    private var heldTvId: String? = null
    private var sentItems: Map<String, MediaItem> = emptyMap()

    private val thumbGate = Semaphore(THUMBS_IN_FLIGHT)
    private val thumbLimiter = SlidingWindowLimiter(THUMBS_PER_WINDOW, THUMB_WINDOW_MS)
    private val refreshGate = MinIntervalGate(REFRESH_INTERVAL_MS)
    private val detailGate = RequestGate(ANSWERS_IN_FLIGHT, ANSWERS_PER_WINDOW, ANSWER_WINDOW_MS)
    private val seasonGate = RequestGate(ANSWERS_IN_FLIGHT, ANSWERS_PER_WINDOW, ANSWER_WINDOW_MS)
    private var prefetchJob: Job? = null

    // Detail and season lookups for the current socket, cancelled with it so a closed socket's
    // lookups give up their slots to the next socket's asks.
    private var answers: Job = SupervisorJob(scope.coroutineContext[Job])

    private val readyWindow = TvReadyWindowPolicy()
    private var readyDeadlineJob: Job? = null

    // §13: the doorbell's bound port, and the one last sent on the current library socket.
    private var doorbellPort: Int? = null
    private var knockAtSent: Int? = null
    private var knockCheck: Job? = null

    private class Built(val catalog: EncodedCatalog, val items: Map<String, MediaItem>)

    init {
        scope.launch {
            combine(phone.control.connection, preference.enabled, phone.mediaAccess) { _, _, _ -> Unit }
                .collect { reconcile() }
        }
        scope.launch {
            merge(
                phone.library, phone.playbackProgress, watchedStore.state, phone.unplayableFiles,
                phone.silentAudioFiles, phone.simplifiedVideoNames, phone.filmPosters, FilmPosters.arrivals,
                MediaProbe.hdrVerdicts,
            ).collect { markDirty() }
        }
        scope.launch { phone.filmPosters.collect { if (it) startPrefetch() else stopPrefetch() } }
        scope.launch { TvReadyService.ended.collect { readyWindowEnded(it) } }
        // The cast's own foreground service takes over once it is up.
        scope.launch {
            ServerStateHolder.state.collect { state ->
                if (state.status == ServerStatus.RUNNING && readyWindow.open) closeReadyWindow("cast")
            }
        }
    }

    fun select(enabled: Boolean) = preference.select(enabled)

    fun selectConnectMode(mode: TvConnectMode) = connectModePreference.select(mode)

    // --- Link lifecycle (contract §3) ---------------------------------------

    private fun reconcile() {
        // Off ends the window whatever control v2 is doing (contract §8).
        if (!preference.enabled.value) {
            closeReadyWindow("setting")
            cancelPrompts()
        } else {
            reconcileReadyMode()
        }
        val endpoint = phone.control.authenticatedEndpoint()
            ?.takeIf { phone.control.connection.value == ConnectionStatus.CONNECTED }
        // Connected by any path, so asking is moot.
        endpoint?.let { cancelPrompt(it.tvId) }
        if (endpoint == null) {
            closeLink(LibraryCloseText.CONTROL)
            readyWindow.controlLost(now())
            scheduleReadyDeadline()
            return
        }
        readyWindow.controlRestored()
        scheduleReadyDeadline()
        if (!preference.enabled.value) {
            closeLink("setting", optOut = true)
            TvReadyService.releaseKnockWake()
            return
        }
        if (phone.mediaAccess.value == MediaAccess.NONE) {
            closeLink("media_access", optOut = true)
            TvReadyService.releaseKnockWake()
            return
        }
        if (endpoint.tvId in unsupported) {
            closeLink("unsupported")
            TvReadyService.releaseKnockWake()
            return
        }
        if (link.isOpen && linkFor == endpoint) return
        if (dialJob?.isActive == true && dialFor == endpoint) return
        closeLink("endpoint")
        backoff.reset()
        dial(endpoint)
    }

    private fun dial(endpoint: ControlClient.AuthenticatedEndpoint, afterMs: Long = 0L) {
        dialFor = endpoint
        dialJob = scope.launch {
            if (afterMs > 0L) delay(afterMs)
            while (true) {
                if (!stillWanted(endpoint)) return@launch
                val pairing = phone.pairing(endpoint.tvId)?.takeIf { it.keyId == endpoint.keyId }
                if (pairing == null) {
                    // Control v2 publishes CONNECTED before the coordinator saves a new or
                    // re-made pairing, so a first pair can get here a moment before its key.
                    FlickLog.w("library", "dial waiting reason=no_pairing")
                    delay(backoff.next())
                    continue
                }
                val target = LibraryLink.Target(
                    endpoint.tvId, endpoint.keyId, pairing.key, endpoint.peerIp, endpoint.host, endpoint.port,
                )
                val attempt = opening.begin(endpoint)
                val outcome = try {
                    link.connect(target)
                } finally {
                    opening.end(attempt)
                }
                when (outcome) {
                    LibraryLink.Outcome.Ready -> {
                        // The TV may have ended it already, and onLinkClosed has then planned the next dial.
                        if (!link.isOpen || linkFor != endpoint) return@launch
                        if (!stillWanted(endpoint)) {
                            link.close("superseded")
                            endLinkState()
                            return@launch
                        }
                        dialFor = null
                        startPrefetch()
                        return@launch
                    }
                    LibraryLink.Outcome.Unsupported -> {
                        unsupported += endpoint.tvId
                        TvReadyService.releaseKnockWake()
                        return@launch
                    }
                    is LibraryLink.Outcome.Failed -> delay(backoff.next())
                }
            }
        }
    }

    private fun stillWanted(endpoint: ControlClient.AuthenticatedEndpoint): Boolean =
        preference.enabled.value && phone.mediaAccess.value != MediaAccess.NONE &&
            phone.control.connection.value == ConnectionStatus.CONNECTED &&
            phone.control.authenticatedEndpoint() == endpoint && endpoint.tvId !in unsupported

    /** Inside [LibraryLink.connect], before the TV's first frame can be handed on. */
    private fun onLinkOpened() {
        val endpoint = opening.value
        linkFor = endpoint
        linkOpenedAtMs = now()
        generation++
        catalogAsked = false
        playOutstanding = false
        casting.socketOpened()
        if (endpoint != null && heldTvId != endpoint.tvId) {
            held = null
            heldTvId = endpoint.tvId
        }
        knockAtSent = null
        sendKnockAt()
    }

    /** The TV ended the socket while control v2 may still be up: dial again after the backoff. */
    private fun onLinkClosed() {
        val endpoint = linkFor
        backoff.socketEnded(now() - linkOpenedAtMs)
        endLinkState()
        if (endpoint != null && stillWanted(endpoint)) dial(endpoint, backoff.next())
    }

    /**
     * [optOut]: the phone is withdrawing its library, not just dropping a socket, so the TV is
     * first sent an empty catalog and stops showing it at once (contract §3).
     */
    private fun closeLink(reason: String, optOut: Boolean = false) {
        dialJob?.cancel()
        dialJob = null
        dialFor = null
        if (link.isOpen) {
            if (optOut) {
                val empty = EncodedCatalog.of(emptyList())
                link.closeAfter(LibraryFrames.full(empty), reason)
                held = empty
                sentItems = emptyMap()
                FlickLog.i("library", "catalog sent=opt_out")
            } else {
                link.close(reason)
            }
        }
        endLinkState()
    }

    private fun endLinkState() {
        linkFor = null
        knockAtSent = null
        generation++
        catalogAsked = false
        playOutstanding = false
        pushJob?.cancel()
        pushJob = null
        answers.cancel()
        answers = SupervisorJob(scope.coroutineContext[Job])
        stopPrefetch()
    }

    // --- Catalog (contract §5) ----------------------------------------------

    private fun markDirty() {
        freshness.changed()
        schedulePush()
    }

    /**
     * The debounced push. Nothing is rebuilt while a cast is live: the progress store writes
     * every few seconds during a film, and [onCastCleanup] or [onCastQueueCleared] asks again
     * once it ends. Asked again after every delivery too, so a change that lands mid-build is
     * never lost.
     */
    private fun schedulePush() {
        if (!freshness.dirty || !link.isOpen || !catalogAsked || phone.castLive()) return
        if (pushJob?.isActive == true) return
        val socket = generation
        pushJob = scope.launch {
            delay(PUSH_DEBOUNCE_MS)
            catalogLock.withLock {
                if (socket != generation || phone.castLive()) return@withLock
                val current = currentCatalog() ?: return@withLock
                if (socket != generation) return@withLock
                deliver(CatalogDelivery.push(current.catalog, held), current)
            }
            if (pushJob === coroutineContext[Job]) pushJob = null
            if (socket == generation) schedulePush()
        }
    }

    private fun onCatWant(have: String?) {
        val socket = generation
        scope.launch {
            catalogLock.withLock {
                val current = currentCatalog() ?: return@withLock
                if (socket != generation) return@withLock
                catalogAsked = true
                deliver(CatalogDelivery.answer(have, current.catalog, held), current)
            }
            if (socket == generation) schedulePush()
        }
    }

    private fun deliver(answer: CatalogAnswer, current: Built) {
        val sent = when (answer) {
            CatalogAnswer.None -> true
            is CatalogAnswer.Patch -> link.sendText(answer.frame)
            is CatalogAnswer.Full -> link.sendBatch(answer.frames)
        }
        if (!sent) return
        // A knock's wake lock covers the MediaStore read and the build, up to this first delivery.
        TvReadyService.releaseKnockWake()
        held = current.catalog
        sentItems = current.items
        val kind = when (answer) {
            CatalogAnswer.None -> "none"
            is CatalogAnswer.Patch -> "patch"
            is CatalogAnswer.Full -> "full parts=${answer.frames.size - 2}"
        }
        FlickLog.i("library", "catalog sent=$kind count=${current.catalog.size} dropped=${current.catalog.dropped}")
        casting.afterDelivery()?.let { (castId, id) ->
            if (phone.castLive() && held?.json(id) != null) link.sendText(LibraryFrames.casting(castId, id))
        }
    }

    /** The catalog as the phone's state stands, rebuilt only when something it reads has moved. */
    private suspend fun currentCatalog(): Built? {
        built?.takeIf { !freshness.dirty }?.let { return it }
        // A library still on its first read would tell the TV the phone has nothing.
        if (phone.library.value.items.isEmpty()) {
            withTimeoutOrNull(LIBRARY_WAIT_MS) {
                combine(phone.libraryLoading, phone.libraryComplete) { loading, complete -> !loading && complete != null }
                    .first { it }
            }
        }
        val token = freshness.buildStarting()
        val items = phone.library.value.items
        val progress = phone.playbackProgress.value
        val checkpoints = (progress as? PlaybackProgressState.Ready)?.checkpoints.orEmpty()
        val watchedNow = watchedStore.state.value
        val unplayable = phone.unplayableFiles.value.keys
        val silent = phone.silentAudioFiles.value.keys
        val simplified = phone.simplifiedVideoNames.value
        val postersOn = phone.filmPosters.value && FilmPosters.available
        val generic = context.getString(R.string.media_title_generic)
        val startedAtNs = System.nanoTime()
        val next = withContext(Dispatchers.Default) {
            val rows = items.map { item ->
                CatalogSourceRow(
                    fingerprint = PlaybackMediaFingerprint.of(item),
                    uriKey = item.uriKey,
                    name = item.name,
                    durationMs = item.durationMs,
                    addedSeconds = item.dateAddedSeconds,
                    relativePath = item.relativePath,
                    width = item.width,
                    height = item.height,
                )
            }
            val byKey = items.associateBy { it.uriKey }
            val inputs = CatalogInputs(
                simplifiedNames = simplified,
                genericTitle = generic,
                editionLabel = { context.getString(it.labelResource()) },
                resumeMs = { fingerprint, durationMs -> resumePositionMs(progress, fingerprint, durationMs) },
                checkpoint = { fingerprint -> checkpoints[fingerprint] },
                watched = watchedNow,
                unplayable = unplayable,
                silent = silent,
                art = if (postersOn) { query -> FilmPosters.cached(context, query) } else null,
                hdr = { key -> LibraryHdr.wire(byKey[key]?.let { MediaProbe.cachedHdr(it.uri) }) },
            )
            names.retain(items.mapTo(HashSet()) { it.name })
            val catalogItems = LibraryCatalogBuilder.build(rows, inputs, names)
            val encoded = EncodedCatalog.of(catalogItems)
            val byId = HashMap<String, MediaItem>(items.size)
            catalogItems.forEachIndexed { index, item -> byId.putIfAbsent(item.id, items[index]) }
            Built(encoded, byId)
        }
        FlickLog.d("library", "catalog built count=${next.catalog.size} ms=${(System.nanoTime() - startedAtNs) / 1_000_000L}")
        built = next
        freshness.built(token)
        return next
    }

    // --- Requests (contract §6) ---------------------------------------------

    private fun onTvFrame(frame: TvFrame) {
        // Every request is the TV in use, which is what the ready window measures.
        touchReadyWindow()
        when (frame) {
            is TvFrame.CatWant -> onCatWant(frame.have)
            is TvFrame.Play -> onPlay(frame)
            is TvFrame.DetailWant -> onDetail(frame.id)
            is TvFrame.SeasonWant -> onSeason(frame.showKey, frame.season)
            is TvFrame.ThumbWant -> onThumb(frame.id)
            TvFrame.Refresh -> if (refreshGate.tryPass(now())) phone.refreshLibrary()
            TvFrame.Browsing -> Unit
            else -> Unit
        }
    }

    private fun onPlay(frame: TvFrame.Play) {
        if (playOutstanding) return
        playOutstanding = true
        val socket = generation
        val item = sentItems[frame.id]?.takeIf { held?.json(frame.id) != null }
        val refusal = LibraryPlayPolicy.refusal(
            enabled = preference.enabled.value,
            listed = item != null,
            startInFlight = phone.castStartInFlight(),
            mayStartForeground = mayStartForegroundService(),
        )
        if (refusal != null || item == null) {
            answerPlay(socket, frame.req, refusal ?: PlayRefusal.MISSING)
            return
        }
        scope.launch {
            // A hung provider must not hold the answer past the 2 s playAck deadline, and a
            // blocking open is not cancellable, so it runs apart and is only waited on.
            val check = async(Dispatchers.IO) { isReadable(item) }
            val readable = withTimeoutOrNull(READABLE_CHECK_MS) { check.await() }
            if (readable == null) check.cancel()
            if (socket != generation) return@launch
            // Asked again: a tap on the phone may have started a cast while the file was opened.
            val late = LibraryPlayPolicy.refusal(
                enabled = preference.enabled.value,
                listed = true,
                startInFlight = phone.castStartInFlight(),
                mayStartForeground = mayStartForegroundService(),
            ) ?: PlayRefusal.UNAVAILABLE.takeIf { readable == false }
            if (late != null) {
                answerPlay(socket, frame.req, late)
                return@launch
            }
            phone.play(item, frame.fromStart)
            answerPlay(socket, frame.req, null)
        }
    }

    private fun answerPlay(socket: Long, req: String, refusal: PlayRefusal?) {
        if (socket != generation) return
        playOutstanding = false
        link.sendText(LibraryFrames.playAck(req, refusal == null, refusal))
        FlickLog.i("library", "play ok=${refusal == null} code=${refusal?.wire ?: "none"}")
    }

    private fun isReadable(item: MediaItem): Boolean =
        runCatching { context.contentResolver.openFileDescriptor(item.uri, "r")?.use { true } ?: false }
            .getOrDefault(false)

    private fun onDetail(id: String) {
        if (!detailGate.tryAdmit(now())) return
        val socket = generation
        val item = sentItems[id]
        val postersOn = phone.filmPosters.value
        scope.launch(answers) {
            detailGate.run { answerDetail(socket, id, item, postersOn) }
        }
    }

    private suspend fun answerDetail(socket: Long, id: String, item: MediaItem?, postersOn: Boolean) {
        var overview: String? = null
        var tagline: String? = null
        var hdr: CatalogItem.Hdr? = null
        var subtitle: String? = null
        if (item != null) {
            hdr = LibraryHdr.wire(MediaProbe.cachedHdr(item.uri))
            subtitle = phone.subtitleLabel(item)
            val parsed = withContext(Dispatchers.Default) { VideoNames.parse(item.name) }
            val base = if (postersOn && FilmDetailsStore.available) FilmPosterPolicy.queryFor(parsed) else null
            if (base != null) {
                // The film the art was found as, by id, as the phone's own detail page asks.
                val art = withContext(Dispatchers.IO) { FilmPosters.cached(context, base) }
                val tmdbId = art?.tmdbId
                val show = art?.show.takeIf { tmdbId != null }
                val query = if (show != null) base.copy(show = show) else base
                val details = FilmDetailsStore.details(context, query, tmdbId)
                overview = details?.overview
                tagline = details?.tagline
            }
        }
        if (socket != generation) return
        link.sendText(LibraryFrames.detail(id, overview, tagline, hdr, subtitle))
    }

    private fun onSeason(showKey: String, season: Int) {
        if (!seasonGate.tryAdmit(now())) return
        val socket = generation
        val tvId = TvSeasonPolicy.tmdbId(showKey)?.takeIf { phone.filmPosters.value }
        scope.launch(answers) {
            val episodes = seasonGate.run { tvId?.let { TvSeasons.episodes(context, it, season) }.orEmpty() }
            if (socket != generation) return@launch
            link.sendBatch(LibraryFrames.season(showKey, season, episodes))
        }
    }

    private fun onThumb(id: String) {
        if (!thumbLimiter.tryPass(now())) return
        val socket = generation
        val item = sentItems[id]
        scope.launch {
            val frame = thumbGate.withPermit {
                if (socket != generation || item == null) return@withPermit null
                withContext(Dispatchers.IO) {
                    runCatching { LibraryThumbs.jpeg(context, item) }.getOrNull()
                }?.let { LibraryFrames.thumb(id, it) }
            }
            if (socket != generation) return@launch
            if (frame != null) link.sendBinary(frame) else link.sendText(LibraryFrames.thumbMiss(id))
        }
    }

    /** Called for every control-v2 `loadMedia`, whoever started the cast (contract §6.6). */
    fun onLoadMedia(castId: String, item: MediaItem) {
        val id = LibraryProtocol.itemId(PlaybackMediaFingerprint.of(item))
        casting.loaded(castId, id)
        if (!link.isOpen || held?.json(id) == null) return
        link.sendText(LibraryFrames.casting(castId, id))
    }

    // --- Art prefetch -------------------------------------------------------

    /**
     * Art for files the phone's grid never scrolled to, so the TV is not left with frames
     * where posters belong. FilmPosters' own eligibility, cache, backoff and in-flight
     * sharing apply; this only decides which titles to ask about and how many at once.
     */
    private fun startPrefetch() {
        if (!link.isOpen || !phone.filmPosters.value || !FilmPosters.available) return
        if (prefetchJob?.isActive == true) return
        val items = phone.library.value.items
        prefetchJob = scope.launch(Dispatchers.IO) {
            val queries = items.asSequence()
                .mapNotNull { FilmPosterPolicy.queryFor(VideoNames.parse(it.name)) }
                .distinctBy { it.cacheKey }
                .filter { FilmPosters.cached(context, it) == null }
                .toList()
            if (queries.isEmpty()) return@launch
            FlickLog.d("library", "art prefetch count=${queries.size}")
            val gate = Semaphore(PREFETCH_PARALLELISM)
            kotlinx.coroutines.coroutineScope {
                for (query in queries) {
                    gate.acquire()
                    launch {
                        try {
                            FilmPosters.art(context, query)
                        } finally {
                            gate.release()
                        }
                    }
                }
            }
        }
    }

    private fun stopPrefetch() {
        prefetchJob?.cancel()
        prefetchJob = null
    }

    // --- Watched ledger (contract §9) ---------------------------------------

    /**
     * A cast reached its first frame. Called before the progress recorder activates: a replay
     * from the top clears the checkpoint, and a finish-zone checkpoint is the only record of a
     * finish the ledger never saw, so it is backfilled here while it is still read.
     */
    fun onCastActive(castId: String, item: MediaItem) {
        val fingerprint = PlaybackMediaFingerprint.of(item)
        watchedRecorder.activate(castId, fingerprint)
        val checkpoint = (phone.playbackProgress.value as? PlaybackProgressState.Ready)?.checkpoints?.get(fingerprint)
        WatchedLedgerPolicy.backfillAtMs(
            checkpoint,
            item.durationMs,
            recorded = fingerprint in watchedStore.state.value,
        )?.let { watchedStore.backfill(fingerprint, it) }
    }

    fun onConfirmed(castId: String, positionMs: Long, durationMs: Long, phase: PlaybackPhase) {
        watchedRecorder.onConfirmed(castId, positionMs, durationMs, phase)?.let(watchedStore::mark)
        if (phase == PlaybackPhase.ENDED) touchReadyWindow()
    }

    /**
     * A cast's teardown, whatever ended it. The ready window is decided a dispatch later: a
     * new film supersedes the old one through this same teardown, and it is that film's
     * start which must be seen, not the gap before it.
     */
    fun onCastCleanup(castId: String) {
        watchedRecorder.finish(castId)?.let(watchedStore::mark)
        casting.ended(castId)
        scope.launch(Dispatchers.Main) {
            openReadyWindow(justLeftForeground = false)
            schedulePush()
        }
    }

    /**
     * A cast that was queued or behind its liveness check is no longer, whether it started or
     * not. One that never started has no [onCastCleanup], so the push it held back is asked
     * for here. Asked a dispatch later, once a cast that did start is live.
     */
    fun onCastQueueCleared() {
        scope.launch(Dispatchers.Main) { schedulePush() }
    }

    // --- Ready window and ready mode (contract §8) ---------------------------

    /** The app left the viewer's sight. */
    fun onAppBackground() {
        openReadyWindow(justLeftForeground = true)
    }

    /** The activity started: a Stop pressed before this no longer holds ready mode back. */
    fun onAppForeground() {
        if (preferenceStore.stoppedSinceOpened()) preferenceStore.saveStopped(false)
    }

    /** The phone booted or Flick was updated (§8): only ready mode starts from here. */
    fun onSystemStart() {
        if (readyModeHolds()) openReadyWindow(justLeftForeground = false)
    }

    /**
     * `TvReadyService` was restarted by the platform after the process died. The TV name for
     * its notification when ready mode still holds; null and the service stops.
     */
    fun readyServiceRestarted(): String? {
        if (!readyModeHolds() || phone.castLive()) return null
        if (!readyWindow.open) {
            readyWindow.opened(now(), ready = true)
            FlickLog.i("library", "ready window opened mode=ready restart=true")
            scheduleReadyDeadline()
        }
        return readyName()
    }

    /**
     * [justLeftForeground] is the activity stopping right now. Android lists "the app
     * transitions from a user-visible state, such as an activity" among the exemptions from
     * the background start restriction, so that one moment counts as visible.
     */
    private fun openReadyWindow(justLeftForeground: Boolean) {
        val ready = readyModeHolds()
        if (readyWindow.open) {
            if (ready != readyWindow.readyMode) switchReadyMode(ready)
            touchReadyWindow()
            return
        }
        val allowed = TvReadyWindowPolicy.mayOpen(
            enabled = preference.enabled.value,
            controlAuthenticated = phone.control.connection.value == ConnectionStatus.CONNECTED &&
                phone.control.authenticatedEndpoint() != null,
            linkOpen = link.isOpen,
            // Not the media server's status: it still reads RUNNING until the stop it was just
            // sent lands, and that is exactly when the window has to be started.
            castServing = phone.castLive(),
            mayStartForeground = justLeftForeground || mayStartForegroundService(),
            readyMode = ready,
        )
        if (!allowed) return
        if (!TvReadyService.start(context, readyName(), ready)) return
        readyWindow.opened(now(), ready)
        FlickLog.i("library", "ready window opened mode=${if (ready) "ready" else "window"}")
        scheduleReadyDeadline()
    }

    /** Ready mode's conditions moved while the window is open: the service follows. */
    private fun reconcileReadyMode() {
        if (!readyWindow.open) return
        val ready = readyModeHolds()
        if (ready != readyWindow.readyMode) switchReadyMode(ready)
    }

    private fun switchReadyMode(ready: Boolean) {
        // A promoted service takes a new start command whatever this process's state is.
        if (!TvReadyService.start(context, readyName(), ready)) {
            // Still in ready mode with what it needs withdrawn, and nothing would ever end it.
            if (!ready) closeReadyWindow("mode_refused")
            return
        }
        readyWindow.modeChanged(ready)
        FlickLog.i("library", "ready window mode=${if (ready) "ready" else "window"}")
        scheduleReadyDeadline()
    }

    private fun readyModeHolds(): Boolean = TvReadyMode.holds(
        enabled = preference.enabled.value,
        verifiedPairing = phone.readyPairing() != null,
        mediaAccess = phone.mediaAccessGranted(),
        batteryExempt = batteryExempt(),
        stoppedSinceOpened = preferenceStore.stoppedSinceOpened(),
    )

    private fun readyName(): String? = phone.tvName() ?: phone.readyPairing()?.name

    private fun touchReadyWindow() {
        if (!readyWindow.open) return
        readyWindow.touch(now())
        scheduleReadyDeadline()
    }

    private fun scheduleReadyDeadline() {
        readyDeadlineJob?.cancel()
        readyDeadlineJob = null
        val deadline = readyWindow.deadlineMs() ?: return
        readyDeadlineJob = scope.launch {
            delay((deadline - now()).coerceAtLeast(0L))
            if (!readyWindow.expired(now())) {
                scheduleReadyDeadline()
                return@launch
            }
            // Asked here rather than kept current: the battery exemption has no callback.
            reconcileReadyMode()
            if (!readyWindow.expired(now())) {
                scheduleReadyDeadline()
                return@launch
            }
            val expiry = readyWindow.onExpired(
                now(),
                castLive = phone.castLive(),
                dialInFlight = phone.dialInFlight(),
                visible = phone.visible(),
            )
            when (expiry) {
                TvReadyWindowPolicy.Expiry.CLOSE -> closeReadyWindow("expired")
                TvReadyWindowPolicy.Expiry.HANG_UP -> hangUpIdle()
                TvReadyWindowPolicy.Expiry.TOUCH -> scheduleReadyDeadline()
            }
        }
    }

    /**
     * §8 ready mode: nobody has used the TV link for the window's length, so the phone stops
     * paying for control's pings. The service and the doorbell stay, and the TV knocks when
     * it is next opened.
     */
    private fun hangUpIdle() {
        readyDeadlineJob = null
        val linked = link.isOpen || phone.control.connection.value == ConnectionStatus.CONNECTED
        closeLink("idle")
        if (phone.control.connection.value != ConnectionStatus.DISCONNECTED) phone.hangUpControl()
        if (linked) FlickLog.i("library", "ready idle hang_up")
    }

    private fun closeReadyWindow(reason: String) {
        if (!readyWindow.open) return
        readyWindow.closed()
        readyDeadlineJob?.cancel()
        readyDeadlineJob = null
        TvReadyService.stop(context)
        FlickLog.i("library", "ready window closed reason=$reason")
    }

    private fun readyWindowEnded(end: TvReadyService.End) {
        if (end == TvReadyService.End.USER) preferenceStore.saveStopped(true)
        if (!readyWindow.open) return
        readyWindow.closed()
        readyDeadlineJob?.cancel()
        readyDeadlineJob = null
        FlickLog.i("library", "ready window closed reason=${if (end == TvReadyService.End.USER) "stop" else "refused"}")
    }

    // --- Doorbell (contract §13) ---------------------------------------------

    /** The listener bound, moved, or stopped. */
    fun onDoorbellPort(port: Int?) {
        doorbellPort = port
        sendKnockAt()
    }

    private fun sendKnockAt() {
        if (!link.isOpen) return
        val port = KnockPolicy.knockAtPort(doorbellPort, knockAtSent) ?: return
        if (link.sendText(LibraryFrames.knockAt(port))) knockAtSent = port
    }

    /**
     * Which `knockOk` a verified knock from [tvId] gets. On the doorbell's listener thread,
     * before [onKnock]: everything read here is safe off main.
     */
    fun knockReply(tvId: String): KnockReply = KnockReplyPolicy.reply(
        snoozed = promptSnooze.isSnoozed(tvId, now()),
        mode = connectMode.value,
        visible = phone.visible(),
        notificationsAvailable = ConnectPrompt.available(context),
        resumingThatTv = phone.silentResumeTv() == tvId,
    )

    /**
     * A verified knock from (tvId, keyId), with the knock's wake lock held. Nothing here
     * moves the visible screen: the resume is the silent one, and the library opens behind it.
     * [knockHost] is where the knock came from: a candidate behind the stored endpoint only.
     * [reply] is what the TV was already told.
     */
    fun onKnock(tvId: String, keyId: String, knockHost: String?, reply: KnockReply) {
        phone.ensureMediaAccess()
        val pairing = phone.pairing(tvId)?.takeIf { it.keyId == keyId }
        if (pairing == null) {
            cancelPrompt(tvId)
            journalKnock("no_pairing")
            TvReadyService.releaseKnockWake()
            return
        }
        if (reply == KnockReply.DECLINED) {
            journalKnock("declined")
            TvReadyService.releaseKnockWake()
            return
        }
        touchReadyWindow()
        // The earlier knock still waiting on its check gives way, and is journaled as such.
        if (knockCheck?.isActive == true) journalKnock("superseded")
        knockCheck?.cancel()
        knockCheck = null
        val line = phone.control.line()
        val check = KnockResponse.needsLivenessCheck(
            castLive = phone.castLive(),
            knockResumingTv = phone.knockResumingTv(),
            control = knockControl(tvId, keyId),
            visible = phone.visible(),
        )
        if (line == null || !check) {
            answerKnock(pairing, knockHost, controlAlive = false, reply)
            return
        }
        knockCheck = scope.launch {
            val verdict = phone.control.confirmAlive(line, roundTrip = true)
            ensureActive()
            // A line installed while the ping was out is fresh, whatever this one did.
            val alive = verdict != Liveness.DEAD || phone.control.line() !== line
            if (!alive) FlickLog.i("library", "knock control unanswered")
            answerKnock(pairing, knockHost, alive, reply)
        }
    }

    private fun knockControl(tvId: String, keyId: String): KnockControl {
        val endpoint = phone.control.authenticatedEndpoint()
            ?.takeIf { phone.control.connection.value == ConnectionStatus.CONNECTED }
            ?: return KnockControl.NONE
        return if (endpoint.tvId == tvId && endpoint.keyId == keyId) KnockControl.THAT_TV else KnockControl.OTHER_TV
    }

    private fun answerKnock(pairing: PairingStore.Pairing, knockHost: String?, controlAlive: Boolean, reply: KnockReply) {
        val control = knockControl(pairing.tvId, pairing.keyId)
        val endpoint = phone.control.authenticatedEndpoint()
        val action = KnockResponse.action(
            tvId = pairing.tvId,
            castLive = phone.castLive(),
            knockResumingTv = phone.knockResumingTv(),
            control = control,
            libraryOpen = control == KnockControl.THAT_TV && link.isOpen && linkFor == endpoint,
            dialing = phone.dialing(),
            visible = phone.visible(),
            controlAlive = controlAlive,
        )
        when (action) {
            KnockAction.CAST_LIVE, KnockAction.BUSY -> TvReadyService.releaseKnockWake()
            // The resume in flight releases the lock when it fails, the first catalog when it works.
            KnockAction.RESUMING -> phone.lateResumeHint(pairing.tvId, knockHost)
            KnockAction.LINKED, KnockAction.LIBRARY -> {
                closeLink("knock")
                reconcile()
            }
            KnockAction.RESUME -> {
                if (resumeForKnock(pairing, knockHost, reply)) return
            }
        }
        journalKnock(action.journal)
    }

    /** True when it journaled the knock itself. */
    private fun resumeForKnock(pairing: PairingStore.Pairing, knockHost: String?, reply: KnockReply): Boolean {
        val mode = connectMode.value
        val visibleNow = phone.visible()
        var step = KnockReplyPolicy.resumeStep(
            reply,
            mode,
            visibleNow,
            promptAvailable = KnockReplyPolicy.needsPromptCheck(reply, mode, visibleNow) && ConnectPrompt.available(context),
        )
        if (step == KnockReplyPolicy.ResumeStep.PROMPT) {
            val posted = runCatching { ConnectPrompt.post(context, pairing.tvId, pairing.name) }.getOrDefault(false)
            if (posted) {
                promptHints[pairing.tvId] = knockHost
                TvReadyService.releaseKnockWake()
                journalKnock("asked")
                return true
            }
            step = KnockReplyPolicy.ResumeStep.CONNECT_UNPROMPTED
        }
        if (step == KnockReplyPolicy.ResumeStep.CONNECT_UNPROMPTED) {
            FlickLog.i("library", "prompt unavailable")
            IncidentJournal.record("prompt_unavailable")
        }
        if (step == KnockReplyPolicy.ResumeStep.NOTHING) {
            TvReadyService.releaseKnockWake()
            return false
        }
        if (!phone.silentResume(pairing, knockHost, SilentResumeKind.KNOCK)) TvReadyService.releaseKnockWake()
        return false
    }

    private fun journalKnock(outcome: String) {
        FlickLog.i("library", "knock outcome=$outcome")
        IncidentJournal.record("knock", "outcome" to outcome)
    }

    // --- The "Connect?" prompt (contract §13.5) --------------------------------

    /** "Connect" or "Not now" on the prompt for [tvId], on main. */
    fun onPromptChoice(tvId: String, connect: Boolean) {
        ConnectPrompt.cancel(context, tvId)
        val hint = promptHints.remove(tvId)
        if (!connect) {
            promptSnooze.snooze(tvId, now())
            journalPrompt("later")
            return
        }
        promptSnooze.clear(tvId)
        phone.ensureMediaAccess()
        val pairing = phone.pairing(tvId)
        val skipped = when {
            pairing == null -> "no_pairing"
            phone.castLive() -> "cast_live"
            !preference.enabled.value -> "setting"
            else -> when (knockControl(tvId, pairing.keyId)) {
                KnockControl.THAT_TV -> "linked"
                KnockControl.OTHER_TV -> "other_tv"
                KnockControl.NONE -> null
            }
        }
        if (skipped != null || pairing == null) {
            journalPrompt("connect", skipped ?: "no_pairing")
            return
        }
        touchReadyWindow()
        // A body tap reaches a stopped activity after onStart's connect-on-open has dialed.
        val step = PromptConnectPolicy.step(
            tvId = tvId,
            resumingTv = phone.knockResumingTv(),
            resumingKind = phone.silentResumeKind(),
            dialing = phone.dialing(),
            otherDialing = phone.dialingBesidesSilentResume(),
        )
        when (step) {
            PromptConnectPolicy.Step.JOIN -> {
                TvReadyService.acquireKnockWake(context)
                phone.lateResumeHint(tvId, hint)
                journalPrompt("connect", "resuming")
                return
            }
            PromptConnectPolicy.Step.BUSY -> {
                journalPrompt("connect", "busy")
                return
            }
            PromptConnectPolicy.Step.SUPERSEDE -> phone.supersedeSilentResume()
            PromptConnectPolicy.Step.START -> Unit
        }
        // The resume's failure path releases it, the library's first catalog its success.
        TvReadyService.acquireKnockWake(context)
        if (!phone.silentResume(pairing, hint, SilentResumeKind.PROMPT)) {
            TvReadyService.releaseKnockWake()
            journalPrompt("connect", "busy")
            return
        }
        journalPrompt("connect")
    }

    private fun cancelPrompt(tvId: String) {
        if (!promptHints.containsKey(tvId)) return
        promptHints.remove(tvId)
        ConnectPrompt.cancel(context, tvId)
    }

    private fun cancelPrompts() {
        promptHints.keys.toList().forEach(::cancelPrompt)
    }

    private fun journalPrompt(choice: String, skipped: String? = null) {
        FlickLog.i("library", "prompt choice=$choice${skipped?.let { " skipped=$it" } ?: ""}")
        if (skipped == null) IncidentJournal.record("prompt", "choice" to choice)
        else IncidentJournal.record("prompt", "choice" to choice, "skipped" to skipped)
    }

    /** An OPEN or PROMPT resume to [tvId] started: the person acted, so "Not now" no longer holds. */
    fun onPersonResume(tvId: String) {
        promptSnooze.clear(tvId)
    }

    // --- Helpers ------------------------------------------------------------

    private fun mayStartForegroundService(): Boolean = LibraryPlayPolicy.mayStartForegroundService(
        sdkInt = Build.VERSION.SDK_INT,
        visible = phone.visible(),
        batteryExempt = batteryExempt(),
        processAllowed = foregroundStartAllowed(Build.VERSION.SDK_INT, currentProcessImportance()),
    )

    private fun batteryExempt(): Boolean = runCatching {
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
    }.getOrDefault(false)

    private fun now(): Long = SystemClock.elapsedRealtime()

    private companion object {
        const val PUSH_DEBOUNCE_MS = 2_000L
        const val READABLE_CHECK_MS = 1_500L
        const val THUMBS_IN_FLIGHT = 6
        const val THUMBS_PER_WINDOW = 60
        const val THUMB_WINDOW_MS = 10_000L
        const val REFRESH_INTERVAL_MS = 10_000L
        const val PREFETCH_PARALLELISM = 2
        const val LIBRARY_WAIT_MS = 20_000L
        const val ANSWERS_IN_FLIGHT = 4
        const val ANSWERS_PER_WINDOW = 20
        const val ANSWER_WINDOW_MS = 10_000L
    }
}
