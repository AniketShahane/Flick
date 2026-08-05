package com.flick.receiver.player

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The net for a graph that fails without saying so.
 *
 * `recoverFromFrameTurnFailure` only hears about a graph media3 raised a
 * `PlaybackException` for. A graph that simply stops producing frames raises
 * nothing: the player stays healthy, the audio runs, the position advances, and
 * the viewer is looking at a dead picture with no error to classify. Every answer
 * here is worth the viewer's turn for the rest of the film — the fallback latch
 * is per film and is never retried — so the two directions are pinned separately:
 * a graph that IS working must never be torn down, and a graph that is not must
 * be, exactly once.
 *
 * The half that needs the most pinning is the LIVE swap. `setVideoEffects` returns
 * while the re-registration is still a queued renderer message, so the frames
 * already inside the GL pipeline keep arriving under the PREVIOUS effect list —
 * and each of them reaches the same per-frame callback a working new list would.
 * Taking any of those as proof is how a wedged swap used to go unnoticed
 * completely.
 */
class FrameTurnWatchdogTest {

    private val settleMs = PlayerController.FRAME_TURN_SWAP_SETTLE_MS
    private val deadlineMs = PlayerController.FRAME_TURN_DEADLINE_MS

    private fun FrameTurnWatchdog.armNewGraph(nowMs: Long = 0L): Long =
        arm(FrameTurnWatchdog.Engagement.NewGraph, nowMs, settleMs)

    private fun FrameTurnWatchdog.armLiveSwap(nowMs: Long = 0L): Long =
        arm(FrameTurnWatchdog.Engagement.LiveSwap, nowMs, settleMs)

    // --- The graph that works -------------------------------------------------

    @Test fun aFreshWatchdogIsWatchingNothing() {
        val watchdog = FrameTurnWatchdog()
        assertFalse(watchdog.isArmed)
        assertEquals(FrameTurnWatchdog.NOT_ARMED, watchdog.evidenceGenerationFor(0L))
        assertFalse(watchdog.onFrameRendered(FrameTurnWatchdog.NOT_ARMED))
    }

    @Test fun theFirstFrameOfANewGraphDisarmsIt() {
        val watchdog = FrameTurnWatchdog()
        val generation = watchdog.armNewGraph(nowMs = 1_000L)
        assertTrue(watchdog.isArmed)
        assertEquals(generation, watchdog.evidenceGenerationFor(1_000L))
        assertTrue(watchdog.onFrameRendered(generation))
        assertFalse(watchdog.isArmed)
        assertEquals(
            FrameTurnWatchdog.Verdict.Stale,
            watchdog.consumeDeadline(generation, renderingExpected = true),
        )
    }

    /**
     * The frame callback runs on the playback thread and posts to the main one, so
     * several frames can be in flight behind the first. Only one may report the
     * engagement healthy, or a later post could cancel a deadline armed since.
     */
    @Test fun onlyTheFirstOfABurstOfFramesCounts() {
        val watchdog = FrameTurnWatchdog()
        val generation = watchdog.armNewGraph()
        assertTrue(watchdog.onFrameRendered(generation))
        repeat(4) { assertFalse(watchdog.onFrameRendered(generation)) }
    }

    // --- The live swap --------------------------------------------------------

    /**
     * The defect this exists for. A film that is already turned and playing has a
     * frame due within a frame interval of the key press, and that frame was drawn
     * with the effect list being replaced — so it says nothing whatsoever about
     * the new one.
     */
    @Test fun aFrameFromInsideTheDrainWindowIsNoEvidenceAboutALiveSwap() {
        val watchdog = FrameTurnWatchdog()
        val swapAtMs = 4_000L
        watchdog.armLiveSwap(nowMs = swapAtMs)
        // A 24 fps film renders one about 42 ms later, and keeps doing so.
        var frameAtMs = swapAtMs + 42L
        while (frameAtMs < swapAtMs + settleMs) {
            assertEquals(
                FrameTurnWatchdog.NOT_ARMED,
                watchdog.evidenceGenerationFor(frameAtMs),
            )
            frameAtMs += 42L
        }
        assertTrue(watchdog.isArmed)
    }

    @Test fun theFirstFrameAfterTheDrainWindowProvesALiveSwap() {
        val watchdog = FrameTurnWatchdog()
        val swapAtMs = 4_000L
        val generation = watchdog.armLiveSwap(nowMs = swapAtMs)
        assertEquals(generation, watchdog.evidenceGenerationFor(swapAtMs + settleMs))
        assertTrue(watchdog.onFrameRendered(generation))
        assertEquals(
            FrameTurnWatchdog.Verdict.Stale,
            watchdog.consumeDeadline(generation, renderingExpected = true),
        )
    }

    /**
     * The failure the whole change exists to close: the re-registration wedges,
     * the pipeline drains the handful of frames it already held, and then nothing
     * — over a player that is still healthy, still advancing and raising nothing.
     */
    @Test fun aWedgedLiveSwapIsCondemnedThoughItsPreSwapFramesRendered() {
        val watchdog = FrameTurnWatchdog()
        val swapAtMs = 4_000L
        val generation = watchdog.armLiveSwap(nowMs = swapAtMs)
        // Media3 admits about half a dozen frames into the graph; they drain and
        // that is the last of them.
        repeat(6) { index ->
            assertEquals(
                FrameTurnWatchdog.NOT_ARMED,
                watchdog.evidenceGenerationFor(swapAtMs + 42L * (index + 1)),
            )
        }
        assertEquals(
            FrameTurnWatchdog.Verdict.NoFrames,
            watchdog.consumeDeadline(generation, renderingExpected = true),
        )
    }

    /** The same instant, the same frame: only how the graph was engaged differs. */
    @Test fun theEngagementIsWhatDecidesWhetherAFrameCounts() {
        val newGraph = FrameTurnWatchdog()
        val newGraphGeneration = newGraph.armNewGraph(nowMs = 500L)
        assertEquals(newGraphGeneration, newGraph.evidenceGenerationFor(510L))

        val liveSwap = FrameTurnWatchdog()
        liveSwap.armLiveSwap(nowMs = 500L)
        assertEquals(FrameTurnWatchdog.NOT_ARMED, liveSwap.evidenceGenerationFor(510L))
    }

    /**
     * Spending the drain window out of the deadline is the whole cost of closing
     * the gap, and it has to stay negligible: what condemns a swapped graph is
     * still most of a rebuffer-proof drought, not a few frame intervals.
     */
    @Test fun theDrainWindowCostsTheDeadlineAlmostNothing() {
        assertTrue(settleMs * 4 < deadlineMs)
        assertTrue(deadlineMs - settleMs > 10_000L)
    }

    // --- The graph that does not ----------------------------------------------

    @Test fun aDeadlineWithNoFrameBehindItCondemnsTheGraph() {
        val watchdog = FrameTurnWatchdog()
        val generation = watchdog.armNewGraph()
        assertEquals(
            FrameTurnWatchdog.Verdict.NoFrames,
            watchdog.consumeDeadline(generation, renderingExpected = true),
        )
        assertFalse(watchdog.isArmed)
    }

    /** One fallback, not one per deadline that happens to still be queued. */
    @Test fun theVerdictIsReachedExactlyOnce() {
        val watchdog = FrameTurnWatchdog()
        val generation = watchdog.armNewGraph()
        assertEquals(
            FrameTurnWatchdog.Verdict.NoFrames,
            watchdog.consumeDeadline(generation, renderingExpected = true),
        )
        assertEquals(
            FrameTurnWatchdog.Verdict.Stale,
            watchdog.consumeDeadline(generation, renderingExpected = true),
        )
    }

    // --- A paused film is not evidence ----------------------------------------

    /**
     * The false positive that would cost the most: a viewer pauses, turns the
     * picture, and the shader swap has no frames to work on until they resume.
     * Firing there would latch the film as un-turnable over a graph that is
     * perfectly healthy.
     */
    @Test fun aPausedFilmKeepsTheEngagementArmedInsteadOfCondemningIt() {
        val watchdog = FrameTurnWatchdog()
        val generation = watchdog.armLiveSwap()
        repeat(3) {
            assertEquals(
                FrameTurnWatchdog.Verdict.NotYet,
                watchdog.consumeDeadline(generation, renderingExpected = false),
            )
            assertTrue(watchdog.isArmed)
        }
        // And the same engagement is still the one judged when playback resumes.
        assertEquals(
            FrameTurnWatchdog.Verdict.NoFrames,
            watchdog.consumeDeadline(generation, renderingExpected = true),
        )
    }

    @Test fun aFrameThatArrivesWhilePausedStillClearsIt() {
        val watchdog = FrameTurnWatchdog()
        val generation = watchdog.armNewGraph()
        assertEquals(
            FrameTurnWatchdog.Verdict.NotYet,
            watchdog.consumeDeadline(generation, renderingExpected = false),
        )
        assertTrue(watchdog.onFrameRendered(generation))
        assertEquals(
            FrameTurnWatchdog.Verdict.Stale,
            watchdog.consumeDeadline(generation, renderingExpected = true),
        )
    }

    // --- What "rendering expected" is -----------------------------------------

    @Test fun aPlayingFilmIsEvidenceAndAPausedOneIsNot() {
        assertTrue(framesExpectedFrom(playWhenReady = true, playbackState = Player.STATE_READY))
        assertFalse(framesExpectedFrom(playWhenReady = false, playbackState = Player.STATE_READY))
    }

    /**
     * A film that has run out renders nothing ever again while `playWhenReady`
     * stays set, so a deadline landing there would condemn a graph that worked for
     * the whole film and hand the viewer a rebuild of one they have finished.
     */
    @Test fun aFinishedFilmIsNotEvidenceAboutTheGraph() {
        assertFalse(framesExpectedFrom(playWhenReady = true, playbackState = Player.STATE_ENDED))
    }

    /**
     * A rebuffer is deliberately NOT excused: a graph that stopped consuming
     * frames leaves the video renderer un-ready, which is the state a genuine
     * rebuffer produces, so excusing it would excuse the failure being watched
     * for. The deadline's length is what separates them.
     */
    @Test fun aRebufferIsNotExcused() {
        assertTrue(framesExpectedFrom(playWhenReady = true, playbackState = Player.STATE_BUFFERING))
    }

    // --- Generations ----------------------------------------------------------

    /**
     * A turn changed while its own deadline is still queued replaces the
     * engagement. The stale deadline must not condemn the new graph, which has had
     * no time at all.
     */
    @Test fun aDeadlineFromAReplacedEngagementIsDropped() {
        val watchdog = FrameTurnWatchdog()
        val first = watchdog.armNewGraph()
        val second = watchdog.armNewGraph()
        assertNotEquals(first, second)
        assertEquals(
            FrameTurnWatchdog.Verdict.Stale,
            watchdog.consumeDeadline(first, renderingExpected = true),
        )
        assertTrue(watchdog.isArmed)
        assertEquals(
            FrameTurnWatchdog.Verdict.NoFrames,
            watchdog.consumeDeadline(second, renderingExpected = true),
        )
    }

    /**
     * The other half of the same race, and the reason the frame callback carries a
     * generation at all: it samples one on the playback thread and posts, and the
     * main thread can re-arm before that post runs. Clearing the NEW engagement on
     * the strength of a frame from the old one would leave a fresh graph with no
     * deadline watching it.
     */
    @Test fun aFrameSampledBeforeAReArmCannotClearTheEngagementAfterIt() {
        val watchdog = FrameTurnWatchdog()
        val first = watchdog.armNewGraph(nowMs = 100L)
        val sampled = watchdog.evidenceGenerationFor(120L)
        assertEquals(first, sampled)
        val second = watchdog.armNewGraph(nowMs = 200L)
        assertFalse(watchdog.onFrameRendered(sampled))
        assertTrue(watchdog.isArmed)
        assertEquals(
            FrameTurnWatchdog.Verdict.NoFrames,
            watchdog.consumeDeadline(second, renderingExpected = true),
        )
    }

    /** Every teardown path calls this, so nothing may survive it. */
    @Test fun cancellingLeavesNoDeadlineAbleToFire() {
        val watchdog = FrameTurnWatchdog()
        val generation = watchdog.armNewGraph()
        watchdog.cancel()
        assertFalse(watchdog.isArmed)
        assertEquals(FrameTurnWatchdog.NOT_ARMED, watchdog.evidenceGenerationFor(0L))
        assertEquals(
            FrameTurnWatchdog.Verdict.Stale,
            watchdog.consumeDeadline(generation, renderingExpected = true),
        )
        assertFalse(watchdog.onFrameRendered(generation))
    }

    /** A generation is never reissued, so a cancelled one can never be revived. */
    @Test fun generationsNeverRepeat() {
        val watchdog = FrameTurnWatchdog()
        val seen = mutableSetOf<Long>()
        repeat(20) {
            seen += watchdog.armNewGraph()
            watchdog.cancel()
        }
        assertEquals(20, seen.size)
        assertFalse(FrameTurnWatchdog.NOT_ARMED in seen)
    }

    /** "Nothing was armed" must never be mistaken for an engagement. */
    @Test fun theNotArmedGenerationIsNeverAVerdict() {
        val watchdog = FrameTurnWatchdog()
        watchdog.armNewGraph()
        assertFalse(watchdog.onFrameRendered(FrameTurnWatchdog.NOT_ARMED))
        assertEquals(
            FrameTurnWatchdog.Verdict.Stale,
            watchdog.consumeDeadline(FrameTurnWatchdog.NOT_ARMED, renderingExpected = true),
        )
        assertTrue(watchdog.isArmed)
    }
}
