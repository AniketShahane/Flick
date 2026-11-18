package com.flick.sender.net

import com.flick.sender.model.ConnectionStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The browse runs only while something could read what it finds. */
class DiscoveryDemandTest {

    private fun wanted(
        foreground: Boolean = false,
        attemptInFlight: Boolean = false,
        blockWaitArmed: Boolean = false,
        castLive: Boolean = false,
        connection: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    ) = DiscoveryDemand.wanted(foreground, attemptInFlight, blockWaitArmed, castLive, connection)

    @Test fun aBackgroundedPhoneWithNoCastStopsBrowsing() {
        for (status in ConnectionStatus.entries) assertFalse(status.name, wanted(connection = status))
    }

    @Test fun aHealthyCastToABackgroundedPhoneStopsBrowsing() {
        assertFalse(wanted(castLive = true, connection = ConnectionStatus.CONNECTED))
    }

    @Test fun theForegroundAlwaysBrowses() {
        assertTrue(wanted(foreground = true))
        assertTrue(wanted(foreground = true, castLive = true, connection = ConnectionStatus.CONNECTED))
    }

    @Test fun anAttemptInFlightBrowsesInTheBackground() {
        assertTrue(wanted(attemptInFlight = true))
        assertTrue(wanted(attemptInFlight = true, castLive = true, connection = ConnectionStatus.CONNECTED))
    }

    @Test fun anArmedBlockWaitBrowses() {
        assertTrue(wanted(blockWaitArmed = true))
    }

    @Test fun aLiveCastOffItsControlLinkBrowses() {
        for (status in ConnectionStatus.entries) {
            if (status == ConnectionStatus.CONNECTED) continue
            assertTrue(status.name, wanted(castLive = true, connection = status))
        }
    }
}
