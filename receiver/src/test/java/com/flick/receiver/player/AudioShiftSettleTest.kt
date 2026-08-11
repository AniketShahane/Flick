package com.flick.receiver.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the renderer says the nudge reached the picture.
 *
 * The phone walks a drag to its target one hop every 40 ms so the picture is never skipped,
 * which means "report it whenever it differs from last time" reports a single gesture up to
 * forty times. The ring holds 200 entries, so that is a fifth of the TV's whole memory
 * spent on the intermediate values of one drag nobody can act on.
 */
class AudioShiftSettleTest {

    private val hopUs = 40_000L

    /** Mid-walk: the value moved a hop ago, so there is nothing settled to report yet. */
    @Test fun aValueStillWalkingIsNotSettled() {
        assertFalse(audioShiftSettled(nowUs = hopUs, heldSinceUs = 0L))
    }

    /**
     * The whole point: a bound-to-bound walk is forty hops over 1.6 s, and not one instant
     * of it may be mistaken for the end.
     */
    @Test fun noInstantOfAWalkLooksLikeTheEndOfOne() {
        for (hop in 1..40) {
            val heldSinceUs = hop * hopUs
            assertTrue(
                "hop $hop",
                !audioShiftSettled(nowUs = heldSinceUs + hopUs, heldSinceUs = heldSinceUs),
            )
        }
    }

    @Test fun aValueThatStoppedMovingIsReported() {
        assertTrue(audioShiftSettled(nowUs = AUDIO_SHIFT_SETTLE_US, heldSinceUs = 0L))
        assertTrue(audioShiftSettled(nowUs = 5_000_000L, heldSinceUs = 0L))
    }

    /**
     * A renderer that was disabled between casts resumes with a stale hold stamp, and the
     * current shift is then reported on its first tick. That is the correct answer, not a
     * missed one: the shift it is rendering with now is exactly what a reader wants.
     */
    @Test fun aRendererResumingAfterAGapReportsWhatItIsRenderingWith() {
        assertTrue(audioShiftSettled(nowUs = 60_000_000L, heldSinceUs = 1_000L))
    }

    /**
     * The window has to outlast a hop by more than scheduling jitter between two renderer
     * ticks, or a slow tick mid-walk publishes a value the walk has already left behind.
     */
    @Test fun theSettleWindowOutlastsAHopByMoreThanJitter() {
        assertTrue(AUDIO_SHIFT_SETTLE_US > hopUs * 4)
    }

    /** And stays short enough to land while the viewer still has the nudge in mind. */
    @Test fun theSettleWindowIsStillPromptToARead() {
        assertTrue(AUDIO_SHIFT_SETTLE_US <= 500_000L)
    }
}
