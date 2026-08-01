package com.flick.sender.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportCheckoutLaunchGateTest {
    @Test fun claimsOnlyTheFirstCheckoutTap() {
        val gate = SupportCheckoutLaunchGate()

        assertTrue(gate.claim())
        assertFalse(gate.claim())
        assertFalse(gate.claim())
    }
}
