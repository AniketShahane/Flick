package com.flick.sender.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NsdRetryGateTest {
    @Test fun failedStartAndUnexpectedStopScheduleExactlyOneRetry() {
        val gate = NsdRetryGate()
        assertTrue(gate.begin())
        assertTrue(gate.startFailed())
        assertFalse(gate.stopped())
        assertTrue(gate.retryFired())
        assertTrue(gate.begin())
        gate.stopRequested()
        assertFalse(gate.retryFired())
    }
}
