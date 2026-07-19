package com.flick.receiver.net

import android.os.Handler
import android.os.Looper
import com.flick.receiver.player.PlaybackFrame
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.application.install
import io.ktor.server.plugins.origin
import io.ktor.server.request.host
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.atomic.AtomicLong

/**
 * The TV's control server (control-channel.md §1/§4): Ktor CIO + WebSockets on
 * `ws://<tvIp>:<port>/control`. Control-ONLY — it carries no media and no file
 * access, exposes only playback verbs, binds the TV's LAN IP (never 0.0.0.0),
 * pins the request Host (anti-rebinding), and is pairing-gated. An unpaired LAN
 * device gets nothing.
 *
 * Threading: WS frames arrive on Ktor IO threads. Every command is marshalled to
 * the main thread via [main] before touching [ControlCommands] (which touch
 * ExoPlayer). The ~10 Hz `state` feed reads [stateProvider], which the app keeps
 * fresh from a main-thread loop, so the server itself never touches the player.
 *
 * Hardening (mirrors the media server's Semaphore(4) + idle-timeout posture):
 *  - WebSocket ping/timeout reaps vanished peers so no zombie session lingers.
 *  - Unauthenticated connections are capped ([Semaphore]) and must authenticate
 *    within a deadline; garbage pre-auth frames close the socket.
 *  - `hello` is only accepted while the pairing screen is displayed, and brute
 *    force is throttled in [PairingManager] (rotate-on-miss + escalating lockout).
 *  - Session adoption is serialized behind a mutex with a generation counter, and
 *    every main-thread command re-checks the generation so a superseded session
 *    can never mutate the new session's playback.
 */
class ControlServer(
    private val pairing: PairingManager,
    private val commands: ControlCommands,
    private val stateProvider: () -> PlaybackFrame,
) {
    private val main = Handler(Looper.getMainLooper())
    private val seq = AtomicLong(0L)

    private var server: EmbeddedServer<*, *>? = null

    // Single-active-session invariant, serialized behind [adoptMutex]. A plain
    // volatile check-then-assign let two sessions both win; the generation makes
    // stale main-thread command runnables detectable and droppable.
    private val adoptMutex = Mutex()
    private val genCounter = AtomicLong(0L)

    @Volatile
    private var activeSession: DefaultWebSocketServerSession? = null

    @Volatile
    private var activeEmit: (suspend (String) -> Unit)? = null

    @Volatile
    private var activeGeneration: Long = -1L

    /** Cap on concurrent connections that have not yet authenticated (anti-DoS). */
    private val unauthPermits = Semaphore(MAX_UNAUTH_SESSIONS)

    /**
     * Whether the pairing UI is on screen right now. `hello` (code pairing) is
     * refused unless this is true, so the 4-digit code is never attackable while
     * shown to no one (TV idle/playing). `resume` (128-bit key) is unaffected.
     */
    @Volatile
    var acceptingPairings: Boolean = false

    var boundHost: String? = null
        private set
    var boundPort: Int = -1
        private set

    /** Binds [host] on an ephemeral port and returns the real bound port. */
    suspend fun start(host: String): Int {
        stop()
        val srv = embeddedServer(CIO, port = 0, host = host) {
            install(WebSockets) {
                pingPeriodMillis = PING_PERIOD_MS
                timeoutMillis = PONG_TIMEOUT_MS
            }
            routing {
                webSocket("/control") { handleSession(host) }
            }
        }
        srv.start(wait = false)
        val port = srv.engine.resolvedConnectors().firstOrNull()?.port ?: -1
        server = srv
        boundHost = host
        boundPort = port
        return port
    }

    fun stop() {
        val srv = server ?: return
        server = null
        activeSession = null
        activeEmit = null
        boundPort = -1
        runCatching { srv.stop(GRACE_MS, TIMEOUT_MS) }
    }

    /**
     * Push a TV→phone `error` frame to the current control session (used by the
     * session controller for preflight/backgrounded/fatal failures). No-ops when
     * nothing is connected. Called from the main thread.
     */
    fun sendError(code: String, message: String) {
        val session = activeSession ?: return
        val emit = activeEmit ?: return
        session.launch { runCatching { emit(errorJson(code, message)) } }
    }

    private suspend fun DefaultWebSocketServerSession.handleSession(boundHostExpected: String) {
        // Anti-rebinding: the request Host must equal the bound LAN IP.
        val requestHost = runCatching { call.request.host() }.getOrNull()
        if (requestHost == null || !requestHost.equals(boundHostExpected, ignoreCase = true)) {
            runCatching { close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "host")) }
            return
        }

        // Cap concurrent unauthenticated sessions so a flood of silent connections
        // can't accumulate coroutines/sockets on the TV.
        if (!unauthPermits.tryAcquire()) {
            runCatching { close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "busy")) }
            return
        }
        var permitHeld = true
        var myGeneration = -1L

        val phoneIp = runCatching { call.request.origin.remoteHost }.getOrNull()
        val sendLock = Mutex()
        val emit: suspend (String) -> Unit = { json -> sendLock.withLock { send(Frame.Text(json)) } }

        try {
            // Authenticate within a deadline; a peer that never presents a valid
            // hello/resume is dropped instead of held open forever.
            val key = withTimeoutOrNull(AUTH_DEADLINE_MS) { authenticate(emit, phoneIp) }
            if (key == null) {
                runCatching { close(CloseReason(CloseReason.Codes.NORMAL, "denied")) }
                return
            }

            // Authenticated: free the pre-auth permit and become the sole session.
            unauthPermits.release()
            permitHeld = false
            myGeneration = adopt(this, emit)
            startStateFeed(myGeneration, emit)

            // Authenticated command surface.
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val json = runCatching { JSONObject(frame.readText()) }.getOrNull() ?: continue
                when (json.optString("t")) {
                    "loadMedia" -> {
                        val url = json.optString("url")
                        if (!isValidMediaUrl(url, phoneIp)) {
                            emit(errorJson(ControlErrors.HOST_MISMATCH, "loadMedia URL must be http://<pairedPhone>:<port>/v/<token>"))
                        } else {
                            val title = json.optString("title").ifBlank { null }
                            val durationMs = json.optLong("durationMs", 0L)
                            val startMs = json.optLong("startMs", 0L)
                            postIfCurrent(myGeneration) { commands.onLoadMedia(url, title, durationMs, startMs) }
                        }
                    }
                    "play" -> postIfCurrent(myGeneration) { commands.onPlay() }
                    "pause" -> postIfCurrent(myGeneration) { commands.onPause() }
                    "seek" -> {
                        val posMs = json.optLong("posMs", 0L)
                        postIfCurrent(myGeneration) { commands.onSeek(posMs) }
                    }
                    "skip" -> {
                        val deltaMs = json.optLong("deltaMs", 0L)
                        postIfCurrent(myGeneration) { commands.onSkip(deltaMs) }
                    }
                    "setVolume" -> {
                        val level = json.optDouble("level", 1.0).toFloat()
                        postIfCurrent(myGeneration) { commands.onSetVolume(level) }
                    }
                    "stop" -> postIfCurrent(myGeneration) { commands.onStop() }
                    "ping" -> emit(pongJson(json.optString("id")))
                    // Unknown types are ignored (forward-compatible).
                    else -> Unit
                }
            }
        } finally {
            if (permitHeld) unauthPermits.release()
            if (myGeneration != -1L) {
                // Only relinquish ownership if we are STILL the active session
                // (never null out a successor that already superseded us).
                withContext(NonCancellable) {
                    adoptMutex.withLock {
                        if (activeGeneration == myGeneration) {
                            activeSession = null
                            activeEmit = null
                        }
                    }
                }
            }
        }
    }

    /**
     * Pre-auth loop: read frames until a valid `hello`/`resume` (returns the key),
     * a denial, or a burst of garbage frames (returns null → caller closes). Bad
     * frames are counted so a peer can't stream junk indefinitely.
     */
    private suspend fun DefaultWebSocketServerSession.authenticate(
        emit: suspend (String) -> Unit,
        remoteHost: String?,
    ): String? {
        var invalidFrames = 0
        for (frame in incoming) {
            if (frame !is Frame.Text) {
                if (++invalidFrames >= MAX_PREAUTH_INVALID) return null
                continue
            }
            val json = runCatching { JSONObject(frame.readText()) }.getOrNull()
            if (json == null) {
                if (++invalidFrames >= MAX_PREAUTH_INVALID) return null
                continue
            }
            when (json.optString("t")) {
                "hello" -> {
                    // Only pairable while the code is actually on screen.
                    if (!acceptingPairings) {
                        emit(deniedJson())
                        return null
                    }
                    val device = json.optString("device").ifBlank { "phone" }
                    return when (val result = pairing.attemptPair(json.optString("code"), device, remoteHost)) {
                        is PairingManager.PairResult.Success -> {
                            emit(pairedJson(result.key))
                            result.key
                        }
                        // Denied or LockedOut: identical denial (leak nothing).
                        else -> {
                            emit(deniedJson())
                            null
                        }
                    }
                }
                "resume" -> {
                    val candidate = json.optString("key")
                    return if (pairing.isKnownKey(candidate)) {
                        emit(pairedJson(candidate))
                        candidate
                    } else {
                        emit(deniedJson())
                        null
                    }
                }
                // Ignore anything else until authenticated (unpaired = nothing), but
                // don't let it stream forever.
                else -> if (++invalidFrames >= MAX_PREAUTH_INVALID) return null
            }
        }
        return null
    }

    /**
     * Make [session] the sole live control session and return its generation.
     * Serialized so two sessions can't both win the check-then-assign; supersedes
     * (closes) the previous session.
     */
    private suspend fun adopt(session: DefaultWebSocketServerSession, emit: suspend (String) -> Unit): Long {
        val gen = genCounter.incrementAndGet()
        var previous: DefaultWebSocketServerSession? = null
        adoptMutex.withLock {
            previous = activeSession
            activeSession = session
            activeEmit = emit
            activeGeneration = gen
        }
        previous?.let {
            if (it !== session) runCatching { it.close(CloseReason(CloseReason.Codes.NORMAL, "superseded")) }
        }
        return gen
    }

    /** Post a player command to the main thread, dropping it if we've been superseded. */
    private fun postIfCurrent(generation: Long, action: () -> Unit) {
        main.post { if (activeGeneration == generation) action() }
    }

    /** Launch the ~10 Hz confirmed-position feed inside the session scope. */
    private fun CoroutineScope.startStateFeed(generation: Long, emit: suspend (String) -> Unit) {
        launch {
            while (isActive && activeGeneration == generation) {
                try {
                    emit(stateJson(stateProvider(), seq.incrementAndGet()))
                } catch (_: Exception) {
                    break
                }
                delay(STATE_INTERVAL_MS)
            }
        }
    }

    /**
     * Strict `loadMedia` URL check: the control server is only allowed to point
     * the player at the paired phone's byte-range endpoint. We validate scheme,
     * host, port, and path shape (and forbid user-info/query/fragment) so it can
     * never be turned into an arbitrary LAN fetch/SSRF primitive. Redirects are
     * disabled in the preflight probe; playback keeps cross-protocol redirects off.
     */
    private fun isValidMediaUrl(url: String, phoneIp: String?): Boolean {
        if (phoneIp.isNullOrBlank()) return false
        val expectedHost = phoneIp.removePrefix("::ffff:")
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!"http".equals(uri.scheme, ignoreCase = true)) return false
        if (uri.userInfo != null) return false
        val host = uri.host ?: return false
        if (!host.equals(expectedHost, ignoreCase = true)) return false
        if (uri.port !in 1..65535) return false
        if (uri.rawQuery != null || uri.rawFragment != null) return false
        val path = uri.path ?: return false
        return MEDIA_PATH_REGEX.matches(path)
    }

    // --- Frame builders (org.json; compact) ---------------------------------

    private fun pairedJson(key: String): String = JSONObject()
        .put("t", "paired")
        .put("key", key)
        .put("tv", pairing.tvName)
        .toString()

    private fun deniedJson(): String = JSONObject().put("t", "denied").toString()

    private fun pongJson(id: String): String = JSONObject()
        .put("t", "pong")
        .put("id", id)
        .toString()

    private fun errorJson(code: String, message: String): String = JSONObject()
        .put("t", "error")
        .put("code", code)
        .put("message", message)
        .toString()

    private fun stateJson(frame: PlaybackFrame, seq: Long): String = JSONObject()
        .put("t", "state")
        .put("posMs", frame.posMs)
        .put("durationMs", frame.durationMs)
        .put("playing", frame.playing)
        .put("bufferedMs", frame.bufferedMs)
        .put("phase", frame.phase.wire)
        .put("volume", frame.volume.toDouble())
        .put("seq", seq)
        .toString()

    companion object {
        private const val STATE_INTERVAL_MS = 100L // ~10 Hz
        private const val GRACE_MS = 300L
        private const val TIMEOUT_MS = 800L

        // Reap a vanished phone: ping every 15s, drop it if no pong within 15s.
        private const val PING_PERIOD_MS = 15_000L
        private const val PONG_TIMEOUT_MS = 15_000L

        // Pre-auth bounds (anti-DoS): at most this many un-authenticated sockets,
        // each with this long to authenticate and this many junk frames tolerated.
        private const val MAX_UNAUTH_SESSIONS = 4
        private const val AUTH_DEADLINE_MS = 10_000L
        private const val MAX_PREAUTH_INVALID = 8

        // The only path the media server serves is /v/<url-safe-base64 token>.
        private val MEDIA_PATH_REGEX = Regex("^/v/[A-Za-z0-9_-]+$")
    }
}
