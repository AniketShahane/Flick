package com.flick.receiver.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeProofGateTest {
    @Test fun invalidFirstProofConsumesTheChallengeBeforeAnyRetry() {
        val gate = ResumeProofGate()
        gate.issue()
        assertTrue(gate.consume()) // first, malformed/invalid proof attempt
        assertFalse(gate.consume()) // a corrected retry cannot reuse the nonce pair
    }
}
