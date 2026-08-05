package com.flick.receiver.player

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The net for a turn that fails without saying so.
 *
 * `recoverFromTurnFailure` only hears about a turn media3 raised a
 * `PlaybackException` for. A picture that simply stops moving raises nothing: the
 * player stays healthy, the audio runs, the position advances, and the viewer is
 * looking at a dead frame with no error to classify. Every answer here is worth
 * the viewer's turn for the rest of the film — the fallback latch is per film and
 * is never retried — so the two directions are pinned separately: a turn that IS
 * working must never be torn down, and a turn that is not must be, exactly once.
 *
 * The half that needed the most rethinking is the one that hid the real bug. The
 * version of this that watched only the engagement took the first rendered frame
 * as proof and cancelled itself forever, so a picture that rendered once and then
 * froze was never looked at again — which is exactly what happened on the verified
 * hardware. There is no "proved" state here any more.
 */
class TurnWatchdogTest {

    private val deadlineMs = PlayerController.TURN_DEADLINE_MS

    // --- The turn that works --------------------------------------------------

    @Test fun aFreshWatchdogIsWatchingNothing() {
        val watchdog = TurnWatchdog()
        assertFalse(watchdog.isEngaged)
        assertFalse(watchdog.hasRenderedAFrame)
        assertEquals(
            TurnWatchdog.Verdict.Stale,
            watchdog.consumeDeadline(TurnWatchdog.NOT_ENGAGED, nowMs = 0L, renderingExpected = true),
        )
    }

    @Test fun framesInsideTheWindowKeepTheTurnAlive() {
        val watchdog = TurnWatchdog()
        val generation = watchdog.engage(nowMs = 1_000L)
        assertTrue(watchdog.isEngaged)
        watchdog.onFrameRendered(atMs = 1_042L)
        assertEquals(
            TurnWatchdog.Verdict.Alive,
            watchdog.consumeDeadline(generation, nowMs = 1_000L + deadlineMs, renderingExpected = true),
        )
        assertTrue(watchdog.isEngaged)
    }

    /**
     * A working turn is asked the same question for the whole film, and every
     * window has to be answerable on its own — the frames from ten minutes ago say
     * nothing about the picture now.
     */
    @Test fun aTurnThatKeepsRenderingIsNeverCondemned() {
        val watchdog = TurnWatchdog()
        var nowMs = 1_000L
        val generation = watchdog.engage(nowMs)
        repeat(50) {
            nowMs += deadlineMs
            watchdog.onFrameRendered(atMs = nowMs - 42L)
            assertEquals(
                TurnWatchdog.Verdict.Alive,
                watchdog.consumeDeadline(generation, nowMs, renderingExpected = true),
            )
        }
        assertTrue(watchdog.isEngaged)
    }

    // --- The turn that does not -----------------------------------------------

    /** Nothing at all reached the panel: the engagement itself failed. */
    @Test fun aDeadlineWithNoFrameBehindItCondemnsTheTurn() {
        val watchdog = TurnWatchdog()
        val generation = watchdog.engage(nowMs = 0L)
        assertEquals(
            TurnWatchdog.Verdict.NoFrames,
            watchdog.consumeDeadline(generation, nowMs = deadlineMs, renderingExpected = true),
        )
        assertFalse(watchdog.isEngaged)
    }

    /**
     * The failure this whole rewrite exists to catch, and the one the previous
     * watchdog was structurally unable to see: one frame reaches the panel, the
     * picture freezes, and the player carries on perfectly healthy behind it.
     */
    @Test fun aSingleFrameFollowedByAFreezeIsCondemned() {
        val watchdog = TurnWatchdog()
        val generation = watchdog.engage(nowMs = 1_000L)
        watchdog.onFrameRendered(atMs = 1_100L)
        // The window that frame belongs to passes healthily.
        assertEquals(
            TurnWatchdog.Verdict.Alive,
            watchdog.consumeDeadline(generation, nowMs = 1_000L + deadlineMs, renderingExpected = true),
        )
        assertTrue(watchdog.hasRenderedAFrame)
        // And then nothing ever again.
        assertEquals(
            TurnWatchdog.Verdict.NoFrames,
            watchdog.consumeDeadline(generation, nowMs = 1_000L + 2 * deadlineMs, renderingExpected = true),
        )
        assertFalse(watchdog.isEngaged)
    }

    /** One fallback, not one per deadline that happens to still be queued. */
    @Test fun theVerdictIsReachedExactlyOnce() {
        val watchdog = TurnWatchdog()
        val generation = watchdog.engage(nowMs = 0L)
        assertEquals(
            TurnWatchdog.Verdict.NoFrames,
            watchdog.consumeDeadline(generation, nowMs = deadlineMs, renderingExpected = true),
        )
        assertEquals(
            TurnWatchdog.Verdict.Stale,
            watchdog.consumeDeadline(generation, nowMs = 2 * deadlineMs, renderingExpected = true),
        )
    }

    // --- A film nobody is watching is not evidence ----------------------------

    /**
     * The false positive that would cost the most: a viewer pauses, turns the
     * picture, and no frame is due until they resume. Firing there would latch the
     * film as un-turnable over a surface that is perfectly healthy.
     */
    @Test fun aPausedFilmKeepsTheTurnEngagedInsteadOfCondemningIt() {
        val watchdog = TurnWatchdog()
        val generation = watchdog.engage(nowMs = 0L)
        repeat(3) { window ->
            assertEquals(
                TurnWatchdog.Verdict.NotYet,
                watchdog.consumeDeadline(
                    generation,
                    nowMs = deadlineMs * (window + 1),
                    renderingExpected = false,
                ),
            )
            assertTrue(watchdog.isEngaged)
        }
        // And the same engagement is still the one judged when playback resumes.
        assertEquals(
            TurnWatchdog.Verdict.NoFrames,
            watchdog.consumeDeadline(generation, nowMs = deadlineMs * 5, renderingExpected = true),
        )
    }

    /**
     * The quiet stretch does not carry forward. A film resumed after an hour
     * paused owes its first frame from the moment it resumed, not from the moment
     * the turn was engaged — otherwise the window that judges it has already
     * expired before a single frame could arrive.
     */
    @Test fun aResumedFilmIsJudgedFromWhenItResumed() {
        val watchdog = TurnWatchdog()
        val generation = watchdog.engage(nowMs = 0L)
        assertEquals(
            TurnWatchdog.Verdict.NotYet,
            watchdog.consumeDeadline(generation, nowMs = 3_600_000L, renderingExpected = false),
        )
        // One frame, right after the resume, and the window it lands in is the one
        // that opened with it.
        watchdog.onFrameRendered(atMs = 3_600_100L)
        assertEquals(
            TurnWatchdog.Verdict.Alive,
            watchdog.consumeDeadline(generation, nowMs = 3_600_000L + deadlineMs, renderingExpected = true),
        )
    }

    /**
     * A frame proves the window it landed in and no later one. Carrying it
     * forward is exactly the flaw that hid the freeze: one frame at the start of a
     * film would otherwise stand in for the whole of it.
     */
    @Test fun aFrameProvesTheWindowItLandedInAndNoLaterOne() {
        val watchdog = TurnWatchdog()
        val generation = watchdog.engage(nowMs = 0L)
        watchdog.onFrameRendered(atMs = 10L)
        assertEquals(
            TurnWatchdog.Verdict.Alive,
            watchdog.consumeDeadline(generation, nowMs = deadlineMs, renderingExpected = true),
        )
        assertEquals(
            TurnWatchdog.Verdict.NoFrames,
            watchdog.consumeDeadline(generation, nowMs = 2 * deadlineMs, renderingExpected = true),
        )
    }

    // --- What "rendering expected" is -----------------------------------------

    @Test fun aPlayingFilmIsEvidenceAndAPausedOneIsNot() {
        assertTrue(framesExpectedFrom(true, Player.STATE_READY, provenOnce = false))
        assertTrue(framesExpectedFrom(true, Player.STATE_READY, provenOnce = true))
        assertFalse(framesExpectedFrom(false, Player.STATE_READY, provenOnce = false))
        assertFalse(framesExpectedFrom(false, Player.STATE_READY, provenOnce = true))
    }

    /**
     * A film that has run out renders nothing ever again while `playWhenReady`
     * stays set, so a deadline landing there would condemn a turn that worked for
     * the whole film and hand the viewer a re-prepare of one they have finished.
     */
    @Test fun aFinishedFilmIsNotEvidenceAboutTheTurn() {
        assertFalse(framesExpectedFrom(true, Player.STATE_ENDED, provenOnce = true))
        assertFalse(framesExpectedFrom(true, Player.STATE_ENDED, provenOnce = false))
    }

    /** An idle player has no pipeline at all, so it owes no frame either. */
    @Test fun anIdlePlayerIsNotEvidenceAboutTheTurn() {
        assertFalse(framesExpectedFrom(true, Player.STATE_IDLE, provenOnce = true))
        assertFalse(framesExpectedFrom(true, Player.STATE_IDLE, provenOnce = false))
    }

    /**
     * The one asymmetry, and it is the difference between the two questions a
     * deadline can be asking. Before any frame, a wedged turn leaves the video
     * renderer un-ready — which is indistinguishable from a rebuffer, so excusing
     * BUFFERING would excuse the failure being watched for. After a frame the turn
     * has demonstrably reached the panel, and the receiver's own retry policy
     * rides out ~100 s of network trouble: condemning a working turn because the
     * Wi-Fi stalled would take the picture off a viewer already watching a spinner.
     */
    @Test fun aRebufferIsExcusedOnlyOnceTheTurnHasRenderedSomething() {
        assertTrue(framesExpectedFrom(true, Player.STATE_BUFFERING, provenOnce = false))
        assertFalse(framesExpectedFrom(true, Player.STATE_BUFFERING, provenOnce = true))
    }

    // --- Generations ----------------------------------------------------------

    /**
     * A turn re-engaged while its own deadline is still queued replaces the
     * engagement. The stale deadline must not condemn the new one, which has had
     * no time at all.
     */
    @Test fun aDeadlineFromAReplacedEngagementIsDropped() {
        val watchdog = TurnWatchdog()
        val first = watchdog.engage(nowMs = 0L)
        val second = watchdog.engage(nowMs = 100L)
        assertNotEquals(first, second)
        assertEquals(
            TurnWatchdog.Verdict.Stale,
            watchdog.consumeDeadline(first, nowMs = deadlineMs, renderingExpected = true),
        )
        assertTrue(watchdog.isEngaged)
        assertEquals(
            TurnWatchdog.Verdict.NoFrames,
            watchdog.consumeDeadline(second, nowMs = 100L + deadlineMs, renderingExpected = true),
        )
    }

    /** Re-engaging starts from no evidence, not from the previous surface's frames. */
    @Test fun aNewEngagementInheritsNoFrames() {
        val watchdog = TurnWatchdog()
        watchdog.engage(nowMs = 0L)
        watchdog.onFrameRendered(atMs = 50L)
        assertTrue(watchdog.hasRenderedAFrame)
        val second = watchdog.engage(nowMs = 100L)
        assertFalse(watchdog.hasRenderedAFrame)
        assertEquals(
            TurnWatchdog.Verdict.NoFrames,
            watchdog.consumeDeadline(second, nowMs = 100L + deadlineMs, renderingExpected = true),
        )
    }

    /** Every teardown path calls this, so nothing may survive it. */
    @Test fun disengagingLeavesNoDeadlineAbleToFire() {
        val watchdog = TurnWatchdog()
        val generation = watchdog.engage(nowMs = 0L)
        watchdog.disengage()
        assertFalse(watchdog.isEngaged)
        assertEquals(
            TurnWatchdog.Verdict.Stale,
            watchdog.consumeDeadline(generation, nowMs = deadlineMs, renderingExpected = true),
        )
    }

    /** A generation is never reissued, so a disengaged one can never be revived. */
    @Test fun generationsNeverRepeat() {
        val watchdog = TurnWatchdog()
        val seen = mutableSetOf<Long>()
        repeat(20) {
            seen += watchdog.engage(nowMs = 0L)
            watchdog.disengage()
        }
        assertEquals(20, seen.size)
        assertFalse(TurnWatchdog.NOT_ENGAGED in seen)
    }

    /** "Nothing was engaged" must never be mistaken for an engagement. */
    @Test fun theNotEngagedGenerationIsNeverAVerdict() {
        val watchdog = TurnWatchdog()
        watchdog.engage(nowMs = 0L)
        assertEquals(
            TurnWatchdog.Verdict.Stale,
            watchdog.consumeDeadline(TurnWatchdog.NOT_ENGAGED, nowMs = deadlineMs, renderingExpected = true),
        )
        assertTrue(watchdog.isEngaged)
    }
}
