package com.flick.receiver.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire range is the one number the phone and the TV must agree on exactly —
 * the sender clamps to the same bound and step. A value one side accepts and the
 * other refuses is not a nudge that does nothing — the receiver refuses the frame
 * rather than clamping it, and a refused frame costs the whole control socket.
 */
class AudioDelayPolicyTest {

    // --- accepts --------------------------------------------------------------

    @Test fun acceptsEveryStepInsideTheRange() {
        var value = AudioDelayPolicy.MIN_MS.toLong()
        var steps = 0
        while (value <= AudioDelayPolicy.MAX_MS) {
            assertTrue("$value", AudioDelayPolicy.accepts(value))
            value += AudioDelayPolicy.STEP_MS
            steps++
        }
        assertEquals(161, steps)
    }

    @Test fun acceptsBothEndsAndTheMiddle() {
        assertTrue(AudioDelayPolicy.accepts(-2_000L))
        assertTrue(AudioDelayPolicy.accepts(0L))
        assertTrue(AudioDelayPolicy.accepts(2_000L))
    }

    @Test fun rejectsAnythingPastEitherEnd() {
        // On the step grid, so the BOUND is what refuses them rather than the step.
        assertFalse(AudioDelayPolicy.accepts(2_025L))
        assertFalse(AudioDelayPolicy.accepts(-2_025L))
        assertFalse(AudioDelayPolicy.accepts(2_500L))
        assertFalse(AudioDelayPolicy.accepts(-2_500L))
        assertFalse(AudioDelayPolicy.accepts(Long.MAX_VALUE))
        assertFalse(AudioDelayPolicy.accepts(Long.MIN_VALUE))
    }

    @Test fun rejectsAValueOffTheStepEvenWellInsideTheRange() {
        assertFalse(AudioDelayPolicy.accepts(10L))
        assertFalse(AudioDelayPolicy.accepts(1L))
        assertFalse(AudioDelayPolicy.accepts(-10L))
        assertFalse(AudioDelayPolicy.accepts(24L))
        assertFalse(AudioDelayPolicy.accepts(26L))
        assertFalse(AudioDelayPolicy.accepts(-1_999L))
    }

    // --- clamp ----------------------------------------------------------------

    @Test fun clampHoldsTheRangeAndLeavesEverythingInsideAlone() {
        assertEquals(2_000, AudioDelayPolicy.clamp(5_000))
        assertEquals(-2_000, AudioDelayPolicy.clamp(-5_000))
        assertEquals(2_000, AudioDelayPolicy.clamp(Int.MAX_VALUE))
        assertEquals(-2_000, AudioDelayPolicy.clamp(Int.MIN_VALUE))
        assertEquals(0, AudioDelayPolicy.clamp(0))
        assertEquals(-225, AudioDelayPolicy.clamp(-225))
    }

    /** A step the wire already refused is not the receiver's to invent a value for. */
    @Test fun clampDoesNotSnapToTheStep() {
        assertEquals(10, AudioDelayPolicy.clamp(10))
    }

    // --- conversion -----------------------------------------------------------

    @Test fun theShiftCarriesTheSignThroughUnchanged() {
        // Positive delayMs = audio later = the picture pulled EARLIER, which is a
        // position handed forward to the video renderer.
        assertEquals(2_000_000L, AudioDelayPolicy.videoShiftUs(2_000))
        assertEquals(25_000L, AudioDelayPolicy.videoShiftUs(25))
        assertEquals(0L, AudioDelayPolicy.videoShiftUs(0))
        assertEquals(-25_000L, AudioDelayPolicy.videoShiftUs(-25))
        assertEquals(-2_000_000L, AudioDelayPolicy.videoShiftUs(-2_000))
    }
}
