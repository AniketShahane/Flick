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
 * Phones are kept apart by key id, and the screens show the phone that authenticated most
 * recently. A phone's catalog outlives its socket (§5.2) and goes only with a forget or the
 * process.
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

        /** The catalog the screens show, swapped only together with its shelves. */
        var shown: Pair<LibraryCatalog, LibraryShelves>? = null
        var shelvesSeq = 0L

        val casting = CastingMap()
        val details = HashMap<String, LibraryDetail>()
        val detailsAsked = HashSet<String>()
        val seasons = HashMap<LibrarySeasonKey, List<LibraryEpisodeInfo>>()
        val seasonsAsked = HashSet<LibrarySeasonKey>()
        val seasonParts = SeasonAssembler()
        val thumbQueue = ThumbnailQueue()
        val thumbs = object : LinkedHashMap<String, ImageBitmap>(THUMB_CACHE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean = size > THUMB_CACHE
        }
        val refreshGate = LibraryRateGate(REFRESH_INTERVAL_MS)
        val browsingGate = LibraryRateGate(BROWSING_INTERVAL_MS)
    }

    private val lock = Any()
    private val phones = HashMap<String, Phone>()
    private var visibleKeyId: String? = null
    private var authCounter = 0L
    private val playMachine = LibraryPlayMachine()
    private var playTimer: Job? = null
    private val streak = UpNextStreak()
    private var upNextCastId: String? = null
    private var autoplayTimer: Job? = null

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
        phone.authOrder = ++authCounter
        older?.close("superseded")
        phone.thumbQueue.onDisconnect()
        // Requests the old socket never answered are asked again on this one.
        phone.detailsAsked.retainAll(phone.details.keys)
        phone.seasonsAsked.retainAll(phone.seasons.keys)
        phone.seasonParts.clear()
        val previous = visibleKeyId
        visibleKeyId = keyId
        if (previous != keyId) {
            clearUpNextLocked()
            publishDataLocked()
        }
        link.send(LibraryFrames.catWant(phone.assembler.current?.rev))
        pumpThumbnailsLocked(phone)
        publishStateLocked()
        true
    }

    fun onClosed(link: LibraryLinkHandle) = synchronized(lock) {
        val phone = phones.values.firstOrNull { it.link === link } ?: return@synchronized
        phone.link = null
        phone.thumbQueue.onDisconnect()
        if (phone.keyId == visibleKeyId) {
            playMachine.onLinkLost()
            schedulePlayTimerLocked()
            clearUpNextLocked()
        }
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
            is PhoneFrame.PlayAck -> if (visible && playMachine.onAck(frame.req, frame.ok, frame.refusal, clock())) {
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
            is PhoneFrame.Casting -> phone.casting.put(frame.castId, frame.id)
            is PhoneFrame.Unknown -> FlickLog.i("library", "frame ignored t=${typeLabel(frame.type)}")
            is PhoneFrame.Hello, is PhoneFrame.Proof, PhoneFrame.Malformed -> return Verdict.CLOSE
        }
        Verdict.CONTINUE
    }

    /** A binary `FLT1` frame. Decoded off the socket's reader, and only if it was asked for. */
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
        if (visibleKeyId == keyId) {
            visibleKeyId = phones.values.maxByOrNull { it.authOrder }?.keyId
            playMachine.onLinkLost()
            schedulePlayTimerLocked()
            clearUpNextLocked()
            publishDataLocked()
        }
        publishStateLocked()
    }

    fun forgetAll() = synchronized(lock) {
        phones.values.forEach { it.link?.close("forgotten") }
        phones.clear()
        visibleKeyId = null
        playMachine.onLinkLost()
        schedulePlayTimerLocked()
        clearUpNextLocked()
        publishDataLocked()
        publishStateLocked()
    }

    // --- From the app --------------------------------------------------------

    /** A paired phone was renamed on this TV; the screens name it afresh. */
    fun onPhonesChanged() = synchronized(lock) { publishStateLocked() }

    /**
     * The TV accepted a control-v2 `loadMedia` and started [castId]. A Play waiting on the
     * phone resolves here, and any Up next card belongs to the film this replaces.
     */
    fun onCastStarted(castId: String) = synchronized(lock) {
        playMachine.onCastStarted()
        schedulePlayTimerLocked()
        if (upNextCastId != castId) clearUpNextLocked()
        publishStateLocked()
    }

    /** [castId] reached `ended` on screen. */
    fun onCastEnded(castId: String) = synchronized(lock) {
        if (upNextCastId == castId && _upNext.value != null) return@synchronized
        val phone = visibleKeyId?.let(phones::get) ?: return@synchronized
        val catalog = phone.assembler.current
        val offer = UpNextPolicy.offer(
            castItemId = phone.casting[castId],
            catalog = catalog,
            connected = phone.link != null,
            autoplayEnabled = autoplayEnabled(),
            streak = streak,
            nowMs = clock(),
            showOf = { ended -> showOf(phone, catalog, ended) },
        ) ?: return@synchronized
        upNextCastId = castId
        _upNext.value = offer
        FlickLog.i("library", "upNext offered autoplay=${offer.autoplayAtMs != null} stillWatching=${offer.stillWatching}")
        scheduleAutoplayLocked(offer)
    }

    /** The ended film was replayed, stopped or replaced: its card goes. */
    fun onCastNotEnded() = synchronized(lock) { clearUpNextLocked() }

    /** Any key on the remote: someone is in the room. */
    fun onRemoteKey() = synchronized(lock) { streak.viewerPressed() }

    /** The composition that owns this hub is gone: no timer or decode outlives it. */
    fun shutdown() = scope.cancel()

    // --- LibraryHub ----------------------------------------------------------

    override fun play(id: String, from: PlayFrom) {
        synchronized(lock) {
            val phone = visibleKeyId?.let(phones::get)
            val link = phone?.link
            val reqId = newRequestId()
            if (playMachine.request(id, link != null, reqId, clock())) {
                link!!.send(LibraryFrames.play(reqId, id, from))
                FlickLog.i("library", "play sent from=${from.wire}")
            }
            schedulePlayTimerLocked()
            publishStateLocked()
        }
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
            if (item.art != null || id in phone.thumbs) return
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
        synchronized(lock) {
            val phone = connectedLocked() ?: return
            if (phone.browsingGate.tryAcquire(clock())) phone.link?.send(LibraryFrames.browsing())
        }
    }

    override fun playUpNext() {
        val next = synchronized(lock) {
            autoplayTimer?.cancel()
            autoplayTimer = null
            val offer = _upNext.value ?: return
            _upNext.value = offer.copy(autoplayAtMs = null, stillWatching = false)
            offer.next.id
        }
        play(next, PlayFrom.START)
    }

    override fun dismissUpNext() {
        synchronized(lock) { clearUpNextLocked() }
    }

    // --- Internals (all under lock) -------------------------------------------

    private fun connectedLocked(): Phone? = visibleKeyId?.let(phones::get)?.takeIf { it.link != null }

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
                FlickLog.i("library", "catalog swapped items=${catalog.items.size}")
                phone.thumbQueue.retainOnly { id -> catalog[id]?.art == null }
                val seq = ++phone.shelvesSeq
                scope.launch(workDispatcher) {
                    val shelves = LibraryShelving.shelves(catalog)
                    synchronized(lock) {
                        if (phones[phone.keyId] !== phone || phone.shelvesSeq != seq) return@launch
                        phone.shown = catalog to shelves
                        if (phone.keyId == visibleKeyId) publishStateLocked()
                    }
                }
            }
        }
        return Verdict.CONTINUE
    }

    private fun pumpThumbnailsLocked(phone: Phone) {
        val link = phone.link ?: return
        for (id in phone.thumbQueue.pump(clock())) link.send(LibraryFrames.thumbWant(id))
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

    private fun clearUpNextLocked() {
        autoplayTimer?.cancel()
        autoplayTimer = null
        upNextCastId = null
        _upNext.value = null
    }

    private fun publishStateLocked() {
        val phone = visibleKeyId?.let(phones::get)
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
        private const val THUMB_CACHE = 120
        private const val TMDB_SHOW_PREFIX = "tv:"
        private val TypeShape = Regex("[A-Za-z0-9]{1,32}")

        /** A phone-chosen string reaches the log only when it is shaped like a frame type. */
        fun typeLabel(type: String): String = if (TypeShape.matches(type)) type else "other"
    }
}
