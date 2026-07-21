package com.flick.receiver.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastGenerationGateTest {
    @Test fun supersessionAndCancellationInvalidateDelayedA() {
        val gate = CastGenerationGate()
        val a = gate.adopt("A")
        val b = gate.adopt("B")
        assertFalse(gate.isCurrent("A", a))
        assertTrue(gate.isCurrent("B", b))
        gate.invalidate()
        assertFalse(gate.isCurrent("B", b))
    }

    @Test fun firstFrameForQueuedACannotAcknowledgeBOrSurviveLifecycleLoss() {
        val gate = CastGenerationGate()
        val a = gate.adopt("A")
        val b = gate.adopt("B")
        assertFalse(gate.isCurrent("A", a))
        assertTrue(gate.isCurrent("B", b))
        gate.invalidate()
        assertFalse(gate.isCurrent("B", b))
    }

    @Test fun staleAControlLossCannotClearNewlyAdoptedBLease() {
        val gate = CastGenerationGate()
        gate.adopt("A", controlLeaseGeneration = 11L)
        val b = gate.adopt("B", controlLeaseGeneration = 12L)
        assertFalse(gate.shouldInvalidateForControlLoss(11L))
        assertTrue(gate.shouldInvalidateForControlLoss(12L))
        assertTrue(gate.isCurrent("B", b))
    }

    @Test fun cancellationInvalidatesPreparingToActiveRace() {
        val gate = CastGenerationGate()
        val preparing = gate.adopt("A", controlLeaseGeneration = 9L)
        gate.invalidate()
        assertFalse(gate.isCurrent("A", preparing))
    }
    @Test fun localLifecycleAuthorityClearsEvenWhenLeaseDoesNotMatch() {
        val gate = CastGenerationGate()
        val b = gate.adopt("B", controlLeaseGeneration = 12L)
        assertFalse(gate.shouldInvalidateForControlLoss(11L))
        gate.forceInvalidate()
        assertFalse(gate.isCurrent("B", b))
    }
}
