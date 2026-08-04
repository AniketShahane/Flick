package com.flick.sender.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleGridTest {

    @Test fun everySampleLandsInsideTheFrame() {
        // Whatever the grid is inset by, a coordinate outside the bitmap is an exception on
        // a background thread rather than a black tile.
        var extent = 1
        while (extent <= 4_096) {
            for (count in listOf(SampleRows, SampleColumns)) {
                for (position in sampleAxis(extent, count)) {
                    assertTrue("$extent/$count -> $position", position in 0 until extent)
                }
            }
            extent = extent * 2 + 1
        }
    }

    @Test fun theEdgesOfTheFrameAreNeverRead() {
        // An eighth at each end belongs to whatever bars the file was encoded with, and
        // nothing in that band is allowed to contribute to a judgement.
        for (extent in listOf(288, 448, 512, 1_080, 1_920, 2_160)) {
            val inset = extent / 8
            for (count in listOf(SampleRows, SampleColumns)) {
                val positions = sampleAxis(extent, count)
                assertTrue("$extent/$count -> ${positions.first()}", positions.first() >= inset)
                assertTrue("$extent/$count -> ${positions.last()}", positions.last() < extent - inset)
            }
        }
    }

    @Test fun theGridWalksTheInteriorInOrder() {
        val positions = sampleAxis(1_080, SampleRows)
        for (i in 1 until positions.size) {
            assertTrue("${positions.toList()}", positions[i] > positions[i - 1])
        }
    }

    @Test fun theBarsOfAScopeFilmFallOutsideTheGrid() {
        // 2.39:1 letterboxed into 16:9 — the shape most films this app plays arrive in.
        val height = 1_080
        val bar = letterboxBar(height)
        val rows = sampleAxis(height, SampleRows)
        assertTrue("${rows.first()} vs $bar", rows.first() >= bar)
        assertTrue("${rows.last()} vs ${height - bar}", rows.last() < height - bar)
    }

    @Test fun theBarsOfAPillarboxedFilmFallOutsideTheGrid() {
        // 4:3 pillarboxed into 16:9 — the other padding a library holds, and the shape the
        // inset is sized against on the horizontal axis.
        val width = 1_920
        val bar = pillarboxBar(width)
        val columns = sampleAxis(width, SampleColumns)
        assertTrue("${columns.first()} vs $bar", columns.first() >= bar)
        assertTrue("${columns.last()} vs ${width - bar}", columns.last() < width - bar)
    }

    @Test fun aFlatCardBehindLetterboxBarsIsBlank() {
        // The reading this grid exists to remove: over the whole frame a third of the rows
        // land in the bars, and the bars alone manufacture a spread that carries a slate
        // past the uniformity floor. mean 85, spread 57 — both floors cleared by a picture
        // of nothing.
        val card = letterboxed(288) { _, _ -> 128 }
        assertFalse(frameStats(fullExtent(512, 288, card)).blank)
        assertTrue(frameStats(interior(512, 288, card)).blank)
    }

    @Test fun aFlatCardBehindPillarboxBarsIsBlank() {
        // The same manufactured spread on the other axis, which is what a film padded into
        // a frame wider than itself produces.
        val card = pillarboxed(512) { _, _ -> 128 }
        assertFalse(frameStats(fullExtent(512, 288, card)).blank)
        assertTrue(frameStats(interior(512, 288, card)).blank)
    }

    @Test fun aFadeToBlackIsStillCaught() {
        // The failure the whole judgement exists for. Its interior is as black as its
        // edges, so reading less of the frame costs nothing here.
        val fade = { _: Int, _: Int -> 0 }
        assertTrue(frameStats(interior(512, 288, fade)).blank)
        assertTrue(frameStats(interior(512, 288, letterboxed(288, fade))).blank)
    }

    @Test fun aLetterboxedSceneIsStillAPicture() {
        // The other half of the change: the inset must not swallow so much of the frame
        // that a real scene stops reading as one.
        val scene = letterboxed(288) { x, _ -> x * 255 / 512 }
        assertFalse(frameStats(interior(512, 288, scene)).blank)
    }

    // The grid as it is laid now: over the frame's interior.
    private fun interior(width: Int, height: Int, frame: (Int, Int) -> Int): IntArray =
        sampled(sampleAxis(width, SampleColumns), sampleAxis(height, SampleRows), frame)

    // The grid as it was laid before: evenly over the frame's whole extent, bars included.
    private fun fullExtent(width: Int, height: Int, frame: (Int, Int) -> Int): IntArray =
        sampled(fullExtentAxis(width, SampleColumns), fullExtentAxis(height, SampleRows), frame)

    private fun sampled(columns: IntArray, rows: IntArray, frame: (Int, Int) -> Int): IntArray =
        IntArray(SampleRows * SampleColumns) { i ->
            frame(columns[i % SampleColumns], rows[i / SampleColumns])
        }

    private fun fullExtentAxis(extent: Int, count: Int) =
        IntArray(count) { index -> (2 * index + 1) * extent / (2 * count) }

    private fun letterboxed(height: Int, frame: (Int, Int) -> Int): (Int, Int) -> Int {
        val bar = letterboxBar(height)
        return { x, y -> if (y < bar || y >= height - bar) 0 else frame(x, y) }
    }

    private fun pillarboxed(width: Int, frame: (Int, Int) -> Int): (Int, Int) -> Int {
        val bar = pillarboxBar(width)
        return { x, y -> if (x < bar || x >= width - bar) 0 else frame(x, y) }
    }

    // 2.39:1 inside 16:9: the picture keeps 16/9 ÷ 2.39 of the height and the rest is black.
    private fun letterboxBar(height: Int) = (height - height * 1_778 / 2_390) / 2

    // 4:3 inside 16:9: the picture keeps three quarters of the width.
    private fun pillarboxBar(width: Int) = (width - width * 3 / 4) / 2
}
