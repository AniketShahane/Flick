package com.flick.sender.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairSubmitGuardTest {

    @Test
    fun theReturnKeyMaySubmitOnlyWhatTheButtonWouldHaveSubmitted() {
        assertTrue(canSubmitDiscoveredPair("4821", connecting = false))
        listOf("", "4", "48", "482", "48210", "48 1", "48a1", "-821")
            .forEach { assertFalse(it, canSubmitDiscoveredPair(it, connecting = false)) }
    }

    @Test
    fun anAttemptAlreadyInFlightRefusesASecondSubmit() {
        // The sheet stays open through the attempt, so both the button and a held-down
        // return key are still there to press; neither may restart the handshake.
        assertFalse(canSubmitDiscoveredPair("4821", connecting = true))
        assertFalse(canSubmitManualPair("192.168.42.17", "47654", "4821", connecting = true))
    }

    @Test
    fun manualEntryNeedsThePrivateAddressThePortAndTheCodeTogether() {
        assertTrue(canSubmitManualPair("192.168.42.17", "47654", "4821", connecting = false))
        assertTrue(canSubmitManualPair("10.0.42.7", "8080", "0000", connecting = false))
        // 172.16/12 is the third private block the guard admits, and it is the one a
        // rewrite of the address test would most easily drop: both ends of it here.
        assertTrue(canSubmitManualPair("172.16.0.5", "47654", "4821", connecting = false))
        assertTrue(canSubmitManualPair("172.31.255.254", "47654", "4821", connecting = false))
        // No code, no connect: the endpoint is a prefill until the code authorises it.
        assertFalse(canSubmitManualPair("192.168.42.17", "47654", "", connecting = false))
        assertFalse(canSubmitManualPair("192.168.42.17", "47654", "482", connecting = false))
        // Addresses this app will not dial, whatever else is filled in.
        listOf("", "192.0.2.17", "192.168.042.17", "tv.lan", "192.168.42")
            .forEach { assertFalse(it, canSubmitManualPair(it, "47654", "4821", connecting = false)) }
        // Ports the control channel cannot be reached on.
        listOf("", "0", "047654", "65536", "476a4")
            .forEach {
                assertFalse(it, canSubmitManualPair("192.168.42.17", it, "4821", connecting = false))
            }
    }

    @Test
    fun aPastedAddressIsJudgedOnTheValueThatWouldBeSent() {
        // Space around a pasted address is trimmed before the submit, so refusing it
        // here would disable the button for an endpoint the phone goes on to dial.
        assertTrue(canSubmitManualPair("  192.168.42.17 ", "47654", "4821", connecting = false))
        assertFalse(canSubmitManualPair("192.168 42.17", "47654", "4821", connecting = false))
    }
}
