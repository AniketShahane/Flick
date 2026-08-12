package com.flick.sender

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The TCP port the embedded HTTP server binds to (shared contract value). */
const val SERVER_PORT: Int = 8080

/** High-level lifecycle of the media server, as reflected in the UI. */
enum class ServerStatus { IDLE, STARTING, RUNNING, ERROR }

/** A cast-correlated terminal source-server outcome for the cast coordinator. */
enum class SourceServerTerminalKind { STOPPED, FAILED }

data class SourceServerEvent(
    val sequence: Long,
    val castId: String,
    val generation: Long,
    val kind: SourceServerTerminalKind,
    val errorCode: String? = null,
)

/**
 * A fault this phone's own server raised about a cast that was already being served.
 *
 * Separate from [SourceServerEvent] because it is not a terminal: the request handler
 * that raises it neither owns the cast nor can end one. It is the record the coordinator
 * prefers over whatever the receiver later guesses about a body that stopped arriving.
 */
data class SourceFaultEvent(
    val sequence: Long,
    val castId: String,
    val code: String,
)

/**
 * Immutable snapshot of what the phone UI should show. Produced by the service
 * (source of truth for RUNNING) and by the Activity (for the pre-flight
 * STARTING / no-network ERROR states).
 */
data class ServerUiState(
    val status: ServerStatus = ServerStatus.IDLE,
    val castId: String? = null,
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
}

/**
 * Process-wide holder that bridges the foreground [CastServerService] (which owns
 * the running server) and the Compose UI in [MainActivity]. A plain StateFlow
 * keeps the two in sync without Binder plumbing — both live in the same process.
 */
object ServerStateHolder {

    private val _state = MutableStateFlow(ServerUiState())
    val state: StateFlow<ServerUiState> = _state.asStateFlow()

    private val _terminalEvent = MutableStateFlow<SourceServerEvent?>(null)
    /** Last source-server terminal event, retained so a newly-attached coordinator can observe it. */
    val terminalEvent: StateFlow<SourceServerEvent?> = _terminalEvent.asStateFlow()
    private var terminalSequence = 0L

    private val _sourceFault = MutableStateFlow<SourceFaultEvent?>(null)
    /** Last mid-stream fault this phone's server raised, retained for the same reason. */
    val sourceFault: StateFlow<SourceFaultEvent?> = _sourceFault.asStateFlow()
    private var faultSequence = 0L

    /** A pick just happened; we are resolving metadata / the LAN IP. */
    fun beginStarting(castId: String) {
        _state.value = ServerUiState(status = ServerStatus.STARTING, castId = castId)
    }

    fun setStarting(castId: String, name: String?, size: Long, ip: String?) {
        _state.value = ServerUiState(
            status = ServerStatus.STARTING,
            castId = castId,
            displayName = name,
            sizeBytes = size,
            lanIp = ip,
        )
    }

    fun setRunning(castId: String, name: String?, size: Long, ip: String, token: String) {
        _state.value = ServerUiState(
            status = ServerStatus.RUNNING,
            castId = castId,
            displayName = name,
            sizeBytes = size,
            lanIp = ip,
            token = token,
        )
    }

    fun setError(castId: String?, message: String) {
        _state.value = ServerUiState(status = ServerStatus.ERROR, castId = castId, errorMessage = message)
    }

    fun setIdle() {
        _state.value = ServerUiState()
    }

    /**
     * Record why the bytes stopped, against whichever cast is being served right now.
     *
     * The cast id is read here rather than passed in: the HTTP handler that raises this
     * is deliberately ignorant of cast identity — it holds a token and a file descriptor
     * — and threading one through would give the request path a second thing to keep in
     * step with a retarget. A publish with no cast running is dropped, because a fault
     * with nothing to attribute it to is not evidence about anything.
     */
    @Synchronized
    fun publishSourceFault(code: String) {
        val castId = _state.value.castId ?: return
        _sourceFault.value = SourceFaultEvent(sequence = ++faultSequence, castId = castId, code = code)
    }

    @Synchronized
    internal fun publishTerminal(
        session: CastGeneration,
        kind: SourceServerTerminalKind,
        errorCode: String? = null,
    ) {
        _terminalEvent.value = SourceServerEvent(
            sequence = ++terminalSequence,
            castId = session.castId,
            generation = session.value,
            kind = kind,
            errorCode = errorCode,
        )
    }
}
