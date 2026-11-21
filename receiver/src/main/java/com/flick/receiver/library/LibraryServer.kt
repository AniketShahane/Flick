package com.flick.receiver.library

import com.flick.receiver.net.ConnectionPermit
import com.flick.receiver.net.ConnectionPermitGate
import com.flick.receiver.util.FlickLog
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The TV end of `/library` (`docs/design/library-channel.md`). The control server applies
 * `/control`'s pre-read gates and hands the socket here; everything after that — the
 * library's own permit pool, the §4 handshake, the session — is this class's.
 *
 * [findKey] reads the pairing store and is only ever called with no lock of this class or
 * of [hub] held. [knockBook] learns where each proven phone's doorbell can be reached.
 */
class LibraryServer(
    private val tvId: () -> String,
    private val findKey: (tvId: String, keyId: String) -> String?,
    private val hub: TvLibrary,
    private val knockBook: KnockAddressBook? = null,
) {
    /**
     * A handshake that has read a key and not yet been installed. A forget marks it, so a
     * proof finished against a key the TV has since removed installs nothing — the same
     * window `ControlServer`'s resume tickets close. `@Volatile` and lock-free, so marking
     * one from the forget path can never wait on anything.
     */
    private class Ticket(val keyId: String) {
        @Volatile var revoked = false
    }

    private val permits = ConnectionPermitGate(MAX_PREAUTH_CONNECTIONS, MAX_PREAUTH_CONNECTIONS_PER_PEER)
    private val tickets: MutableSet<Ticket> = ConcurrentHashMap.newKeySet()
    private val random = SecureRandom()

    /** Call after the pairing store has durably removed [keyId]. Takes no pairing lock. */
    fun forget(keyId: String) {
        tickets.forEach { if (it.keyId == keyId) it.revoked = true }
        hub.forget(keyId)
        knockBook?.forget(keyId)
    }

    /** Call after the pairing store has durably removed every key. */
    fun forgetAll() {
        tickets.forEach { it.revoked = true }
        hub.forgetAll()
        knockBook?.forgetAll()
    }

    /** One `/library` socket that has passed the control server's binding, Host and peer gates. */
    suspend fun serve(session: DefaultWebSocketSession, peer: String, host: String, port: Int) {
        val permit = when (val decision = permits.tryAcquire(peer)) {
            is ConnectionPermit.Granted -> decision.permit
            ConnectionPermit.PeerLimit -> return session.closePolicy("preauth_limit scope=peer")
            ConnectionPermit.GlobalLimit -> return session.closePolicy("preauth_limit scope=global")
        }
        val ticket = AtomicReference<Ticket?>()
        try {
            val step = try {
                withTimeoutOrNull(AUTH_TIMEOUT_MS) { session.handshake(peer, host, port, ticket) }
            } finally {
                permit.release()
            }
            val proven = step as? LibraryHandshake.Step.Authenticated
            if (proven == null) {
                if (step == LibraryHandshake.Step.Deny) runCatching { session.send(Frame.Text(LibraryFrames.denied())) }
                return session.closePolicy(if (step == null) "auth_timeout" else "auth_refused")
            }
            session.serveSession(proven, ticket, peer)
        } finally {
            ticket.getAndSet(null)?.let(tickets::remove)
        }
    }

    private suspend fun DefaultWebSocketSession.handshake(
        peer: String,
        host: String,
        port: Int,
        ticket: AtomicReference<Ticket?>,
    ): LibraryHandshake.Step? {
        val machine = LibraryHandshake(
            tvId = tvId(),
            peerIp = peer,
            serverHost = host,
            serverPort = port,
            findKey = { claimedTvId, keyId ->
                // Registered BEFORE the store is read: a forget sweeping after this line
                // marks it, and one that wrote before the read leaves nothing to find.
                val claim = Ticket(keyId)
                tickets.add(claim)
                ticket.set(claim)
                findKey(claimedTvId, keyId)
            },
            newNonce = { LibraryAuth.randomNonce(random) },
        )
        for (frame in incoming) {
            if (frame !is Frame.Text || !frame.fin) return LibraryHandshake.Step.Violation
            when (val step = machine.onFrame(PhoneFrame.parse(frame.readText()))) {
                is LibraryHandshake.Step.Reply -> send(Frame.Text(step.frame))
                LibraryHandshake.Step.Ignore -> Unit
                else -> {
                    FlickLog.i("library", "handshake end step=${step::class.simpleName} peer=$peer")
                    return step
                }
            }
        }
        return null
    }

    /** The hub's handle on one installed socket: a bounded outbox and a single writer. */
    private class Link(private val session: DefaultWebSocketSession) : LibraryLinkHandle {
        val outbox = Channel<String>(OUTBOX_CAPACITY)
        private val closing = AtomicBoolean(false)

        override fun send(frame: String) {
            if (closing.get()) return
            if (outbox.trySend(frame).isFailure) close("outbox_full")
        }

        override fun close(reason: String) {
            if (!markClosing()) return
            session.launch { session.closePolicy("library_$reason") }
        }

        /** False when the socket was already closing. */
        fun markClosing(): Boolean {
            if (!closing.compareAndSet(false, true)) return false
            outbox.close()
            return true
        }
    }

    private suspend fun DefaultWebSocketSession.serveSession(
        proven: LibraryHandshake.Step.Authenticated,
        ticket: AtomicReference<Ticket?>,
        peer: String,
    ) {
        val link = Link(this)
        link.outbox.trySend(proven.readyFrame)
        val installed = hub.onAuthenticated(proven.keyId, link) { ticket.get()?.revoked != true }
        ticket.getAndSet(null)?.let(tickets::remove)
        if (!installed) {
            link.markClosing()
            FlickLog.i("library", "auth refused reason=forgotten keyIdFp=${FlickLog.fp(proven.keyId)}")
            runCatching { send(Frame.Text(LibraryFrames.denied())) }
            return closePolicy("auth_forgotten")
        }
        FlickLog.i("library", "auth ok keyIdFp=${FlickLog.fp(proven.keyId)}")
        val stillPaired = { findKey(tvId(), proven.keyId) != null }
        knockBook?.recordPeer(proven.keyId, peer, stillPaired)
        // runCatching: a send that fails because the socket went is the reader's to report.
        val writer = launch {
            runCatching { for (frame in link.outbox) send(Frame.Text(frame)) }
        }
        var reason = "peer_closed"
        try {
            for (frame in incoming) {
                val verdict = when (frame) {
                    is Frame.Text -> if (!frame.fin) {
                        TvLibrary.Verdict.CLOSE
                    } else {
                        val parsed = PhoneFrame.parse(frame.readText())
                        if (parsed is PhoneFrame.KnockAt) knockBook?.recordPort(proven.keyId, parsed.port, stillPaired)
                        hub.onFrame(link, parsed)
                    }
                    is Frame.Binary -> {
                        val thumb = if (frame.fin) PhoneFrame.thumbnail(frame.data) else null
                        if (thumb == null) {
                            TvLibrary.Verdict.CLOSE
                        } else {
                            hub.onThumbnail(link, thumb.first, thumb.second)
                            TvLibrary.Verdict.CONTINUE
                        }
                    }
                    else -> TvLibrary.Verdict.CLOSE
                }
                if (verdict == TvLibrary.Verdict.CLOSE) {
                    reason = "violation frame=${frame.frameType.name}"
                    link.markClosing()
                    closePolicy("library_violation")
                    break
                }
            }
        } finally {
            hub.onClosed(link)
            link.markClosing()
            writer.cancel()
            FlickLog.i("library", "close reason=$reason keyIdFp=${FlickLog.fp(proven.keyId)}")
        }
    }

    private companion object {
        const val MAX_PREAUTH_CONNECTIONS = 2
        const val MAX_PREAUTH_CONNECTIONS_PER_PEER = 1
        const val AUTH_TIMEOUT_MS = 6_000L

        /** Far above any burst the hub sends; reaching it means the phone stopped reading. */
        const val OUTBOX_CAPACITY = 512

        suspend fun DefaultWebSocketSession.closePolicy(reason: String) {
            FlickLog.i("library", "close policy reason=$reason")
            runCatching { close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "policy")) }
        }
    }
}
