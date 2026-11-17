package com.flick.sender.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A pong held back by TCP must not release a link the echo watch has just repaired — but
 * an echo that has since gone quiet is no proof at all.
 */
class DirectLinkWatchPolicyTest {

    private val sent = 50_000L
    private val fresh = DirectLinkWatchPolicy.ECHO_PROOF_FRESH_MS

    @Test fun theFreshnessWindowIsTwoAndAHalfSeconds() {
        assertEquals(2_500L, fresh)
    }

    @Test fun noEchoAnsweredLeavesThePingTheProof() {
        assertTrue(DirectLinkWatchPolicy.releasesFor(sent, lastEchoAnsweredAtMs = 0L, nowMs = sent + 6_000L))
    }

    @Test fun anEchoAnsweredBeforeThePingProvesNothingAboutIt() {
        assertTrue(DirectLinkWatchPolicy.releasesFor(sent, lastEchoAnsweredAtMs = sent - 1_000L, nowMs = sent + 100L))
        assertTrue(DirectLinkWatchPolicy.releasesFor(sent, lastEchoAnsweredAtMs = sent, nowMs = sent + 100L))
    }

    @Test fun aFreshEchoAnsweredAfterThePingMeansThePongIsOnlyLate() {
        val answered = sent + 5_000L
        assertFalse(DirectLinkWatchPolicy.releasesFor(sent, answered, nowMs = answered + 1_000L))
    }

    @Test fun anEchoAnsweredAfterThePingButSinceStaleIsNoProof() {
        val answered = sent + 500L
        assertTrue(DirectLinkWatchPolicy.releasesFor(sent, answered, nowMs = answered + fresh + 1))
        assertTrue(DirectLinkWatchPolicy.releasesFor(sent, answered, nowMs = sent + 16_000L))
    }

    @Test fun theBoundaryIsStillFresh() {
        val answered = sent + 500L
        assertFalse(DirectLinkWatchPolicy.releasesFor(sent, answered, nowMs = answered + fresh))
    }
}
