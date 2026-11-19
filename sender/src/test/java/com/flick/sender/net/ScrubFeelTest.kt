package com.flick.sender.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a drag feels like. One tick per 10 s of film was ~720 across a two-hour bar — a
 * continuous buzz — so ticks follow the bar's 36 notches, are rate-limited, and the ends
 * get one firmer knock each.
 */
class ScrubFeelTest {

    private val notchMs = DURATION_MS / SeekPolicy.SCRUB_DETENTS
    private val maxMs = SeekPolicy.scrubMaxMs(DURATION_MS)

    @Test fun crossingANotchTicks() {
        val feel = started(at = notchMs / 2, nowMs = 0L)
        assertNull(feel.move(notchMs / 2 + 1_000L, DURATION_MS, 100L))
        assertEquals(HapticCue.DETENT, feel.move(notchMs + 1_000L, DURATION_MS, 200L))
    }

    @Test fun aNotchCrossedTooSoonAfterTheLastTickIsSkipped() {
        val feel = started(at = notchMs / 2, nowMs = 0L)
        assertEquals(HapticCue.DETENT, feel.move(notchMs + 1L, DURATION_MS, 100L))
        assertNull(feel.move(2 * notchMs + 1L, DURATION_MS, 100L + ScrubFeel.MIN_TICK_GAP_MS - 1L))
        assertEquals(
            HapticCue.DETENT,
            feel.move(3 * notchMs + 1L, DURATION_MS, 100L + ScrubFeel.MIN_TICK_GAP_MS),
        )
    }

    /** The skipped notch is recorded, so staying in it later is silent rather than a late tick. */
    @Test fun aSkippedTickIsNotReplayed() {
        val feel = started(at = notchMs / 2, nowMs = 0L)
        assertEquals(HapticCue.DETENT, feel.move(notchMs + 1L, DURATION_MS, 100L))
        assertNull(feel.move(2 * notchMs + 1L, DURATION_MS, 110L))
        assertNull(feel.move(2 * notchMs + 500L, DURATION_MS, 1_000L))
    }

    @Test fun theGripCountsAsTheLastCue() {
        val feel = started(at = notchMs / 2, nowMs = 1_000L)
        assertNull(feel.move(5 * notchMs, DURATION_MS, 1_010L))
    }

    @Test fun arrivingAtTheGuardedEndKnocksOnceAndReplacesTheTick() {
        val feel = started(at = 3_600_000L, nowMs = 0L)
        assertEquals(HapticCue.EDGE, feel.move(maxMs, DURATION_MS, 100L))
        assertNull(feel.move(maxMs, DURATION_MS, 300L))
    }

    @Test fun arrivingAtTheStartKnocksOnce() {
        val feel = started(at = 3_600_000L, nowMs = 0L)
        assertEquals(HapticCue.EDGE, feel.move(0L, DURATION_MS, 100L))
        assertNull(feel.move(0L, DURATION_MS, 300L))
    }

    /** The knock is not rate-limited: it marks a place, and the hand has to feel it. */
    @Test fun theKnockIsNotRateLimited() {
        val feel = started(at = 3_600_000L, nowMs = 0L)
        assertEquals(HapticCue.EDGE, feel.move(0L, DURATION_MS, 1L))
    }

    @Test fun leavingAnEndReArmsItsKnock() {
        val feel = started(at = 3_600_000L, nowMs = 0L)
        assertEquals(HapticCue.EDGE, feel.move(maxMs, DURATION_MS, 100L))
        feel.move(maxMs - notchMs, DURATION_MS, 300L)
        assertEquals(HapticCue.EDGE, feel.move(maxMs, DURATION_MS, 500L))
    }

    @Test fun startingAtAnEndDoesNotKnock() {
        val feel = started(at = 0L, nowMs = 0L)
        assertNull(feel.move(0L, DURATION_MS, 100L))
        feel.move(notchMs * 3, DURATION_MS, 200L)
        assertEquals(HapticCue.EDGE, feel.move(0L, DURATION_MS, 300L))
    }

    @Test fun aShortClipKnocksAtItsOwnGuardedEnd() {
        val feel = ScrubFeel().apply { start(30_000L, 60_000L, 0L) }
        assertNull(feel.move(56_000L, 60_000L, 10L))
        assertEquals(HapticCue.EDGE, feel.move(57_000L, 60_000L, 100L))
    }

    private fun started(at: Long, nowMs: Long) = ScrubFeel().apply { start(at, DURATION_MS, nowMs) }

    private companion object {
        const val DURATION_MS = 7_200_000L
    }
}
