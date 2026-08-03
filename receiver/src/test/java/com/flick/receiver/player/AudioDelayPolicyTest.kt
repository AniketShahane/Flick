package com.flick.receiver.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire range is the one number the phone and the TV must agree on exactly —
 * the sender clamps to the same bound and step, so a value one side accepts and
 * the other refuses is a nudge that silently does nothing.
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
        assertEquals(41, steps)
    }

    @Test fun acceptsBothEndsAndTheMiddle() {
        assertTrue(AudioDelayPolicy.accepts(-500L))
        assertTrue(AudioDelayPolicy.accepts(0L))
        assertTrue(AudioDelayPolicy.accepts(500L))
    }

    @Test fun rejectsAnythingPastEitherEnd() {
        assertFalse(AudioDelayPolicy.accepts(501L))
        assertFalse(AudioDelayPolicy.accepts(-501L))
        assertFalse(AudioDelayPolicy.accepts(525L))
        assertFalse(AudioDelayPolicy.accepts(-525L))
        assertFalse(AudioDelayPolicy.accepts(Long.MAX_VALUE))
        assertFalse(AudioDelayPolicy.accepts(Long.MIN_VALUE))
    }

    @Test fun rejectsAValueOffTheStepEvenWellInsideTheRange() {
        assertFalse(AudioDelayPolicy.accepts(10L))
        assertFalse(AudioDelayPolicy.accepts(1L))
        assertFalse(AudioDelayPolicy.accepts(-10L))
        assertFalse(AudioDelayPolicy.accepts(24L))
        assertFalse(AudioDelayPolicy.accepts(26L))
        assertFalse(AudioDelayPolicy.accepts(-499L))
    }

    // --- clamp ----------------------------------------------------------------

    @Test fun clampHoldsTheRangeAndLeavesEverythingInsideAlone() {
        assertEquals(500, AudioDelayPolicy.clamp(5_000))
        assertEquals(-500, AudioDelayPolicy.clamp(-5_000))
        assertEquals(500, AudioDelayPolicy.clamp(Int.MAX_VALUE))
        assertEquals(-500, AudioDelayPolicy.clamp(Int.MIN_VALUE))
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
        assertEquals(500_000L, AudioDelayPolicy.videoShiftUs(500))
        assertEquals(25_000L, AudioDelayPolicy.videoShiftUs(25))
        assertEquals(0L, AudioDelayPolicy.videoShiftUs(0))
        assertEquals(-25_000L, AudioDelayPolicy.videoShiftUs(-25))
        assertEquals(-500_000L, AudioDelayPolicy.videoShiftUs(-500))
    }
}
