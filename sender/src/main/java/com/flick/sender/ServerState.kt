package com.flick.sender

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The TCP port the embedded HTTP server binds to (shared contract value). */
const val SERVER_PORT: Int = 8080

/** Bind address for the embedded server: every interface, so the TV can reach it. */
const val SERVER_HOST: String = "0.0.0.0"

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
    val errorMessage: String? = null,
) {
    val videoUrl: String? get() = lanIp?.let { "http://$it:$port/video" }
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

    fun setRunning(name: String?, size: Long, ip: String) {
        _state.value = ServerUiState(
            status = ServerStatus.RUNNING,
            displayName = name,
            sizeBytes = size,
            lanIp = ip,
        )
    }

    fun setError(message: String) {
        _state.value = ServerUiState(status = ServerStatus.ERROR, errorMessage = message)
    }

    fun setIdle() {
        _state.value = ServerUiState()
    }
}
