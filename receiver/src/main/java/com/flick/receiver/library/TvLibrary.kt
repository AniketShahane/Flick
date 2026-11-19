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
        val detailsAsked = HashSet<String>()
        val seasons = HashMap<LibrarySeasonKey, List<LibraryEpisodeInfo>>()
        val seasonsAsked = HashSet<LibrarySeasonKey>()
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

    private val _state = MutableStateFlow(LibraryState())
    private val _details = MutableStateFlow<Map<String, LibraryDetail>>(emptyMap())
    private val _seasons = MutableStateFlow<Map<LibrarySeasonKey, List<LibraryEpisodeInfo>>>(emptyMap())
    private val _thumbnails = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    private val _upNext = MutableStateFlow<UpNextOffer?>(null)

    override val state: StateFlow<LibraryState> = _state.asStateFlow()
    override val details: StateFlow<Map<String, LibraryDetail>> = _details.asStateFlow()
    override val seasons: StateFlow<Map<LibrarySeasonKey, List<LibraryEpisodeInfo>>> = _seasons.asStateFlow()
    override val thumbnails: StateFlow<Map<String, ImageBitmap>> = _thumbnails.asStateFlow()
    override val upNext: StateFlow<UpNextOffer?> = _upNext.asStateFlow()

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
        phone.detailsAsked.retainAll(phone.details.keys)
        phone.seasonsAsked.retainAll(phone.seasons.keys)
        phone.seasonParts.clear()
        link.send(LibraryFrames.catWant(phone.assembler.current?.rev))
        pumpThumbnailsLocked(phone)
        scheduleHideTimerLocked()
        publishStateLocked()
        true
    }

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
            is PhoneFrame.Detail -> if (frame.id in phone.detailsAsked && frame.id !in phone.details) {
                phone.details[frame.id] = LibraryDetail(frame.overview, frame.tagline, frame.hdr, frame.subtitle)
                if (visible) publishDataLocked()
            }
            is PhoneFrame.Season -> {
                val key = LibrarySeasonKey(frame.showKey, frame.season)
                if (key in phone.seasonsAsked && key !in phone.seasons) {
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
                val fresh = phone.casting[frame.castId] == null
                phone.casting.put(frame.castId, frame.id)
                // Frames on the two sockets are unordered (§6.6): the cast may already have ended.
                if (fresh && frame.castId == lastEndedCastId && _upNext.value == null) offerUpNextLocked(frame.castId)
            }
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

    /** A paired phone was renamed on this TV; the screens name it afresh. */
    fun onPhonesChanged() = synchronized(lock) { publishStateLocked() }

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
        playMachine.onCastStarted()
        schedulePlayTimerLocked()
        if (upNextCastId != castId) clearUpNextLocked()
        if (lastEndedCastId != castId) lastEndedCastId = null
        publishStateLocked()
    }

    /** [castId] reached `ended` on screen. */
    fun onCastEnded(castId: String) = synchronized(lock) {
        lastEndedCastId = castId
        offerUpNextLocked(castId)
    }

    /** The ended film was replayed or replaced: its card goes. */
    fun onCastNotEnded() = synchronized(lock) {
        lastEndedCastId = null
        clearUpNextLocked()
        publishStateLocked()
    }

    /** No cast is on screen any more: its card goes, and shelves held back during it are built. */
    fun onCastStopped() = synchronized(lock) {
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
            if (!LibraryWire.itemId(id) || id in phone.details || !phone.detailsAsked.add(id)) return
            phone.link?.send(LibraryFrames.detailWant(id))
        }
    }

    override fun wantSeason(showKey: String, season: Int) {
        synchronized(lock) {
            val phone = connectedLocked() ?: return
            if (!showKey.startsWith(TMDB_SHOW_PREFIX) || season !in 0..999) return
            val key = LibrarySeasonKey(showKey, season)
            if (key in phone.seasons || !phone.seasonsAsked.add(key)) return
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
        synchronized(lock) {
            autoplayTimer?.cancel()
            autoplayTimer = null
            val offer = _upNext.value ?: return
            // The card stays up, and reads the play's own state: asking, or why it failed.
            _upNext.value = offer.copy(autoplayAtMs = null, stillWatching = false)
            sendPlayLocked(upNextKeyId?.let(phones::get), offer.next.id, PlayFrom.START)
        }
    }

    override fun dismissUpNext() {
        synchronized(lock) {
            val offer = _upNext.value
            // Its failure was shown on the card; it must not reappear over the library.
            if (offer != null && (playMachine.state as? LibraryPlay.Failed)?.id == offer.next.id) playMachine.clearResult()
            lastEndedCastId = null
            clearUpNextLocked()
            publishStateLocked()
        }
    }

    // --- Internals (all under lock) -------------------------------------------

    private fun connectedLocked(): Phone? = visibleKeyId?.let(phones::get)?.takeIf { it.link != null }

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

    private fun offerUpNextLocked(castId: String) {
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
        _upNext.value = offer
        FlickLog.i("library", "upNext offered autoplay=${offer.autoplayAtMs != null} stillWatching=${offer.stillWatching}")
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
            val fire = synchronized(lock) {
                if (_upNext.value !== offer) return@launch
                streak.autoplayed()
                true
            }
            if (fire) {
                FlickLog.i("library", "upNext autoplay")
                playUpNext()
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

    private fun clearUpNextLocked() {
        autoplayTimer?.cancel()
        autoplayTimer = null
        upNextCastId = null
        upNextKeyId = null
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

        /** A phone gone this long has stopped sharing as far as the screens are concerned. */
        const val LOST_HIDE_MS = 10 * 60_000L
        private const val THUMB_CACHE = 120
        private const val TMDB_SHOW_PREFIX = "tv:"
        private val TypeShape = Regex("[A-Za-z0-9]{1,32}")

        /** A phone-chosen string reaches the log only when it is shaped like a frame type. */
        fun typeLabel(type: String): String = if (TypeShape.matches(type)) type else "other"
    }
}
