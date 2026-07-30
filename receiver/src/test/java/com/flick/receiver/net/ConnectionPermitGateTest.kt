package com.flick.receiver.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionPermitGateTest {

    private fun ConnectionPermitGate.acquire(peer: String) =
        (tryAcquire(peer) as? ConnectionPermit.Granted)?.permit

    @Test fun unauthenticatedConnectionsAreCappedWithoutBlocking() {
        val gate = ConnectionPermitGate(maxConnections = 2, maxPerPeer = 2)
        val first = gate.acquire("10.0.0.2")!!
        assertTrue(gate.acquire("10.0.0.3") != null)
        assertEquals(ConnectionPermit.GlobalLimit, gate.tryAcquire("10.0.0.4"))
        first.release()
        assertTrue(gate.acquire("10.0.0.4") != null)
    }

    /** The lockout the per-peer cap exists for: one host must not be able to take them all. */
    @Test fun onePeerCannotExhaustEveryPermit() {
        val gate = ConnectionPermitGate(maxConnections = 4, maxPerPeer = 2)
        assertTrue(gate.acquire("10.0.0.9") != null)
        assertTrue(gate.acquire("10.0.0.9") != null)
        assertEquals(ConnectionPermit.PeerLimit, gate.tryAcquire("10.0.0.9"))
        assertEquals(ConnectionPermit.PeerLimit, gate.tryAcquire("10.0.0.9"))
        // The owner's own phone, still admitted while the flood is running.
        assertTrue(gate.acquire("10.0.0.5") != null)
        assertTrue(gate.acquire("10.0.0.6") != null)
    }

    /** A flooding peer reconnecting as its sockets time out never gains ground. */
    @Test fun releasedPermitsDoNotAccumulateAgainstAPeer() {
        val gate = ConnectionPermitGate(maxConnections = 4, maxPerPeer = 2)
        repeat(20) {
            val permit = gate.acquire("10.0.0.9")
            assertTrue(permit != null)
            permit!!.release()
        }
        assertTrue(gate.acquire("10.0.0.5") != null)
        assertTrue(gate.acquire("10.0.0.5") != null)
    }

    /** The specific diagnosis wins: a peer at its own cap is never told the global one. */
    @Test fun peerLimitIsReportedAheadOfGlobalExhaustion() {
        val gate = ConnectionPermitGate(maxConnections = 2, maxPerPeer = 2)
        assertTrue(gate.acquire("10.0.0.9") != null)
        assertTrue(gate.acquire("10.0.0.9") != null)
        assertEquals(ConnectionPermit.PeerLimit, gate.tryAcquire("10.0.0.9"))
        assertEquals(ConnectionPermit.GlobalLimit, gate.tryAcquire("10.0.0.8"))
    }

    /**
     * The growth argument: keys exist only while a live socket holds a permit, so the
     * table is bounded by the global cap and drains back to nothing on its own. This
     * is what stands in for an eviction policy.
     */
    @Test fun theTableHoldsOnlyLiveConnections() {
        val gate = ConnectionPermitGate(maxConnections = 4, maxPerPeer = 2)
        val held = (1..40).mapNotNull { gate.acquire("10.0.0.$it") }
        assertEquals(4, held.size)
        assertEquals(4, gate.trackedPeers())
        held.forEach { it.release() }
        assertEquals(0, gate.trackedPeers())
    }

    @Test fun aPeerLeavesTheTableOnlyOnceItsLastPermitIsReleased() {
        val gate = ConnectionPermitGate(maxConnections = 4, maxPerPeer = 2)
        val first = gate.acquire("10.0.0.9")!!
        val second = gate.acquire("10.0.0.9")!!
        first.release()
        assertEquals(1, gate.trackedPeers())
        second.release()
        assertEquals(0, gate.trackedPeers())
    }

    /** Over-releasing a semaphore would silently raise the global ceiling above its cap. */
    @Test fun releasingTwiceCannotCreateAPermit() {
        val gate = ConnectionPermitGate(maxConnections = 2, maxPerPeer = 2)
        val first = gate.acquire("10.0.0.2")!!
        val second = gate.acquire("10.0.0.3")!!
        first.release()
        first.release()
        first.release()
        assertTrue(gate.acquire("10.0.0.4") != null)
        assertEquals(ConnectionPermit.GlobalLimit, gate.tryAcquire("10.0.0.5"))
        second.release()
        assertTrue(gate.acquire("10.0.0.5") != null)
    }

    /** A refusal must not consume a permit from the ceiling it was refused by. */
    @Test fun aRefusedPeerConsumesNothing() {
        val gate = ConnectionPermitGate(maxConnections = 4, maxPerPeer = 1)
        val held = gate.acquire("10.0.0.9")!!
        repeat(50) { assertEquals(ConnectionPermit.PeerLimit, gate.tryAcquire("10.0.0.9")) }
        assertEquals(1, gate.trackedPeers())
        held.release()
        assertEquals(0, gate.trackedPeers())
        assertTrue(gate.acquire("10.0.0.9") != null)
    }

    @Test fun aZeroPerPeerCapAdmitsNobody() {
        val gate = ConnectionPermitGate(maxConnections = 4, maxPerPeer = 0)
        assertEquals(ConnectionPermit.PeerLimit, gate.tryAcquire("10.0.0.9"))
        assertEquals(0, gate.trackedPeers())
    }
}
