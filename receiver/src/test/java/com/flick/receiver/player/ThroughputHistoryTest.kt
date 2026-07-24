package com.flick.receiver.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThroughputHistoryTest {

    private fun ring(capacity: Int) = ThroughputHistory(capacity = capacity, samplesPerSlot = 1)

    @Test fun emptyHistoryReportsNothingRatherThanZeroBars() {
        val history = ThroughputHistory()

        assertTrue(history.isEmpty)
        assertEquals(0, history.size)
        assertEquals(0L, history.peakBps)
        assertEquals(0L, history.latestBps)

        val snapshot = history.snapshot()
        assertTrue(snapshot.isEmpty)
        assertEquals(emptyList<Long>(), snapshot.samplesBps)
        assertEquals(0L, snapshot.peakBps)
        assertEquals(ThroughputHistory.CAPACITY, snapshot.capacity)
        assertEquals(0f, snapshot.ratioAt(0), 0f)
    }

    @Test fun windowIsSizedToTheFortyBarHistogram() {
        assertEquals(40, ThroughputHistory.CAPACITY)
        assertEquals(40, ThroughputHistory().capacity)
        assertEquals(ThroughputHistory.CAPACITY, ThroughputSnapshot.EMPTY.capacity)
    }

    @Test fun samplesReadOldestToNewest() {
        val history = ring(capacity = 4)
        history.append(10L)
        history.append(20L)
        history.append(30L)

        assertEquals(listOf(10L, 20L, 30L), history.snapshot().samplesBps)
        assertEquals(30L, history.latestBps)
        assertFalse(history.isEmpty)
    }

    @Test fun ringWrapsAndKeepsOnlyTheMostRecentWindow() {
        val history = ring(capacity = 4)
        (1..6).forEach { history.append(it * 100L) }

        val snapshot = history.snapshot()
        assertEquals(4, snapshot.size)
        assertEquals(listOf(300L, 400L, 500L, 600L), snapshot.samplesBps)
        assertEquals(600L, snapshot.latestBps)
    }

    @Test fun peakFallsWhenTheBarHoldingItAgesOut() {
        val history = ring(capacity = 3)
        history.append(900L)
        history.append(100L)
        history.append(200L)
        assertEquals(900L, history.peakBps)

        history.append(300L)

        assertEquals(listOf(100L, 200L, 300L), history.snapshot().samplesBps)
        assertEquals(300L, history.peakBps)
    }

    @Test fun ratiosAreMeasuredAgainstTheRollingPeak() {
        val history = ring(capacity = 4)
        history.append(0L)
        history.append(50L)
        history.append(100L)

        val snapshot = history.snapshot()
        assertEquals(100L, snapshot.peakBps)
        assertEquals(0f, snapshot.ratioAt(0), 0.0001f)
        assertEquals(0.5f, snapshot.ratioAt(1), 0.0001f)
        assertEquals(1f, snapshot.ratioAt(2), 0.0001f)
        // Out of range never invents a bar.
        assertEquals(0f, snapshot.ratioAt(3), 0f)
        assertEquals(0f, snapshot.ratioAt(-1), 0f)
    }

    @Test fun negativeEstimatesClampToZeroInsteadOfDraggingTheMeanDown() {
        val history = ring(capacity = 2)
        history.append(-1L)
        history.append(Long.MIN_VALUE)

        assertEquals(listOf(0L, 0L), history.snapshot().samplesBps)
        assertEquals(0L, history.peakBps)
        assertEquals(0f, history.snapshot().ratioAt(0), 0f)
    }

    @Test fun twoDiagnosticsTicksFoldIntoOneBarSoTheWindowSpansFortySeconds() {
        val history = ThroughputHistory(capacity = 3, samplesPerSlot = 2)

        history.append(100L)
        // The open bar is visible immediately rather than leaving the panel blank.
        assertEquals(listOf(100L), history.snapshot().samplesBps)

        history.append(300L)
        assertEquals(listOf(200L), history.snapshot().samplesBps)

        history.append(50L)
        assertEquals(listOf(200L, 50L), history.snapshot().samplesBps)
        assertEquals(200L, history.peakBps)

        history.append(50L)
        assertEquals(listOf(200L, 50L), history.snapshot().samplesBps)
    }

    @Test fun defaultCadenceFillsTheWindowInEightySamples() {
        val history = ThroughputHistory()
        repeat(2 * ThroughputHistory.CAPACITY) { history.append(1_000L) }

        assertEquals(ThroughputHistory.CAPACITY, history.size)

        history.append(1_000L)
        assertEquals(ThroughputHistory.CAPACITY, history.size)
    }

    @Test fun snapshotIsAFrozenCopy() {
        val history = ring(capacity = 3)
        history.append(10L)
        val taken = history.snapshot()

        history.append(20L)

        assertEquals(listOf(10L), taken.samplesBps)
        assertEquals(10L, taken.peakBps)
        assertEquals(listOf(10L, 20L), history.snapshot().samplesBps)
    }

    @Test fun clearDropsThePreviousFilmsBars() {
        val history = ring(capacity = 3)
        history.append(500L)
        history.append(700L)

        history.clear()

        assertTrue(history.isEmpty)
        assertEquals(0L, history.peakBps)
        assertEquals(0L, history.latestBps)
        assertEquals(emptyList<Long>(), history.snapshot().samplesBps)
    }
}
