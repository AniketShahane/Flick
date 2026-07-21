package com.flick.receiver.net

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.flick.receiver.player.PlaybackFrame
import com.flick.receiver.util.FlickLog
import io.ktor.http.HttpHeaders
import io.ktor.http.RequestConnectionPoint
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
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

    /** One immutable tuple so a reader can never see a host from one bind and a port from another. */
    private data class Binding(val engine: EmbeddedServer<*, *>, val host: String, val port: Int)

    private val main = Handler(Looper.getMainLooper())
    private val serverLock = Any()
    private val counter = AtomicLong()
    private val sequence = AtomicLong()
    private val ownership = ControlOwnership()
    private val preAuthConnections = ConnectionPermitGate(MAX_PREAUTH_CONNECTIONS)
    @Volatile private var generation = 0L
    @Volatile private var active: Connection? = null
    @Volatile private var binding: Binding? = null
    private val lifecycleLock = Mutex()
    // onDispose runs after the composition scope is cancelled, so engine teardown
    // needs a scope that outlives it.
    private val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val boundHost: String? get() = binding?.host
    val boundPort: Int get() = binding?.port ?: -1

    /**
     * Walks [ports] in order and returns the port that actually bound, or -1.
     * The caller persists and advertises the RETURNED port, never the requested one.
     */
    suspend fun start(host: String, ports: List<Int>): Int = lifecycleLock.withLock {
        stopLocked()
        for (candidate in ports) {
            // Published only after startSuspend returns, but created first so the
            // routing lambda can hand a connection accepted in that window a
            // consistent host+port instead of rejecting it.
            val bound = CompletableDeferred<Pair<String, Int>>()
            val connector = EngineConnectorBuilder()
            connector.host = host
            connector.port = candidate
            val server = embeddedServer(
                CIO,
                environment = applicationEnvironment { },
                configure = {
                    connectors.add(connector)
                    // Defaults to false in Ktor 3.1.3, so a same-port rebind throws
                    // EADDRINUSE while a prior peer socket lingers in TIME_WAIT —
                    // which is exactly what a durable fixed port does on every restart.
                    reuseAddress = true
                },
            ) {
                install(WebSockets) { maxFrameSize = MAX_FRAME.toLong() }
                routing { webSocket("/control") { session(bound) } }
            }
            // NonCancellable: once an engine has begun listening it must reach
            // `binding` or be stopped, or a cancelled reconcile (ON_STOP mid-bind)
            // would strand a listening socket that nothing owns or can close.
            val resolved = runCatching {
                withContext(NonCancellable + Dispatchers.IO) {
                    server.startSuspend(false)
                    server.engine.resolvedConnectors().first().port
                }
            }.getOrElse { error ->
                FlickLog.w("bind", "start failed host=$host port=$candidate", error)
                runCatching { withContext(NonCancellable + Dispatchers.IO) { server.stopSuspend(0, 100) } }
                null
            } ?: continue
            binding = Binding(server, host, resolved)
            bound.complete(host to resolved)
            return@withLock resolved
        }
        FlickLog.e("bind", "no candidate port bound host=$host tried=${ports.size}")
        -1
    }

    suspend fun stop() = lifecycleLock.withLock { stopLocked() }

    /** Non-suspending teardown for onDispose; revokes the lease now, stops the engine off-thread. */
    fun stopDetached() {
        revokeActive("closed")
        lifecycleScope.launch { stop() }
    }

    private suspend fun stopLocked() {
        revokeActive("closed")
        val prior = binding ?: return
        binding = null
        withContext(NonCancellable + Dispatchers.IO) { runCatching { prior.engine.stopSuspend(300, 800) } }
        FlickLog.i("bind", "stopped host=${prior.host} port=${prior.port}")
    }

    private fun revokeActive(closeReason: String) {
        val lost = synchronized(serverLock) {
            val prior = active
            active = null
            generation = counter.incrementAndGet()
            ownership.invalidate()?.also { notifyControlLost(it) }
            prior
        }
        lost?.let { connection ->
            connection.session.launch {
                runCatching { connection.session.close(CloseReason(CloseReason.Codes.GOING_AWAY, closeReason)) }
            }
        }
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
        revokeActive("revoked")
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

    private suspend fun DefaultWebSocketServerSession.session(bound: CompletableDeferred<Pair<String, Int>>) {
        // Snapshot once: host and port then belong to the same bind for the whole
        // session, and a connection accepted in the publication window waits for
        // the binding instead of being rejected.
        val (host, port) = withTimeoutOrNull(BIND_PUBLISH_TIMEOUT_MS) { bound.await() } ?: return closePolicy("not_bound")
        val hosts = call.request.headers.getAll(HttpHeaders.Host)
        val peer = peerIdentity(call.request.local)
        FlickLog.d("ws", "connect peer=$peer hostHdrCount=${hosts?.size ?: 0}")
        if (port !in 1..65535) return closePolicy("port_unbound port=$port")
        if (hosts != listOf("$host:$port")) return closePolicy("host_pin got=${hosts?.joinToString(",") ?: "none"} want=$host:$port")
        if (!MediaUrlValidator.isPrivateIpv4(peer)) return closePolicy("peer_not_private peer=$peer")
        if (!preAuthConnections.tryAcquire()) return closePolicy("preauth_limit")

        val emitLock = Mutex()
        val emit: suspend (String) -> Unit = { payload -> emitLock.withLock { send(Frame.Text(payload)) } }
        val auth = try { withTimeoutOrNull(AUTH_TIMEOUT_MS) { authenticate(emit, peer, host, port) } } finally { preAuthConnections.release() }
            ?: return closePolicy("auth_timeout_or_denied")
        FlickLog.i("auth", "ok mode=${if (auth.resumed) "resume" else "pair"} keyIdFp=${FlickLog.fp(auth.record.keyId)} peer=$peer")

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
            FlickLog.i("ws", "busy reason=active_cast")
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
            FlickLog.i("ws", "close gen=${connection.generation} peer=$peer released=$released")
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
        // A schema mismatch otherwise looks exactly like a silent six-second hang.
        fun overBudget(kind: String): Boolean {
            malformed++
            FlickLog.v("ws", "preauth malformed kind=$kind count=$malformed peer=$peer")
            return malformed >= MAX_PREAUTH_FRAMES
        }
        for (frame in incoming) {
            if (frame !is Frame.Text || !frame.fin) { closeUnsupported(); return null }
            val text = frame.readText()
            if (text.toByteArray(Charsets.UTF_8).size > MAX_FRAME) { closeTooBig(); return null }
            val obj = StrictJson.objectOnly(text)
            if (obj == null) {
                if (challenge != null) { emit(deniedFrame(DENIED_UNKNOWN)); return null }
                if (overBudget("not_object")) return null
                continue
            }
            val type = obj.string("t")?.value
            if (challenge != null && type != "resumeProof") { emit(deniedFrame(DENIED_UNKNOWN)); return null }
            when (type) {
                "negotiate" -> {
                    if (negotiation != null || challenge != null || !obj.exactly(NEGOTIATE_FIELDS) || obj.integer("v") != 2L || obj.integer("minV") != 2L || obj.integer("maxV") != 2L || !id(obj.string("clientNonce")?.value)) {
                        if (overBudget("negotiate")) return null
                        continue
                    }
                    val clientNonce = obj.string("clientNonce")!!.value
                    negotiation = clientNonce to randomId()
                    emit(json(negotiatedFrameFields(clientNonce, negotiation.second, pairing.tvId, CAP)))
                }
                "pair" -> {
                    val negotiated = negotiation
                    if (!obj.exactly(PAIR_FIELDS) || obj.integer("v") != 2L || negotiated == null || obj.string("clientNonce")?.value != negotiated.first || obj.string("serverNonce")?.value != negotiated.second || !code(obj.string("code")?.value) || !label(obj.string("device")?.value, 80)) {
                        if (overBudget("pair")) return null
                        continue
                    }
                    negotiation = null // exactly one pair can consume a negotiation.
                    val pairResult = synchronized(serverLock) {
                        if (ownership.isBusy()) null else pairing.attemptPair(obj.string("code")!!.value, obj.string("device")!!.value, peer)
                    }
                    FlickLog.i("pair", "attempt result=${pairResult?.let { it::class.simpleName } ?: "Busy"} peer=$peer")
                    return when (pairResult) {
                        is PairAttemptResult.Success -> {
                            val auth = Auth(PairingRecord(pairResult.keyId, pairResult.key, pairResult.deviceLabel), peer, negotiated.first, negotiated.second, pairing.tvName, false)
                            emit(paired(auth, host, port))
                            auth
                        }
                        else -> { emit(deniedFrame(deniedReasonFor(pairResult))); null }
                    }
                }
                "resumeInit" -> {
                    if (challenge != null || negotiation != null || !obj.exactly(RESUME_INIT_FIELDS) || obj.integer("v") != 2L || !id(obj.string("tvId")?.value) || !id(obj.string("keyId")?.value) || !id(obj.string("clientNonce")?.value)) {
                        if (overBudget("resumeInit")) return null
                        continue
                    }
                    val record = pairing.findKey(obj.string("tvId")!!.value, obj.string("keyId")!!.value)
                    if (record == null) {
                        FlickLog.i("auth", "resume denied reason=unknown_key keyIdFp=${FlickLog.fp(obj.string("keyId")?.value)} peer=$peer")
                        emit(deniedFrame(DENIED_PROOF))
                        return null
                    }
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
                        FlickLog.i("auth", "resume denied reason=proof peer=$peer")
                        emit(deniedFrame(DENIED_PROOF))
                        return null
                    }
                    challenge = null
                    return value
                }
                else -> if (overBudget("unknown_type")) return null
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
                // Seven independent reject conditions used to collapse into one
                // silent `return false` that the phone reported as startup_timeout.
                fun reject(reason: String): Boolean {
                    FlickLog.i("cast", "loadMedia reject reason=$reason peer=$peer")
                    return false
                }
                val cast = castId() ?: return reject("fields")
                val duration = o.integer("durationMs") ?: return reject("duration")
                val start = o.integer("startMs") ?: return reject("start")
                val url = o.string("url")?.value
                val title = o.string("title")?.value
                if (!o.exactly(LOAD_FIELDS)) return reject("fields")
                if (!url(url, peer)) return reject("url")
                if (!label(title, 200)) return reject("title")
                if (!ms(duration)) return reject("duration")
                if (!ms(start) || (duration > 0 && start > duration)) return reject("start")
                val outcome = synchronized(serverLock) {
                    if (active?.token !== connection.token || active?.generation != connection.generation) return reject("ownership")
                    when (ownership.adoptCast(connection.token, connection.generation, cast)) {
                        ControlOwnership.CastAdoption.DUPLICATE -> onMainResult { commands.replayResult(cast) } ?: ControlCastResult.Accepted(cast)
                        ControlOwnership.CastAdoption.NEW -> {
                            pairing.closeSurface()
                            onMainResult { commands.onLoadMedia(connection.generation, cast, url!!, title!!, duration, start) }
                                ?: run { ownership.clearCast(connection.token, connection.generation, cast); return reject("main_timeout") }
                        }
                        ControlOwnership.CastAdoption.STALE_LEASE -> return reject("stale_lease")
                    }
                }
                FlickLog.i("cast", "loadMedia accept castIdFp=${FlickLog.fp(cast)} src=${FlickLog.endpoint(url)} durationMs=$duration startMs=$start")
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
        connection.session.launch { connection.session.closePolicy("postauth_malformed") }
    }
    private fun emit(connection: Connection, payload: String) {
        if (active?.token !== connection.token || active?.generation != connection.generation || generation != connection.generation) return
        connection.session.launch { if (active?.token === connection.token && generation == connection.generation) connection.emit(payload) }
    }
    private fun notifyControlLost(lostGeneration: Long) { main.post { commands.onControlLost(lostGeneration) } }
    /**
     * The wire bytes are unchanged and stay generic; [reason] never leaves the
     * device. Five distinct pre-auth exits used to funnel through one argument-less
     * close, so an active rejection was indistinguishable from nothing listening.
     */
    private suspend fun DefaultWebSocketServerSession.closePolicy(reason: String) {
        FlickLog.w("ws", "close policy reason=$reason")
        runCatching { close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "policy")) }
    }
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
    private fun deniedFrame(reason: String) = json(deniedFrameFields(reason))
    // Android's org.json does NOT wrap a java.util.List into a JSONArray: put()
    // stores it raw and JSONStringer then emits it via toString() as a quoted
    // string, so `cap` would ship as "[a, b]" and fail the sender's array schema.
    private fun json(values: Map<String, Any?>): String = controlFrameJson(values)

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
        // A socket accepted between listen() and the binding being published waits
        // rather than being rejected; it can never outlive the auth deadline.
        private const val BIND_PUBLISH_TIMEOUT_MS = 2_000L
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

// The complete `denied` reason vocabulary. Only "code" and "expired" are derived
// from what the user typed, so the frame stays non-enumerating: it is not an
// oracle for guessing a key id, a device, or whether a TV has ever been paired.
internal const val DENIED_CODE = "code"
internal const val DENIED_EXPIRED = "expired"
internal const val DENIED_SURFACE = "surface"
internal const val DENIED_LOCKED = "locked"
internal const val DENIED_BUSY = "busy"
internal const val DENIED_STORAGE = "storage"
internal const val DENIED_PROOF = "proof"
internal const val DENIED_UNKNOWN = "unknown"

internal val DENIED_REASONS = setOf(
    DENIED_CODE, DENIED_EXPIRED, DENIED_SURFACE, DENIED_LOCKED,
    DENIED_BUSY, DENIED_STORAGE, DENIED_PROOF, DENIED_UNKNOWN,
)

/** A null result is the ownership busy short-circuit, which never reaches PairingManager. */
internal fun deniedReasonFor(result: PairAttemptResult?): String = when (result) {
    null -> DENIED_BUSY
    is PairAttemptResult.InvalidCode -> DENIED_CODE
    is PairAttemptResult.Expired -> DENIED_EXPIRED
    is PairAttemptResult.SurfaceClosed -> DENIED_SURFACE
    is PairAttemptResult.LockedOut -> DENIED_LOCKED
    is PairAttemptResult.PersistenceFailed -> DENIED_STORAGE
    is PairAttemptResult.Success -> DENIED_UNKNOWN
}

internal fun deniedFrameFields(reason: String): LinkedHashMap<String, Any?> = linkedMapOf(
    "t" to "denied",
    "v" to 2,
    "reason" to if (reason in DENIED_REASONS) reason else DENIED_UNKNOWN,
)

/**
 * The peer identity fed to the private-IPv4 gate, to per-host pairing throttling
 * and to field 8 of the resume HMAC transcript.
 *
 * `remoteHost` resolves `InetSocketAddress.getHostName()` — a blocking
 * reverse-DNS (PTR) query on the LAN, which returns a HOSTNAME on any router
 * running dnsmasq (most consumer/ISP routers answer PTR for DHCP clients).
 * `remoteAddress` uses `getHostString()` and is the only non-resolving,
 * non-header-derived peer identity available. `local` is used rather than
 * `origin` because `origin` is overridable by a Forwarded/XForwardedHeaders
 * plugin, while `local` is always the CIO connection point derived from the
 * accepted socket. The sender validates `peerIp` as an IPv4 literal, so this must
 * never be a name. When the socket address is null Ktor returns the literal
 * "unknown", which correctly fails the gate — fail-closed is intended.
 */
internal fun peerIdentity(point: RequestConnectionPoint): String =
    point.remoteAddress.removePrefix("::ffff:")

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

internal fun negotiatedFrameFields(
    clientNonce: String,
    serverNonce: String,
    tvId: String,
    capabilities: List<String>,
): LinkedHashMap<String, Any?> = linkedMapOf(
    "t" to "negotiated",
    "v" to 2,
    "clientNonce" to clientNonce,
    "serverNonce" to serverNonce,
    "tvId" to tvId,
    "cap" to capabilities,
)

/** Converts protocol collections explicitly because Android's JSONObject does not. */
internal fun controlFrameJson(values: Map<String, Any?>): String = JSONObject().also { target ->
    values.forEach { (key, value) ->
        target.put(key, if (value is Collection<*>) JSONArray(value) else value)
    }
}.toString()

internal fun stoppedFrameFields(castId: String): LinkedHashMap<String, Any?> = linkedMapOf(
    "t" to "stopped",
    "v" to 2,
    "castId" to castId,
)
