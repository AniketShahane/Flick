package com.flick.sender.net

import android.content.Context
import android.os.Build
import com.flick.sender.CastServerService
import com.flick.sender.R
import com.flick.sender.ServerStateHolder
import com.flick.sender.ServerStatus
import com.flick.sender.media.MediaLibrary
import com.flick.sender.model.CastErrorKind
import com.flick.sender.model.ConnectionStatus
import com.flick.sender.model.DiscoveredTv
import com.flick.sender.model.MediaItem
import com.flick.sender.model.TvAvailability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Where the phone currently is. Empty state is Library with no items. */
sealed interface Route {
    data object Connect : Route
    data object Library : Route
    data class Detail(val item: MediaItem) : Route
    data object Connecting : Route
    data object NowPlaying : Route
    data class Failure(val kind: CastErrorKind) : Route
}

/** A TV the phone is paired/connected to. */
data class PairedTv(val name: String, val host: String, val port: Int)

/**
 * Why a pairing attempt failed, resolved to a localized string at the UI (never a
 * raw exception message — that would leak untranslated Ktor text into the sheet).
 */
enum class PairErrorKind { CODE_MISMATCH, UNREACHABLE }

/**
 * The phone's application brain: it owns discovery, pairing, the control channel,
 * the [PlaybackSession] hero state, the MediaStore gallery, and navigation — and
 * it drives the existing hardened media server ([CastServerService]) unchanged
 * (the WS control channel is layered alongside it, never through it).
 */
class FlickController(
    private val appContext: Context,
    private val scope: CoroutineScope,
) {
    val nsd = NsdDiscovery(appContext)
    val control = ControlClient(scope)
    val session = PlaybackSession(control)
    private val haptics = FlickHaptics(appContext)
    private val pairingStore = PairingStore(appContext)

    private val deviceLabel = Build.MODEL ?: "Phone"

    // --- navigation & overlays ---
    private val _route = MutableStateFlow<Route>(
        if (pairingStore.last() != null) Route.Library else Route.Connect,
    )
    val route: StateFlow<Route> = _route.asStateFlow()

    private val _showQualitySheet = MutableStateFlow(false)
    val showQualitySheet: StateFlow<Boolean> = _showQualitySheet.asStateFlow()

    private val _showAdvisories = MutableStateFlow(false)
    val showAdvisories: StateFlow<Boolean> = _showAdvisories.asStateFlow()

    // --- data ---
    val devices: StateFlow<List<DiscoveredTv>> = nsd.devices
    private val _mediaItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val mediaItems: StateFlow<List<MediaItem>> = _mediaItems.asStateFlow()
    private val _libraryLoading = MutableStateFlow(false)
    val libraryLoading: StateFlow<Boolean> = _libraryLoading.asStateFlow()
    private val _hasPermission = MutableStateFlow(false)
    val hasPermission: StateFlow<Boolean> = _hasPermission.asStateFlow()

    // --- connection ---
    val connection: StateFlow<ConnectionStatus> = control.connection
    private val _connectedTv = MutableStateFlow(pairingStore.last()?.let { PairedTv(it.name, it.host, it.port) })
    val connectedTv: StateFlow<PairedTv?> = _connectedTv.asStateFlow()

    /** A discovered TV tapped in S1 that still needs a code (null once paired). */
    private val _pairTarget = MutableStateFlow<DiscoveredTv?>(null)
    val pairTarget: StateFlow<DiscoveredTv?> = _pairTarget.asStateFlow()

    private val _pairError = MutableStateFlow<PairErrorKind?>(null)
    val pairError: StateFlow<PairErrorKind?> = _pairError.asStateFlow()

    /**
     * True once Connect was reached from inside the app (cast icon / flick with no TV)
     * rather than as the launch destination — lets system-back on Connect return to
     * Library instead of exiting the app.
     */
    private val _connectFromLibrary = MutableStateFlow(false)
    val connectFromLibrary: StateFlow<Boolean> = _connectFromLibrary.asStateFlow()

    val playback = session.state
    val pulses = session.pulses

    private var pendingCastItem: MediaItem? = null
    private val _castingItem = MutableStateFlow<MediaItem?>(null)
    val castingItem: StateFlow<MediaItem?> = _castingItem.asStateFlow()

    init {
        scope.launch { control.frames.collect { session.onFrame(it) } }
        scope.launch { session.haptics.collect { haptics.play(it) } }
        scope.launch {
            control.connection.collect { status ->
                when (status) {
                    // Every fresh session: reset the seq watermark so a restarted TV's
                    // state feed (seq restarting near 0) isn't dropped as stale.
                    ConnectionStatus.CONNECTED -> session.onConnected()
                    // The WS died mid-cast (silent TV death now surfaces via ping): the
                    // remote can no longer drive the TV, so leave the frozen hero for a
                    // real failure face instead of a live-looking dead remote.
                    ConnectionStatus.DISCONNECTED, ConnectionStatus.FAILED ->
                        if (_route.value is Route.NowPlaying) {
                            _route.value = Route.Failure(CastErrorKind.UNREACHABLE)
                        }
                    else -> {}
                }
            }
        }
    }

    // --- lifecycle ---------------------------------------------------------

    fun onStart() {
        nsd.start()
    }

    fun dispose() {
        nsd.stop()
        control.shutdown()
    }

    // --- permissions & library --------------------------------------------

    fun onPermissionResult(granted: Boolean) {
        _hasPermission.value = granted
        if (granted) loadLibrary()
    }

    fun loadLibrary() {
        scope.launch {
            _libraryLoading.value = true
            _mediaItems.value = MediaLibrary.query(appContext)
            _libraryLoading.value = false
        }
    }

    // --- navigation --------------------------------------------------------

    fun openConnect() {
        nsd.start()
        _connectFromLibrary.value = true
        _route.value = Route.Connect
    }

    fun openLibrary() {
        _route.value = Route.Library
    }

    fun openDetail(item: MediaItem) {
        _route.value = Route.Detail(item)
    }

    fun back() {
        _route.value = when (val r = _route.value) {
            is Route.Detail -> Route.Library
            is Route.Failure -> Route.Library
            Route.Connecting -> Route.Library
            Route.NowPlaying -> Route.Library
            Route.Connect -> Route.Library
            else -> r
        }
    }

    fun toggleQualitySheet(show: Boolean) { _showQualitySheet.value = show }
    fun toggleAdvisories(show: Boolean) { _showAdvisories.value = show }

    // --- pairing -----------------------------------------------------------

    fun selectDevice(tv: DiscoveredTv) {
        val key = pairingStore.keyFor(tv.host)
        if (key != null) {
            resumeConnect(PairedTv(tv.name, tv.host, tv.port), key)
        } else {
            _pairTarget.value = tv
        }
    }

    fun cancelPairing() {
        _pairTarget.value = null
        _pairError.value = null
    }

    fun submitCode(code: String) {
        val tv = _pairTarget.value ?: return
        connectWithCode(tv.host, tv.port, tv.name, code)
    }

    fun connectManual(host: String, port: Int, code: String) {
        val key = pairingStore.keyFor(host)
        val fallbackName = appContext.getString(R.string.np_tv_generic)
        if (key != null && code.isBlank()) {
            resumeConnect(PairedTv(fallbackName, host, port), key)
        } else {
            connectWithCode(host, port, fallbackName, code)
        }
    }

    private fun connectWithCode(host: String, port: Int, name: String, code: String) {
        _pairError.value = null
        scope.launch {
            when (val res = control.connect(host, port, deviceLabel, code = code)) {
                is ControlClient.Result.Paired -> {
                    val tvName = res.tvName.ifBlank { name }
                    pairingStore.save(PairingStore.Pairing(tvName, host, port, res.key))
                    _connectedTv.value = PairedTv(tvName, host, port)
                    _pairTarget.value = null
                    proceedAfterConnect()
                }
                ControlClient.Result.Denied ->
                    _pairError.value = PairErrorKind.CODE_MISMATCH
                is ControlClient.Result.Error ->
                    _pairError.value = PairErrorKind.UNREACHABLE
                ControlClient.Result.Resumed -> {
                    _connectedTv.value = PairedTv(name, host, port)
                    _pairTarget.value = null
                    proceedAfterConnect()
                }
            }
        }
    }

    private fun resumeConnect(tv: PairedTv, key: String) {
        // The TV's control port is ephemeral (rebinds on every receiver restart), but
        // PairingStore holds the port from the original pairing. Prefer the port NSD is
        // currently advertising for this host so a one-tap resume after a TV reboot dials
        // the live port, not a dead one.
        val target = tv.copy(port = freshPortFor(tv.host, tv.port))
        scope.launch {
            when (control.connect(target.host, target.port, deviceLabel, key = key)) {
                is ControlClient.Result.Error -> {
                    _route.value = Route.Failure(CastErrorKind.UNREACHABLE)
                }
                ControlClient.Result.Denied -> {
                    // The TV forgot this key (pairings cleared / app data wiped / reinstall):
                    // discard the stale key and route to code entry so re-pairing is
                    // possible — otherwise the discovery path loops on the dead key forever.
                    pairingStore.forget(target.host)
                    _pairTarget.value = DiscoveredTv(target.name, target.host, target.port, null, TvAvailability.UNKNOWN)
                    _connectFromLibrary.value = true
                    _route.value = Route.Connect
                }
                else -> {
                    _connectedTv.value = target
                    pairingStore.updatePort(target.host, target.port)
                    proceedAfterConnect()
                }
            }
        }
    }

    /** The port NSD currently advertises for [host], or [fallback] if not discovered. */
    private fun freshPortFor(host: String, fallback: Int): Int =
        nsd.devices.value.firstOrNull { it.host == host }?.port ?: fallback

    private fun proceedAfterConnect() {
        val item = pendingCastItem
        if (item != null) {
            startCast(item)
        } else {
            _route.value = Route.Library
        }
    }

    // --- cast flow ---------------------------------------------------------

    fun flickToTv(item: MediaItem) {
        val tv = _connectedTv.value
        pendingCastItem = item
        if (tv == null) {
            openConnect()
            return
        }
        if (connection.value == ConnectionStatus.CONNECTED) {
            startCast(item)
        } else {
            val key = pairingStore.keyFor(tv.host)
            if (key != null) resumeConnect(tv, key) else openConnect()
        }
    }

    private fun startCast(item: MediaItem) {
        pendingCastItem = null
        _castingItem.value = item
        _route.value = Route.Connecting
        scope.launch {
            // Cast startup is transactional: the foreground media server (which holds the
            // Wi-Fi/wake locks and the notification) is started up front, but if the URL
            // never comes up, the WS isn't serving, or we're cancelled before loadMedia,
            // it MUST be torn back down — otherwise the service leaks locks with no UI.
            var committed = false
            try {
                // Kick the hardened media server for this file (unchanged contract).
                ServerStateHolder.beginStarting()
                CastServerService.start(appContext, item.uri, item.name, item.sizeBytes)

                val url = awaitServerUrl()
                if (url == null) {
                    _castingItem.value = null
                    _route.value = Route.Failure(CastErrorKind.NO_LAN)
                    return@launch
                }
                if (connection.value != ConnectionStatus.CONNECTED) {
                    _castingItem.value = null
                    _route.value = Route.Failure(CastErrorKind.REACHABLE_NOT_SERVING)
                    return@launch
                }
                session.loadMedia(url, item.name, item.durationMs, 0L)
                _route.value = Route.NowPlaying
                committed = true
            } finally {
                if (!committed) CastServerService.stop(appContext)
            }
        }
    }

    private suspend fun awaitServerUrl(): String? {
        val state = withTimeoutOrNull(SERVER_START_TIMEOUT_MS) {
            ServerStateHolder.state.first {
                (it.status == ServerStatus.RUNNING && it.videoUrl != null) ||
                    it.status == ServerStatus.ERROR
            }
        }
        return state?.takeIf { it.status == ServerStatus.RUNNING }?.videoUrl
    }

    fun stopCast() {
        session.stop()
        CastServerService.stop(appContext)
        _castingItem.value = null
        _route.value = Route.Library
    }

    // --- transport (delegates to the session) ------------------------------

    fun playPause() = session.togglePlayPause()
    fun skip(deltaMs: Long) = session.skip(deltaMs)
    fun scrubStart() = session.scrubStart()
    fun scrubTo(fraction: Float) = session.scrubTo(fraction)
    fun scrubEnd() = session.scrubEnd()
    fun setVolume(level: Float) = session.setVolume(level)

    private companion object {
        const val SERVER_START_TIMEOUT_MS = 9_000L
    }
}
