package com.flick.sender.ui.screens

import com.flick.sender.model.ConnectionStatus
import com.flick.sender.net.PairedTv
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the pairing sheets treat as "an attempt is running", which is also what decides
 * whether a second submit is refused.
 *
 * `CONFIRM_ON_TV` is the state added for the on-TV confirmation: a correct-shaped code
 * is already on the wire and a person at the television is being asked about it. It has
 * to count as in flight, because it can last tens of seconds and re-submitting would
 * tear down the very socket the receiver is answering — and dial again with a code the
 * receiver has already consumed.
 */
class PairAttemptInFlightTest {

    @Test fun everyStageOfAnAttemptCountsAsInFlight() {
        assertTrue(pairAttemptInFlight(ConnectionStatus.CONNECTING))
        assertTrue(pairAttemptInFlight(ConnectionStatus.PAIRING))
        assertTrue(pairAttemptInFlight(ConnectionStatus.CONFIRM_ON_TV))
    }

    @Test fun nothingElseDoes() {
        assertFalse(pairAttemptInFlight(ConnectionStatus.DISCONNECTED))
        assertFalse(pairAttemptInFlight(ConnectionStatus.CONNECTED))
        assertFalse(pairAttemptInFlight(ConnectionStatus.FAILED))
    }

    /**
     * Waiting on a person is not a live link. The device row's connected badge and the
     * "undiscovered connection" row both hang off this, and either would claim a TV was
     * being driven while its own screen was still asking whether to admit the phone.
     */
    @Test fun waitingForAConfirmationIsNotALiveLink() {
        assertFalse(linkLive(ConnectionStatus.CONFIRM_ON_TV, paired()))
    }

    private fun paired() = PairedTv(
        name = "Living Room",
        host = "192.168.1.40",
        port = 8009,
        tvId = "tv-a",
    )
}
