package com.flick.sender.net

import com.flick.sender.model.PlaybackPhase
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where a run of ±10s taps lands, and when the head stops waiting for the TV.
 *
 * Both were user-visible defects on the hero screen: one seek per tap made a 30 s skip
 * cost three decoder flushes, and a 3 s seek deadline fired *inside* the ordinary cost of
 * a 4K seek and pulled the head backwards to where the TV still was — so three taps read
 * as three stalls followed by the taps being thrown away.
 */
class SeekPolicyTest {

    // --- where a tap run lands ----------------------------------------------

    @Test fun aRunOfThreeBackTapsIsOneTargetThirtySecondsEarlier() {
        assertEquals(570_000L, tapRun(600_000L, -10_000L, taps = 3, durationMs = DURATION_MS))
    }

    @Test fun aRunOfTapsBothWaysNetsOut() {
        var head = 600_000L
        head = SeekPolicy.skipTarget(head, -10_000L, DURATION_MS)
        head = SeekPolicy.skipTarget(head, -10_000L, DURATION_MS)
        head = SeekPolicy.skipTarget(head, 10_000L, DURATION_MS)
        assertEquals(590_000L, head)
    }

    @Test fun aRunPastTheStartRestsOnTheStartAndNeverGoesNegative() {
        assertEquals(0L, tapRun(12_000L, -10_000L, taps = 3, durationMs = DURATION_MS))
        assertEquals(0L, tapRun(12_000L, -10_000L, taps = 40, durationMs = DURATION_MS))
    }

    @Test fun aRunPastTheEndRestsOnTheDuration() {
        assertEquals(DURATION_MS, tapRun(DURATION_MS - 12_000L, 10_000L, taps = 3, durationMs = DURATION_MS))
    }

    /**
     * durationMs == 0 is MediaStore's silence, not a zero-length film: clamping the high
     * end to it would turn every +10s into `seek 0` and restart the film from the start.
     * The receiver clamps the high end against the real duration.
     */
    @Test fun withNoKnownDurationForwardStillMovesForwardInsteadOfRestartingTheFilm() {
        assertEquals(610_000L, SeekPolicy.skipTarget(600_000L, 10_000L, durationMs = 0L))
        assertEquals(630_000L, tapRun(600_000L, 10_000L, taps = 3, durationMs = 0L))
    }

    @Test fun withNoKnownDurationTheStartIsStillClamped() {
        assertEquals(0L, SeekPolicy.skipTarget(5_000L, -10_000L, durationMs = 0L))
    }

    // --- where a scrubber landing lands -------------------------------------

    @Test fun aScrubberLandingIsTakenAsGiven() {
        assertEquals(1_234_567L, SeekPolicy.seekTarget(1_234_567L, DURATION_MS))
    }

    @Test fun aScrubberLandingIsClampedToTheFilm() {
        assertEquals(DURATION_MS, SeekPolicy.seekTarget(DURATION_MS + 60_000L, DURATION_MS))
        assertEquals(0L, SeekPolicy.seekTarget(-5_000L, DURATION_MS))
    }

    /** Same rule as a tap run: an unknown duration is silence, and the receiver clamps it. */
    @Test fun withNoKnownDurationAScrubberLandingKeepsItsPosition() {
        assertEquals(1_234_567L, SeekPolicy.seekTarget(1_234_567L, durationMs = 0L))
        assertEquals(0L, SeekPolicy.seekTarget(-1L, durationMs = 0L))
    }

    // --- when the head stops waiting ----------------------------------------

    @Test fun theGhostReachingTheHeadIsAnArrival() {
        assertEquals(
            SeekPolicy.Pending.ARRIVED,
            SeekPolicy.pending(600_000L, 599_800L, PlaybackPhase.PLAYING, outstandingMs = 500L),
        )
    }

    @Test fun theArrivalWindowIsExact() {
        assertEquals(
            SeekPolicy.Pending.ARRIVED,
            SeekPolicy.pending(600_000L, 600_000L - SeekPolicy.RECONCILE_MS, PlaybackPhase.PLAYING, 500L),
        )
        assertEquals(
            SeekPolicy.Pending.WAITING,
            SeekPolicy.pending(600_000L, 600_000L - SeekPolicy.RECONCILE_MS - 1L, PlaybackPhase.PLAYING, 500L),
        )
    }

    /** The regression: 3 s used to be a timeout, and 3 s is an ordinary 4K seek. */
    @Test fun anOrdinaryFourKSeekIsStillWaitingWhereTheOldThreeSecondTimeoutGaveUp() {
        for (outstanding in longArrayOf(3_100L, 5_000L, 8_000L, SeekPolicy.SEEK_DEADLINE_MS)) {
            assertEquals(
                "outstanding=$outstanding",
                SeekPolicy.Pending.WAITING,
                SeekPolicy.pending(600_000L, 300_000L, PlaybackPhase.BUFFERING, outstanding),
            )
        }
    }

    /** A BUFFERING frame carries the position the player is seeking FROM. */
    @Test fun aTvStillBufferingTowardThePositionIsNeverGivenUpOnAtTheDeadline() {
        assertEquals(
            SeekPolicy.Pending.WAITING,
            SeekPolicy.pending(600_000L, 300_000L, PlaybackPhase.BUFFERING, SeekPolicy.SEEK_DEADLINE_MS + 1L),
        )
    }

    @Test fun aTvSettledSomewhereElsePastTheDeadlineLosesTheHead() {
        for (phase in listOf(PlaybackPhase.PLAYING, PlaybackPhase.PAUSED, PlaybackPhase.ENDED)) {
            assertEquals(
                phase.name,
                SeekPolicy.Pending.ABANDONED,
                SeekPolicy.pending(600_000L, 300_000L, phase, SeekPolicy.SEEK_DEADLINE_MS + 1L),
            )
        }
    }

    /** The escape hatch: a receiver wedged in BUFFERING cannot hold the head forever. */
    @Test fun aTvWedgedInBufferingStillLosesTheHeadAtTheCeiling() {
        assertEquals(
            SeekPolicy.Pending.ABANDONED,
            SeekPolicy.pending(600_000L, 300_000L, PlaybackPhase.BUFFERING, SeekPolicy.SEEK_ABANDON_MS + 1L),
        )
    }

    @Test fun noDeadlineIsSpentOnASeekThatArrived() {
        assertEquals(
            SeekPolicy.Pending.ARRIVED,
            SeekPolicy.pending(600_000L, 600_000L, PlaybackPhase.BUFFERING, SeekPolicy.SEEK_ABANDON_MS * 2),
        )
    }

    private fun tapRun(startMs: Long, deltaMs: Long, taps: Int, durationMs: Long): Long {
        var head = startMs
        repeat(taps) { head = SeekPolicy.skipTarget(head, deltaMs, durationMs) }
        return head
    }

    private companion object {
        const val DURATION_MS = 7_200_000L
    }
}
