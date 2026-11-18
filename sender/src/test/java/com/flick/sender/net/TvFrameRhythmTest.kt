package com.flick.sender.net

import org.junit.Assert.assertEquals
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

    @Test fun theTuning() {
        assertEquals(250L, TvFrameRhythm.HOT_SYNC_GRACE_MS)
        assertEquals(1_500L, TvFrameRhythm.COLD_SYNC_GRACE_MS)
        assertEquals(400L, TvFrameRhythm.ROUND_TRIP_ALLOWANCE_MS)
        assertEquals(2_500L, TvFrameRhythm.HOT_TRUST_MS)
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
}
