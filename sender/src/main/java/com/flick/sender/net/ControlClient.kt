package com.flick.sender.net

import com.flick.sender.NetworkUtils
import com.flick.sender.model.ConnectionStatus
import com.flick.sender.util.FlickLog
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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
        /** [reason] is the receiver's denied-frame enum when it sent one, else null. */
        data class Denied(val reason: String? = null) : Result
        data object UpdateRequired : Result
        /** Nothing answered at all: the throwable arrived before or at the WS upgrade. */
        data class Unreachable(val pairCodeSent: Boolean = false) : Result
        /** The dial neither completed nor failed inside the attempt window. */
        data class TimedOut(val pairCodeSent: Boolean = false) : Result
        /**
         * The upgrade succeeded and the receiver then closed the socket. This is an
         * ACTIVE rejection (peer-identity / policy gate), not "nothing is listening".
         */
        data class RejectedByTv(val pairCodeSent: Boolean = false) : Result
        data class ProtocolError(val pairCodeSent: Boolean = false) : Result
        data object Busy : Result
    }

    private val client = HttpClient(CIO) {
        install(WebSockets) {
            pingIntervalMillis = PING_INTERVAL_MS
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

    /**
     * What phase 1 of a first-time pairing produced — the negotiated nonce pair, or
     * its own terminal answer.
     *
     * It exists because pairing now has two budgets rather than one. Everything up to
     * and including putting the `pair` frame on the wire keeps the original six-second
     * window, so a TV that is off or on another subnet is still called unreachable in
     * six seconds. Only the answer to that frame may take longer, and only because the
     * receiver is asking a person.
     */
    private sealed interface PairHandshake {
        data class Negotiated(val clientNonce: String, val serverNonce: String) : PairHandshake
        data class Aborted(val result: Result) : PairHandshake
    }

    /** Optional external-subtitle fields, published as one value for [send]'s thread. */
    private data class LoadSubtitle(val castId: String, val url: String, val label: String?, val language: String?)

    @Volatile
    private var loadSubtitle: LoadSubtitle? = null

    suspend fun pair(host: String, port: Int, device: String, code: String): Result {
        if (!PairLaunch.isCanonicalIpv4(host) || port !in 1..65535 || !ControlProtocolV2.code(code)) return Result.ProtocolError()
        _connection.value = ConnectionStatus.CONNECTING
        var codeSent = false
        val result = open(
            host,
            port,
            decisionBudgetMs = controlDecisionBudgetMs(firstTimePairing = true),
        ) { socket, handshakeBudgetMs ->
            _connection.value = ConnectionStatus.PAIRING
            val clientNonce = ControlProtocolV2.randomId()
            // Phase 1 — whatever is left of the original six seconds after the dial.
            // Nothing in here waits on a human, so nothing in here is allowed to spend
            // the decision budget: a receiver that upgrades and then never negotiates
            // is a broken or hostile peer, not a viewer walking to the TV.
            val handshake = withTimeoutOrNull(handshakeBudgetMs) {
                socket.send(Frame.Text(frame("negotiate", "v" to 2, "minV" to 2, "maxV" to 2, "clientNonce" to clientNonce)))
                val negotiated = receive(socket).objectOrNull()
                    ?: run { FlickLog.w("ws", "pair abort stage=negotiate reason=schema"); return@withTimeoutOrNull PairHandshake.Aborted(Result.UpdateRequired) }
                if (!ControlFrameSchema.preAuth(asMap(negotiated)) ||
                    negotiated.optString("t") != "negotiated" || negotiated.optInt("v", -1) != 2 ||
                    negotiated.optString("clientNonce") != clientNonce || negotiated.optString("serverNonce") == clientNonce || !ControlProtocolV2.id(negotiated.optString("serverNonce")) ||
                    !ControlProtocolV2.id(negotiated.optString("tvId")) || !caps(negotiated.optJSONArray("cap"))) {
                    FlickLog.w("ws", "pair abort stage=negotiate reason=schema")
                    return@withTimeoutOrNull PairHandshake.Aborted(Result.UpdateRequired)
                }
                val serverNonce = negotiated.getString("serverNonce")
                // A write failure can occur after bytes leave the phone; do not offer a possibly consumed code again.
                codeSent = true
                socket.send(Frame.Text(frame("pair", "v" to 2, "clientNonce" to clientNonce, "serverNonce" to serverNonce, "code" to code, "device" to (ControlProtocolV2.normalizedLabel(device, 80) ?: "Phone"))))
                PairHandshake.Negotiated(clientNonce, serverNonce)
            } ?: run {
                FlickLog.w("ws", "pair abort stage=negotiate reason=handshake_timeout")
                return@open Result.TimedOut(pairCodeSent = codeSent)
            }
            val serverNonce = when (handshake) {
                is PairHandshake.Aborted -> return@open handshake.result
                is PairHandshake.Negotiated -> handshake.serverNonce
            }
            // Phase 2 — the receiver has the code and is asking the room. A wrong code
            // is denied here in milliseconds; a right one waits on a person, so the
            // phone says which of those it is doing rather than spinning silently.
            _connection.value = ConnectionStatus.CONFIRM_ON_TV
            val paired = receive(socket).objectOrNull() ?: return@open Result.Unreachable(pairCodeSent = true)
            if (ControlFrameSchema.preAuth(asMap(paired)) && paired.optString("t") == "denied") {
                val reason = deniedReason(paired)
                FlickLog.w("ws", "pair abort stage=paired reason=denied denied=$reason")
                return@open Result.Denied(reason)
            }
            if (!ControlFrameSchema.preAuth(asMap(paired))) {
                FlickLog.w("ws", "pair abort stage=paired reason=schema")
                return@open Result.ProtocolError(pairCodeSent = true)
            }
            val parsed = pairedEndpoint(paired, host, port, clientNonce, serverNonce, false)
                ?: run { FlickLog.w("ws", "pair abort stage=paired reason=schema"); return@open Result.ProtocolError(pairCodeSent = true) }
            val key = paired.optString("key")
            if (!ControlProtocolV2.key(key)) {
                FlickLog.w("ws", "pair abort stage=paired reason=bad_key")
                return@open Result.ProtocolError(pairCodeSent = true)
            }
            when (busyDisposition(socket)) {
                Result.Busy -> return@open Result.PairedBusy(key, parsed)
                is Result.ProtocolError -> return@open Result.ProtocolError(pairCodeSent = true)
                null -> Unit
                else -> return@open Result.ProtocolError(pairCodeSent = true)
            }
            installAuthenticated(socket, parsed)
            // Never the key itself: the identifiers are enough to correlate a pairing.
            FlickLog.i("auth", "paired tvId=${parsed.tvId} keyId=${parsed.keyId} $host:$port")
            Result.Paired(key, parsed)
        }
        return when (result) {
            is Result.Unreachable -> result.copy(pairCodeSent = result.pairCodeSent || codeSent)
            is Result.TimedOut -> result.copy(pairCodeSent = result.pairCodeSent || codeSent)
            is Result.RejectedByTv -> result.copy(pairCodeSent = result.pairCodeSent || codeSent)
            is Result.ProtocolError -> result.copy(pairCodeSent = result.pairCodeSent || codeSent)
            else -> result
        }
    }

    suspend fun resume(pairing: PairingStore.Pairing, host: String = pairing.host, port: Int = pairing.port): Result {
        if (pairing.needsRepair || !PairLaunch.isCanonicalIpv4(host) || port !in 1..65535) return Result.Unreachable()
        _connection.value = ConnectionStatus.CONNECTING
        // No decision budget: resume must stay silent and instant. It never reaches a
        // prompt — the receiver's confirmation lives only on the `pair` path — so the
        // whole handshake keeps exactly the one six-second window it always had.
        return open(
            host,
            port,
            decisionBudgetMs = controlDecisionBudgetMs(firstTimePairing = false),
        ) { socket, _ ->
            val clientNonce = ControlProtocolV2.randomId()
            socket.send(Frame.Text(frame("resumeInit", "v" to 2, "tvId" to pairing.tvId, "keyId" to pairing.keyId, "clientNonce" to clientNonce)))
            val challenge = receive(socket).objectOrNull()
                ?: run { FlickLog.w("ws", "resume abort stage=resumeInit reason=schema"); return@open Result.Unreachable() }
            if (ControlFrameSchema.preAuth(asMap(challenge)) && challenge.optString("t") == "denied") {
                val reason = deniedReason(challenge)
                FlickLog.w("ws", "resume abort stage=resumeChallenge reason=denied denied=$reason")
                return@open Result.Denied(reason)
            }
            if (!ControlFrameSchema.preAuth(asMap(challenge))) {
                FlickLog.w("ws", "resume abort stage=resumeChallenge reason=schema")
                return@open Result.ProtocolError()
            }
            val parsed = pairedEndpoint(challenge, host, port, clientNonce, null, true)
                ?: run { FlickLog.w("ws", "resume abort stage=resumeChallenge reason=schema"); return@open Result.ProtocolError() }
            if (parsed.tvId != pairing.tvId || parsed.keyId != pairing.keyId) {
                FlickLog.w("ws", "resume abort stage=resumeChallenge reason=bad_key")
                return@open Result.ProtocolError()
            }
            val serverNonce = challenge.getString("serverNonce")
            if (serverNonce == clientNonce) {
                FlickLog.w("ws", "resume abort stage=resumeChallenge reason=schema")
                return@open Result.ProtocolError()
            }
            val proof = ControlProtocolV2.proof(pairing.key, "client", pairing.tvId, pairing.keyId, clientNonce, serverNonce, parsed.peerIp, host, port, parsed.tv)
            socket.send(Frame.Text(frame("resumeProof", "v" to 2, "tvId" to pairing.tvId, "keyId" to pairing.keyId, "clientNonce" to clientNonce, "serverNonce" to serverNonce, "proof" to proof)))
            val resumed = receive(socket).objectOrNull()
                ?: run { FlickLog.w("ws", "resume abort stage=resumeProof reason=schema"); return@open Result.Unreachable() }
            if (ControlFrameSchema.preAuth(asMap(resumed)) && resumed.optString("t") == "denied") {
                val reason = deniedReason(resumed)
                FlickLog.w("ws", "resume abort stage=resumeProof reason=denied denied=$reason")
                return@open Result.Denied(reason)
            }
            if (!ControlFrameSchema.preAuth(asMap(resumed))) {
                FlickLog.w("ws", "resume abort stage=resumeProof reason=schema")
                return@open Result.ProtocolError()
            }
            val final = pairedEndpoint(resumed, host, port, clientNonce, serverNonce, true)
                ?: run { FlickLog.w("ws", "resume abort stage=resumeProof reason=schema"); return@open Result.ProtocolError() }
            if (final != parsed || !ControlProtocolV2.constantTimeEquals(
                    ControlProtocolV2.proof(pairing.key, "server", pairing.tvId, pairing.keyId, clientNonce, serverNonce, final.peerIp, host, port, final.tv),
                    resumed.optString("proof"))) {
                FlickLog.w("ws", "resume abort stage=resumeProof reason=bad_key")
                return@open Result.ProtocolError()
            }
            busyDisposition(socket)?.let { return@open it }
            installAuthenticated(socket, final)
            FlickLog.i("auth", "resumed tvId=${final.tvId} keyId=${final.keyId} $host:$port")
            Result.Resumed(final)
        }
    }

    /**
     * Hand [command] to the control socket, answering whether it reached one.
     *
     * False means the frame provably never left this phone — there was no session to
     * write to, or it does not fit the wire's cap. True is NOT delivery: the write
     * itself completes on [scope], and a socket that dies under it is reported by the
     * `write` drop below rather than by this return, because nothing here can wait for
     * an answer a caller under a finger is not allowed to block on.
     *
     * Every drop is logged and no success is, deliberately. A scrub and a walked
     * audio-delay each put frames on this channel around 20-25 times a second, so a line
     * per send would bury the log it is meant to make readable — while a verb that dies
     * here otherwise leaves no trace anywhere, since the receiver acknowledges none of
     * them and `state` carries none of them back.
     */
    fun send(command: JSONObject): Boolean {
        val verb = command.optString("t")
        val active = session ?: run {
            FlickLog.w("ws", "send drop t=$verb reason=no_session")
            return false
        }
        val augmented = withLoadSubtitle(command)
        var encoded = augmented.toString()
        if (!withinFrameCap(encoded)) {
            // An external subtitle must never cost the video its load: drop the
            // optional fields rather than the frame.
            if (augmented === command) {
                FlickLog.w("ws", "send drop t=$verb reason=oversize")
                return false
            }
            encoded = command.toString()
            if (!withinFrameCap(encoded)) {
                FlickLog.w("ws", "send drop t=$verb reason=oversize")
                return false
            }
        }
        scope.launch {
            runCatching { active.send(Frame.Text(encoded)) }.onFailure { error ->
                FlickLog.w("ws", "send drop t=$verb reason=write ${error.javaClass.simpleName}")
                // Cancellation is still cancellation: absorbing it here would leave this
                // coroutine claiming to have finished a write its scope had already ended.
                if (error is CancellationException) throw error
            }
        }
        return true
    }

    /**
     * Arm the optional external-subtitle fields the next `loadMedia` for [castId]
     * carries; a null [url] disarms. Nothing armed means the outgoing frame is
     * returned untouched, so the bytes an un-updated receiver sees for ordinary
     * playback are unchanged and v=2 stays an honest version.
     */
    fun armLoadSubtitle(castId: String, url: String?, label: String?, language: String?) {
        loadSubtitle = url?.let { LoadSubtitle(castId, it, label, language) }
    }

    fun disarmLoadSubtitle() {
        loadSubtitle = null
    }

    private fun withLoadSubtitle(command: JSONObject): JSONObject {
        val armed = loadSubtitle ?: return command
        // Keyed to the cast it was armed for: a stale arm can never ride a later load.
        if (command.optString("t") != "loadMedia" || command.optString("castId") != armed.castId) return command
        val fields = ControlProtocolV2.subtitleFields(armed.url, armed.label, armed.language)
        if (fields.isEmpty()) return command
        val out = JSONObject()
        command.keys().forEach { out.put(it, command.get(it)) }
        fields.forEach { out.put(it.first, it.second) }
        return out
    }

    private fun withinFrameCap(encoded: String): Boolean =
        encoded.toByteArray(Charsets.UTF_8).size <= ControlProtocolV2.MAX_FRAME_BYTES

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

    /**
     * Dials [host]:[port] and runs [action] on the session.
     *
     * The window is split rather than widened. [OPEN_TIMEOUT_MS] still bounds the
     * upgrade absolutely, and the action is handed what is LEFT of it so that its own
     * pre-decision phase fits inside exactly the budget the whole handshake used to
     * have. [decisionBudgetMs] is extra time on top, and it is nonzero only where the
     * action is waiting on a person rather than on software — that is what keeps a TV
     * that is off, asleep or on another subnet reported as unreachable in six seconds.
     */
    private suspend fun open(
        host: String,
        port: Int,
        decisionBudgetMs: Long,
        action: suspend (DefaultClientWebSocketSession, Long) -> Result,
    ): Result {
        FlickLog.d("ws", "dial $host:$port")
        // Distinguishes "the upgrade never completed" from "the receiver upgraded and
        // then closed on us" — the latter is an ACTIVE pre-auth rejection and must
        // never be reported as an unreachable TV.
        var upgraded = false
        return try {
            closeInternal()
            // withTimeoutOrNull, not withTimeout: null means OUR window elapsed, while a
            // cancellation from an enclosing timeout still propagates as a
            // CancellationException and must not be reported as a TV that timed out.
            withTimeoutOrNull(OPEN_TIMEOUT_MS + decisionBudgetMs) {
                val dialStartedAtMs = System.nanoTime() / 1_000_000L
                // The dial keeps the ORIGINAL six seconds of its own, whatever the
                // action is allowed to wait for afterwards. A null here is a dial that
                // never completed, and it falls through to exactly the TimedOut this
                // method has always answered with rather than being absorbed by the
                // wider window.
                val socket = withTimeoutOrNull(OPEN_TIMEOUT_MS) {
                    client.webSocketSession(host = host, port = port, path = "/control")
                }
                if (socket == null) {
                    null
                } else {
                    upgraded = true
                    session = socket
                    // What is left of those six seconds. The action's own pre-decision
                    // phase has to fit inside it, so upgrade plus handshake together
                    // are still bounded by exactly the budget they had before.
                    val handshakeBudgetMs =
                        (OPEN_TIMEOUT_MS - (System.nanoTime() / 1_000_000L - dialStartedAtMs)).coerceAtLeast(0L)
                    val result = action(socket, handshakeBudgetMs)
                    if (result !is Result.Paired && result !is Result.Resumed) {
                        _connection.value = ConnectionStatus.DISCONNECTED
                        closeInternal()
                    }
                    result
                }
            } ?: run {
                FlickLog.w("ws", "connect timed out $host:$port upgraded=$upgraded")
                _connection.value = ConnectionStatus.DISCONNECTED
                closeInternal(); Result.TimedOut()
            }
        } catch (e: CancellationException) {
            _connection.value = ConnectionStatus.DISCONNECTED
            closeInternal(); throw e
        } catch (e: Exception) {
            // The single highest-value line on the phone: it names the exact throwable
            // behind what the UI used to flatten into "couldn't reach that TV".
            FlickLog.w("ws", "connect failed $host:$port ${e.javaClass.simpleName} upgraded=$upgraded", e)
            _connection.value = ConnectionStatus.DISCONNECTED
            closeInternal()
            // Past the upgrade, "nothing is listening" is provably wrong: a
            // ClosedReceiveChannelException here is the receiver's pre-auth policy close.
            if (upgraded) Result.RejectedByTv() else Result.Unreachable()
        }
    }

    private fun installAuthenticated(socket: DefaultClientWebSocketSession, value: AuthenticatedEndpoint) {
        endpoint = value
        _connection.value = ConnectionStatus.CONNECTED
        reader = scope.launch(transportFailures(socket)) {
            // A peer that stops answering does not end this loop: Ktor closes
            // `incoming` WITH the failure as the channel's cause, so it arrives as a
            // throw. Cancellation and our own defects are re-raised unchanged; only a
            // transport failure becomes the disconnect `dropAuthenticated` performs.
            absorbingTransportFailure(
                onTransportLoss = { FlickLog.w("ws", "control lost ${it.javaClass.simpleName}", it) },
                always = { dropAuthenticated(socket) },
            ) {
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
            }
        }
    }

    /**
     * The backstop for whatever escapes [absorbingTransportFailure] itself — a defect
     * it re-raises on purpose, or a throw out of the teardown. Nothing higher up can
     * stand in for it: Ktor's ping/pong watchdog is a SIBLING coroutine of this
     * reader, so [open]'s try/catch had returned the moment pairing succeeded and
     * never saw the timeout that killed the process mid-cast.
     *
     * It is attached to the reader's own `launch` because a CoroutineExceptionHandler
     * is consulted only on the context of the coroutine whose failure reaches a root.
     * The application scope's SupervisorJob refuses the failure, which makes the
     * reader itself that root; a handler on any scope that did not launch the reader
     * would never be looked at.
     */
    private fun transportFailures(socket: DefaultClientWebSocketSession) = CoroutineExceptionHandler { _, error ->
        when (ControlTransportFailure.classify(error)) {
            ControlFailure.CANCELLED -> Unit
            ControlFailure.TRANSPORT -> {
                FlickLog.w("ws", "control lost ${error.javaClass.simpleName}", error)
                dropAuthenticated(socket)
            }
            // Re-raising the SAME instance is what keeps a real defect fatal:
            // kotlinx hands that instance straight to the default uncaught handler
            // instead of reporting a failure inside the handler.
            ControlFailure.BUG -> throw error
        }
    }

    /** Reconnects happen: a late failure from a prior socket must never tear down its successor. */
    private fun dropAuthenticated(socket: DefaultClientWebSocketSession) {
        if (session !== socket) return
        endpoint = null
        _connection.value = ConnectionStatus.DISCONNECTED
        closeInternal()
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

    /** Reason enum from the receiver's denied frame; absent on an older receiver. */
    private fun deniedReason(frame: JSONObject): String? =
        frame.optString("reason").takeIf { it.isNotEmpty() }

    internal companion object {
        const val OPEN_TIMEOUT_MS = 6_000L

        /**
         * Extra time a FIRST-TIME pairing may spend waiting for the answer to its
         * `pair` frame, on top of [OPEN_TIMEOUT_MS].
         *
         * The receiver's on-TV confirmation window is 30 s
         * (`PairingManager.CONFIRM_WINDOW_MS`). This is deliberately longer, and the
         * five seconds are not slack — they decide which of the two ends gets to
         * explain what happened. The receiver's own expiry answers `denied(expired)`,
         * which the phone turns into "that code expired, a new one is on the TV"; a
         * sender that gave up first would replace that with a bare "the TV didn't
         * answer in time", which is both less true and less useful. So this must
         * outlast the receiver's deadline by more than a LAN round trip.
         *
         * It buys nothing on any other path: resume passes no decision budget at all.
         */
        internal const val PAIR_DECISION_TIMEOUT_MS = 35_000L

        // P2 residual: without a wire-level ready acknowledgement, silence in this fixed window cannot prove availability.
        const val BUSY_DISPOSITION_MS = 250L

        /**
         * Ktor derives the pong deadline as exactly twice this value and offers no
         * separate setting, and its pinger spends a whole interval draining stale
         * pongs BEFORE it puts the next ping on the wire. So the two numbers that
         * matter are not the same number: a stalled link is tolerated for 2x
         * (30 seconds) of missing pong once a ping is out, while a TV that dies is
         * noticed between 2x and 3x (30 to 45 seconds) later, never sooner — the
         * phone reads CONNECTED over a dead cast for that whole window.
         *
         * The tolerance floor is what this value is chosen for. The former 5_000
         * tolerated 10 seconds, which an ordinary home-Wi-Fi stall under a 4K VBR
         * peak exceeds, and crossing it releases the receiver's lease and costs the
         * user the film; the 45-second detection ceiling is the price paid for that.
         * 15_000 also keeps a ping on the wire about every 15 seconds, inside Ktor
         * CIO's 45-second server connection-idle timeout, so an authenticated but
         * idle session is never reaped for silence either. The receiver is unaffected
         * in both directions: it installs no pinger of its own (the server plugin's
         * pingPeriod defaults to zero) and the pong is answered by Ktor's own ponger
         * rather than by ControlServer, so single-controller ownership never sees it.
         */
        const val PING_INTERVAL_MS = 15_000L
    }
}

/**
 * The extra window a control handshake may spend waiting on a PERSON, by mode.
 *
 * Resume gets zero, and that is the whole rule worth testing: a phone reconnecting to
 * a TV it has already paired with must never stop on a prompt, so it keeps exactly the
 * single six-second budget it has always had and a TV that has gone is still reported
 * as unreachable in six seconds. Only a first-time pairing can reach the receiver's
 * "Allow this phone?" card, and only because it presented a correct code to get there.
 */
internal fun controlDecisionBudgetMs(firstTimePairing: Boolean): Long =
    if (firstTimePairing) ControlClient.PAIR_DECISION_TIMEOUT_MS else 0L
