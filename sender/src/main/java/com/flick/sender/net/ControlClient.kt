package com.flick.sender.net

import com.flick.sender.NetworkUtils
import com.flick.sender.model.ConnectionStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** Strict v2 sender endpoint. Keys are accepted only in the single paired response. */
class ControlClient(private val scope: CoroutineScope) {
    data class AuthenticatedEndpoint(val tvId: String, val keyId: String, val tv: String, val peerIp: String, val host: String, val port: Int)
    sealed interface Result {
        data class Paired(val key: String, val endpoint: AuthenticatedEndpoint) : Result
        /** Pairing succeeded and the receiver immediately reported another active controller. */
        data class PairedBusy(val key: String, val endpoint: AuthenticatedEndpoint) : Result
        data class Resumed(val endpoint: AuthenticatedEndpoint) : Result
        data object Denied : Result
        data object UpdateRequired : Result
        data class Unreachable(val pairCodeSent: Boolean = false) : Result
        data class ProtocolError(val pairCodeSent: Boolean = false) : Result
        data object Busy : Result
    }

    private val client = HttpClient(CIO) {
        install(WebSockets) {
            pingIntervalMillis = 5_000
            // Enforce the frozen decoded-frame limit before readText allocates it.
            maxFrameSize = ControlProtocolV2.MAX_FRAME_BYTES.toLong()
        }
    }
    private val _connection = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connection: StateFlow<ConnectionStatus> = _connection.asStateFlow()
    val frames = MutableSharedFlow<JSONObject>(extraBufferCapacity = 64)
    private var session: DefaultClientWebSocketSession? = null
    private var reader: Job? = null
    private var endpoint: AuthenticatedEndpoint? = null

    suspend fun pair(host: String, port: Int, device: String, code: String): Result {
        if (!PairLaunch.isCanonicalIpv4(host) || port !in 1..65535 || !ControlProtocolV2.code(code)) return Result.ProtocolError()
        _connection.value = ConnectionStatus.CONNECTING
        var codeSent = false
        val result = open(host, port) { socket ->
            _connection.value = ConnectionStatus.PAIRING
            val clientNonce = ControlProtocolV2.randomId()
            socket.send(Frame.Text(frame("negotiate", "v" to 2, "minV" to 2, "maxV" to 2, "clientNonce" to clientNonce)))
            val negotiated = receive(socket).objectOrNull() ?: return@open Result.UpdateRequired
            if (!ControlFrameSchema.preAuth(asMap(negotiated)) ||
                negotiated.optString("t") != "negotiated" || negotiated.optInt("v", -1) != 2 ||
                negotiated.optString("clientNonce") != clientNonce || negotiated.optString("serverNonce") == clientNonce || !ControlProtocolV2.id(negotiated.optString("serverNonce")) ||
                !ControlProtocolV2.id(negotiated.optString("tvId")) || !caps(negotiated.optJSONArray("cap"))) return@open Result.UpdateRequired
            val serverNonce = negotiated.getString("serverNonce")
            // A write failure can occur after bytes leave the phone; do not offer a possibly consumed code again.
            codeSent = true
            socket.send(Frame.Text(frame("pair", "v" to 2, "clientNonce" to clientNonce, "serverNonce" to serverNonce, "code" to code, "device" to (ControlProtocolV2.normalizedLabel(device, 80) ?: "Phone"))))
            val paired = receive(socket).objectOrNull() ?: return@open Result.Unreachable(pairCodeSent = true)
            if (ControlFrameSchema.preAuth(asMap(paired)) && paired.optString("t") == "denied") return@open Result.Denied
            if (!ControlFrameSchema.preAuth(asMap(paired))) return@open Result.ProtocolError(pairCodeSent = true)
            val parsed = pairedEndpoint(paired, host, port, clientNonce, serverNonce, false) ?: return@open Result.ProtocolError(pairCodeSent = true)
            val key = paired.optString("key")
            if (!ControlProtocolV2.key(key)) return@open Result.ProtocolError(pairCodeSent = true)
            when (busyDisposition(socket)) {
                Result.Busy -> return@open Result.PairedBusy(key, parsed)
                is Result.ProtocolError -> return@open Result.ProtocolError(pairCodeSent = true)
                null -> Unit
                else -> return@open Result.ProtocolError(pairCodeSent = true)
            }
            installAuthenticated(socket, parsed)
            Result.Paired(key, parsed)
        }
        return when (result) {
            is Result.Unreachable -> result.copy(pairCodeSent = result.pairCodeSent || codeSent)
            is Result.ProtocolError -> result.copy(pairCodeSent = result.pairCodeSent || codeSent)
            else -> result
        }
    }

    suspend fun resume(pairing: PairingStore.Pairing, host: String = pairing.host, port: Int = pairing.port): Result {
        if (pairing.needsRepair || !PairLaunch.isCanonicalIpv4(host) || port !in 1..65535) return Result.Unreachable()
        _connection.value = ConnectionStatus.CONNECTING
        return open(host, port) { socket ->
            val clientNonce = ControlProtocolV2.randomId()
            socket.send(Frame.Text(frame("resumeInit", "v" to 2, "tvId" to pairing.tvId, "keyId" to pairing.keyId, "clientNonce" to clientNonce)))
            val challenge = receive(socket).objectOrNull() ?: return@open Result.Unreachable()
            if (ControlFrameSchema.preAuth(asMap(challenge)) && challenge.optString("t") == "denied") return@open Result.Denied
            if (!ControlFrameSchema.preAuth(asMap(challenge))) return@open Result.ProtocolError()
            val parsed = pairedEndpoint(challenge, host, port, clientNonce, null, true) ?: return@open Result.ProtocolError()
            if (parsed.tvId != pairing.tvId || parsed.keyId != pairing.keyId) return@open Result.ProtocolError()
            val serverNonce = challenge.getString("serverNonce")
            if (serverNonce == clientNonce) return@open Result.ProtocolError()
            val proof = ControlProtocolV2.proof(pairing.key, "client", pairing.tvId, pairing.keyId, clientNonce, serverNonce, parsed.peerIp, host, port, parsed.tv)
            socket.send(Frame.Text(frame("resumeProof", "v" to 2, "tvId" to pairing.tvId, "keyId" to pairing.keyId, "clientNonce" to clientNonce, "serverNonce" to serverNonce, "proof" to proof)))
            val resumed = receive(socket).objectOrNull() ?: return@open Result.Unreachable()
            if (ControlFrameSchema.preAuth(asMap(resumed)) && resumed.optString("t") == "denied") return@open Result.Denied
            if (!ControlFrameSchema.preAuth(asMap(resumed))) return@open Result.ProtocolError()
            val final = pairedEndpoint(resumed, host, port, clientNonce, serverNonce, true) ?: return@open Result.ProtocolError()
            if (final != parsed || !ControlProtocolV2.constantTimeEquals(
                    ControlProtocolV2.proof(pairing.key, "server", pairing.tvId, pairing.keyId, clientNonce, serverNonce, final.peerIp, host, port, final.tv),
                    resumed.optString("proof"))) return@open Result.ProtocolError()
            busyDisposition(socket)?.let { return@open it }
            installAuthenticated(socket, final)
            Result.Resumed(final)
        }
    }

    fun send(command: JSONObject) {
        val active = session ?: return
        val encoded = command.toString()
        if (encoded.toByteArray(Charsets.UTF_8).size > ControlProtocolV2.MAX_FRAME_BYTES) return
        scope.launch { runCatching { active.send(Frame.Text(encoded)) } }
    }

    fun close() { _connection.value = ConnectionStatus.DISCONNECTED; closeInternal() }
    /** Cancels only an untrusted negotiation; never tears down an authenticated remote. */
    fun cancelUnauthenticated() {
        if (endpoint == null) {
            _connection.value = ConnectionStatus.DISCONNECTED
            closeInternal()
        }
    }
    fun shutdown() { close(); client.close() }
    fun authenticatedEndpoint(): AuthenticatedEndpoint? = endpoint

    private suspend fun open(host: String, port: Int, action: suspend (DefaultClientWebSocketSession) -> Result): Result = try {
        closeInternal()
        withTimeout(6_000) {
            val socket = client.webSocketSession(host = host, port = port, path = "/control")
            session = socket
            val result = action(socket)
            if (result !is Result.Paired && result !is Result.Resumed) {
                _connection.value = ConnectionStatus.DISCONNECTED
                closeInternal()
            }
            result
        }
    } catch (e: CancellationException) {
        _connection.value = ConnectionStatus.DISCONNECTED
        closeInternal(); throw e
    } catch (_: Exception) {
        _connection.value = ConnectionStatus.DISCONNECTED
        closeInternal(); Result.Unreachable()
    }

    private fun installAuthenticated(socket: DefaultClientWebSocketSession, value: AuthenticatedEndpoint) {
        endpoint = value
        _connection.value = ConnectionStatus.CONNECTED
        reader = scope.launch {
            try {
                for (incoming in socket.incoming) {
                    if (incoming !is Frame.Text || !incoming.fin) {
                        closeBad(socket, CloseReason.Codes.CANNOT_ACCEPT)
                        break
                    }
                    val frame = when (val parsed = StrictControlJson.parse(incoming.readText())) {
                        is StrictControlJson.Result.Object -> parsed.value
                        StrictControlJson.Result.Oversize -> { closeBad(socket, CloseReason.Codes.TOO_BIG); break }
                        StrictControlJson.Result.Malformed -> { closeBad(socket, CloseReason.Codes.VIOLATED_POLICY); break }
                    }
                    if (!validEvent(frame)) {
                        closeBad(socket, CloseReason.Codes.VIOLATED_POLICY)
                        break
                    }
                    frames.emit(frame)
                }
            } finally {
                if (session === socket) { endpoint = null; _connection.value = ConnectionStatus.DISCONNECTED; closeInternal() }
            }
        }
    }

    private sealed interface Received {
        data class Object(val value: JSONObject) : Received
        data object Closed : Received
    }

    private fun Received.objectOrNull() = (this as? Received.Object)?.value

    private suspend fun receive(socket: DefaultClientWebSocketSession): Received {
        val incoming = socket.incoming.receive()
        if (incoming !is Frame.Text || !incoming.fin) {
            closeBad(socket, CloseReason.Codes.CANNOT_ACCEPT)
            return Received.Closed
        }
        return when (val parsed = StrictControlJson.parse(incoming.readText())) {
            is StrictControlJson.Result.Object -> Received.Object(parsed.value)
            StrictControlJson.Result.Oversize -> { closeBad(socket, CloseReason.Codes.TOO_BIG); Received.Closed }
            StrictControlJson.Result.Malformed -> { closeBad(socket, CloseReason.Codes.VIOLATED_POLICY); Received.Closed }
        }
    }

    /** A receiver reports active ownership immediately after proof, before the sender may serve bytes. */
    private suspend fun busyDisposition(socket: DefaultClientWebSocketSession): Result? {
        return when (val received = withTimeoutOrNull(BUSY_DISPOSITION_MS) { receive(socket) }) {
            null -> null
            Received.Closed -> Result.ProtocolError()
            is Received.Object -> if (ControlFrameSchema.event(asMap(received.value)) && received.value.optString("t") == "busy") Result.Busy else Result.ProtocolError()
        }
    }

    private fun pairedEndpoint(json: JSONObject, host: String, port: Int, clientNonce: String, serverNonce: String?, proofExpected: Boolean): AuthenticatedEndpoint? {
        val fields = mutableSetOf("t", "v", "tv", "tvId", "keyId", "peerIp", "serverHost", "serverPort", "cap")
        if (!proofExpected) fields += "key" else {
            fields += "clientNonce"; fields += "serverNonce"
            if (serverNonce != null) fields += "proof"
        }
        val expectedType = if (proofExpected && serverNonce == null) "resumeChallenge" else if (proofExpected) "resumed" else "paired"
        if (!schema(json, fields) || json.optString("t") != expectedType || json.optInt("v", -1) != 2 ||
            (proofExpected && clientNonce.isNotEmpty() && json.optString("clientNonce") != clientNonce) ||
            (proofExpected && serverNonce != null && json.optString("serverNonce") != serverNonce) || !ControlProtocolV2.id(json.optString("tvId")) ||
            !ControlProtocolV2.id(json.optString("keyId")) || (proofExpected && !ControlProtocolV2.id(json.optString("serverNonce"))) ||
            !PairLaunch.isCanonicalIpv4(json.optString("peerIp")) || !NetworkUtils.isOwnedLanIpv4(json.optString("peerIp")) ||
            json.optString("serverHost") != host || json.optInt("serverPort", -1) != port || !caps(json.optJSONArray("cap"))) return null
        val rawTv = json.optString("tv")
        val tv = ControlProtocolV2.normalizedLabel(rawTv, 80)?.takeIf { it == rawTv } ?: return null
        return AuthenticatedEndpoint(json.getString("tvId"), json.getString("keyId"), tv, json.getString("peerIp"), host, port)
    }

    private fun validEvent(json: JSONObject): Boolean = ControlFrameSchema.event(asMap(json))

    private fun caps(array: JSONArray?): Boolean = array?.let { List(it.length()) { i -> it.optString(i, "") } }?.let(ControlProtocolV2::canonicalCaps) == true
    private fun schema(json: JSONObject, fields: Set<String>): Boolean = json.keys().asSequence().toSet() == fields
    private fun frame(type: String, vararg fields: Pair<String, Any>): String = JSONObject().put("t", type).apply { fields.forEach { put(it.first, it.second) } }.toString()

    private fun asMap(json: JSONObject): Map<String, Any?> = json.keys().asSequence().associateWith { key ->
        when (val value = json.get(key)) {
            is JSONArray -> List(value.length()) { index -> value.get(index) }
            else -> value
        }
    }

    private fun closeInternal() { reader?.cancel(); reader = null; val old = session; session = null; endpoint = null; if (old != null) scope.launch { runCatching { old.close() } } }

    private suspend fun closeBad(socket: DefaultClientWebSocketSession, code: CloseReason.Codes) {
        runCatching { socket.close(CloseReason(code, "invalid")) }
    }

    private companion object {
        // P2 residual: without a wire-level ready acknowledgement, silence in this fixed window cannot prove availability.
        const val BUSY_DISPOSITION_MS = 250L
    }
}
