package com.flick.sender.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * When a control link that has gone quiet is allowed to cost the viewer their film.
 *
 * The failure this answers happened on real hardware: a cast playing perfectly, a
 * subtitle attached mid-film, thirty-three seconds of control silence, and then a ping
 * timeout that ended a cast the phone was still serving at ~58 Mbps. The TV had not gone
 * anywhere and the film was on screen the whole time.
 */
class ControlRecoveryPolicyTest {

    /**
     * The cast that was lost. Bytes a second ago means the TV is pulling them, and that
     * is a better witness about right now than a watchdog whose shortest possible verdict
     * is thirty seconds old.
     */
    @Test fun aTvStillPullingBytesHasNotGoneAway() {
        assertTrue(ControlRecoveryPolicy.mediaPathServing(lastByteAtMs = 120_000L, nowMs = 121_000L))
    }

    /**
     * The case the old behaviour was right about, and which must not change: nothing has
     * left this phone for the TV either, so the two witnesses agree.
     */
    @Test fun aMediaSocketThatWentQuietTooIsAnAbsentTv() {
        assertFalse(ControlRecoveryPolicy.mediaPathServing(lastByteAtMs = 120_000L, nowMs = 180_000L))
    }

    /**
     * A cast that never moved a byte has proven nothing about the link. Zero is the
     * absence of a reading, not a reading of zero, and reading it as "long ago" is what
     * keeps a startup that never got going out of the recovery path.
     */
    @Test fun aCastThatNeverServedAByteIsNeverServing() {
        assertFalse(ControlRecoveryPolicy.mediaPathServing(lastByteAtMs = 0L, nowMs = 0L))
        assertFalse(ControlRecoveryPolicy.mediaPathServing(lastByteAtMs = 0L, nowMs = 5_000L))
    }

    /**
     * The window has to sit under the watchdog it second-guesses. The control link cannot
     * be reported lost sooner than 30 s of missing pong, so a TV that vanished has already
     * fallen out of this window by the time anything consults it — which is the whole
     * reason the media path may overrule the WebSocket at all.
     */
    @Test fun theServingWindowClosesLongBeforeAPingTimeoutCanFire() {
        assertTrue(ControlRecoveryPolicy.SERVING_WINDOW_MS < ControlClient.PING_INTERVAL_MS * 2)
    }

    /** A first loss, and one after a long healthy stretch, are both attempt one. */
    @Test fun aLossWithNoRecentRecoveryStartsTheRun() {
        assertEquals(1, ControlRecoveryPolicy.attempt(previousAttempts = 0, lastAttemptAtMs = 0L, nowMs = 5_000L))
        assertEquals(
            1,
            ControlRecoveryPolicy.attempt(previousAttempts = 2, lastAttemptAtMs = 1_000L, nowMs = 3_600_000L),
        )
    }

    /** Two inside the window are one broken link, not two incidents. */
    @Test fun lossesInsideTheWindowAccumulate() {
        assertEquals(2, ControlRecoveryPolicy.attempt(previousAttempts = 1, lastAttemptAtMs = 10_000L, nowMs = 30_000L))
        assertEquals(3, ControlRecoveryPolicy.attempt(previousAttempts = 2, lastAttemptAtMs = 30_000L, nowMs = 40_000L))
    }

    @Test fun aServingTvOnAnActiveCastIsRecovered() {
        assertTrue(
            ControlRecoveryPolicy.recovers(
                reachedActive = true,
                mediaServing = true,
                canDial = true,
                attempt = 1,
            ),
        )
    }

    /**
     * The bound. A third attempt inside the same minute would be this app reconnecting in
     * a loop the viewer can neither see nor stop, so the failure is surfaced instead —
     * retryable, which is what leaves them a way back.
     */
    @Test fun theRunIsBounded() {
        assertTrue(recoversAt(ControlRecoveryPolicy.MAX_ATTEMPTS))
        assertFalse(recoversAt(ControlRecoveryPolicy.MAX_ATTEMPTS + 1))
    }

    /**
     * Only a cast the TV was already playing. A startup that lost its control link has no
     * position worth resuming and no proof the receiver can play the file at all, so
     * re-dialing one would be retrying a cast the viewer has never seen work.
     */
    @Test fun aStartupThatNeverReachedTheFilmIsNotRecovered() {
        assertFalse(
            ControlRecoveryPolicy.recovers(
                reachedActive = false,
                mediaServing = true,
                canDial = true,
                attempt = 1,
            ),
        )
    }

    /** The idle media socket keeps the behaviour this phone has always had. */
    @Test fun anAbsentTvIsStillAnAbsentTv() {
        assertFalse(
            ControlRecoveryPolicy.recovers(
                reachedActive = true,
                mediaServing = false,
                canDial = true,
                attempt = 1,
            ),
        )
    }

    /** No stored pairing or no cast request means there is nothing to re-establish with. */
    @Test fun nothingToDialIsNothingToRecover() {
        assertFalse(
            ControlRecoveryPolicy.recovers(
                reachedActive = true,
                mediaServing = true,
                canDial = false,
                attempt = 1,
            ),
        )
    }

    private fun recoversAt(attempt: Int) = ControlRecoveryPolicy.recovers(
        reachedActive = true,
        mediaServing = true,
        canDial = true,
        attempt = attempt,
    )
}
