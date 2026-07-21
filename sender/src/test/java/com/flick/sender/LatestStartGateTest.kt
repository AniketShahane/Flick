package com.flick.sender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestStartGateTest {
    private val castA = "MDEyMzQ1Njc4OWFiY2RlZg"
    private val castB = "QUJDREVGR0hJSktMTU5PUA"

    @Test fun delayedACompletionCannotPublishRunningAfterBStarts() {
        val gate = LatestStartGate()
        val ownership = GenerationResourceOwnership()
        val a = gate.begin(castA)
        ownership.claimServer(a) // A is blocked in MediaHttpServer.start().

        val b = gate.begin(castB)

        assertFalse(gate.runIfLatest(a) { error("stale A published RUNNING") })
        val releasedA = ownership.release(a)
        assertTrue(releasedA.server)
        assertFalse(releasedA.locks)

        ownership.claimServer(b)
        ownership.claimLocks(b)
        assertTrue(gate.runIfLatest(b) {})
        assertTrue(gate.isLatest(b))
    }

    @Test fun delayedAFailureCannotInvalidateOrCloseBResources() {
        val gate = LatestStartGate()
        val ownership = GenerationResourceOwnership()
        val a = gate.begin(castA)
        val b = gate.begin(castB)
        ownership.claimServer(b)
        ownership.claimLocks(b)

        assertFalse(gate.invalidateIfLatest(a))
        val staleRelease = ownership.release(a)

        assertFalse(staleRelease.server)
        assertFalse(staleRelease.locks)
        assertTrue(gate.isLatest(b))
        val bRelease = ownership.release(b)
        assertTrue(bRelease.server)
        assertTrue(bRelease.locks)
    }

    @Test fun delayedStopForAIsIgnoredAfterBSupersedesIt() {
        val gate = LatestStartGate()
        gate.begin(castA)
        val b = gate.begin(castB)

        assertNull(gate.stop(castA))
        assertTrue(gate.isLatest(b))
        assertEquals(b, gate.stop(castB))
        assertFalse(gate.isLatest(b))
    }

    @Test fun latestFailureInvalidatesOnlyItsOwnGeneration() {
        val gate = LatestStartGate()
        val a = gate.begin(castA)

        assertTrue(gate.invalidateIfLatest(a))
        assertFalse(gate.isLatest(a))

        val b = gate.begin(castB)
        assertTrue(gate.isLatest(b))
    }
}
