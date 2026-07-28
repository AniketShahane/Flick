package com.flick.receiver.ui.screens

import com.flick.receiver.player.ThroughputSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The histogram draws its forty bars from one draw scope, so the rules that used
 * to fall out of forty composables' identity — an unmeasured slot is not drawn, a
 * slot's first sample snaps — are now [HistogramBars]' job. This is where they are
 * held. Every one of them is an honesty rule: a bar the receiver did not measure
 * may not appear, and a bar it did measure may not appear to have travelled
 * somewhere it never was.
 */
class StreamMetricsHistogramTest {

    private val slots = 4

    /** Peak defaults to the window's own high-water mark, as the ring's does. */
    private fun window(vararg samples: Long) = ThroughputSnapshot(
        samplesBps = samples.toList(),
        peakBps = samples.maxOrNull() ?: 0L,
        latestBps = samples.lastOrNull() ?: 0L,
        capacity = slots,
    )

    @Test fun aSlotWithNoSampleIsNotDrawnAtAll() {
        val bars = HistogramBars(slots)
        assertEquals(slots, bars.emptySlots)
        bars.retarget(window(100L), 1f)
        // One reading exists, so exactly one slot may be painted.
        assertEquals(slots - 1, bars.emptySlots)
    }

    @Test fun aSlotsFirstSampleSnaps() {
        val bars = HistogramBars(slots)
        bars.retarget(window(100L), 1f)
        // At progress 0 — the instant the transition would begin — the new bar is
        // already at its measured height rather than climbing out of the floor.
        assertEquals(1f, bars.heightFractionAt(slots - 1, 0f), 1e-6f)
    }

    @Test fun aMeasuredBarNeverCollapsesToNothing() {
        val bars = HistogramBars(slots)
        bars.retarget(window(0L, 1_000L), 1f)
        // A measured zero is still a reading, so it keeps the design's 6 % floor.
        assertEquals(0.06f, bars.heightFractionAt(slots - 2, 1f), 1e-6f)
    }

    @Test fun theRowSlidesLeftOneSampleAtATime() {
        val bars = HistogramBars(slots)
        bars.retarget(window(500L, 1_000L), 1f)
        assertEquals(0.5f, bars.heightFractionAt(slots - 2, 1f), 1e-6f)
        assertEquals(1f, bars.heightFractionAt(slots - 1, 1f), 1e-6f)

        // One more sample marches the window: what stood at the newest slot is now
        // one slot left, and it TRAVELS there — at the start of the transition it
        // is still drawing the height it held.
        bars.retarget(window(500L, 1_000L, 1_000L), 1f)
        assertEquals(0.5f, bars.heightFractionAt(slots - 2, 0f), 1e-6f)
        assertEquals(1f, bars.heightFractionAt(slots - 2, 1f), 1e-6f)
    }

    @Test fun aBarCaughtMidFlightSetsOffFromWhereItIsDrawn() {
        val bars = HistogramBars(slots)
        bars.retarget(window(1_000L, 1_000L, 1_000L, 1_000L), 1f)
        bars.retarget(window(250L, 1_000L, 1_000L, 1_000L), 1f)
        val midFlight = bars.heightFractionAt(0, 0.5f)
        assertEquals(0.625f, midFlight, 1e-6f)

        // Interrupted halfway down and re-aimed at the peak: it must leave from the
        // height on screen, not from the 0.25 the cancelled transition was aimed at.
        bars.retarget(window(1_000L, 1_000L, 1_000L, 1_000L), 0.5f)
        assertEquals(midFlight, bars.heightFractionAt(0, 0f), 1e-6f)
        assertEquals(1f, bars.heightFractionAt(0, 1f), 1e-6f)
    }

    @Test fun theTintTurnsAtHalfTheRollingPeak() {
        val bars = HistogramBars(slots)
        bars.retarget(window(490L, 500L, 1_000L, 1_000L), 1f)
        assertEquals(0f, bars.tintBlendAt(0, 1f), 1e-6f)
        assertEquals(1f, bars.tintBlendAt(1, 1f), 1e-6f)
    }

    @Test fun aClearedWindowEmptiesTheRowAndTheBarsSnapBackIn() {
        val bars = HistogramBars(slots)
        bars.retarget(window(1_000L, 1_000L, 1_000L, 1_000L), 1f)
        assertEquals(0, bars.emptySlots)

        // A new cast must not inherit the previous film's bars, and must not draw
        // the row emptying out either.
        bars.retarget(window(), 1f)
        assertEquals(slots, bars.emptySlots)
        bars.retarget(window(700L), 1f)
        assertEquals(1f, bars.heightFractionAt(slots - 1, 0f), 1e-6f)
    }

    @Test fun aWindowThatOnlyFillsANewSlotHasNothingToAnimate() {
        val bars = HistogramBars(slots)
        assertFalse(bars.retarget(window(1_000L), 1f))
        assertFalse(bars.retarget(window(1_000L, 1_000L), 1f))
        assertTrue(bars.retarget(window(1_000L, 1_000L, 500L), 1f))
    }

    @Test fun theBarsTileTheRowExactlyAsTheWeightedRowDid() {
        // The panel's content box at StreamMetricsPanelWidth, and the 2.5 dp gap.
        val width = 454f
        val gap = 2.5f
        val pitch = histogramBarPitch(width, gap, 40)
        assertEquals((width - 39 * gap) / 40f, pitch - gap, 1e-4f)
        // Forty bars and thirty-nine gaps land the last right edge on the width.
        assertEquals(width, 39 * pitch + (pitch - gap), 1e-3f)
    }
}
