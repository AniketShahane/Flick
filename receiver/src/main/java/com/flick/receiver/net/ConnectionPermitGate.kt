package com.flick.receiver.net

import java.util.concurrent.Semaphore

/**
 * What a socket asking for a pre-auth permit is told. Both refusals are the same
 * generic policy close on the wire; they are distinct only so the device-local log
 * can say which ceiling was reached.
 */
internal sealed interface ConnectionPermit {
    data class Granted(val permit: ConnectionPermitGate.Permit) : ConnectionPermit

    /** Every permit is held, by any mix of peers. */
    data object GlobalLimit : ConnectionPermit

    /** This remote address alone already holds its share. */
    data object PeerLimit : ConnectionPermit
}

/**
 * Small non-blocking cap for unauthenticated sockets: a global ceiling AND a
 * per-remote-address one.
 *
 * The global cap alone was a lockout. One host on the same Wi-Fi could open
 * [maxConnections] sockets, send no frames, and reopen each as the authentication
 * window expired — a fraction of a connection per second of effort — and hold every
 * permit indefinitely. That blocks first-time pairing *and* resume of an existing
 * pairing, so the owner's own phone stops being admitted with nothing on the TV to
 * say why. The per-peer cap is what leaves permits for everyone else.
 *
 * Accounting is over permits **currently held**, never over history, and that is
 * the whole reason this table needs no eviction policy or expiry: an entry exists
 * only while a live pre-auth socket holds a permit, so the number of keys can never
 * exceed [maxConnections] — the global semaphore bounds the table for us. A
 * rate-decayed table keyed on source addresses would be a table an attacker writes
 * into; this one is a table the accepted-connection count writes into. It also
 * cannot refuse an honest phone that reconnects repeatedly through a flaky link,
 * because nothing about a released permit is remembered.
 *
 * It does not stop a single attacker holding several LAN addresses, which raises
 * the cost from one socket loop to acquiring N addresses; against a peer already on
 * the trusted Wi-Fi, cheaper app-agnostic denial exists regardless.
 */
internal class ConnectionPermitGate(
    maxConnections: Int,
    private val maxPerPeer: Int,
) {
    private val permits = Semaphore(maxConnections, true)
    private val lock = Any()
    private val heldPerPeer = HashMap<String, Int>()

    /**
     * The per-peer cap is checked FIRST, so a peer at its own ceiling is told that
     * rather than being credited with a global exhaustion other peers caused.
     */
    fun tryAcquire(peer: String): ConnectionPermit = synchronized(lock) {
        val held = heldPerPeer[peer] ?: 0
        if (held >= maxPerPeer) return ConnectionPermit.PeerLimit
        if (!permits.tryAcquire()) return ConnectionPermit.GlobalLimit
        heldPerPeer[peer] = held + 1
        ConnectionPermit.Granted(Permit(peer))
    }

    /** Live keys, which is what the growth argument above is about. */
    internal fun trackedPeers(): Int = synchronized(lock) { heldPerPeer.size }

    /**
     * One held permit, returned rather than keyed by name so the release can never
     * be charged to a different peer than the acquire was.
     */
    inner class Permit internal constructor(private val peer: String) {
        private var released = false

        /**
         * Idempotent: over-releasing a `Semaphore` silently raises the global
         * ceiling above [maxConnections], which is the one failure this class
         * exists to prevent.
         */
        fun release() {
            synchronized(lock) {
                if (released) return
                released = true
                val held = heldPerPeer[peer] ?: 0
                if (held <= 1) heldPerPeer.remove(peer) else heldPerPeer[peer] = held - 1
            }
            permits.release()
        }
    }
}
