package com.flick.sender.net

import com.flick.sender.ui.components.PlayheadExtrapolator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The TV sends `state` every 100 ms for 3 s after a command and every second otherwise.
 * The scrub shimmer reads staleness against whichever rhythm this phone can prove.
 */
class TvFrameRhythmTest {

    private val rhythm = TvFrameRhythm()
    private val t0 = 1_000_000L

    /**
     * The phone trusts the fast rhythm only inside the TV's hot window: the tight grace can
     * convict a gap at the last moment of trust, so fast frames must still be due through
     * that grace and one more tick of the TV's loop.
     */
    @Test fun hotTrustEndsInsideTheReceiversHotWindow() {
        assertTrue(
            TvFrameRhythm.HOT_TRUST_MS + TvFrameRhythm.HOT_SYNC_GRACE_MS + RECEIVER_TICK_MS < RECEIVER_HOT_WINDOW_MS,
        )
    }

    /** A slow frame is due a cold interval after the last, late by up to a tick, then the trip. */
    @Test fun theColdGraceCoversTheReceiversSlowRhythm() {
        assertTrue(TvFrameRhythm.COLD_SYNC_GRACE_MS > RECEIVER_COLD_INTERVAL_MS + RECEIVER_TICK_MS + JITTER_ALLOWANCE_MS)
    }

    /** A replacing cast skips its ping only on a frame; the slow rhythm alone must keep one inside the window. */
    @Test fun aSlowFrameKeepsTheSocketFresh() {
        assertTrue(ControlLiveness.FRESH_MS > RECEIVER_COLD_INTERVAL_MS + RECEIVER_TICK_MS + JITTER_ALLOWANCE_MS)
    }

    /** The local playhead must not reach its lead cap between two healthy slow frames. */
    @Test fun thePlayheadOutrunsNoHealthySlowGap() {
        assertTrue(PlayheadExtrapolator.MAX_LEAD_MS > RECEIVER_COLD_INTERVAL_MS + RECEIVER_TICK_MS + JITTER_ALLOWANCE_MS)
    }

    @Test fun aColdFeedAtItsSlowRhythmIsNotSyncing() {
        // The first touch of a scrub on a TV nobody has commanded: last frame up to 1 s old.
        assertFalse(rhythm.syncing(t0, lastConfirmedAtMs = t0 - 1_000L))
        assertFalse(rhythm.syncing(t0, lastConfirmedAtMs = t0 - 1_500L))
        assertTrue(rhythm.syncing(t0, lastConfirmedAtMs = t0 - 1_501L))
    }

    @Test fun neverHeardIsSyncing() {
        assertTrue(rhythm.syncing(t0, lastConfirmedAtMs = 0L))
    }

    @Test fun theFirstFourHundredMillisecondsOfADragKeepTheSlowGrace() {
        rhythm.onCommandSent(t0)
        rhythm.onCommandSent(t0 + 50L)
        assertFalse(rhythm.provablyHot(t0 + 399L))
        assertFalse(rhythm.syncing(t0 + 399L, lastConfirmedAtMs = t0 - 900L))
        assertTrue(rhythm.provablyHot(t0 + 400L))
    }

    @Test fun theSwitchToTheTightGraceDoesNotConvictTheSlowGapBeforeIt() {
        rhythm.onCommandSent(t0)
        var now = t0
        while (now < t0 + 400L) { now += 50L; rhythm.onCommandSent(now) }
        // The last frame came at the slow rhythm before the drag began.
        assertFalse(rhythm.syncing(t0 + 400L, lastConfirmedAtMs = t0 - 900L))
        assertFalse(rhythm.syncing(t0 + 650L, lastConfirmedAtMs = t0 - 900L))
        assertTrue(rhythm.syncing(t0 + 651L, lastConfirmedAtMs = t0 - 900L))
    }

    @Test fun aStallDuringAnActiveDragLightsAtTheTightGrace() {
        var now = t0
        rhythm.onCommandSent(now)
        while (now < t0 + 2_000L) { now += 50L; rhythm.onCommandSent(now) }
        val lastFrame = now - 100L
        assertFalse(rhythm.syncing(lastFrame + 250L, lastFrame))
        assertTrue(rhythm.syncing(lastFrame + 251L, lastFrame))
    }

    @Test fun healthyFastFramesStayInvisible() {
        var now = t0
        rhythm.onCommandSent(now)
        while (now < t0 + 2_000L) {
            now += 50L
            rhythm.onCommandSent(now)
            assertFalse(rhythm.syncing(now, lastConfirmedAtMs = now - (now - t0) % 100L))
        }
    }

    /** Thumb held still past the TV's window: the next move starts a new run at the slow grace. */
    @Test fun movingAgainAfterHoldingStillIsBackToTheSlowGrace() {
        rhythm.onCommandSent(t0)
        rhythm.onCommandSent(t0 + 500L)
        val resumedAt = t0 + 500L + 3_500L
        assertFalse(rhythm.provablyHot(resumedAt))
        assertFalse(rhythm.syncing(resumedAt, lastConfirmedAtMs = resumedAt - 1_000L))
        rhythm.onCommandSent(resumedAt)
        assertFalse(rhythm.provablyHot(resumedAt + 399L))
        assertFalse(rhythm.syncing(resumedAt + 50L, lastConfirmedAtMs = resumedAt - 1_000L))
    }

    @Test fun trustInTheHotWindowEndsBeforeTheTvsDoes() {
        rhythm.onCommandSent(t0)
        assertTrue(rhythm.provablyHot(t0 + 2_499L))
        assertFalse(rhythm.provablyHot(t0 + 2_500L))
    }

    private companion object {
        /**
         * The receiver's `StateFeedCadence.HOT_WINDOW_MS` and `COLD_INTERVAL_MS`
         * (receiver/src/main/java/com/flick/receiver/net/StateFeedCadence.kt) and the loop
         * they gate, `ControlServer.STATE_TICK_MS` (ControlServer.kt in the same package).
         * Mirrored rather than imported — the two apps share no code.
         */
        const val RECEIVER_HOT_WINDOW_MS = 3_000L
        const val RECEIVER_COLD_INTERVAL_MS = 1_000L
        const val RECEIVER_TICK_MS = 100L

        /** Wi-Fi delivery jitter a healthy home link adds to a frame's trip. */
        const val JITTER_ALLOWANCE_MS = 250L
    }
}
