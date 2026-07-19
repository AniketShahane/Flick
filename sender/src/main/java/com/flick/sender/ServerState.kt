package com.flick.sender

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The TCP port the embedded HTTP server binds to (shared contract value). */
const val SERVER_PORT: Int = 8080

/** High-level lifecycle of the media server, as reflected in the UI. */
enum class ServerStatus { IDLE, STARTING, RUNNING, ERROR }

/**
 * Immutable snapshot of what the phone UI should show. Produced by the service
 * (source of truth for RUNNING) and by the Activity (for the pre-flight
 * STARTING / no-network ERROR states).
 */
data class ServerUiState(
    val status: ServerStatus = ServerStatus.IDLE,
    val displayName: String? = null,
    val sizeBytes: Long = -1L,
    val lanIp: String? = null,
    val port: Int = SERVER_PORT,
    val token: String? = null,
    val errorMessage: String? = null,
) {
    // The cast URL only exists once the session token is minted: without it there
    // is no servable path, so the UI must not display a bare host:port link.
    val videoUrl: String? get() =
        if (lanIp != null && token != null) "http://$lanIp:$port/v/$token" else null
    val pingUrl: String? get() = lanIp?.let { "http://$it:$port/ping" }
}

/**
 * Process-wide holder that bridges the foreground [CastServerService] (which owns
 * the running server) and the Compose UI in [MainActivity]. A plain StateFlow
 * keeps the two in sync without Binder plumbing — both live in the same process.
 */
object ServerStateHolder {

    private val _state = MutableStateFlow(ServerUiState())
    val state: StateFlow<ServerUiState> = _state.asStateFlow()

    /** A pick just happened; we are resolving metadata / the LAN IP. */
    fun beginStarting() {
        _state.value = ServerUiState(status = ServerStatus.STARTING)
    }

    fun setStarting(name: String?, size: Long, ip: String?) {
        _state.value = ServerUiState(
            status = ServerStatus.STARTING,
            displayName = name,
            sizeBytes = size,
            lanIp = ip,
        )
    }

    fun setRunning(name: String?, size: Long, ip: String, token: String) {
        _state.value = ServerUiState(
            status = ServerStatus.RUNNING,
            displayName = name,
            sizeBytes = size,
            lanIp = ip,
            token = token,
        )
    }

    fun setError(message: String) {
        _state.value = ServerUiState(status = ServerStatus.ERROR, errorMessage = message)
    }

    fun setIdle() {
        _state.value = ServerUiState()
    }
}
