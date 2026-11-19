package com.flick.sender.library

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.flick.sender.R
import com.flick.sender.ServerStateHolder
import com.flick.sender.ServerStatus
import com.flick.sender.media.FilmPosterPolicy
import com.flick.sender.media.MediaAccess
import com.flick.sender.media.MediaProbe
import com.flick.sender.media.PlaybackMediaFingerprint
import com.flick.sender.media.PlaybackProgressState
import com.flick.sender.media.VideoNames
import com.flick.sender.media.labelResource
import com.flick.sender.media.resumePositionMs
import com.flick.sender.model.ConnectionStatus
import com.flick.sender.model.HdrType
import com.flick.sender.model.MediaItem
import com.flick.sender.model.PlaybackPhase
import com.flick.sender.net.ControlClient
import com.flick.sender.net.FilmDetailsStore
import com.flick.sender.net.FilmPosters
import com.flick.sender.net.LibraryView
import com.flick.sender.net.PairingStore
import com.flick.sender.util.FlickLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    private val watchedStore = WatchedStore(context, scope)
    private val watchedRecorder = WatchedRecorder()

    /** Contract §9, for the catalog's `watched` field and nothing on the phone's own screens. */
    val watched: StateFlow<Map<String, Long>> = watchedStore.state

    private val link = LibraryLink(scope, object : LibraryLink.Handler {
        override fun onFrame(frame: TvFrame) = onTvFrame(frame)
        override fun onClosed(reason: String) = onLinkClosed()
    })
    private val backoff = LibraryDialBackoff()

    // TVs whose control server has no /library route. Process lifetime, per the contract.
    private val unsupported = HashSet<String>()
    private var dialJob: Job? = null
    private var dialFor: ControlClient.AuthenticatedEndpoint? = null
    private var linkFor: ControlClient.AuthenticatedEndpoint? = null

    // Moves on every socket, so an answer computed for one never lands on its successor.
    private var generation = 0L
    private var catalogAsked = false
    private var playOutstanding = false

    private val catalogLock = Mutex()
    private val names = CatalogNameFacts.Cache()
    private var built: Built? = null
    private var dirty = true
    private var pushJob: Job? = null

    // What the TV is known to hold, for patches; kept across reconnects to the same TV
    // because the TV keeps its catalog in memory across them too.
    private var held: EncodedCatalog? = null
    private var heldTvId: String? = null
    private var sentItems: Map<String, MediaItem> = emptyMap()

    private val thumbGate = Semaphore(THUMBS_IN_FLIGHT)
    private val thumbLimiter = SlidingWindowLimiter(THUMBS_PER_WINDOW, THUMB_WINDOW_MS)
    private val refreshGate = MinIntervalGate(REFRESH_INTERVAL_MS)
    private var prefetchJob: Job? = null

    private val readyWindow = TvReadyWindowPolicy()
    private var readyDeadlineJob: Job? = null

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
        scope.launch { TvReadyService.ended.collect { readyWindowEnded() } }
        // The cast's own foreground service takes over once it is up.
        scope.launch {
            ServerStateHolder.state.collect { state ->
                if (state.status == ServerStatus.RUNNING && readyWindow.open) closeReadyWindow("cast")
            }
        }
    }

    fun select(enabled: Boolean) = preference.select(enabled)

    // --- Link lifecycle (contract §3) ---------------------------------------

    private fun reconcile() {
        val endpoint = phone.control.authenticatedEndpoint()
            ?.takeIf { phone.control.connection.value == ConnectionStatus.CONNECTED }
        if (endpoint == null) {
            closeLink("control")
            readyWindow.controlLost(now())
            scheduleReadyDeadline()
            return
        }
        readyWindow.controlRestored()
        scheduleReadyDeadline()
        if (!preference.enabled.value) {
            closeLink("setting")
            closeReadyWindow("setting")
            return
        }
        if (phone.mediaAccess.value == MediaAccess.NONE) {
            closeLink("media_access")
            return
        }
        if (endpoint.tvId in unsupported) {
            closeLink("unsupported")
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
                    FlickLog.w("library", "dial skipped reason=no_pairing")
                    return@launch
                }
                val target = LibraryLink.Target(
                    endpoint.tvId, endpoint.keyId, pairing.key, endpoint.peerIp, endpoint.host, endpoint.port,
                )
                when (val outcome = link.connect(target)) {
                    LibraryLink.Outcome.Ready -> {
                        if (!stillWanted(endpoint)) {
                            link.close("superseded")
                            return@launch
                        }
                        backoff.reset()
                        onLinkReady(endpoint)
                        return@launch
                    }
                    LibraryLink.Outcome.Unsupported -> {
                        unsupported += endpoint.tvId
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

    private fun onLinkReady(endpoint: ControlClient.AuthenticatedEndpoint) {
        linkFor = endpoint
        dialFor = null
        generation++
        catalogAsked = false
        playOutstanding = false
        if (heldTvId != endpoint.tvId) {
            held = null
            heldTvId = endpoint.tvId
        }
        startPrefetch()
    }

    /** The TV ended the socket while control v2 may still be up: dial again after the backoff. */
    private fun onLinkClosed() {
        val endpoint = linkFor
        endLinkState()
        if (endpoint != null && stillWanted(endpoint)) dial(endpoint, backoff.next())
    }

    private fun closeLink(reason: String) {
        dialJob?.cancel()
        dialJob = null
        dialFor = null
        if (link.isOpen) link.close(reason)
        endLinkState()
    }

    private fun endLinkState() {
        linkFor = null
        generation++
        catalogAsked = false
        playOutstanding = false
        pushJob?.cancel()
        pushJob = null
        stopPrefetch()
    }

    // --- Catalog (contract §5) ----------------------------------------------

    private fun markDirty() {
        dirty = true
        if (!link.isOpen || !catalogAsked || pushJob?.isActive == true) return
        val socket = generation
        pushJob = scope.launch {
            delay(PUSH_DEBOUNCE_MS)
            catalogLock.withLock {
                if (socket != generation) return@withLock
                val current = currentCatalog() ?: return@withLock
                if (socket != generation) return@withLock
                deliver(CatalogDelivery.push(current.catalog, held), current)
            }
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
        }
    }

    private fun deliver(answer: CatalogAnswer, current: Built) {
        val sent = when (answer) {
            CatalogAnswer.None -> true
            is CatalogAnswer.Patch -> link.sendText(answer.frame)
            is CatalogAnswer.Full -> link.sendBatch(answer.frames)
        }
        if (!sent) return
        held = current.catalog
        sentItems = current.items
        val kind = when (answer) {
            CatalogAnswer.None -> "none"
            is CatalogAnswer.Patch -> "patch"
            is CatalogAnswer.Full -> "full parts=${answer.frames.size - 2}"
        }
        FlickLog.i("library", "catalog sent=$kind count=${current.catalog.size} dropped=${current.catalog.dropped}")
    }

    /** The catalog as the phone's state stands, rebuilt only when something it reads has moved. */
    private suspend fun currentCatalog(): Built? {
        built?.takeIf { !dirty }?.let { return it }
        // A library still on its first read would tell the TV the phone has nothing.
        if (phone.library.value.items.isEmpty()) {
            withTimeoutOrNull(LIBRARY_WAIT_MS) {
                combine(phone.libraryLoading, phone.libraryComplete) { loading, complete -> !loading && complete != null }
                    .first { it }
            }
        }
        dirty = false
        val items = phone.library.value.items
        val progress = phone.playbackProgress.value
        val watchedNow = watchedStore.state.value.keys
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
                watched = watchedNow,
                unplayable = unplayable,
                silent = silent,
                art = if (postersOn) { query -> FilmPosters.cached(context, query) } else null,
                hdr = { key -> byKey[key]?.let { MediaProbe.cachedHdr(it.uri) }?.let(::wireHdr) },
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
            val readable = withTimeoutOrNull(READABLE_CHECK_MS) { readable(item) } ?: true
            if (socket != generation) return@launch
            // Asked again: a tap on the phone may have started a cast while the file was opened.
            val late = LibraryPlayPolicy.refusal(
                enabled = preference.enabled.value,
                listed = true,
                startInFlight = phone.castStartInFlight(),
                mayStartForeground = mayStartForegroundService(),
            ) ?: PlayRefusal.UNAVAILABLE.takeIf { !readable }
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

    private suspend fun readable(item: MediaItem): Boolean = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.openFileDescriptor(item.uri, "r")?.use { true } ?: false }
            .getOrDefault(false)
    }

    private fun onDetail(id: String) {
        val socket = generation
        val item = sentItems[id]
        val postersOn = phone.filmPosters.value
        scope.launch {
            var overview: String? = null
            var tagline: String? = null
            var hdr: CatalogItem.Hdr? = null
            var subtitle: String? = null
            if (item != null) {
                hdr = MediaProbe.cachedHdr(item.uri)?.let(::wireHdr)
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
            if (socket != generation) return@launch
            link.sendText(LibraryFrames.detail(id, overview, tagline, hdr, subtitle))
        }
    }

    private fun onSeason(showKey: String, season: Int) {
        val socket = generation
        val tvId = TvSeasonPolicy.tmdbId(showKey)?.takeIf { phone.filmPosters.value }
        scope.launch {
            val episodes = tvId?.let { TvSeasons.episodes(context, it, season) }.orEmpty()
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
        if (!link.isOpen) return
        val id = LibraryProtocol.itemId(PlaybackMediaFingerprint.of(item))
        if (held?.json(id) == null) return
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

    /** A cast reached its first frame. */
    fun onCastActive(castId: String, item: MediaItem) {
        watchedRecorder.activate(castId, PlaybackMediaFingerprint.of(item))
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
        scope.launch(Dispatchers.Main) { openReadyWindow(justLeftForeground = false) }
    }

    // --- Ready window (contract §8) -----------------------------------------

    /** The app left the viewer's sight. */
    fun onAppBackground() {
        openReadyWindow(justLeftForeground = true)
    }

    /**
     * [justLeftForeground] is the activity stopping right now. Android lists "the app
     * transitions from a user-visible state, such as an activity" among the exemptions from
     * the background start restriction, so that one moment counts as visible.
     */
    private fun openReadyWindow(justLeftForeground: Boolean) {
        if (readyWindow.open) {
            touchReadyWindow()
            return
        }
        val allowed = TvReadyWindowPolicy.mayOpen(
            enabled = preference.enabled.value,
            controlAuthenticated = phone.control.connection.value == ConnectionStatus.CONNECTED &&
                phone.control.authenticatedEndpoint() != null,
            linkOpen = link.isOpen,
            castServing = phone.castLive() || ServerStateHolder.state.value.status == ServerStatus.RUNNING,
            mayStartForeground = justLeftForeground || mayStartForegroundService(),
        )
        if (!allowed) return
        if (!TvReadyService.start(context, phone.tvName())) return
        readyWindow.opened(now())
        FlickLog.i("library", "ready window opened")
        scheduleReadyDeadline()
    }

    private fun touchReadyWindow() {
        if (!readyWindow.open) return
        readyWindow.touch(now())
        scheduleReadyDeadline()
    }

    private fun scheduleReadyDeadline() {
        readyDeadlineJob?.cancel()
        val deadline = readyWindow.deadlineMs() ?: return
        readyDeadlineJob = scope.launch {
            delay((deadline - now()).coerceAtLeast(0L))
            if (readyWindow.expired(now())) closeReadyWindow("expired") else scheduleReadyDeadline()
        }
    }

    private fun closeReadyWindow(reason: String) {
        if (!readyWindow.open) return
        readyWindow.closed()
        readyDeadlineJob?.cancel()
        readyDeadlineJob = null
        TvReadyService.stop(context)
        FlickLog.i("library", "ready window closed reason=$reason")
    }

    private fun readyWindowEnded() {
        if (!readyWindow.open) return
        readyWindow.closed()
        readyDeadlineJob?.cancel()
        readyDeadlineJob = null
        FlickLog.i("library", "ready window closed reason=service")
    }

    // --- Helpers ------------------------------------------------------------

    private fun mayStartForegroundService(): Boolean = LibraryPlayPolicy.mayStartForegroundService(
        sdkInt = Build.VERSION.SDK_INT,
        visible = phone.visible(),
        batteryExempt = batteryExempt(),
    )

    private fun batteryExempt(): Boolean = runCatching {
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
    }.getOrDefault(false)

    private fun wireHdr(type: HdrType): CatalogItem.Hdr = when (type) {
        HdrType.DOLBY_VISION -> CatalogItem.Hdr.DOLBY_VISION
        HdrType.HDR10 -> CatalogItem.Hdr.HDR10
        HdrType.NONE -> CatalogItem.Hdr.SDR
    }

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
    }
}
