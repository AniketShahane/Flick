package com.flick.sender.library

import com.flick.sender.util.FlickLog
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

/**
 * The doorbell listener (contract §13). One thread blocks in `accept` and handles each
 * connection inline, so there is one handshake at a time and nothing runs between knocks:
 * no timer, no wake lock, no discovery.
 *
 * [keyFor], [replyFor] and [onKnock] are called on the listener thread. [replyFor] decides
 * which `knockOk` a verified knock gets, so it may read only state that is safe off main.
 * [onKnock] runs after `knockOk` has been written, with the reply that was sent; its
 * `peerHost` is the address the knock came from, a hint only (§13.3).
 *
 * [nowMs] must keep counting through deep sleep (`SystemClock.elapsedRealtime` on Android):
 * the admission limits are in real time, and a clock that stops while the phone sleeps would
 * keep a window full for hours.
 */
internal class TvDoorbell(
    private val keyFor: (tvId: String, keyId: String) -> String?,
    private val onKnock: (tvId: String, keyId: String, peerHost: String, reply: KnockReply) -> Unit,
    private val nowMs: () -> Long,
    private val peerAllowed: (String?) -> Boolean = KnockPolicy::peerAllowed,
    private val nonce: () -> String = { LibraryProtocol.randomNonce() },
    private val log: (String) -> Unit = { FlickLog.i("doorbell", it) },
    private val newServer: () -> ServerSocket = ::ServerSocket,
    private val replyFor: (tvId: String, keyId: String) -> KnockReply = { _, _ -> KnockReply.CONNECT },
) {
    private val gate = KnockGate()
    private val rateRefusals = ThrottledLog()
    private val proofRefusals = ThrottledLog()
    private val drops = ThrottledLog()
    private val lock = Any()
    private var server: ServerSocket? = null
    private var thread: Thread? = null
    private var shutDown = false

    /** The bound port, or null while nothing listens. */
    val port: Int? get() = synchronized(lock) { server?.localPort }

    /**
     * Listen on [address] at the first free §13 port, falling back to an ephemeral one.
     * Replaces whatever was listening before. Returns the bound port, or null.
     */
    fun bind(address: InetAddress): Int? {
        close()
        if (synchronized(lock) { shutDown }) return null
        val socket = (KnockProtocol.PORTS + KnockProtocol.EPHEMERAL).firstNotNullOfOrNull { candidate ->
            val server = newServer()
            try {
                // The socket of a knock just answered sits in TIME_WAIT on this side; Linux still
                // refuses a second listener on the port with this set.
                server.reuseAddress = true
                server.bind(InetSocketAddress(address, candidate), BACKLOG)
                server
            } catch (e: IOException) {
                runCatching { server.close() }
                null
            }
        } ?: run {
            log("bind failed")
            return null
        }
        val worker = Thread({ acceptLoop(socket) }, "flick-doorbell").apply { isDaemon = true }
        val kept = synchronized(lock) {
            if (!shutDown) {
                server = socket
                thread = worker
            }
            !shutDown
        }
        if (!kept) {
            runCatching { socket.close() }
            return null
        }
        worker.start()
        log("listening port=${socket.localPort}")
        return socket.localPort
    }

    /** Close for good: a bind still in flight on another thread then leaves nothing listening. */
    fun shutdown() {
        synchronized(lock) { shutDown = true }
        close()
    }

    fun close() {
        val socket = synchronized(lock) {
            val current = server
            server = null
            thread = null
            current
        } ?: return
        runCatching { socket.close() }
        log("closed")
    }

    private fun acceptLoop(socket: ServerSocket) {
        var backoffMs = 0L
        while (!socket.isClosed) {
            val peer = try {
                socket.accept()
            } catch (e: IOException) {
                if (socket.isClosed) break
                // EMFILE or ECONNABORTED: the listener is still bound and must keep answering.
                if (backoffMs == 0L) log("accept failed err=${e.javaClass.simpleName}")
                backoffMs = KnockPolicy.acceptBackoffMs(backoffMs)
                try {
                    Thread.sleep(backoffMs)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                continue
            }
            backoffMs = 0L
            // One bad connection, or a failed close of it, must not stop the listener for every later knock.
            try {
                handle(peer)
            } catch (e: RuntimeException) {
                log("knock failed err=${e.javaClass.simpleName}")
            } finally {
                try {
                    peer.close()
                } catch (e: IOException) {
                    log("knock close failed err=${e.javaClass.simpleName}")
                }
            }
        }
    }

    private fun handle(peer: Socket) {
        val startedMs = nowMs()
        val remoteIp = peer.inetAddress?.hostAddress
        val localIp = peer.localAddress?.hostAddress
        if (!peerAllowed(remoteIp) || !peerAllowed(localIp)) return
        if (!gate.tryAdmit(remoteIp!!, startedMs)) {
            rateRefusals.line(startedMs, "knock refused reason=rate")?.let(log)
            return
        }
        val deadline = startedMs + KnockProtocol.DEADLINE_MS
        val verdict = try {
            peer.tcpNoDelay = true
            val phoneNonce = nonce()
            val out = peer.getOutputStream()
            out.write((KnockProtocol.hello(phoneNonce) + "\n").toByteArray(StandardCharsets.UTF_8))
            out.flush()
            val line = KnockProtocol.readLine(peer.getInputStream()) {
                val left = deadline - nowMs()
                if (left <= 0) throw SocketTimeoutException()
                peer.soTimeout = left.toInt()
            } ?: return
            KnockProtocol.verify(line, phoneNonce, localIp!!, peer.localPort, keyFor)
        } catch (e: IOException) {
            drops.line(startedMs, "knock dropped err=${e.javaClass.simpleName}")?.let(log)
            return
        }
        if (verdict !is KnockProtocol.Verdict.Valid) {
            proofRefusals.line(startedMs, "knock refused reason=proof")?.let(log)
            return
        }
        // A reply that cannot be decided must not cost a genuine knock its answer.
        val reply = try {
            replyFor(verdict.tvId, verdict.keyId)
        } catch (e: RuntimeException) {
            KnockReply.CONNECT
        }
        try {
            if (nowMs() >= deadline) return
            val out = peer.getOutputStream()
            out.write((KnockProtocol.ok(reply) + "\n").toByteArray(StandardCharsets.UTF_8))
            out.flush()
        } catch (e: IOException) {
            // The TV may not hear knockOk; the knock is still genuine, so the phone still answers it.
            log("knockOk unsent err=${e.javaClass.simpleName}")
        }
        log("knock ok ms=${nowMs() - startedMs}")
        onKnock(verdict.tvId, verdict.keyId, remoteIp, reply)
    }

    private companion object {
        const val BACKLOG = 2
    }
}
