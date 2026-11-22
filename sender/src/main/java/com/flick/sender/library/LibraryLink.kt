package com.flick.sender.library

import com.flick.sender.util.FlickLog
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSocketException
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The library socket (contract §2, §4, §7): one dial, the mutual proof, then a reader and a
 * single writer. What the frames mean is [Handler]'s; this owns only the socket.
 *
 * Main-confined: every method and every [Handler] call runs on [scope]'s dispatcher.
 */
internal class LibraryLink(
    private val scope: CoroutineScope,
    private val handler: Handler,
    private val client: HttpClient = newClient(),
) {
    /** Everything the dial needs, all of it from control v2's verified endpoint and the pairing store. */
    data class Target(
        val tvId: String,
        val keyId: String,
        val key: String,
        val peerIp: String,
        val host: String,
        val port: Int,
    ) {
        // The pairing key must never reach a log line through a data-class toString.
        override fun toString(): String = "Target(port=$port)"
    }

    sealed interface Outcome {
        data object Ready : Outcome

        /** HTTP 404 on the upgrade: a TV from before the library existed. */
        data object Unsupported : Outcome
        data class Failed(val reason: String) : Outcome
    }

    interface Handler {
        /**
         * The proof has passed and a socket is installed. Called before any frame of it can
         * reach [onFrame]: the TV sends its first `catWant` right behind `libReady`, and the
         * reader runs on [scope]'s dispatcher inline.
         */
        fun onOpened()

        /** An authenticated frame from the TV, already parsed; never a handshake frame. */
        fun onFrame(frame: TvFrame)

        /** The authenticated socket ended, for whatever reason and from whichever side. */
        fun onClosed(reason: String)
    }

    private sealed interface Outgoing {
        class Text(val frame: String) : Outgoing
        class Batch(val frames: List<String>) : Outgoing
        class Binary(val bytes: ByteArray) : Outgoing
    }

    private var socket: DefaultClientWebSocketSession? = null
    private var outgoing: Channel<Outgoing>? = null
    private var reader: Job? = null
    private var writer: Job? = null

    val isOpen: Boolean get() = socket != null

    /** Dial [target] and prove both ends inside six seconds. Only [Outcome.Ready] leaves a socket open. */
    suspend fun connect(target: Target): Outcome {
        close("redial")
        var dialed: DefaultClientWebSocketSession? = null
        val startedAtNs = System.nanoTime()
        val outcome = try {
            withTimeoutOrNull(HANDSHAKE_MS) {
                val session = client.webSocketSession(host = target.host, port = target.port, path = LibraryProtocol.PATH)
                dialed = session
                handshake(session, target)
            } ?: Outcome.Failed("timeout")
        } catch (e: CancellationException) {
            dialed?.let(::closeQuietly)
            throw e
        } catch (e: WebSocketException) {
            // Ktor reports a refused upgrade only through this message, naming the status last.
            if (e.message?.trimEnd()?.endsWith(" 404") == true) Outcome.Unsupported else Outcome.Failed("upgrade")
        } catch (e: Exception) {
            Outcome.Failed(e.javaClass.simpleName)
        }
        val tookMs = (System.nanoTime() - startedAtNs) / 1_000_000L
        val session = dialed
        if (outcome != Outcome.Ready || session == null) {
            session?.let(::closeQuietly)
            FlickLog.w("library", "dial outcome=${outcome.logName()} ms=$tookMs")
            return if (outcome == Outcome.Ready) Outcome.Failed("no_socket") else outcome
        }
        install(session)
        FlickLog.i("library", "ready ms=$tookMs")
        return Outcome.Ready
    }

    private suspend fun handshake(session: DefaultClientWebSocketSession, target: Target): Outcome {
        val clientNonce = LibraryProtocol.randomNonce()
        session.send(Frame.Text(LibraryFrames.hello(target.tvId, target.keyId, clientNonce)))
        val challenge = when (val frame = receive(session)) {
            is TvFrame.Challenge -> frame
            TvFrame.Denied -> return Outcome.Failed("denied")
            else -> return Outcome.Failed("challenge")
        }
        // Any mismatch closes with nothing more sent: the proof is computed only over facts
        // this phone already holds from its authenticated control session.
        if (challenge.tvId != target.tvId || challenge.keyId != target.keyId ||
            challenge.clientNonce != clientNonce || challenge.serverNonce == clientNonce ||
            challenge.peerIp != target.peerIp || challenge.serverHost != target.host ||
            challenge.serverPort != target.port
        ) {
            return Outcome.Failed("mismatch")
        }
        val clientProof = LibraryProtocol.proof(
            target.key, "client", target.tvId, target.keyId, clientNonce, challenge.serverNonce,
            target.peerIp, target.host, target.port,
        )
        session.send(Frame.Text(LibraryFrames.proof(clientNonce, challenge.serverNonce, clientProof)))
        val ready = when (val frame = receive(session)) {
            is TvFrame.Ready -> frame
            TvFrame.Denied -> return Outcome.Failed("denied")
            else -> return Outcome.Failed("ready")
        }
        val expected = LibraryProtocol.proof(
            target.key, "server", target.tvId, target.keyId, clientNonce, challenge.serverNonce,
            target.peerIp, target.host, target.port,
        )
        if (ready.serverNonce != challenge.serverNonce || !LibraryProtocol.constantTimeEquals(expected, ready.proof)) {
            return Outcome.Failed("server_proof")
        }
        return Outcome.Ready
    }

    private suspend fun receive(session: DefaultClientWebSocketSession): TvFrame? {
        val frame = session.incoming.receive()
        if (frame !is Frame.Text || !frame.fin) return null
        return TvFrame.parse(frame.readText())
    }

    private fun install(session: DefaultClientWebSocketSession) {
        val queue = Channel<Outgoing>(MAX_QUEUED)
        socket = session
        outgoing = queue
        handler.onOpened()
        writer = scope.launch {
            try {
                for (item in queue) {
                    when (item) {
                        is Outgoing.Text -> session.send(Frame.Text(item.frame))
                        is Outgoing.Batch -> item.frames.forEach { session.send(Frame.Text(it)) }
                        is Outgoing.Binary -> session.send(Frame.Binary(true, item.bytes))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FlickLog.w("library", "write failed ${e.javaClass.simpleName}")
                ended(session, "write")
            }
        }
        reader = scope.launch {
            var reason = "closed"
            try {
                for (frame in session.incoming) {
                    if (frame !is Frame.Text || !frame.fin) {
                        reason = "binary"
                        violated(session)
                        break
                    }
                    when (val parsed = TvFrame.parse(frame.readText())) {
                        TvFrame.Malformed -> {
                            reason = "malformed"
                            violated(session)
                            break
                        }
                        is TvFrame.Unknown -> FlickLog.d("library", "ignored t=${parsed.type.take(32)}")
                        // The handshake is over; a late handshake frame carries nothing to act on.
                        is TvFrame.Challenge, is TvFrame.Ready, TvFrame.Denied -> Unit
                        else -> if (socket === session) handler.onFrame(parsed)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reason = e.javaClass.simpleName
            }
            ended(session, reason)
        }
    }

    fun sendText(frame: String): Boolean = enqueue(Outgoing.Text(frame))

    /** Frames that must reach the TV back to back, as a catalog does. */
    fun sendBatch(frames: List<String>): Boolean = frames.isNotEmpty() && enqueue(Outgoing.Batch(frames))

    fun sendBinary(bytes: ByteArray): Boolean = enqueue(Outgoing.Binary(bytes))

    /**
     * Contract §3: a full queue closes the socket, which then reports through
     * [Handler.onClosed] like any other end — possibly before this returns.
     */
    private fun enqueue(item: Outgoing): Boolean {
        val queue = outgoing ?: return false
        if (queue.trySend(item).isSuccess) return true
        socket?.let { ended(it, "overflow") }
        return false
    }

    /**
     * Send [frames], then close from this side once they are written or [FLUSH_MS] has
     * passed. Nothing more reaches [Handler] from this socket, and [Handler.onClosed] is not
     * called for it.
     */
    fun closeAfter(frames: List<String>, reason: String) {
        val session = socket ?: return
        val queue = outgoing
        val drain = writer
        if (frames.isEmpty() || queue == null || drain == null || !queue.trySend(Outgoing.Batch(frames)).isSuccess) {
            close(reason)
            return
        }
        socket = null
        outgoing = null
        writer = null
        reader?.cancel()
        reader = null
        // A closed channel still hands the writer everything already queued, then ends its loop.
        queue.close()
        FlickLog.i("library", "closed by phone reason=$reason after=${frames.size}")
        scope.launch {
            withTimeoutOrNull(FLUSH_MS) { drain.join() }
            drain.cancel()
            runCatching { session.close() }
        }
    }

    /** Close the socket from this side. [Handler.onClosed] is not called for a close asked for here. */
    fun close(reason: String) {
        val session = socket ?: return
        detach()
        FlickLog.i("library", "closed by phone reason=$reason")
        closeQuietly(session, LibraryCloseText.of(reason))
    }

    private fun violated(session: DefaultClientWebSocketSession) {
        scope.launch { runCatching { session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "invalid")) } }
    }

    private fun ended(session: DefaultClientWebSocketSession, reason: String) {
        if (socket !== session) return
        detach()
        closeQuietly(session)
        FlickLog.i("library", "closed reason=$reason")
        handler.onClosed(reason)
    }

    private fun detach() {
        socket = null
        outgoing?.close()
        outgoing = null
        val running = listOfNotNull(reader, writer)
        reader = null
        writer = null
        running.forEach { it.cancel() }
    }

    private fun closeQuietly(session: DefaultClientWebSocketSession, text: String = "") {
        scope.launch { runCatching { session.close(CloseReason(CloseReason.Codes.NORMAL, text)) } }
    }

    private fun Outcome.logName(): String = when (this) {
        Outcome.Ready -> "ready"
        Outcome.Unsupported -> "unsupported"
        is Outcome.Failed -> reason
    }

    companion object {
        const val HANDSHAKE_MS = 6_000L

        /** Queued sends, a catalog batch counting once; the TV bounds its own side at 512 frames. */
        const val MAX_QUEUED = 512
        const val FLUSH_MS = 2_000L

        /**
         * Its own client, never control v2's: that one pings every 15 s, and the library socket
         * needs no liveness probe of its own — it closes whenever control v2 does. The TV pings it
         * (60 s, 20-s timeout) to find a half-open link; Ktor answers those pings itself.
         */
        fun newClient(): HttpClient = HttpClient(CIO) {
            install(WebSockets) {
                maxFrameSize = LibraryProtocol.MAX_FRAME_BYTES.toLong()
            }
        }
    }
}
