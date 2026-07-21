package com.flick.sender.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingAttemptGateTest {
    @Test fun qrDismissalInvalidatesTheInFlightCompletion() {
        val gate = PairingAttemptGate()
        val scanned = gate.begin()
        gate.invalidate()
        assertFalse(gate.isCurrent(scanned))
        assertTrue(gate.isCurrent(gate.begin()))
    }
}
