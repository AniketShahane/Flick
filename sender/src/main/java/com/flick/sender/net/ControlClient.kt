package com.flick.sender.net

import android.util.Log
import com.flick.sender.model.ConnectionStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * The phone's half of the control channel (control-channel.md §4): a Ktor CIO
 * WebSocket **client** to `ws://<tvIp>:<ctrlPort>/control`. Carries only playback
 * verbs — no media, no file access. Frames are compact JSON built with `org.json`
 * (no serialization dep). Sends are serialized through a single writer coroutine
 * so ordering is preserved; incoming TV frames are fanned out on [frames].
 *
 * This does NOT touch the hardened media path — the media server keeps serving
 * `GET/HEAD /v/{token}` byte-range exactly as shipped.
 */
class ControlClient(private val scope: CoroutineScope) {

    sealed interface Result {
        data class Paired(val key: String, val tvName: String) : Result
        object Resumed : Result
        object Denied : Result
        data class Error(val message: String?) : Result
    }

    // pingIntervalMillis makes Ktor send protocol Ping frames and close the socket
    // if a Pong doesn't return in time — the only way a silent TV death (power pull,
    // Wi-Fi drop, roam without a TCP RST) surfaces as a DISCONNECTED transition
    // instead of a forever-frozen "CONNECTED" ghost with commands draining into a
    // black hole. The reader's finally turns that close into DISCONNECTED.
    private val client = HttpClient(CIO) {
        install(WebSockets) { pingIntervalMillis = PING_INTERVAL_MS }
    }

    private val _connection = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connection: StateFlow<ConnectionStatus> = _connection.asStateFlow()

    /** Every TV → phone frame (state / error / pong), decoded. */
    val frames = MutableSharedFlow<JSONObject>(extraBufferCapacity = 128)

    private var session: DefaultClientWebSocketSession? = null
    private val outgoing = Channel<String>(Channel.BUFFERED)
    private var readerJob: Job? = null
    private var writerJob: Job? = null

    /**
     * Open the control WS and authenticate. With a [key] this is a one-tap
     * `resume`; with a [code] it is a first-run `hello` awaiting `paired`/`denied`.
     */
    suspend fun connect(
        host: String,
        port: Int,
        deviceLabel: String,
        code: String? = null,
        key: String? = null,
    ): Result {
        closeInternal()
        // Discard any commands buffered while disconnected (dead-session writer): they
        // must NOT replay ahead of hello/resume on the fresh socket, and a full buffer
        // would silently drop the auth frame itself (trySend failure), wedging pairing.
        drainOutgoing()
        _connection.value = ConnectionStatus.CONNECTING
        // Bound the whole handshake (session open + first-frame wait). A host that
        // accepts TCP but never speaks WS / never answers would otherwise suspend
        // connect() forever, leaving the pairing sheet spinning with no error.
        val result = try {
            withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                val s = client.webSocketSession(host = host, port = port, path = "/control")
                session = s
                startWriter(s)
                if (key != null) {
                    // resume: symmetric with hello — the TV replies `paired` on success
                    // or `denied`+close for an unknown key (revoked/cleared pairing). We
                    // MUST await that verdict, or a denied resume is reported CONNECTED
                    // and the cast dies with a misdiagnosis and no re-pair path.
                    send(JSONObject().put("t", "resume").put("v", PROTOCOL_V).put("key", key))
                    when (receiveJson(s)?.optString("t")) {
                        "paired" -> {
                            _connection.value = ConnectionStatus.CONNECTED
                            startReader(s)
                            Result.Resumed
                        }
                        "denied" -> {
                            _connection.value = ConnectionStatus.FAILED
                            closeInternal()
                            Result.Denied
                        }
                        else -> {
                            _connection.value = ConnectionStatus.FAILED
                            closeInternal()
                            Result.Error("connection closed")
                        }
                    }
                } else {
                    _connection.value = ConnectionStatus.PAIRING
                    send(
                        JSONObject().put("t", "hello").put("v", PROTOCOL_V)
                            .put("code", code ?: "").put("device", deviceLabel),
                    )
                    val first = receiveJson(s)
                    when (first?.optString("t")) {
                        "paired" -> {
                            _connection.value = ConnectionStatus.CONNECTED
                            startReader(s)
                            Result.Paired(first.optString("key"), first.optString("tv"))
                        }
                        "denied" -> {
                            _connection.value = ConnectionStatus.FAILED
                            closeInternal()
                            Result.Denied
                        }
                        else -> {
                            // No paired/denied verdict (closed or a bogus first frame):
                            // never treat an unexpected frame as acceptance.
                            _connection.value = ConnectionStatus.FAILED
                            closeInternal()
                            Result.Error("connection closed")
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            // Scope torn down (e.g. controller disposed) — don't swallow structured
            // cancellation; release the half-open socket and propagate.
            closeInternal()
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "connect failed: ${e.message}")
            _connection.value = ConnectionStatus.FAILED
            closeInternal()
            Result.Error(e.message)
        }
        if (result != null) return result
        // Timed out: tear down the half-open socket and surface a real error.
        _connection.value = ConnectionStatus.FAILED
        closeInternal()
        return Result.Error("timed out")
    }

    /** Fire a command frame (non-blocking; ordered by the writer coroutine). */
    fun send(obj: JSONObject) {
        outgoing.trySend(obj.toString())
    }

    fun close() {
        _connection.value = ConnectionStatus.DISCONNECTED
        closeInternal()
    }

    /** Release the HTTP client entirely (call when the controller is disposed). */
    fun shutdown() {
        close()
        runCatching { client.close() }
    }

    // --- internals ---------------------------------------------------------

    private suspend fun receiveJson(s: DefaultClientWebSocketSession): JSONObject? {
        return try {
            var result: JSONObject? = null
            while (result == null) {
                val frame = s.incoming.receive()
                if (frame is Frame.Text) result = JSONObject(frame.readText())
            }
            result
        } catch (e: CancellationException) {
            // Let the connect-timeout cancellation propagate — swallowing it here would
            // defeat withTimeoutOrNull and report a bogus non-timeout result.
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** Drop any commands still queued from a prior (dead) session before reconnecting. */
    private fun drainOutgoing() {
        while (outgoing.tryReceive().isSuccess) { /* discard stale command */ }
    }

    private fun startReader(s: DefaultClientWebSocketSession) {
        readerJob = scope.launch {
            try {
                for (frame in s.incoming) {
                    if (frame is Frame.Text) {
                        runCatching { JSONObject(frame.readText()) }.getOrNull()?.let { frames.emit(it) }
                    }
                }
            } catch (_: Exception) {
                // channel closed / socket dropped
            } finally {
                if (_connection.value == ConnectionStatus.CONNECTED) {
                    _connection.value = ConnectionStatus.DISCONNECTED
                }
            }
        }
    }

    private fun startWriter(s: DefaultClientWebSocketSession) {
        writerJob = scope.launch {
            try {
                for (msg in outgoing) {
                    if (!isActive) break
                    s.send(Frame.Text(msg))
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun closeInternal() {
        readerJob?.cancel(); readerJob = null
        writerJob?.cancel(); writerJob = null
        val s = session
        session = null
        if (s != null) scope.launch { runCatching { s.close() } }
    }

    private companion object {
        const val TAG = "ControlClient"
        const val PROTOCOL_V = 1
        const val PING_INTERVAL_MS = 5_000L
        const val CONNECT_TIMEOUT_MS = 6_000L
    }
}
