package com.flick.sender.net

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The quiet period that ends a run of ±10s taps, on virtual time.
 *
 * The seeks recorded here are modelled the way [PlaybackSession] composes the two pieces
 * — each tap moves the head with [SeekPolicy.skipTarget] and re-arms; the commit sends one
 * absolute seek for wherever the head ended up — because the session itself reads
 * `SystemClock` and builds `org.json` frames, and neither exists in a plain JVM test.
 */
class SkipBurstTimerTest {

    @Test fun aRunOfThreeBackTapsSendsExactlyOneSeekForTheWholeThirtySeconds() = runTest {
        var head = 600_000L
        val seeks = mutableListOf<Long>()
        val burst = SkipBurstTimer(backgroundScope) { seeks += head }

        repeat(3) {
            head = SeekPolicy.skipTarget(head, -10_000L, DURATION_MS)
            burst.arm()
            // Deliberate repeated tapping, well inside the quiet window.
            advanceTimeBy(120L)
            runCurrent()
        }
        assertEquals(emptyList<Long>(), seeks)

        letTheWindowElapse()

        assertEquals(listOf(570_000L), seeks)
        assertFalse(burst.armed)
    }

    @Test fun aSingleTapStillSeeks() = runTest {
        val seeks = mutableListOf<Long>()
        val burst = SkipBurstTimer(backgroundScope) { seeks += 610_000L }

        burst.arm()
        assertTrue(burst.armed)
        advanceTimeBy(SeekPolicy.QUIET_WINDOW_MS - 1L)
        runCurrent()
        assertEquals(emptyList<Long>(), seeks)

        advanceTimeBy(2L)
        runCurrent()

        assertEquals(listOf(610_000L), seeks)
    }

    /** Each tap pushes the commit out again, so a long run is still one seek. */
    @Test fun aTapInsideTheWindowDiscardsTheCommitTheLastTapArmed() = runTest {
        var commits = 0
        val burst = SkipBurstTimer(backgroundScope) { commits++ }

        burst.arm()
        advanceTimeBy(200L)
        burst.arm()
        // Past the first tap's deadline: it fired only if re-arming failed to cancel it.
        advanceTimeBy(200L)
        runCurrent()
        assertEquals(0, commits)

        letTheWindowElapse()

        assertEquals(1, commits)
    }

    @Test fun aScrubStartingMidRunCancelsTheQueuedSeek() = runTest {
        var commits = 0
        val burst = SkipBurstTimer(backgroundScope) { commits++ }

        burst.arm()
        advanceTimeBy(100L)
        // What scrubStart does: the drag will send its own seek.
        burst.cancel()
        letTheWindowElapse()

        assertEquals(0, commits)
        assertFalse(burst.armed)
    }

    /** A queued seek must never land against a different cast. */
    @Test fun aNewCastOrAStopCancelsTheQueuedSeek() = runTest {
        var commits = 0
        val burst = SkipBurstTimer(backgroundScope) { commits++ }

        burst.arm()
        burst.cancel()
        letTheWindowElapse()

        assertEquals(0, commits)
    }

    @Test fun leavingTheScreenMidRunLandsTheSeekAtOnceAndOnlyOnce() = runTest {
        var commits = 0
        val burst = SkipBurstTimer(backgroundScope) { commits++ }

        burst.arm()
        advanceTimeBy(100L)
        burst.commitNow()
        assertEquals(1, commits)

        letTheWindowElapse()

        assertEquals(1, commits)
        assertFalse(burst.armed)
    }

    @Test fun commitNowWithNoRunInProgressSendsNothing() = runTest {
        var commits = 0
        val burst = SkipBurstTimer(backgroundScope) { commits++ }

        burst.commitNow()
        burst.arm()
        letTheWindowElapse()
        burst.commitNow()

        assertEquals(1, commits)
    }

    @Test fun aSecondRunAfterTheFirstOneCommittedGetsItsOwnSeek() = runTest {
        var commits = 0
        val burst = SkipBurstTimer(backgroundScope) { commits++ }

        burst.arm()
        letTheWindowElapse()
        burst.arm()
        letTheWindowElapse()

        assertEquals(2, commits)
    }

    /**
     * Let the quiet period run out, and never reach for `advanceUntilIdle()` to do it.
     *
     * Idleness in `runTest` is judged on the test coroutine alone: work launched in
     * `backgroundScope` — which is where the real timer lives — is deliberately excluded, so
     * `advanceUntilIdle()` returns with the commit still queued. That does not merely fail to
     * prove the timer fires; in a test that asserts a commit did NOT happen it passes no
     * matter what the timer does. Advancing the window explicitly is the only form here that
     * can distinguish the two.
     */
    private fun TestScope.letTheWindowElapse() {
        advanceTimeBy(SeekPolicy.QUIET_WINDOW_MS + 1L)
        runCurrent()
    }

    private companion object {
        const val DURATION_MS = 7_200_000L
    }
}
