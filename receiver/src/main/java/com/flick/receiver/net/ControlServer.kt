package com.flick.receiver.net

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.flick.receiver.player.PlaybackFrame
import io.ktor.http.HttpHeaders
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.origin
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** V2-only receiver endpoint. Authentication and controller ownership are independent leases. */
class ControlServer(
    private val pairing: PairingManager,
    private val commands: ControlCommands,
    private val stateProvider: () -> PlaybackFrame,
) {
    private data class Auth(
        val record: PairingRecord,
        val peerIp: String,
        val clientNonce: String,
        val serverNonce: String,
        val tv: String,
        val resumed: Boolean,
    )
    private data class Connection(
        val session: DefaultWebSocketServerSession,
        val token: Any,
        val generation: Long,
        val emit: suspend (String) -> Unit,
    )

    private val main = Handler(Looper.getMainLooper())
    private val serverLock = Any()
    private val counter = AtomicLong()
    private val sequence = AtomicLong()
    private val ownership = ControlOwnership()
    private val preAuthConnections = ConnectionPermitGate(MAX_PREAUTH_CONNECTIONS)
    @Volatile private var generation = 0L
    @Volatile private var active: Connection? = null
    private var server: EmbeddedServer<*, *>? = null
    var boundHost: String? = null; private set
    var boundPort: Int = -1; private set

    suspend fun start(host: String): Int {
        stop()
        val value = embeddedServer(CIO, host = host, port = 0) {
            install(WebSockets) { maxFrameSize = MAX_FRAME.toLong() }
            routing { webSocket("/control") { session() } }
        }
        value.start(false)
        server = value
        boundHost = host
        boundPort = value.engine.resolvedConnectors().first().port
        return boundPort
    }

    fun stop() {
        val lost = synchronized(serverLock) {
            val prior = active
            active = null
            generation = counter.incrementAndGet()
            ownership.invalidate()?.also { notifyControlLost(it) }
            prior
        }
        lost?.let { connection ->
            connection.session.launch {
                runCatching { connection.session.close(CloseReason(CloseReason.Codes.GOING_AWAY, "closed")) }
            }
        }
        server?.stop(300, 800)
        server = null
        boundHost = null
        boundPort = -1
    }

    fun sendTerminal(castId: String, code: CastFailureCode, retryable: Boolean, status: Int? = null, beforeReady: Boolean = false) {
        sendResult(ControlCastResult.Failed(castId, code, retryable, status, beforeReady))
    }

    fun sendReady(castId: String, probeLatencyMs: Long, startupMs: Long) {
        sendResult(ControlCastResult.Ready(castId, probeLatencyMs, startupMs))
    }

    /** Main-thread local TV exit uses the same cast-correlated terminal path as WS stop. */
    fun stopLocalCast(): Boolean {
        val connection = active ?: return false
        val castId = ownership.currentCast(connection.token, connection.generation) ?: return false
        return stopCast(connection, castId)
    }

    /** Forget revokes the live controller before a durable key clear can reopen pairing. */
    fun forgetAllPairings(): Boolean {
        val connection = active
        val castId = connection?.let { ownership.currentCast(it.token, it.generation) }
        if (connection != null && castId != null) stopCast(connection, castId)
        val displaced = synchronized(serverLock) {
            val prior = active
            active = null
            generation = counter.incrementAndGet()
            ownership.invalidate()?.also { notifyControlLost(it) }
            prior
        }
        displaced?.let { connection ->
            connection.session.launch {
                runCatching { connection.session.close(CloseReason(CloseReason.Codes.GOING_AWAY, "revoked")) }
            }
        }
        return pairing.forgetAllPairings()
    }

    private fun sendResult(result: ControlCastResult) {
        val castId = when (result) {
            is ControlCastResult.Accepted -> result.castId
            is ControlCastResult.Ready -> result.castId
            is ControlCastResult.Failed -> result.castId
            is ControlCastResult.Stopped -> result.castId
        }
        val connection = active ?: return
        if (!ownership.isCurrent(connection.token, connection.generation, castId)) return
        val frame = resultFrame(result)
        // A terminal result authorizes an idle replacement immediately. Capture
        // this lease's sender before releasing ownership so that replacement does
        // not suppress the final result for the socket that owned the cast.
        if (result is ControlCastResult.Failed || result is ControlCastResult.Stopped) connection.session.launch { connection.emit(frame) } else emit(connection, frame)
        if (result is ControlCastResult.Failed || result is ControlCastResult.Stopped) {
            synchronized(serverLock) { ownership.clearCast(connection.token, connection.generation, castId) }
        }
    }

    private suspend fun DefaultWebSocketServerSession.session() {
        val host = boundHost ?: return closePolicy()
        val port = boundPort
        val hosts = call.request.headers.getAll(HttpHeaders.Host)
        val peer = call.request.origin.remoteHost.removePrefix("::ffff:")
        if (port !in 1..65535 || hosts != listOf("$host:$port") || !MediaUrlValidator.isPrivateIpv4(peer)) return closePolicy()
        if (!preAuthConnections.tryAcquire()) return closePolicy()

        val emitLock = kotlinx.coroutines.sync.Mutex()
        val emit: suspend (String) -> Unit = { payload -> emitLock.withLock { send(Frame.Text(payload)) } }
        val auth = try { withTimeoutOrNull(AUTH_TIMEOUT_MS) { authenticate(emit, peer, host, port) } } finally { preAuthConnections.release() }
            ?: return closePolicy()

        // The session itself is the lease token, so an atomic idle replacement can
        // close exactly the displaced socket after installing the new lease.
        val token: Any = this
        lateinit var connection: Connection
        var displaced: Any? = null
        val busy = synchronized(serverLock) {
            if (ownership.isBusy()) {
                true
            } else {
                val next = counter.incrementAndGet()
                connection = Connection(this, token, next, emit)
                displaced = ownership.adoptIdleConnection(token, next)?.displaced
                generation = next
                active = connection
                false
            }
        }
        if (busy) {
            if (auth.resumed) emit(resumed(auth, host, port))
            emit(json(linkedMapOf("t" to "busy", "v" to 2, "reason" to "active_cast")))
            close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "busy"))
            return
        }
        if (auth.resumed) emit(resumed(auth, host, port))
        // The new lease is visible before the old idle socket is closed. Its finally
        // cannot release or poison this lease because every release checks token+gen.
        (displaced as? DefaultWebSocketServerSession)?.launch {
            runCatching { close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "superseded")) }
        }

        stateFeed(connection)
        val pings = PingGate(SystemClock::elapsedRealtime)
        try {
            for (frame in incoming) {
                if (frame !is Frame.Text || !frame.fin) { closeUnsupported(); return }
                val text = frame.readText()
                if (text.toByteArray(Charsets.UTF_8).size > MAX_FRAME) { closeTooBig(); return }
                val objectValue = StrictJson.objectOnly(text)
                if (objectValue == null) { rejectMalformed(connection, null, null); return }
                if (!authenticatedCommand(objectValue, connection, peer, pings)) { rejectMalformed(connection, objectValue.string("t")?.value, objectValue.string("castId")?.value); return }
            }
        } finally {
            val released = synchronized(serverLock) {
                if (!ownership.release(token, connection.generation)) false else {
                    if (active?.token === token && active?.generation == connection.generation) active = null
                    generation = counter.incrementAndGet()
                    true
                }
            }
            if (released) notifyControlLost(connection.generation)
        }
    }

    private suspend fun DefaultWebSocketServerSession.authenticate(
        emit: suspend (String) -> Unit,
        peer: String,
        host: String,
        port: Int,
    ): Auth? {
        var malformed = 0
        var negotiation: Pair<String, String>? = null
        var challenge: Auth? = null
        val proofGate = ResumeProofGate()
        for (frame in incoming) {
            if (frame !is Frame.Text || !frame.fin) { closeUnsupported(); return null }
            val text = frame.readText()
            if (text.toByteArray(Charsets.UTF_8).size > MAX_FRAME) { closeTooBig(); return null }
            val obj = StrictJson.objectOnly(text)
            if (obj == null) {
                if (challenge != null) { emit(deniedFrame()); return null }
                if (++malformed >= MAX_PREAUTH_FRAMES) return null
                continue
            }
            val type = obj.string("t")?.value
            if (challenge != null && type != "resumeProof") { emit(deniedFrame()); return null }
            when (type) {
                "negotiate" -> {
                    if (negotiation != null || challenge != null || !obj.exactly(NEGOTIATE_FIELDS) || obj.integer("v") != 2L || obj.integer("minV") != 2L || obj.integer("maxV") != 2L || !id(obj.string("clientNonce")?.value)) {
                        if (++malformed >= MAX_PREAUTH_FRAMES) return null
                        continue
                    }
                    val clientNonce = obj.string("clientNonce")!!.value
                    negotiation = clientNonce to randomId()
                    emit(json(linkedMapOf("t" to "negotiated", "v" to 2, "clientNonce" to clientNonce, "serverNonce" to negotiation.second, "tvId" to pairing.tvId, "cap" to CAP)))
                }
                "pair" -> {
                    val negotiated = negotiation
                    if (!obj.exactly(PAIR_FIELDS) || obj.integer("v") != 2L || negotiated == null || obj.string("clientNonce")?.value != negotiated.first || obj.string("serverNonce")?.value != negotiated.second || !code(obj.string("code")?.value) || !label(obj.string("device")?.value, 80)) {
                        if (++malformed >= MAX_PREAUTH_FRAMES) return null
                        continue
                    }
                    negotiation = null // exactly one pair can consume a negotiation.
                    val pairResult = synchronized(serverLock) {
                        if (ownership.isBusy()) null else pairing.attemptPair(obj.string("code")!!.value, obj.string("device")!!.value, peer)
                    }
                    return when (pairResult) {
                        is PairAttemptResult.Success -> {
                            val auth = Auth(PairingRecord(pairResult.keyId, pairResult.key, pairResult.deviceLabel), peer, negotiated.first, negotiated.second, pairing.tvName, false)
                            emit(paired(auth, host, port))
                            auth
                        }
                        else -> { emit(deniedFrame()); null }
                    }
                }
                "resumeInit" -> {
                    if (challenge != null || negotiation != null || !obj.exactly(RESUME_INIT_FIELDS) || obj.integer("v") != 2L || !id(obj.string("tvId")?.value) || !id(obj.string("keyId")?.value) || !id(obj.string("clientNonce")?.value)) {
                        if (++malformed >= MAX_PREAUTH_FRAMES) return null
                        continue
                    }
                    val record = pairing.findKey(obj.string("tvId")!!.value, obj.string("keyId")!!.value)
                    if (record == null) { emit(deniedFrame()); return null }
                    challenge = Auth(record, peer, obj.string("clientNonce")!!.value, randomId(), pairing.tvName, true)
                    proofGate.issue()
                    emit(challenge(challenge, host, port))
                }
                "resumeProof" -> {
                    val value = challenge
                    // Consume before validation: no alternate proof can be tried
                    // against this nonce pair, regardless of why it was rejected.
                    if (!proofGate.consume() || value == null || !obj.exactly(RESUME_PROOF_FIELDS) || obj.integer("v") != 2L || obj.string("tvId")?.value != pairing.tvId || obj.string("keyId")?.value != value.record.keyId || obj.string("clientNonce")?.value != value.clientNonce || obj.string("serverNonce")?.value != value.serverNonce || !proof(obj.string("proof")?.value) || !constant(obj.string("proof")!!.value, hmac(value, "client", host, port))) {
                        challenge = null
                        emit(deniedFrame())
                        return null
                    }
                    challenge = null
                    return value
                }
                else -> if (++malformed >= MAX_PREAUTH_FRAMES) return null
            }
        }
        return null
    }

    private fun authenticatedCommand(o: StrictJsonValue.Obj, connection: Connection, peer: String, pings: PingGate): Boolean {
        val type = o.string("t")?.value ?: return false
        if (!ascii(type, 32) || o.integer("v") != 2L) return false
        fun castId() = o.string("castId")?.value?.takeIf(::id)
        fun stale(cast: String) = emit(connection, commandRejected(cast, type, "stale_cast"))
        when (type) {
            "ping" -> {
                val id = o.string("id")?.value
                if (!o.exactly(PING_FIELDS) || !id(id) || !pings.tryAcquire()) return false
                emit(connection, json(linkedMapOf("t" to "pong", "v" to 2, "id" to id)))
            }
            "loadMedia" -> {
                val cast = castId() ?: return false
                val duration = o.integer("durationMs") ?: return false
                val start = o.integer("startMs") ?: return false
                val url = o.string("url")?.value
                val title = o.string("title")?.value
                if (!o.exactly(LOAD_FIELDS) || !url(url, peer) || !label(title, 200) || !ms(duration) || !ms(start) || (duration > 0 && start > duration)) return false
                val outcome = synchronized(serverLock) {
                    if (active?.token !== connection.token || active?.generation != connection.generation) return false
                    when (ownership.adoptCast(connection.token, connection.generation, cast)) {
                        ControlOwnership.CastAdoption.DUPLICATE -> onMainResult { commands.replayResult(cast) } ?: ControlCastResult.Accepted(cast)
                        ControlOwnership.CastAdoption.NEW -> {
                            pairing.closeSurface()
                            onMainResult { commands.onLoadMedia(connection.generation, cast, url!!, title!!, duration, start) }
                                ?: run { ownership.clearCast(connection.token, connection.generation, cast); return false }
                        }
                        ControlOwnership.CastAdoption.STALE_LEASE -> return false
                    }
                }
                sendResult(outcome)
            }
            "play", "pause", "cancelLoad", "stop" -> {
                val cast = castId() ?: return false
                if (!o.exactly(CAST_FIELDS)) return false
                if (!ownership.isCurrent(connection.token, connection.generation, cast)) {
                    val replay = if (type == "stop") onMainResult { commands.replayResult(cast) } else null
                    if (replay is ControlCastResult.Stopped) {
                        connection.session.launch { connection.emit(resultFrame(replay)) }
                        return true
                    }
                    stale(cast)
                } else if (type == "stop") {
                    return stopCast(connection, cast)
                } else post(connection, cast) {
                    when (type) {
                        "play" -> commands.onPlay(cast)
                        "pause" -> commands.onPause(cast)
                        "cancelLoad" -> if (commands.onCancelLoad(cast)) ownership.clearCast(connection.token, connection.generation, cast)
                        else -> Unit
                    }
                }
            }
            "seek" -> commandWithLong(o, connection, type, "posMs") { cast, value -> commands.onSeek(cast, value) }
            "skip" -> {
                val cast = castId() ?: return false
                val value = o.integer("deltaMs") ?: return false
                if (!o.exactly(SKIP_FIELDS) || value !in setOf(-10_000L, 10_000L)) return false
                if (!ownership.isCurrent(connection.token, connection.generation, cast)) stale(cast) else post(connection, cast) { commands.onSkip(cast, value) }
            }
            "setVolume" -> {
                val cast = castId() ?: return false
                val value = o.number("level") ?: return false
                if (!o.exactly(VOLUME_FIELDS) || value !in 0.0..1.0) return false
                if (!ownership.isCurrent(connection.token, connection.generation, cast)) stale(cast) else post(connection, cast) { commands.onSetVolume(cast, value.toFloat()) }
            }
            else -> return false
        }
        return true
    }

    private fun commandWithLong(o: StrictJsonValue.Obj, connection: Connection, type: String, field: String, action: (String, Long) -> Unit): Boolean {
        val cast = o.string("castId")?.value?.takeIf(::id) ?: return false
        val value = o.integer(field) ?: return false
        if (!o.exactly(setOf("t", "v", "castId", field)) || !ms(value)) return false
        if (!ownership.isCurrent(connection.token, connection.generation, cast)) emit(connection, commandRejected(cast, type, "stale_cast")) else post(connection, cast) { action(cast, value) }
        return true
    }

    private fun post(connection: Connection, castId: String, action: () -> Unit) = main.post {
        if (generation == connection.generation && ownership.isCurrent(connection.token, connection.generation, castId)) action()
    }

    private fun stopCast(connection: Connection, castId: String): Boolean {
        if (!ownership.isCurrent(connection.token, connection.generation, castId)) return false
        if (!onMainBoolean { commands.onStop(castId) }) return false
        // Capture the owning lease before clearing so `stopped` remains correlated
        // even if an idle replacement authenticates immediately afterward.
        connection.session.launch { connection.emit(resultFrame(ControlCastResult.Stopped(castId))) }
        synchronized(serverLock) { ownership.clearCast(connection.token, connection.generation, castId) }
        return true
    }

    /** Ktor calls on a worker; compose/player ownership stays on the main thread. */
    private fun onMainResult(block: () -> ControlCastResult?): ControlCastResult? {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val result = AtomicReference<ControlCastResult?>()
        val done = CountDownLatch(1)
        main.post { runCatching { result.set(block()) }.also { done.countDown() } }
        return if (done.await(MAIN_ADOPTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) result.get() else null
    }
    private fun onMainBoolean(block: () -> Boolean): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val result = AtomicReference(false)
        val done = CountDownLatch(1)
        main.post { runCatching { result.set(block()) }.also { done.countDown() } }
        return done.await(MAIN_ADOPTION_TIMEOUT_MS, TimeUnit.MILLISECONDS) && result.get()
    }

    private fun DefaultWebSocketServerSession.stateFeed(connection: Connection) = launch {
        while (isActive && generation == connection.generation) {
            val cast = ownership.currentCast(connection.token, connection.generation)
            if (cast != null) {
                val frame = stateProvider()
                val phase = frame.phase.wire.takeIf { it in PHASES } ?: "buffering"
                emit(connection, json(linkedMapOf("t" to "state", "v" to 2, "castId" to cast, "posMs" to frame.posMs.coerceIn(0, MAX_MS), "durationMs" to frame.durationMs.coerceIn(0, MAX_MS), "playing" to frame.playing, "bufferedMs" to frame.bufferedMs.coerceIn(0, MAX_MS), "phase" to phase, "volume" to frame.volume.toDouble().coerceIn(0.0, 1.0), "seq" to sequence.incrementAndGet())))
            }
            delay(100)
        }
    }

    private fun rejectMalformed(connection: Connection, type: String?, castId: String?) {
        if (ascii(type, 32) && id(castId)) emit(connection, commandRejected(castId!!, type!!, "malformed"))
        connection.session.launch { connection.session.closePolicy() }
    }
    private fun emit(connection: Connection, payload: String) {
        if (active?.token !== connection.token || active?.generation != connection.generation || generation != connection.generation) return
        connection.session.launch { if (active?.token === connection.token && generation == connection.generation) connection.emit(payload) }
    }
    private fun notifyControlLost(lostGeneration: Long) { main.post { commands.onControlLost(lostGeneration) } }
    private suspend fun DefaultWebSocketServerSession.closePolicy() { runCatching { close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "policy")) } }
    private suspend fun DefaultWebSocketServerSession.closeUnsupported() { runCatching { close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "unsupported")) } }
    private suspend fun DefaultWebSocketServerSession.closeTooBig() { runCatching { close(CloseReason(CloseReason.Codes.TOO_BIG, "size")) } }

    private fun paired(a: Auth, host: String, port: Int) = json(
        pairedFrameFields(
            key = a.record.key,
            keyId = a.record.keyId,
            tv = a.tv,
            tvId = pairing.tvId,
            peerIp = a.peerIp,
            serverHost = host,
            serverPort = port,
            capabilities = CAP,
        ),
    )
    private fun challenge(a: Auth, host: String, port: Int) = json(baseMap("resumeChallenge", a, host, port))
    private fun resumed(a: Auth, host: String, port: Int) = json(baseMap("resumed", a, host, port) + ("proof" to hmac(a, "server", host, port)))
    private fun baseMap(t: String, a: Auth, host: String, port: Int): LinkedHashMap<String, Any?> = linkedMapOf("t" to t, "v" to 2, "tv" to a.tv, "tvId" to pairing.tvId, "keyId" to a.record.keyId, "clientNonce" to a.clientNonce, "serverNonce" to a.serverNonce, "peerIp" to a.peerIp, "serverHost" to host, "serverPort" to port, "cap" to CAP)
    private fun hmac(a: Auth, role: String, host: String, port: Int): String {
        val key = android.util.Base64.decode(a.record.key, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING)
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        val values = listOf("Flick-Control-Resume-V2", role, "2", pairing.tvId, a.record.keyId, a.clientNonce, a.serverNonce, a.peerIp, host, port.toString(), a.tv, CAP.joinToString(","))
        val bytes = values.fold(ByteArray(0)) { prior, value -> prior + ByteBuffer.allocate(4).putInt(value.toByteArray(Charsets.UTF_8).size).array() + value.toByteArray(Charsets.UTF_8) }
        return android.util.Base64.encodeToString(mac.doFinal(bytes), android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
    }
    private fun resultFrame(result: ControlCastResult): String = when (result) {
        is ControlCastResult.Accepted -> json(linkedMapOf("t" to "loadAccepted", "v" to 2, "castId" to result.castId))
        is ControlCastResult.Ready -> json(linkedMapOf("t" to "loadReady", "v" to 2, "castId" to result.castId, "probeLatencyMs" to result.probeLatencyMs.coerceIn(0, 60_000), "startupMs" to result.startupMs.coerceIn(0, 60_000)))
        is ControlCastResult.Failed -> json(linkedMapOf<String, Any?>("t" to if (result.beforeReady) "loadFailed" else "error", "v" to 2, "castId" to result.castId, "code" to result.code.wire, "retryable" to result.retryable).apply { result.httpStatus?.takeIf { it in 100..599 }?.let { put("httpStatus", it) } })
        is ControlCastResult.Stopped -> json(stoppedFrameFields(result.castId))
    }
    private fun commandRejected(cast: String, command: String, code: String) = json(linkedMapOf("t" to "commandRejected", "v" to 2, "castId" to cast, "command" to command, "code" to code))
    private fun deniedFrame() = json(linkedMapOf("t" to "denied", "v" to 2))
    private fun json(values: Map<String, Any?>): String = JSONObject().also { target -> values.forEach { (key, value) -> target.put(key, value) } }.toString()

    private fun ascii(value: String?, max: Int) = value != null && value.length <= max && value.all { it.code in 0x20..0x7e }
    private fun id(value: String?) = value?.matches(ID) == true
    private fun proof(value: String?) = value?.matches(PROOF) == true
    private fun code(value: String?) = value?.matches(CODE) == true
    private fun label(value: String?, max: Int) = value != null && value.codePointCount(0, value.length) <= max && normalizeLabel(value, max) == value && value.isNotBlank()
    private fun ms(value: Long) = value in 0..MAX_MS
    private fun url(value: String?, peer: String) = value != null && value.all { it.code <= 0x7f } && MediaUrlValidator.isValid(value, peer)
    private fun constant(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    private fun randomId() = android.util.Base64.encodeToString(ByteArray(16).also(SecureRandom()::nextBytes), android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)

    companion object {
        private const val MAX_FRAME = 16 * 1024
        private const val MAX_MS = 604_800_000L
        private const val MAX_PREAUTH_CONNECTIONS = 4
        private const val MAX_PREAUTH_FRAMES = 3
        private const val AUTH_TIMEOUT_MS = 6_000L
        private const val MAIN_ADOPTION_TIMEOUT_MS = 1_000L
        private val ID = Regex("^[A-Za-z0-9_-]{22}$")
        private val PROOF = Regex("^[A-Za-z0-9_-]{43}$")
        private val CODE = Regex("^[0-9]{4}$")
        private val CAP = listOf("cast-ack", "first-frame-ready", "structured-errors", "resume-hmac")
        private val PHASES = setOf("buffering", "playing", "paused", "ended")
        private val NEGOTIATE_FIELDS = setOf("t", "v", "minV", "maxV", "clientNonce")
        private val PAIR_FIELDS = setOf("t", "v", "clientNonce", "serverNonce", "code", "device")
        private val RESUME_INIT_FIELDS = setOf("t", "v", "tvId", "keyId", "clientNonce")
        private val RESUME_PROOF_FIELDS = setOf("t", "v", "tvId", "keyId", "clientNonce", "serverNonce", "proof")
        private val PING_FIELDS = setOf("t", "v", "id")
        private val LOAD_FIELDS = setOf("t", "v", "castId", "url", "title", "durationMs", "startMs")
        private val CAST_FIELDS = setOf("t", "v", "castId")
        private val SKIP_FIELDS = setOf("t", "v", "castId", "deltaMs")
        private val VOLUME_FIELDS = setOf("t", "v", "castId", "level")
    }
}

/** `paired` deliberately has no negotiation nonces or proof (control v2 §4). */
internal fun pairedFrameFields(
    key: String,
    keyId: String,
    tv: String,
    tvId: String,
    peerIp: String,
    serverHost: String,
    serverPort: Int,
    capabilities: List<String>,
): LinkedHashMap<String, Any?> = linkedMapOf(
    "t" to "paired",
    "v" to 2,
    "key" to key,
    "keyId" to keyId,
    "tv" to tv,
    "tvId" to tvId,
    "peerIp" to peerIp,
    "serverHost" to serverHost,
    "serverPort" to serverPort,
    "cap" to capabilities,
)

internal fun stoppedFrameFields(castId: String): LinkedHashMap<String, Any?> = linkedMapOf(
    "t" to "stopped",
    "v" to 2,
    "castId" to castId,
)
