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
        assertEquals(401, steps)
    }

    @Test fun acceptsBothEndsAndTheMiddle() {
        assertEquals(-5_000, AudioDelayPolicy.MIN_MS)
        assertEquals(5_000, AudioDelayPolicy.MAX_MS)
        assertTrue(AudioDelayPolicy.accepts(-5_000L))
        assertTrue(AudioDelayPolicy.accepts(0L))
        assertTrue(AudioDelayPolicy.accepts(5_000L))
        // The old bound, which is now an ordinary value well inside the range.
        assertTrue(AudioDelayPolicy.accepts(-2_000L))
        assertTrue(AudioDelayPolicy.accepts(2_000L))
        assertTrue(AudioDelayPolicy.accepts(3_275L))
    }

    @Test fun rejectsAnythingPastEitherEnd() {
        // On the step grid, so the BOUND is what refuses them rather than the step.
        assertFalse(AudioDelayPolicy.accepts(5_025L))
        assertFalse(AudioDelayPolicy.accepts(-5_025L))
        assertFalse(AudioDelayPolicy.accepts(7_500L))
        assertFalse(AudioDelayPolicy.accepts(-7_500L))
        assertFalse(AudioDelayPolicy.accepts(Long.MAX_VALUE))
        assertFalse(AudioDelayPolicy.accepts(Long.MIN_VALUE))
    }

    @Test fun rejectsAValueOffTheStepEvenWellInsideTheRange() {
        assertFalse(AudioDelayPolicy.accepts(10L))
        assertFalse(AudioDelayPolicy.accepts(1L))
        assertFalse(AudioDelayPolicy.accepts(-10L))
        assertFalse(AudioDelayPolicy.accepts(24L))
        assertFalse(AudioDelayPolicy.accepts(26L))
        assertFalse(AudioDelayPolicy.accepts(-4_999L))
    }

    /**
     * The wire rule carries no device term, and must not grow one. A frame the
     * phone was entitled to send and this TV refused takes the control socket down
     * with it, so what a particular heap grant can carry is settled by
     * [AudioDelayPolicy.maxDelayMsFor] afterwards and never here.
     */
    @Test fun theSmallestDeviceStillAcceptsTheWholeWireRange() {
        val smallest = bufferBudgetFor(96L * 1024 * 1024)
        assertTrue(AudioDelayPolicy.maxDelayMsFor(smallest) < AudioDelayPolicy.MAX_MS)
        assertTrue(AudioDelayPolicy.accepts(AudioDelayPolicy.MAX_MS.toLong()))
        assertTrue(AudioDelayPolicy.accepts(AudioDelayPolicy.MIN_MS.toLong()))
    }

    // --- clamp ----------------------------------------------------------------

    @Test fun clampHoldsTheRangeAndLeavesEverythingInsideAlone() {
        assertEquals(5_000, AudioDelayPolicy.clamp(9_000))
        assertEquals(-5_000, AudioDelayPolicy.clamp(-9_000))
        assertEquals(5_000, AudioDelayPolicy.clamp(Int.MAX_VALUE))
        assertEquals(-5_000, AudioDelayPolicy.clamp(Int.MIN_VALUE))
        assertEquals(0, AudioDelayPolicy.clamp(0))
        assertEquals(-225, AudioDelayPolicy.clamp(-225))
        assertEquals(4_800, AudioDelayPolicy.clamp(4_800))
    }

    /** A step the wire already refused is not the receiver's to invent a value for. */
    @Test fun clampDoesNotSnapToTheStep() {
        assertEquals(10, AudioDelayPolicy.clamp(10))
    }

    // --- the device's own ceiling ---------------------------------------------

    /**
     * The half of the bound that is NOT the wire's. A delay claims its own size of
     * forward buffer whichever way it points, and the forward buffer is whatever
     * heap this TV was granted — so the widest delay that can be carried is a
     * per-device number and the wire's ±5 s is only the widest one that may be
     * ASKED for.
     *
     * The tiers are `bufferBudgetFor`'s, quoted as forward buffer of 100 Mbps
     * content: 14,297 ms on the verified TV's 512 MB grant, 7,301 ms at 256 MB,
     * 2,738 ms at 96 MB, 2,282 ms on the 32 MiB byte floor.
     */
    @Test fun theVerifiedTvCarriesTheWholeWireRangeWithRoom() {
        val budget = bufferBudgetFor(512L * 1024 * 1024)
        assertEquals(14_297, budget.plannedPeakFitMs - budget.backBufferMs)
        // 14,297 ms of forward buffer less the 4,765 ms the load control demands
        // before it will resume after a rebuffer.
        assertEquals(9_532, AudioDelayPolicy.maxDelayMsFor(budget))
        assertTrue(AudioDelayPolicy.maxDelayMsFor(budget) > AudioDelayPolicy.MAX_MS)
    }

    @Test fun aSmallerHeapCarriesLessThanTheWireAllows() {
        assertEquals(4_868, AudioDelayPolicy.maxDelayMsFor(bufferBudgetFor(256L * 1024 * 1024)))
        assertEquals(1_826, AudioDelayPolicy.maxDelayMsFor(bufferBudgetFor(96L * 1024 * 1024)))
        // The byte floor, which is the smallest budget that can exist at all.
        assertEquals(1_522, AudioDelayPolicy.maxDelayMsFor(bufferBudgetFor(8L * 1024 * 1024)))
    }

    /**
     * The margin is the point of the cap: a delay that claimed the whole forward
     * buffer would hold the player permanently under the level it resumes from,
     * which is a renderer that runs out of samples — the one failure the delay
     * bound exists to prevent.
     */
    @Test fun theCapAlwaysLeavesTheResumeThresholdBehindIt() {
        for (heapMb in listOf(8L, 32L, 64L, 96L, 128L, 256L, 384L, 512L, 1_024L)) {
            val budget = bufferBudgetFor(heapMb * 1024 * 1024)
            val forwardMs = budget.plannedPeakFitMs - budget.backBufferMs
            val cap = AudioDelayPolicy.maxDelayMsFor(budget)
            assertTrue("${heapMb}MB", cap > 0)
            assertEquals(
                "${heapMb}MB",
                budget.bufferForPlaybackAfterRebufferMs,
                forwardMs - cap,
            )
        }
    }

    /** A degenerate reading of the heap must not produce a negative ceiling. */
    @Test fun theCapIsNeverNegative() {
        assertTrue(AudioDelayPolicy.maxDelayMsFor(bufferBudgetFor(0L)) >= 0)
    }

    // --- conversion -----------------------------------------------------------

    @Test fun theShiftCarriesTheSignThroughUnchanged() {
        // Positive delayMs = audio later = the picture pulled EARLIER, which is a
        // position handed forward to the video renderer.
        assertEquals(5_000_000L, AudioDelayPolicy.videoShiftUs(5_000))
        assertEquals(2_000_000L, AudioDelayPolicy.videoShiftUs(2_000))
        assertEquals(25_000L, AudioDelayPolicy.videoShiftUs(25))
        assertEquals(0L, AudioDelayPolicy.videoShiftUs(0))
        assertEquals(-25_000L, AudioDelayPolicy.videoShiftUs(-25))
        assertEquals(-5_000_000L, AudioDelayPolicy.videoShiftUs(-5_000))
    }
}
