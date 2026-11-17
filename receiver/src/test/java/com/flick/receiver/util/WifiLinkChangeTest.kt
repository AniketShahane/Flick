package com.flick.receiver.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiLinkChangeTest {

    @Test fun theFirstReadingIsAlwaysRecorded() {
        assertTrue(WifiLinkChange.worthRecording(null, null, 5_180, -50, anyRecorded = false))
        assertTrue(WifiLinkChange.worthRecording(null, null, null, null, anyRecorded = false))
    }

    @Test fun anUnchangedOrJitteringLinkIsNot() {
        assertFalse(WifiLinkChange.worthRecording(5_180, -50, 5_180, -50, anyRecorded = true))
        assertFalse(WifiLinkChange.worthRecording(5_180, -50, 5_180, -59, anyRecorded = true))
        assertFalse(WifiLinkChange.worthRecording(5_180, -50, 5_180, -41, anyRecorded = true))
    }

    @Test fun aTenDecibelSwingIs() {
        assertTrue(WifiLinkChange.worthRecording(5_180, -50, 5_180, -60, anyRecorded = true))
        assertTrue(WifiLinkChange.worthRecording(5_180, -60, 5_180, -50, anyRecorded = true))
    }

    @Test fun aChannelOrBandChangeIs() {
        assertTrue(WifiLinkChange.worthRecording(5_180, -50, 5_745, -50, anyRecorded = true))
        assertTrue(WifiLinkChange.worthRecording(5_180, -50, 2_437, -50, anyRecorded = true))
    }

    @Test fun losingOrRegainingTheLinkIs() {
        assertTrue(WifiLinkChange.worthRecording(5_180, -50, null, null, anyRecorded = true))
        assertTrue(WifiLinkChange.worthRecording(null, null, 5_180, -50, anyRecorded = true))
        assertFalse(WifiLinkChange.worthRecording(null, null, null, null, anyRecorded = true))
    }
}
