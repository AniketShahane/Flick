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

    /** The end guard: a landing at or past the end would play one second and end the film. */
    @Test fun aScrubberLandingIsClampedToTheFilmShortOfTheEnd() {
        assertEquals(DURATION_MS - 10_000L, SeekPolicy.seekTarget(DURATION_MS + 60_000L, DURATION_MS))
        assertEquals(DURATION_MS - 10_000L, SeekPolicy.seekTarget(DURATION_MS, DURATION_MS))
        assertEquals(DURATION_MS - 10_000L, SeekPolicy.seekTarget(DURATION_MS - 10_000L, DURATION_MS))
        assertEquals(0L, SeekPolicy.seekTarget(-5_000L, DURATION_MS))
    }

    @Test fun aShortClipLandingIsGuardedByFivePercent() {
        assertEquals(57_000L, SeekPolicy.seekTarget(60_000L, 60_000L))
    }

    /** Same rule as a tap run: an unknown duration is silence, and the receiver clamps it. */
    @Test fun withNoKnownDurationAScrubberLandingKeepsItsPosition() {
        assertEquals(1_234_567L, SeekPolicy.seekTarget(1_234_567L, durationMs = 0L))
        assertEquals(0L, SeekPolicy.seekTarget(-1L, durationMs = 0L))
    }

    // --- the end guard on a drag --------------------------------------------

    @Test fun theGuardIsTenSecondsOnAFeature() {
        assertEquals(10_000L, SeekPolicy.scrubEndGuardMs(DURATION_MS))
        assertEquals(DURATION_MS - 10_000L, SeekPolicy.scrubMaxMs(DURATION_MS))
    }

    @Test fun theGuardIsFivePercentOfAShortClip() {
        assertEquals(3_000L, SeekPolicy.scrubEndGuardMs(60_000L))
        assertEquals(10_000L, SeekPolicy.scrubEndGuardMs(200_000L))
        assertEquals(9_999L, SeekPolicy.scrubEndGuardMs(199_980L))
    }

    @Test fun theGuardIsTenSecondsOnATwoHourFilm() {
        assertEquals(10_000L, SeekPolicy.scrubEndGuardMs(2L * 60 * 60 * 1000))
    }

    /** The TV clamps every seek a second short of the end; the phone never aims past that. */
    @Test fun theGuardNeverDropsBelowTheTvsOwnClamp() {
        assertEquals(1_000L, SeekPolicy.scrubEndGuardMs(20_000L))
        assertEquals(1_000L, SeekPolicy.scrubEndGuardMs(10_000L))
        assertEquals(9_000L, SeekPolicy.scrubTarget(1f, 10_000L))
        assertEquals(9_000L, SeekPolicy.seekTarget(9_500L, 10_000L))
        assertEquals(19_000L, SeekPolicy.scrubMaxMs(20_000L))
    }

    @Test fun aClipNoLongerThanTheClampIsAllGuardAndNeverNegative() {
        assertEquals(1_000L, SeekPolicy.scrubEndGuardMs(1_000L))
        assertEquals(400L, SeekPolicy.scrubEndGuardMs(400L))
        assertEquals(0L, SeekPolicy.scrubMaxMs(1_000L))
        assertEquals(0L, SeekPolicy.scrubMaxMs(400L))
        assertEquals(0L, SeekPolicy.scrubTarget(1f, 400L))
    }

    @Test fun withNoKnownDurationThereIsNoGuard() {
        assertEquals(0L, SeekPolicy.scrubEndGuardMs(0L))
        assertEquals(0L, SeekPolicy.scrubEndGuardMs(-1L))
    }

    @Test fun aDragToTheRightEdgeLandsShortOfTheEnd() {
        assertEquals(DURATION_MS - 10_000L, SeekPolicy.scrubTarget(1f, DURATION_MS))
        assertEquals(DURATION_MS - 10_000L, SeekPolicy.scrubTarget(1.5f, DURATION_MS))
        assertEquals(57_000L, SeekPolicy.scrubTarget(1f, 60_000L))
    }

    @Test fun aDragElsewhereLandsWhereItPoints() {
        assertEquals(0L, SeekPolicy.scrubTarget(0f, DURATION_MS))
        assertEquals(0L, SeekPolicy.scrubTarget(-0.2f, DURATION_MS))
        assertEquals(3_600_000L, SeekPolicy.scrubTarget(0.5f, DURATION_MS))
    }

    /** The ±10s buttons are deliberately unguarded. */
    @Test fun aTapRunStillReachesTheVeryEnd() {
        assertEquals(DURATION_MS, SeekPolicy.skipTarget(DURATION_MS - 3_000L, 10_000L, DURATION_MS))
    }

    // --- the notches a drag is felt in --------------------------------------

    @Test fun thirtySixNotchesSpanTheFilm() {
        assertEquals(0, SeekPolicy.detentOf(0L, DURATION_MS))
        assertEquals(0, SeekPolicy.detentOf(199_999L, DURATION_MS))
        assertEquals(1, SeekPolicy.detentOf(200_000L, DURATION_MS))
        assertEquals(18, SeekPolicy.detentOf(3_600_000L, DURATION_MS))
        assertEquals(35, SeekPolicy.detentOf(DURATION_MS - 1L, DURATION_MS))
    }

    @Test fun theNotchIsClampedToTheBar() {
        assertEquals(35, SeekPolicy.detentOf(DURATION_MS, DURATION_MS))
        assertEquals(35, SeekPolicy.detentOf(DURATION_MS * 2, DURATION_MS))
        assertEquals(0, SeekPolicy.detentOf(-5_000L, DURATION_MS))
        assertEquals(0, SeekPolicy.detentOf(5_000L, durationMs = 0L))
    }

    // --- what letting go owes the TV ----------------------------------------

    @Test fun aTouchThatNeverMovedTheHeadOwesNothing() {
        assertEquals(SeekPolicy.ScrubRelease.NONE, release(600_000L, null, 600_000L))
    }

    @Test fun aReleaseOnAFreshlySentLandingOwesOnlyTheMarkerAndWaitsOnTheTv() {
        assertEquals(SeekPolicy.ScrubRelease.MARKER_AWAITING, release(600_000L, 900_000L, 900_000L, agoMs = 120L))
    }

    /** Held still past the window: the TV landed it long ago and plays on once freed. */
    @Test fun aReleaseAfterHoldingStillOwesOnlyTheMarkerAndFollowsTheTv() {
        assertEquals(SeekPolicy.ScrubRelease.MARKER_FOLLOWING, release(600_000L, 900_000L, 900_000L, agoMs = 2_000L))
    }

    @Test fun theFreshnessBoundaryIsExact() {
        val fresh = SeekPolicy.RELEASE_FRESH_MS
        assertEquals(SeekPolicy.ScrubRelease.MARKER_AWAITING, release(0L, 5_000L, 5_000L, agoMs = fresh - 1))
        assertEquals(SeekPolicy.ScrubRelease.MARKER_FOLLOWING, release(0L, 5_000L, 5_000L, agoMs = fresh))
    }

    @Test fun aReleaseAheadOfTheLastSentLandingOwesTheSeekAndTheMarker() {
        assertEquals(SeekPolicy.ScrubRelease.SEEK_AND_MARKER, release(600_000L, 900_000L, 950_000L))
    }

    /** A release that sends a seek waits on it however long the last one has been out. */
    @Test fun aReleaseThatSeeksAlwaysWaitsOnTheTv() {
        val r = release(600_000L, 900_000L, 950_000L, agoMs = 60_000L)
        assertEquals(SeekPolicy.ScrubRelease.SEEK_AND_MARKER, r)
        assertEquals(true, r.seek && r.marker && r.awaitTv)
    }

    @Test fun aMoveTheThrottleHeldBackStillLandsOnRelease() {
        assertEquals(SeekPolicy.ScrubRelease.SEEK_AND_MARKER, release(600_000L, null, 650_000L))
    }

    /** The TV was told to go elsewhere, so returning to the origin is a real seek. */
    @Test fun aDragBackToWhereItStartedStillSeeksBack() {
        assertEquals(SeekPolicy.ScrubRelease.SEEK_AND_MARKER, release(600_000L, 900_000L, 600_000L))
    }

    @Test fun aDragThatSentTheOriginItselfOwesOnlyTheMarker() {
        assertEquals(SeekPolicy.ScrubRelease.MARKER_AWAITING, release(600_000L, 600_000L, 600_000L))
    }

    /** +10 tapped three times, then the thumb touched and lifted inside the quiet window. */
    @Test fun aTouchThatCancelledATapRunStillSendsItsSeek() {
        assertEquals(SeekPolicy.ScrubRelease.SEEK_AND_MARKER, release(630_000L, null, 630_000L, owed = true))
    }

    @Test fun aCancelledTapRunDraggedElsewhereSeeksWhereTheDragEnded() {
        assertEquals(SeekPolicy.ScrubRelease.SEEK_AND_MARKER, release(630_000L, null, 700_000L, owed = true))
        assertEquals(SeekPolicy.ScrubRelease.SEEK_AND_MARKER, release(630_000L, 680_000L, 700_000L, owed = true))
    }

    /** The drag's own seek already superseded the run, so it is not sent again. */
    @Test fun aCancelledTapRunADragAlreadySentOverOwesOnlyTheMarker() {
        assertEquals(SeekPolicy.ScrubRelease.MARKER_AWAITING, release(630_000L, 700_000L, 700_000L, owed = true))
        assertEquals(
            SeekPolicy.ScrubRelease.MARKER_FOLLOWING,
            release(630_000L, 700_000L, 700_000L, agoMs = 3_000L, owed = true),
        )
    }

    @Test fun noReleaseWaitsWithoutTheMarker() {
        val n = SeekPolicy.ScrubRelease.NONE
        assertEquals(false, n.seek || n.marker || n.awaitTv)
        val f = SeekPolicy.ScrubRelease.MARKER_FOLLOWING
        assertEquals(true, f.marker && !f.seek && !f.awaitTv)
    }

    // --- the viewer's play intent -------------------------------------------

    @Test fun settledPhasesDecideTheIntent() {
        assertEquals(true, SeekPolicy.intendsPlayingAfter(PlaybackPhase.PLAYING, false))
        assertEquals(false, SeekPolicy.intendsPlayingAfter(PlaybackPhase.PAUSED, true))
    }

    /** A rebuffer reports playing=false; reading intent off it would end a drag with `pause`. */
    @Test fun unsettledPhasesLeaveTheIntentAlone() {
        for (phase in listOf(PlaybackPhase.BUFFERING, PlaybackPhase.ENDED, PlaybackPhase.IDLE, PlaybackPhase.ERROR)) {
            assertEquals(phase.name, true, SeekPolicy.intendsPlayingAfter(phase, true))
            assertEquals(phase.name, false, SeekPolicy.intendsPlayingAfter(phase, false))
        }
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

    private fun release(
        originMs: Long,
        lastSentMs: Long?,
        targetMs: Long,
        agoMs: Long = 0L,
        owed: Boolean = false,
    ) = SeekPolicy.scrubRelease(originMs, lastSentMs, agoMs, targetMs, owed)

    private fun tapRun(startMs: Long, deltaMs: Long, taps: Int, durationMs: Long): Long {
        var head = startMs
        repeat(taps) { head = SeekPolicy.skipTarget(head, deltaMs, durationMs) }
        return head
    }

    private companion object {
        const val DURATION_MS = 7_200_000L
    }
}
