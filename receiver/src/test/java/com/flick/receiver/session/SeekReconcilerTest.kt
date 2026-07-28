package com.flick.receiver.session

import org.junit.Assert.assertEquals
import org.junit.Test

class SeekReconcilerTest {

    private fun forward(issuedAtMs: Long = 0L) = PendingSeek(
        targetMs = 110_000L,
        originMs = 100_000L,
        issuedAtElapsedMs = issuedAtMs,
    )

    private fun backward(issuedAtMs: Long = 0L) = PendingSeek(
        targetMs = 90_000L,
        originMs = 100_000L,
        issuedAtElapsedMs = issuedAtMs,
    )

    @Test fun aSampleTakenBeforeTheSeekKeepsItInFlight() {
        assertEquals(SeekPhase.InFlight, SeekReconciler.phaseOf(forward(), 100_050L, 50L))
        assertEquals(SeekPhase.InFlight, SeekReconciler.phaseOf(backward(), 100_050L, 50L))
    }

    @Test fun theTargetReportedSettlesBeforeItLands() {
        assertEquals(SeekPhase.Settling, SeekReconciler.phaseOf(forward(), 110_000L, 90L))
        assertEquals(
            SeekPhase.Landed,
            SeekReconciler.phaseOf(forward(), 110_000L, SeekReconciler.MIN_VISIBLE_MS),
        )
    }

    @Test fun landingPastTheTargetStillCountsAsTheTarget() {
        // Forward, with the film run on well beyond the tolerance.
        assertEquals(SeekPhase.Landed, SeekReconciler.phaseOf(forward(), 112_000L, 500L))
        // Backward, landing short of it.
        assertEquals(SeekPhase.Landed, SeekReconciler.phaseOf(backward(), 88_000L, 500L))
    }

    @Test fun aNudgeShorterThanOneSampleIsAlreadyReported() {
        val nudge = PendingSeek(targetMs = 100_200L, originMs = 100_000L, issuedAtElapsedMs = 0L)
        assertEquals(SeekPhase.Landed, SeekReconciler.phaseOf(nudge, 100_000L, 400L))
    }

    @Test fun aSecondSeekIsReconciledAgainstItsOwnTarget() {
        val second = PendingSeek(targetMs = 120_000L, originMs = 110_000L, issuedAtElapsedMs = 600L)
        // What the first seek asked for resolves nothing about the second.
        assertEquals(SeekPhase.InFlight, SeekReconciler.phaseOf(second, 110_000L, 650L))
        assertEquals(SeekPhase.Settling, SeekReconciler.phaseOf(second, 120_000L, 650L))
        assertEquals(
            SeekPhase.Landed,
            SeekReconciler.phaseOf(second, 120_000L, 600L + SeekReconciler.MIN_VISIBLE_MS),
        )
    }

    @Test fun aTargetTheTvNeverReportsCannotLatchSeekingOn() {
        val stuck = 100_000L
        assertEquals(
            SeekPhase.InFlight,
            SeekReconciler.phaseOf(forward(), stuck, SeekReconciler.DEADLINE_MS - 1L),
        )
        assertEquals(
            SeekPhase.Landed,
            SeekReconciler.phaseOf(forward(), stuck, SeekReconciler.DEADLINE_MS),
        )
        assertEquals(SeekPhase.Landed, SeekReconciler.phaseOf(forward(), stuck, 60_000L))
    }

    @Test fun ordinaryDriftAfterALandingNeverReopensTheSeek() {
        var positionMs = 110_000L
        repeat(50) { tick ->
            positionMs += 100L
            assertEquals(
                SeekPhase.Landed,
                SeekReconciler.phaseOf(forward(), positionMs, 400L + tick * 100L),
            )
        }
    }

    @Test fun theTargetIsWhereThePlayerWillActuallyLand() {
        assertEquals(115_000L, clampedSeekTarget(115_000L, 120_000L))
        // The end guard, so a seek at the tail is not waited on forever.
        assertEquals(119_000L, clampedSeekTarget(119_500L, 120_000L))
        assertEquals(0L, clampedSeekTarget(-5_000L, 120_000L))
        assertEquals(0L, clampedSeekTarget(900L, 500L))
        // Unknown duration is the one case the player leaves unclamped.
        assertEquals(500_000L, clampedSeekTarget(500_000L, 0L))
    }

    @Test fun aBackSkipTargetsTheDeltaFromTheSampledPosition() {
        assertEquals(90_000L, clampedSeekTarget(100_000L - 10_000L, 120_000L))
        assertEquals(0L, clampedSeekTarget(4_000L - 10_000L, 120_000L))
    }
}
