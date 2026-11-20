package com.flick.receiver.library

import androidx.compose.ui.graphics.ImageBitmap
import com.flick.receiver.util.FlickLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One authenticated library socket, as the hub drives it. Both calls return at once. */
interface LibraryLinkHandle {
    /** Queues [frame] behind the socket's single writer. */
    fun send(frame: String)

    /** Closes the socket with 1008; its session then reports [TvLibrary.onClosed]. */
    fun close(reason: String)
}

/**
 * The [LibraryHub] behind the TV's library screens, fed by [LibraryServer].
 *
 * Every mutable field is guarded by [lock]. It is a leaf lock: nothing called while holding
 * it takes another lock — [labelOf] must read a published snapshot, never a synchronized
 * store, because a forget holds the pairing store's monitor on its way to the control
 * server's lock, and only after both are released reaches [forget] here.
 *
 * Phones are kept apart by key id. The screens show the phone holding the control lease,
 * else the one that authenticated most recently, skipping a phone whose catalog is empty or
 * whose socket has been gone for [LOST_HIDE_MS]. They never switch phones under an Up next
 * card or a Play still in flight. A phone's catalog outlives its socket (§5.2) and goes only
 * with a forget or the process, so a hidden phone's reconnect still patches from it.
 */
class TvLibrary(
    private val labelOf: (keyId: String) -> String?,
    private val autoplayEnabled: () -> Boolean,
    private val decodeThumbnail: (ByteArray) -> ImageBitmap?,
    private val clock: () -> Long,
    private val newRequestId: () -> String = { LibraryAuth.randomNonce() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val creditsStorage: CreditsLeadStorage? = null,
) : LibraryHub {

    private class Phone(val keyId: String) {
        val assembler = CatalogAssembler()
        var link: LibraryLinkHandle? = null
        var authOrder = 0L

        /** When its socket closed; null while connected. */
        var lostAtMs: Long? = null

        /** The catalog the screens show, swapped only together with its shelves. */
        var shown: Pair<LibraryCatalog, LibraryShelves>? = null
        var shelvesSeq = 0L

        /** The newest catalog whose shelves wait for the cast on screen to end. */
        var pendingShelves: LibraryCatalog? = null

        val casting = CastingMap()
        val details = HashMap<String, LibraryDetail>()

        /** When each unanswered detail was asked: the phone may drop one silently (§6.2). */
        val detailsAsked = HashMap<String, Long>()
        val seasons = HashMap<LibrarySeasonKey, List<LibraryEpisodeInfo>>()
        val seasonsAsked = HashMap<LibrarySeasonKey, Long>()
        val seasonParts = SeasonAssembler()
        val thumbQueue = ThumbnailQueue()
        var thumbTimer: Job? = null
        var thumbTimerAtMs = 0L
        val thumbs = object : LinkedHashMap<String, ImageBitmap>(THUMB_CACHE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean = size > THUMB_CACHE
        }
        val refreshGate = LibraryRateGate(REFRESH_INTERVAL_MS)
        val browsingGate = LibraryRateGate(BROWSING_INTERVAL_MS)
    }

    private val lock = Any()
    private val phones = HashMap<String, Phone>()
    private var visibleKeyId: String? = null
    private var leaseKeyId: String? = null
    private var authCounter = 0L
    private val playMachine = LibraryPlayMachine()
    private var playTimer: Job? = null

    /** The phone the outstanding `play` went to: only its `playAck` counts. */
    private var playKeyId: String? = null
    private val streak = UpNextStreak()
    private var upNextCastId: String? = null

    /** The phone whose `casting` named the ended cast; Up next's `play` goes to it. */
    private var upNextKeyId: String? = null

    /** The cast on screen that ended, so a `casting` frame arriving after it can still offer Up next. */
    private var lastEndedCastId: String? = null
    private var autoplayTimer: Job? = null
    private var hideTimer: Job? = null

    /** A cast is on screen: shelf rebuilds wait for it to end. */
    private var castLive = false

    private val credits = CreditsLeadBook()

    /** Orders saves of [credits]; taken before [lock], never inside it. */
    private val creditsSaveLock = Any()

    /** The cast on screen, and the last position the app reported for it (-1 before any). */
    private var liveCastId: String? = null
    private var livePosMs = -1L
    private var liveDurationMs = 0L

    /** Leaving after `ended`, or after an autoplay, says nothing about where the credits start. */
    private var liveSampleSettled = false

    /** Back hid this cast's card: its tail does not offer it again, though `ended` still does. */
    private var tailDismissedCastId: String? = null

    /** The card on screen was offered from the tail, before `ended`. */
    private var offerFromTail = false

    /**
     * This cast's episode has no next one in this catalog (a finale): its tail does not
     * scan the catalog again until the catalog or the cast changes.
     */
    private var tailNoNextCastId: String? = null
    private var tailNoNextCatalog: LibraryCatalog? = null

    private val _state = MutableStateFlow(LibraryState())
    private val _details = MutableStateFlow<Map<String, LibraryDetail>>(emptyMap())
    private val _seasons = MutableStateFlow<Map<LibrarySeasonKey, List<LibraryEpisodeInfo>>>(emptyMap())
    private val _thumbnails = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    private val _upNext = MutableStateFlow<UpNextOffer?>(null)
    private val _anyLinked = MutableStateFlow(false)

    override val state: StateFlow<LibraryState> = _state.asStateFlow()
    override val details: StateFlow<Map<String, LibraryDetail>> = _details.asStateFlow()
    override val seasons: StateFlow<Map<LibrarySeasonKey, List<LibraryEpisodeInfo>>> = _seasons.asStateFlow()
    override val thumbnails: StateFlow<Map<String, ImageBitmap>> = _thumbnails.asStateFlow()
    override val upNext: StateFlow<UpNextOffer?> = _upNext.asStateFlow()

    /** Whether any phone's library socket is authenticated, shown or not: an empty library is still a link. */
    val anyLinked: StateFlow<Boolean> = _anyLinked.asStateFlow()

    init {
        creditsStorage?.let { storage -> runCatching { storage.load() }.getOrNull()?.let(credits::decode) }
    }

    /** What the session should do after a frame. */
    enum class Verdict { CONTINUE, CLOSE }

    // --- From LibraryServer --------------------------------------------------

    /**
     * Installs a proven session as [keyId]'s link, unless [stillValid] — evaluated under
     * [lock] — says a forget has revoked it since its key was read. An older socket for the
     * same phone is closed: one session per phone, the newer wins.
     */
    fun onAuthenticated(keyId: String, link: LibraryLinkHandle, stillValid: () -> Boolean): Boolean = synchronized(lock) {
        if (!stillValid()) return false
        val phone = phones.getOrPut(keyId) { Phone(keyId) }
        val older = phone.link
        phone.link = link
        phone.lostAtMs = null
        phone.authOrder = ++authCounter
        older?.close("superseded")
        // The superseded socket's session finds no phone holding it, so its Play settles here.
        if (older != null && playKeyId == keyId) {
            playMachine.onLinkLost()
            schedulePlayTimerLocked()
        }
        phone.thumbQueue.onDisconnect()
        // Requests the old socket never answered are asked again on this one.
        phone.detailsAsked.keys.retainAll(phone.details.keys)
        phone.seasonsAsked.keys.retainAll(phone.seasons.keys)
        phone.seasonParts.clear()
        link.send(LibraryFrames.catWant(phone.assembler.current?.rev))
        pumpThumbnailsLocked(phone)
        scheduleHideTimerLocked()
        publishStateLocked()
        true
    }

    /** The phones whose library socket is authenticated now. */
    fun linkedKeyIds(): Set<String> = synchronized(lock) { phones.values.filter { it.link != null }.mapTo(HashSet()) { it.keyId } }

    fun onClosed(link: LibraryLinkHandle) = synchronized(lock) {
        val phone = phones.values.firstOrNull { it.link === link } ?: return@synchronized
        phone.link = null
        phone.lostAtMs = clock()
        phone.thumbQueue.onDisconnect()
        cancelThumbTimerLocked(phone)
        if (phone.keyId == playKeyId) {
            playMachine.onLinkLost()
            schedulePlayTimerLocked()
        }
        if (phone.keyId == upNextKeyId) clearUpNextLocked()
        scheduleHideTimerLocked()
        publishStateLocked()
    }

    fun onFrame(link: LibraryLinkHandle, frame: PhoneFrame): Verdict = synchronized(lock) {
        val phone = phones.values.firstOrNull { it.link === link } ?: return Verdict.CLOSE
        val visible = phone.keyId == visibleKeyId
        when (frame) {
            is PhoneFrame.CatBegin -> return catalogOutcome(phone, phone.assembler.begin(frame.rev, frame.count, frame.parts))
            is PhoneFrame.CatPart -> return catalogOutcome(phone, phone.assembler.part(frame.rev, frame.index, frame.items))
            is PhoneFrame.CatEnd -> return catalogOutcome(phone, phone.assembler.end(frame.rev))
            is PhoneFrame.CatPatch -> return catalogOutcome(phone, phone.assembler.patch(frame.base, frame.rev, frame.upsert, frame.remove))
            is PhoneFrame.PlayAck -> if (phone.keyId == playKeyId && playMachine.onAck(frame.req, frame.ok, frame.refusal, clock())) {
                FlickLog.i("library", "playAck ok=${frame.ok} code=${frame.refusal?.wire ?: "none"}")
                schedulePlayTimerLocked()
                publishStateLocked()
            }
            is PhoneFrame.Detail -> if (frame.id in phone.detailsAsked.keys && frame.id !in phone.details) {
                phone.details[frame.id] = LibraryDetail(frame.overview, frame.tagline, frame.hdr, frame.subtitle)
                if (visible) publishDataLocked()
            }
            is PhoneFrame.Season -> {
                val key = LibrarySeasonKey(frame.showKey, frame.season)
                if (key in phone.seasonsAsked.keys && key !in phone.seasons) {
                    phone.seasonParts.add(key, frame.part, frame.parts, frame.episodes)?.let { episodes ->
                        phone.seasons[key] = episodes
                        if (visible) publishDataLocked()
                    }
                }
            }
            is PhoneFrame.ThumbMiss -> {
                phone.thumbQueue.onMiss(frame.id)
                pumpThumbnailsLocked(phone)
            }
            is PhoneFrame.Casting -> {
                phone.casting.put(frame.castId, frame.id)
                // Frames on the two sockets are unordered (§6.6): the cast may already have ended.
                // A re-sent one, after a reconnect, decides again: the first decision may have
                // been made, or its card cleared, while the socket was down.
                if (frame.castId == lastEndedCastId && _upNext.value == null) offerUpNextLocked(frame.castId)
            }
            // Recorded by the server before the hub, so the address book is never written under this lock.
            is PhoneFrame.KnockAt -> Unit
            is PhoneFrame.Unknown -> FlickLog.i("library", "frame ignored t=${typeLabel(frame.type)}")
            is PhoneFrame.Hello, is PhoneFrame.Proof, PhoneFrame.Malformed -> return Verdict.CLOSE
        }
        Verdict.CONTINUE
    }

    /** A binary `FLT1` frame. Decoded off the socket's reader, and only while it is still wanted. */
    fun onThumbnail(link: LibraryLinkHandle, id: String, jpeg: ByteArray) {
        val phone = synchronized(lock) {
            val owner = phones.values.firstOrNull { it.link === link } ?: return
            if (!owner.thumbQueue.onArrived(id)) return
            pumpThumbnailsLocked(owner)
            owner
        }
        scope.launch(workDispatcher) {
            val image = runCatching { decodeThumbnail(jpeg) }.getOrNull()
            synchronized(lock) {
                if (phones[phone.keyId] !== phone) return@launch
                if (image == null) {
                    // Undecodable bytes are a miss: asking again would fetch the same bytes.
                    phone.thumbQueue.onMiss(id)
                    FlickLog.w("library", "thumb undecodable bytes=${jpeg.size}")
                    return@launch
                }
                phone.thumbs[id] = image
                if (phone.keyId == visibleKeyId) _thumbnails.value = HashMap(phone.thumbs)
            }
        }
    }

    /** Forget one phone: its socket closes and everything it sent is dropped. */
    fun forget(keyId: String) = synchronized(lock) {
        val phone = phones.remove(keyId) ?: return@synchronized
        phone.link?.close("forgotten")
        phone.link = null
        cancelThumbTimerLocked(phone)
        if (playKeyId == keyId) {
            playMachine.onLinkLost()
            schedulePlayTimerLocked()
            playKeyId = null
        }
        if (upNextKeyId == keyId) clearUpNextLocked()
        scheduleHideTimerLocked()
        publishStateLocked()
    }

    fun forgetAll() = synchronized(lock) {
        phones.values.forEach {
            it.link?.close("forgotten")
            cancelThumbTimerLocked(it)
        }
        phones.clear()
        visibleKeyId = null
        playMachine.onLinkLost()
        schedulePlayTimerLocked()
        playKeyId = null
        clearUpNextLocked()
        lastEndedCastId = null
        scheduleHideTimerLocked()
        publishDataLocked()
        publishStateLocked()
    }

    // --- From the app --------------------------------------------------------

    /**
     * A paired phone was renamed on this TV, or the TV came back from standby: the screens
     * name it afresh, and lost phones are re-judged on [clock], since timers stop in suspend.
     */
    fun onPhonesChanged() = synchronized(lock) {
        scheduleHideTimerLocked()
        publishStateLocked()
    }

    /** The phone holding the control lease, by key id; the screens prefer its library. */
    fun onControlPeer(keyId: String?) = synchronized(lock) {
        if (leaseKeyId == keyId) return@synchronized
        leaseKeyId = keyId
        publishStateLocked()
    }

    /**
     * The TV accepted a control-v2 `loadMedia` and started [castId]. A Play waiting on the
     * phone resolves here, and any Up next card belongs to the film this replaces.
     */
    fun onCastStarted(castId: String) = synchronized(lock) {
        castLive = true
        if (liveCastId != castId) {
            sampleLeavingLocked()
            liveCastId = castId
            livePosMs = -1L
            liveDurationMs = 0L
            liveSampleSettled = false
            tailDismissedCastId = null
        }
        playMachine.onCastStarted()
        schedulePlayTimerLocked()
        if (upNextCastId != castId) clearUpNextLocked()
        if (lastEndedCastId != castId) lastEndedCastId = null
        publishStateLocked()
    }

    /** [castId] reached `ended` on screen. */
    fun onCastEnded(castId: String) = synchronized(lock) {
        lastEndedCastId = castId
        if (castId == liveCastId) liveSampleSettled = true
        offerFromTail = false
        offerUpNextLocked(castId)
    }

    /**
     * Where the cast on screen is, playing or paused; at most once a second. Near the end,
     * within the show's credits lead, it offers Up next before `ended`.
     */
    fun onCastPosition(castId: String, posMs: Long, durationMs: Long) = synchronized(lock) {
        if (castId != liveCastId) return@synchronized
        livePosMs = posMs
        if (durationMs > 0L) liveDurationMs = durationMs
        tailLocked(castId)
    }

    /** The ended film was replayed or replaced: its card goes. */
    fun onCastNotEnded() = synchronized(lock) {
        lastEndedCastId = null
        clearUpNextLocked()
        publishStateLocked()
    }

    /**
     * No cast is on screen any more: its card goes, and shelves held back during it are built.
     * [viewerStoppedCastId] is the cast a stop command ended, if one did; only that exit
     * is a credits sample, never a failure, a lost link or a teardown.
     */
    fun onCastStopped(viewerStoppedCastId: String? = null) = synchronized(lock) {
        if (viewerStoppedCastId != null && viewerStoppedCastId == liveCastId) sampleLeavingLocked()
        liveCastId = null
        tailDismissedCastId = null
        castLive = false
        lastEndedCastId = null
        clearUpNextLocked()
        for (phone in phones.values) phone.pendingShelves?.let { buildShelvesLocked(phone, it) }
        publishStateLocked()
    }

    /**
     * Any key on the remote: someone is in the room. With a library screen showing it also
     * restarts the phone's ready window (§6.5), so an unattended screen lets it lapse.
     */
    fun onRemoteKey(libraryShowing: Boolean = false) = synchronized(lock) {
        streak.viewerPressed()
        if (libraryShowing) browsingLocked()
    }

    /** The composition that owns this hub is gone: no timer or decode outlives it. */
    fun shutdown() = scope.cancel()

    // --- LibraryHub ----------------------------------------------------------

    override fun play(id: String, from: PlayFrom) {
        synchronized(lock) { sendPlayLocked(visibleKeyId?.let(phones::get), id, from) }
    }

    override fun clearPlayResult() {
        synchronized(lock) {
            playMachine.clearResult()
            publishStateLocked()
        }
    }

    override fun wantDetail(id: String) {
        synchronized(lock) {
            val phone = connectedLocked() ?: return
            if (!LibraryWire.itemId(id) || id in phone.details || !askLocked(phone.detailsAsked, id)) return
            phone.link?.send(LibraryFrames.detailWant(id))
        }
    }

    override fun wantSeason(showKey: String, season: Int) {
        synchronized(lock) {
            val phone = connectedLocked() ?: return
            if (!showKey.startsWith(TMDB_SHOW_PREFIX) || season !in 0..999) return
            val key = LibrarySeasonKey(showKey, season)
            if (key in phone.seasons || !askLocked(phone.seasonsAsked, key)) return
            phone.link?.send(LibraryFrames.seasonWant(showKey, season))
        }
    }

    override fun wantThumbnail(id: String) {
        synchronized(lock) {
            val phone = visibleKeyId?.let(phones::get) ?: return
            val item = phone.assembler.current?.get(id) ?: return
            // A read, not a containment check: it refreshes the entry in the access-ordered cache.
            if (item.art != null || phone.thumbs[id] != null) return
            phone.thumbQueue.want(id, clock())
            pumpThumbnailsLocked(phone)
        }
    }

    override fun refresh() {
        synchronized(lock) {
            val phone = connectedLocked() ?: return
            if (phone.refreshGate.tryAcquire(clock())) phone.link?.send(LibraryFrames.refresh())
        }
    }

    override fun browsing() {
        synchronized(lock) { browsingLocked() }
    }

    override fun playUpNext() {
        synchronized(lock) { playUpNextLocked(autoplay = false) }
    }

    override fun dismissUpNext() {
        synchronized(lock) {
            if (_upNext.value != null) tailDismissedCastId = upNextCastId
            lastEndedCastId = null
            clearUpNextLocked()
            publishStateLocked()
        }
    }

    // --- Internals (all under lock) -------------------------------------------

    private fun connectedLocked(): Phone? = visibleKeyId?.let(phones::get)?.takeIf { it.link != null }

    /** Marks [key] asked unless an ask for it is still within [WANT_RETRY_MS]; true when it should be sent. */
    private fun <K> askLocked(asked: HashMap<K, Long>, key: K): Boolean {
        val now = clock()
        val at = asked[key]
        if (at != null && now - at < WANT_RETRY_MS) return false
        asked[key] = now
        return true
    }

    private fun browsingLocked() {
        val phone = connectedLocked() ?: return
        if (phone.browsingGate.tryAcquire(clock())) phone.link?.send(LibraryFrames.browsing())
    }

    private fun sendPlayLocked(phone: Phone?, id: String, from: PlayFrom) {
        val link = phone?.link
        val reqId = newRequestId()
        if (playMachine.request(id, link != null, reqId, clock())) {
            playKeyId = phone!!.keyId
            link!!.send(LibraryFrames.play(reqId, id, from))
            FlickLog.i("library", "play sent from=${from.wire}")
        }
        schedulePlayTimerLocked()
        publishStateLocked()
    }

    private fun playUpNextLocked(autoplay: Boolean) {
        autoplayTimer?.cancel()
        autoplayTimer = null
        val offer = _upNext.value ?: return
        if (upNextCastId == liveCastId) {
            if (autoplay) liveSampleSettled = true else sampleLeavingLocked()
        }
        // The card stays up, and reads the play's own state: asking, or why it failed.
        _upNext.value = offer.copy(autoplayAtMs = null, stillWatching = false)
        sendPlayLocked(upNextKeyId?.let(phones::get), offer.next.id, PlayFrom.START)
    }

    /** The catalog episode [castId] plays, from the newest phone whose `casting` named it. */
    private fun castEpisodeLocked(castId: String): LibraryItem? {
        val phone = phones.values.filter { it.casting[castId] != null }.maxByOrNull { it.authOrder } ?: return null
        return phone.casting[castId]?.let { phone.assembler.current?.get(it) }?.takeIf { it.isEpisode }
    }

    private fun liveRemainingLocked(item: LibraryItem): Long? {
        if (livePosMs < 0L) return null
        val duration = liveDurationMs.takeIf { it > 0L } ?: item.durationMs
        if (duration <= 0L) return null
        return duration - livePosMs.coerceIn(0L, duration)
    }

    private fun durationLocked(item: LibraryItem): Long = liveDurationMs.takeIf { it > 0L } ?: item.durationMs

    private fun capLocked(item: LibraryItem, catalog: LibraryCatalog?): Long =
        CreditsLead.capMs(durationLocked(item), phoneHasTimesLocked(catalog))

    private fun leadLocked(item: LibraryItem, catalog: LibraryCatalog?): Long =
        CreditsLead.effectiveLeadMs(credits.leadMs(item.showKey!!), durationLocked(item), phoneHasTimesLocked(catalog))

    /** Scanned once per catalog: the tail asks every second. */
    private var timesCatalog: LibraryCatalog? = null
    private var timesCatalogHas = false

    private fun phoneHasTimesLocked(catalog: LibraryCatalog?): Boolean {
        if (catalog == null) return false
        if (timesCatalog !== catalog) {
            timesCatalog = catalog
            timesCatalogHas = catalog.items.any { it.playedAtSeconds != null || it.watchedAtSeconds != null }
        }
        return timesCatalogHas
    }

    private fun tailLocked(castId: String) {
        if (lastEndedCastId == castId) return
        val offer = _upNext.value
        if (offer != null) {
            if (!offerFromTail || upNextCastId != castId) return
            val remaining = liveRemainingLocked(offer.ended) ?: return
            val play = playMachine.state
            val outstanding = (play is LibraryPlay.Asking && play.id == offer.next.id) ||
                (play is LibraryPlay.Starting && play.id == offer.next.id)
            val catalog = phones.values.filter { it.casting[castId] != null }.maxByOrNull { it.authOrder }?.assembler?.current
            val clearAt = minOf(leadLocked(offer.ended, catalog) + TAIL_CLEAR_SLACK_MS, capLocked(offer.ended, catalog))
            if (!outstanding && remaining > clearAt) {
                FlickLog.i("library", "upNext cleared seekBack")
                clearUpNextLocked()
                publishStateLocked()
            }
            return
        }
        if (tailDismissedCastId == castId) return
        val phone = phones.values.filter { it.casting[castId] != null }.maxByOrNull { it.authOrder } ?: return
        val catalog = phone.assembler.current ?: return
        if (tailNoNextCastId == castId && tailNoNextCatalog === catalog) return
        val item = phone.casting[castId]?.let(catalog::get)?.takeIf { it.isEpisode } ?: return
        val remaining = liveRemainingLocked(item) ?: return
        if (remaining > leadLocked(item, catalog)) return
        // Only a connected phone's answer is final; a disconnected one is asked again.
        if (phone.link != null && LibraryShelving.following(item, catalog) == null) {
            tailNoNextCastId = castId
            tailNoNextCatalog = catalog
            return
        }
        offerUpNextLocked(castId, fromTail = true)
    }

    /** The live cast is being left before `ended`: where, if it was in the credits, is a sample. */
    private fun sampleLeavingLocked() {
        val castId = liveCastId ?: return
        if (liveSampleSettled) return
        liveSampleSettled = true
        // Leaving from the tail card is where the card appeared, not where the credits start.
        if (offerFromTail && upNextCastId == castId && _upNext.value != null) return
        val item = castEpisodeLocked(castId) ?: return
        if (livePosMs < 0L) return
        val remaining = CreditsLead.sample(livePosMs, durationLocked(item)) ?: return
        credits.record(item.showKey!!, remaining)
        FlickLog.i("library", "credits sample remainingS=${remaining / 1_000L}")
        saveCreditsLocked()
    }

    private fun saveCreditsLocked() {
        val storage = creditsStorage ?: return
        scope.launch(workDispatcher) {
            synchronized(creditsSaveLock) {
                val encoded = synchronized(lock) { credits.encode() }
                runCatching { storage.save(encoded) }
            }
        }
    }

    private fun offerUpNextLocked(castId: String, fromTail: Boolean = false) {
        if (upNextCastId == castId && _upNext.value != null) return
        val phone = phones.values.filter { it.casting[castId] != null }.maxByOrNull { it.authOrder } ?: return
        val catalog = phone.assembler.current
        val offer = UpNextPolicy.offer(
            castItemId = phone.casting[castId],
            catalog = catalog,
            connected = phone.link != null,
            autoplayEnabled = autoplayEnabled(),
            streak = streak,
            nowMs = clock(),
            showOf = { ended -> showOf(phone, catalog, ended) },
        ) ?: return
        upNextCastId = castId
        upNextKeyId = phone.keyId
        offerFromTail = fromTail
        _upNext.value = offer
        // A refusal left over from an earlier ask must not open on the new card.
        if ((playMachine.state as? LibraryPlay.Failed)?.id == offer.next.id) {
            playMachine.clearResult()
            publishStateLocked()
        }
        FlickLog.i("library", "upNext offered autoplay=${offer.autoplayAtMs != null} stillWatching=${offer.stillWatching} tail=$fromTail")
        scheduleAutoplayLocked(offer)
    }

    private fun catalogOutcome(phone: Phone, outcome: CatalogAssembler.Outcome): Verdict {
        when (outcome) {
            CatalogAssembler.Outcome.Pending -> Unit
            CatalogAssembler.Outcome.NeedFull -> phone.link?.send(LibraryFrames.catWant(null))
            CatalogAssembler.Outcome.Violation -> {
                FlickLog.w("library", "catalog violation keyIdFp=${FlickLog.fp(phone.keyId)}")
                return Verdict.CLOSE
            }
            is CatalogAssembler.Outcome.Swapped -> {
                val catalog = outcome.catalog
                FlickLog.d("library", "catalog swapped items=${catalog.items.size} deferred=$castLive")
                phone.thumbQueue.retainOnly { id -> catalog[id]?.art == null }
                // The shelves are a full pass over the catalog; during a film only the newest is built, after it.
                if (castLive) phone.pendingShelves = catalog else buildShelvesLocked(phone, catalog)
            }
        }
        return Verdict.CONTINUE
    }

    private fun buildShelvesLocked(phone: Phone, catalog: LibraryCatalog) {
        phone.pendingShelves = null
        val seq = ++phone.shelvesSeq
        scope.launch(workDispatcher) {
            val shelves = LibraryShelving.shelves(catalog)
            synchronized(lock) {
                if (phones[phone.keyId] !== phone || phone.shelvesSeq != seq) return@launch
                phone.shown = catalog to shelves
                publishStateLocked()
            }
        }
    }

    private fun pumpThumbnailsLocked(phone: Phone) {
        val link = phone.link ?: return
        val now = clock()
        for (id in phone.thumbQueue.pump(now)) link.send(LibraryFrames.thumbWant(id))
        val due = phone.thumbQueue.nextPumpAtMs(now)
        if (due == null) {
            cancelThumbTimerLocked(phone)
            return
        }
        if (phone.thumbTimer?.isActive == true && phone.thumbTimerAtMs == due) return
        phone.thumbTimer?.cancel()
        phone.thumbTimerAtMs = due
        phone.thumbTimer = scope.launch {
            delay((due - clock()).coerceAtLeast(0L))
            synchronized(lock) {
                // Cancelled while waiting for the lock: a newer timer owns the queue.
                if (!isActive || phones[phone.keyId] !== phone) return@launch
                phone.thumbTimer = null
                pumpThumbnailsLocked(phone)
            }
        }
    }

    private fun cancelThumbTimerLocked(phone: Phone) {
        phone.thumbTimer?.cancel()
        phone.thumbTimer = null
    }

    private fun showOf(phone: Phone, catalog: LibraryCatalog?, ended: LibraryItem): LibraryShow? {
        phone.shown?.second?.shows?.firstOrNull { it.key == ended.showKey }?.let { return it }
        val episodes = catalog?.items?.filter { it.showKey == ended.showKey } ?: return null
        return LibraryShelving.shows(episodes).firstOrNull()
    }

    private fun schedulePlayTimerLocked() {
        playTimer?.cancel()
        playTimer = null
        val due = playMachine.deadlineAtMs ?: return
        playTimer = scope.launch {
            delay((due - clock()).coerceAtLeast(0L))
            synchronized(lock) {
                val before = playMachine.state
                playMachine.tick(clock())
                if (playMachine.state != before) {
                    FlickLog.i("library", "play timeout state=${playMachine.state::class.simpleName}")
                    publishStateLocked()
                }
                schedulePlayTimerLocked()
            }
        }
    }

    private fun scheduleAutoplayLocked(offer: UpNextOffer) {
        autoplayTimer?.cancel()
        autoplayTimer = null
        val due = offer.autoplayAtMs ?: return
        autoplayTimer = scope.launch {
            delay((due - clock()).coerceAtLeast(0L))
            synchronized(lock) {
                if (_upNext.value !== offer) return@launch
                streak.autoplayed()
                FlickLog.i("library", "upNext autoplay")
                playUpNextLocked(autoplay = true)
            }
        }
    }

    /** Wakes when the earliest lost phone reaches [LOST_HIDE_MS]; no timer while none is waiting to. */
    private fun scheduleHideTimerLocked() {
        hideTimer?.cancel()
        hideTimer = null
        val now = clock()
        val due = phones.values
            .mapNotNull { phone -> phone.lostAtMs?.takeIf { phone.link == null }?.plus(LOST_HIDE_MS) }
            .filter { it > now }
            .minOrNull() ?: return
        hideTimer = scope.launch {
            delay((due - clock()).coerceAtLeast(0L))
            synchronized(lock) {
                if (!isActive) return@launch
                publishStateLocked()
                scheduleHideTimerLocked()
            }
        }
    }

    /** Every caller publishes the state afterwards. */
    private fun clearUpNextLocked() {
        // Its failure was shown on the card; it must not reappear over the library.
        val offer = _upNext.value
        if (offer != null && (playMachine.state as? LibraryPlay.Failed)?.id == offer.next.id) playMachine.clearResult()
        autoplayTimer?.cancel()
        autoplayTimer = null
        upNextCastId = null
        upNextKeyId = null
        offerFromTail = false
        _upNext.value = null
    }

    private fun eligibleLocked(phone: Phone, nowMs: Long): Boolean {
        if (phone.shown?.first?.items?.isEmpty() == true) return false
        val lostAt = phone.lostAtMs ?: return true
        return phone.link != null || nowMs - lostAt < LOST_HIDE_MS
    }

    /** Re-chooses the phone the screens show, unless a card or a Play in flight holds the current one. */
    private fun reselectVisibleLocked() {
        val current = visibleKeyId?.let(phones::get)
        val play = playMachine.state
        if (current != null && (_upNext.value != null || play is LibraryPlay.Asking || play is LibraryPlay.Starting)) return
        val now = clock()
        val eligible = phones.values.filter { eligibleLocked(it, now) }
        val chosen = eligible.firstOrNull { it.keyId == leaseKeyId } ?: eligible.maxByOrNull { it.authOrder }
        if (chosen?.keyId == visibleKeyId) return
        visibleKeyId = chosen?.keyId
        publishDataLocked()
    }

    private fun publishStateLocked() {
        reselectVisibleLocked()
        _anyLinked.value = phones.values.any { it.link != null }
        // A phone sharing an empty library is not shown, even while a card holds it visible.
        val phone = visibleKeyId?.let(phones::get)?.takeUnless { it.shown?.first?.items?.isEmpty() == true }
        val shown = phone?.shown
        _state.value = LibraryState(
            link = when {
                phone == null -> LibraryLink.NONE
                phone.link != null -> LibraryLink.CONNECTED
                else -> LibraryLink.LOST
            },
            phoneName = phone?.let { labelOf(it.keyId) },
            catalog = shown?.first,
            shelves = shown?.second,
            play = playMachine.state,
            epoch = phone?.authOrder ?: 0L,
        )
    }

    private fun publishDataLocked() {
        val phone = visibleKeyId?.let(phones::get)
        _details.value = phone?.details?.let { HashMap(it) } ?: emptyMap()
        _seasons.value = phone?.seasons?.let { HashMap(it) } ?: emptyMap()
        _thumbnails.value = phone?.thumbs?.let { HashMap(it) } ?: emptyMap()
    }

    companion object {
        const val REFRESH_INTERVAL_MS = 10_000L
        const val BROWSING_INTERVAL_MS = 60_000L

        /** An unanswered `detailWant` or `seasonWant` may be sent again after this. */
        const val WANT_RETRY_MS = 30_000L

        /** A tail card stays while the viewer seeks back less than this past the lead. */
        const val TAIL_CLEAR_SLACK_MS = 10_000L

        /** A phone gone this long has stopped sharing as far as the screens are concerned. */
        const val LOST_HIDE_MS = 10 * 60_000L
        private const val THUMB_CACHE = 120
        private const val TMDB_SHOW_PREFIX = "tv:"
        private val TypeShape = Regex("[A-Za-z0-9]{1,32}")

        /** A phone-chosen string reaches the log only when it is shaped like a frame type. */
        fun typeLabel(type: String): String = if (TypeShape.matches(type)) type else "other"
    }
}
