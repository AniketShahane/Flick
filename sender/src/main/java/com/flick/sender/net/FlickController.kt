package com.flick.sender.net

import android.content.Context
import android.os.Build
import com.flick.sender.CastServerService
import com.flick.sender.R
import com.flick.sender.ServerStateHolder
import com.flick.sender.ServerStatus
import com.flick.sender.SourceServerTerminalKind
import com.flick.sender.media.MediaLibrary
import com.flick.sender.model.CastErrorKind
import com.flick.sender.model.CastFailure
import com.flick.sender.model.ConnectionStatus
import com.flick.sender.model.DiscoveredTv
import com.flick.sender.model.MediaItem
import com.flick.sender.model.TvAvailability
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

sealed interface Route { data object Connect : Route; data object Library : Route; data class Detail(val item: MediaItem) : Route; data object Connecting : Route; data object NowPlaying : Route; data class Failure(val kind: CastErrorKind, val failure: CastFailure) : Route }
data class PairedTv(val name: String, val host: String, val port: Int, val tvId: String)
enum class PairErrorKind { CODE_MISMATCH, UNREACHABLE, INVALID_QR, UPDATE_REQUIRED, INVALID_ENTRY, PAIRING_REQUIRED, LOCAL_STORAGE }
data class PendingPairLaunch(val eventId: Long)
sealed interface CastStartState { data object Idle : CastStartState; data class ConnectingControl(val castId: String) : CastStartState; data class StartingSource(val castId: String) : CastStartState; data class AwaitingAcceptance(val castId: String) : CastStartState; data class AwaitingFirstFrame(val castId: String) : CastStartState; data class Active(val castId: String) : CastStartState; data class Failed(val castId: String, val code: String) : CastStartState }

/** Application-scoped owner of pairing, control, service state and cast generations. */
class CastCoordinator(private val appContext: Context, private val scope: CoroutineScope) {
    val nsd = NsdDiscovery(appContext)
    val control = ControlClient(scope)
    val session = PlaybackSession(control, appContext.getString(R.string.media_title_generic))
    private val haptics = FlickHaptics(appContext)
    private val store = PairingStore(appContext)
    private val deviceLabel = ControlProtocolV2.normalizedLabel(Build.MODEL, 80)
        ?: appContext.getString(R.string.sender_device_generic)
    private var pairingJob: Job? = null
    private var castJob: Job? = null
    private val pairingGate = PairingAttemptGate()
    private val pairCodeReset = PairCodeReset()
    private val castGate = CastGenerationGate()
    private var currentCastId: String? = null
    private var accepted: CompletableDeferred<JSONObject>? = null
    private var ready: CompletableDeferred<JSONObject>? = null
    private var pendingCast: MediaItem? = null
    private var retryItem: MediaItem? = null
    private var loadSentCastId: String? = null

    private val _route = MutableStateFlow<Route>(if (store.last() == null) Route.Connect else Route.Library)
    val route: StateFlow<Route> = _route.asStateFlow()
    private val _pendingPairLaunch = MutableStateFlow<PendingPairLaunch?>(null)
    val pendingPairLaunch: StateFlow<PendingPairLaunch?> = _pendingPairLaunch.asStateFlow()
    private val _showQualitySheet = MutableStateFlow(false); val showQualitySheet = _showQualitySheet.asStateFlow()
    private val _showAdvisories = MutableStateFlow(false); val showAdvisories = _showAdvisories.asStateFlow()
    val devices = nsd.devices
    private val _mediaItems = MutableStateFlow<List<MediaItem>>(emptyList()); val mediaItems = _mediaItems.asStateFlow()
    private val _libraryLoading = MutableStateFlow(false); val libraryLoading = _libraryLoading.asStateFlow()
    private val _hasPermission = MutableStateFlow(false); val hasPermission = _hasPermission.asStateFlow()
    val connection = control.connection
    private val _connectedTv = MutableStateFlow(store.last()?.let { PairedTv(it.name, it.host, it.port, it.tvId) }); val connectedTv = _connectedTv.asStateFlow()
    private val _pairTarget = MutableStateFlow<DiscoveredTv?>(null); val pairTarget = _pairTarget.asStateFlow()
    private val _pairError = MutableStateFlow<PairErrorKind?>(null); val pairError = _pairError.asStateFlow()
    private val _connectFromLibrary = MutableStateFlow(false); val connectFromLibrary = _connectFromLibrary.asStateFlow()
    private val _castingItem = MutableStateFlow<MediaItem?>(null); val castingItem = _castingItem.asStateFlow()
    private val _castStart = MutableStateFlow<CastStartState>(CastStartState.Idle); val castStart = _castStart.asStateFlow()
    private val _castFailure = MutableStateFlow<CastFailure?>(null); val castFailure = _castFailure.asStateFlow()
    val playback = session.state; val pulses = session.pulses

    init {
        scope.launch { control.frames.collect(::onFrame) }
        scope.launch { session.haptics.collect { haptics.play(it) } }
        scope.launch {
            ServerStateHolder.terminalEvent.collect { event ->
                event?.takeIf { it.castId == currentCastId }?.let(::onSourceTerminal)
            }
        }
        scope.launch { control.connection.collect { status ->
            if (status == ConnectionStatus.CONNECTED) session.onConnected()
            if ((status == ConnectionStatus.DISCONNECTED || status == ConnectionStatus.FAILED) && currentCastId != null) terminal(currentCastId!!, "control_disconnected")
        } }
    }

    fun onStart() { nsd.start() }
    fun onPermissionResult(granted: Boolean) { _hasPermission.value = granted; if (granted) loadLibrary() }
    fun loadLibrary() = scope.launch { _libraryLoading.value = true; _mediaItems.value = MediaLibrary.query(appContext); _libraryLoading.value = false }
    fun openConnect() { nsd.start(); _connectFromLibrary.value = true; _route.value = Route.Connect }
    fun openLibrary() { _route.value = Route.Library }
    fun openDetail(item: MediaItem) { _route.value = Route.Detail(item) }
    fun back() {
        if (_route.value == Route.Connecting) cancelCast()
        else {
            if (_route.value == Route.Connect) cancelPairing()
            _route.value = Route.Library
        }
    }
    fun toggleQualitySheet(show: Boolean) { _showQualitySheet.value = show }
    fun toggleAdvisories(show: Boolean) { _showAdvisories.value = show }

    fun acceptPairLaunch(event: IncomingPairEvent) {
        invalidatePairingAttempt()
        _pairError.value = when (event.result) {
            PairLaunchParseResult.Valid -> null; PairLaunchParseResult.UnsupportedVersion, PairLaunchParseResult.Invalid -> PairErrorKind.INVALID_QR
        }
        _pendingPairLaunch.value = if (event.result == PairLaunchParseResult.Valid) PendingPairLaunch(event.eventId) else null
        _pairTarget.value = null; _route.value = Route.Connect
    }
    fun dismissPairLaunch(eventId: Long) {
        if (_pendingPairLaunch.value?.eventId == eventId) {
            invalidatePairingAttempt()
            _pendingPairLaunch.value = null
        }
    }

    /** Discovery is advisory until a stored key completes a proof. */
    fun selectDevice(tv: DiscoveredTv) {
        if ((tv.protocolVersion ?: 0) < ControlProtocolV2.VERSION) { _pairError.value = PairErrorKind.UPDATE_REQUIRED; _route.value = Route.Connect; return }
        val paired = store.last()?.takeIf { it.tvId == tv.tvId && !it.needsRepair }
        if (paired != null) resume(paired) else { _pairTarget.value = null; _pairError.value = PairErrorKind.PAIRING_REQUIRED; _route.value = Route.Connect }
    }
    fun cancelPairing() {
        invalidatePairingAttempt(); _pendingPairLaunch.value = null; _pairTarget.value = null; _pairError.value = null
    }
    fun submitCode(code: String) { /* Legacy UI entry is intentionally not an endpoint. */ _pairError.value = PairErrorKind.INVALID_ENTRY }
    fun connectManual(host: String, port: Int, code: String) = submitTvDisplayedPair(_pendingPairLaunch.value?.eventId ?: 0L, host, port.toString(), code)

    fun submitTvDisplayedPair(eventId: Long, host: String, port: String, code: String) {
        if (eventId != 0L && _pendingPairLaunch.value?.eventId != eventId) return
        if (!PairLaunch.isCanonicalIpv4(host) || !PairLaunch.isCanonicalPort(port) || !PairLaunch.isCode(code)) { _pairError.value = PairErrorKind.INVALID_ENTRY; return }
        val attempt = beginPairingAttempt(); _pairError.value = null
        val legacyAtExactHost = store.legacyForHost(host)
        pairingJob = scope.launch {
            when (val result = control.pair(host, port.toInt(), deviceLabel, code)) {
                is ControlClient.Result.Paired -> if (pairingGate.isCurrent(attempt)) {
                    persistPaired(result.key, result.endpoint, host, port.toInt(), legacyAtExactHost?.host)?.let { pairing ->
                        _connectedTv.value = PairedTv(pairing.name, host, pairing.port, pairing.tvId)
                        _pendingPairLaunch.value = null; _pairTarget.value = null; _route.value = Route.Library
                    }
                }
                is ControlClient.Result.PairedBusy -> if (pairingGate.isCurrent(attempt)) {
                    val pairing = persistPaired(result.key, result.endpoint, host, port.toInt(), legacyAtExactHost?.host)
                    if (pairing != null) {
                        _connectedTv.value = PairedTv(pairing.name, host, pairing.port, pairing.tvId)
                        _pendingPairLaunch.value = null; _pairTarget.value = null
                        if (PairResultPolicy.clearCode(result)) clearEnteredCode()
                        publishBusyFailure()
                    }
                }
                ControlClient.Result.Denied -> if (pairingGate.isCurrent(attempt)) { if (PairResultPolicy.clearCode(result)) clearEnteredCode(); _pairError.value = PairErrorKind.CODE_MISMATCH }
                ControlClient.Result.UpdateRequired -> if (pairingGate.isCurrent(attempt)) _pairError.value = PairErrorKind.UPDATE_REQUIRED
                is ControlClient.Result.Unreachable -> if (pairingGate.isCurrent(attempt)) { if (PairResultPolicy.clearCode(result)) clearEnteredCode(); _pairError.value = PairErrorKind.UNREACHABLE }
                is ControlClient.Result.ProtocolError -> if (pairingGate.isCurrent(attempt)) { if (PairResultPolicy.clearCode(result)) clearEnteredCode(); _pairError.value = PairErrorKind.INVALID_ENTRY }
                ControlClient.Result.Busy -> if (pairingGate.isCurrent(attempt)) { if (PairResultPolicy.clearCode(result)) clearEnteredCode(); publishBusyFailure() }
                else -> if (pairingGate.isCurrent(attempt)) _pairError.value = PairErrorKind.INVALID_ENTRY
            }
        }
    }

    private fun resume(pairing: PairingStore.Pairing, afterResume: (() -> Unit)? = null) {
        val attempt = beginPairingAttempt()
        pairingJob = scope.launch {
            var sawUntrustedFailure = false
            var sawTransportFailure = false
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
                when (val result = control.resume(pairing, candidate.host, candidate.port)) {
                    is ControlClient.Result.Resumed -> if (pairingGate.isCurrent(attempt)) {
                        if (!PairingPersistence.commit { store.commitVerifiedEndpoint(pairing.tvId, result.endpoint.tv, candidate.host, candidate.port) }) {
                            control.close()
                            failPendingResume("control_unreachable")
                            return@launch
                        }
                        _connectedTv.value = PairedTv(result.endpoint.tv, candidate.host, candidate.port, pairing.tvId)
                        _route.value = Route.Library
                        afterResume?.invoke()
                        return@launch
                    }
                    ControlClient.Result.Busy -> if (pairingGate.isCurrent(attempt)) { publishBusyFailure(); return@launch }
                    ControlClient.Result.Denied, is ControlClient.Result.ProtocolError -> sawUntrustedFailure = true
                    is ControlClient.Result.Unreachable -> sawTransportFailure = true
                    else -> sawUntrustedFailure = true
                }
                if (!pairingGate.isCurrent(attempt)) return@launch
            }
            if (pairingGate.isCurrent(attempt)) {
                // A single spoofed/expired candidate must not poison a durable pairing.
                if (sawUntrustedFailure) {
                    store.markNeedsRepair(pairing.tvId)
                    _pairError.value = PairErrorKind.CODE_MISMATCH
                    _route.value = Route.Connect
                } else if (sawTransportFailure) {
                    failPendingResume("control_unreachable")
                }
            }
        }
    }

    fun flickToTv(item: MediaItem) {
        val tv = _connectedTv.value ?: run { openConnect(); return }
        if (control.authenticatedEndpoint() == null) {
            store.get(tv.tvId)?.takeIf { !it.needsRepair }?.let { pairing ->
                pendingCast = item
                resume(pairing) { pendingCast?.takeIf { control.authenticatedEndpoint() != null }?.let(::startCast) }
            } ?: openConnect()
            return
        }
        startCast(item)
    }
    private fun startCast(item: MediaItem) {
        pendingCast = null
        cancelCast(silent = true); val castId = ControlProtocolV2.randomId(); val thisGeneration = castGate.begin(castId); currentCastId = castId
        _castFailure.value = null
        _castingItem.value = item; _route.value = Route.Connecting; _castStart.value = CastStartState.ConnectingControl(castId)
        castJob = scope.launch {
            var readyCommit = false
            try {
                val endpoint = control.authenticatedEndpoint() ?: throw CastStartupFailure("control_unreachable")
                if (!com.flick.sender.NetworkUtils.isOwnedLanIpv4(endpoint.peerIp)) throw CastStartupFailure("no_compatible_lan")
                if (item.uri.scheme != "content" || item.sizeBytes <= 0L) throw CastStartupFailure("source_unavailable")
                _castStart.value = CastStartState.StartingSource(castId)
                ServerStateHolder.beginStarting(castId)
                CastServerService.start(appContext, castId, item.uri, item.name, item.sizeBytes, endpoint.peerIp)
                val server = withTimeoutOrNull(9_000) { ServerStateHolder.state.first { it.castId == castId && (it.status == ServerStatus.RUNNING || it.status == ServerStatus.ERROR) } }
                    ?: throw CastStartupFailure("startup_timeout")
                val videoUrl = server.videoUrl
                if (server.status != ServerStatus.RUNNING || videoUrl == null) throw CastStartupFailure("media_bind_failed")
                accepted = CompletableDeferred(); ready = CompletableDeferred(); _castStart.value = CastStartState.AwaitingAcceptance(castId)
                loadSentCastId = castId
                val title = ControlProtocolV2.normalizedLabel(item.name, 200)
                    ?: appContext.getString(R.string.media_title_generic)
                session.loadMedia(castId, videoUrl, title, item.durationMs, 0L)
                withTimeoutOrNull(2_000) { accepted?.await() } ?: throw CastStartupFailure("startup_timeout")
                _castStart.value = CastStartState.AwaitingFirstFrame(castId)
                withTimeoutOrNull(18_000) { ready?.await() } ?: throw CastStartupFailure("startup_timeout")
                if (!castGate.isCurrent(castId, thisGeneration) || currentCastId != castId) return@launch
                readyCommit = true; _castStart.value = CastStartState.Active(castId); _route.value = Route.NowPlaying
            } catch (failure: CastStartupFailure) { terminal(castId, failure.code) }
              catch (_: Exception) { terminal(castId, "unknown") }
            finally { if (!readyCommit) cleanup(castId) }
        }
    }
    fun cancelCast() = cancelCast(silent = false)
    private fun cancelCast(silent: Boolean) { currentCastId?.let { id -> castJob?.cancel(); cleanup(id, stopRemoteIfLoaded = true); if (!silent) _route.value = Route.Library } }
    fun stopCast() { currentCastId?.let { control.send(JSONObject().put("t", "stop").put("v", 2).put("castId", it)); cleanup(it) }; _route.value = Route.Library }
    private fun cleanup(castId: String, clearStart: Boolean = true, stopRemoteIfLoaded: Boolean = false) {
        if (stopRemoteIfLoaded && CastCleanupPolicy.shouldSendStop(castId, loadSentCastId)) requestRemoteStop(castId)
        castGate.invalidate(castId); accepted?.cancel(); ready?.cancel(); accepted = null; ready = null
        if (loadSentCastId == castId) loadSentCastId = null
        CastServerService.stop(appContext, castId)
        if (currentCastId == castId) { currentCastId = null; _castingItem.value = null; session.clear(); if (clearStart) _castStart.value = CastStartState.Idle }
    }
    private fun terminal(castId: String, code: String, retryable: Boolean = false, httpStatus: Int? = null) {
        if (currentCastId != castId) return
        _castFailure.value = CastFailure(code, retryable, httpStatus)
        _castStart.value = CastStartState.Failed(castId, code)
        retryItem = _castingItem.value.takeIf { retryable }
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
        when (frame.optString("t")) {
            "loadAccepted" -> accepted?.complete(frame)
            "loadReady" -> ready?.complete(frame)
            "loadFailed", "error" -> terminal(id, frame.getString("code"), frame.getBoolean("retryable"), if (frame.has("httpStatus")) frame.getInt("httpStatus") else null)
            "stopped" -> { cleanup(id); _route.value = Route.Library }
        }
    }
    private fun onSourceTerminal(event: com.flick.sender.SourceServerEvent) {
        val id = event.castId
        requestRemoteStop(id)
        when (event.kind) {
            SourceServerTerminalKind.STOPPED -> {
                cleanup(id)
                _route.value = Route.Library
            }
            SourceServerTerminalKind.FAILED -> terminal(id, "media_bind_failed")
        }
    }
    private fun errorKind(code: String) = when (code) { "no_compatible_lan", "host_mismatch" -> CastErrorKind.NO_LAN; "sender_not_serving", "http_rejected", "media_bind_failed" -> CastErrorKind.REACHABLE_NOT_SERVING; "control_unreachable", "control_disconnected", "media_unreachable" -> CastErrorKind.UNREACHABLE; else -> CastErrorKind.GENERIC }
    fun playPause() = session.togglePlayPause(); fun skip(deltaMs: Long) = session.skip(deltaMs); fun scrubStart() = session.scrubStart(); fun scrubTo(fraction: Float) = session.scrubTo(fraction); fun scrubEnd() = session.scrubEnd(); fun setVolume(level: Float) = session.setVolume(level)
    fun retryCast() { retryItem?.let { item -> retryItem = null; flickToTv(item) } }
    private fun requestRemoteStop(castId: String) { control.send(JSONObject().put("t", "stop").put("v", 2).put("castId", castId)) }
    private fun failPendingResume(code: String) {
        val item = pendingCast
        pendingCast = null
        if (item != null) {
            retryItem = item
            _castFailure.value = CastFailure(code, retryable = true)
            _route.value = Route.Failure(errorKind(code), _castFailure.value!!)
        } else _pairError.value = PairErrorKind.UNREACHABLE
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
        pendingCast = null
        _castFailure.value = CastFailure("active_cast_busy", retryable = retryItem != null)
        _route.value = Route.Failure(errorKind("active_cast_busy"), _castFailure.value!!)
    }
    private val _pairCodeRevision = MutableStateFlow(0L); val pairCodeRevision = _pairCodeRevision.asStateFlow()
    private fun clearEnteredCode() { _pairCodeRevision.value = pairCodeReset.clear() }
    private fun beginPairingAttempt(): Long { invalidatePairingAttempt(); return pairingGate.begin() }
    private fun invalidatePairingAttempt() { pairingGate.invalidate(); pairingJob?.cancel(); pairingJob = null; control.cancelUnauthenticated() }
    private class CastStartupFailure(val code: String) : RuntimeException()

    private companion object {
        const val NSD_RESNAPSHOT_MS = 1_500L
        const val MAX_RESUME_CANDIDATES = 4
    }
}

/** Compatibility source alias for screens while all ownership is application scoped. */
typealias FlickController = CastCoordinator
