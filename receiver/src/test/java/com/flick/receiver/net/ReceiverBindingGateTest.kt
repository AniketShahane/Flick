package com.flick.receiver.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverBindingGateTest {
    @Test fun backgroundAndReconciliationCannotBindOrAdvertise() {
        val gate = ReceiverBindingGate(initiallyForeground = true)
        assertTrue(gate.mayBindOrAdvertise())
        gate.onBackground()
        assertFalse(gate.mayBindOrAdvertise())
        gate.onForeground()
        assertTrue(gate.mayBindOrAdvertise())
    }
}
