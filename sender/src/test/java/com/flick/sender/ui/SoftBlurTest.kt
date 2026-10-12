package com.flick.sender.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftBlurTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
    private fun red(p: Int) = (p shr 16) and 0xFF
    private fun alpha(p: Int) = p ushr 24

    @Test fun aFlatImageStaysExactlyFlat() {
        val colour = argb(255, 12, 140, 250)
        val out = SoftBlur.blur(IntArray(12 * 7) { colour }, 12, 7, radius = 3)
        assertTrue(out.all { it == colour })
    }

    @Test fun radiusZeroIsACopyNotTheSameArray() {
        val input = IntArray(9) { argb(255, it * 20, 0, 0) }
        val out = SoftBlur.blur(input, 3, 3, radius = 0)
        assertArrayEquals(input, out)
        assertTrue(out !== input)
    }

    @Test fun theInputIsNeverWrittenTo() {
        val input = IntArray(25) { if (it == 12) argb(255, 255, 255, 255) else argb(255, 0, 0, 0) }
        val before = input.copyOf()
        SoftBlur.blur(input, 5, 5, radius = 2)
        assertArrayEquals(before, input)
    }

    @Test fun aPointSpreadsSymmetricallyAndPeaksWhereItWas() {
        val w = 21
        val h = 21
        val input = IntArray(w * h) { argb(255, 0, 0, 0) }
        input[10 * w + 10] = argb(255, 255, 255, 255)
        val out = SoftBlur.blur(input, w, h, radius = 2, passes = 1)
        val centre = red(out[10 * w + 10])
        assertTrue(centre > 0)
        for (d in 1..4) {
            assertEquals("left/right $d", red(out[10 * w + 10 - d]), red(out[10 * w + 10 + d]))
            assertEquals("up/down $d", red(out[(10 - d) * w + 10]), red(out[(10 + d) * w + 10]))
            assertTrue(red(out[10 * w + 10 + d]) <= centre)
        }
        // One pass of a 5-wide box reaches exactly two pixels out and no further.
        assertEquals(0, red(out[10 * w + 13]))
    }

    @Test fun edgesClampRatherThanFadingToTransparent() {
        val colour = argb(255, 200, 100, 50)
        val out = SoftBlur.blur(IntArray(4 * 4) { colour }, 4, 4, radius = 6)
        assertTrue(out.all { alpha(it) == 255 && red(it) == 200 })
    }

    @Test fun aStepBlursMonotonically() {
        val w = 30
        val input = IntArray(w) { if (it < w / 2) argb(255, 0, 0, 0) else argb(255, 255, 0, 0) }
        val out = SoftBlur.blur(input, w, 1, radius = 3)
        for (x in 1 until w) assertTrue("x=$x", red(out[x]) >= red(out[x - 1]))
        assertEquals(0, red(out[0]))
        assertEquals(255, red(out[w - 1]))
    }

    @Test fun emptyAndSinglePixelImagesAreSafe() {
        assertEquals(0, SoftBlur.blur(IntArray(0), 0, 0, radius = 4).size)
        val one = argb(128, 1, 2, 3)
        assertArrayEquals(intArrayOf(one), SoftBlur.blur(intArrayOf(one), 1, 1, radius = 4))
    }

    @Test fun aLandscapeImageIsCroppedToAPortraitBoxFromItsTop() {
        // 780 × 439 covering 200 × 433: scaled by the height, centred across, top aligned.
        val (w, h, left, top) = SoftBlur.coverWindow(780, 439, 200, 433, biasY = 0f)
        assertEquals(433, h)
        assertEquals(769, w)
        assertEquals((769 - 200) / 2, left)
        assertEquals(0, top)
    }

    @Test fun aPortraitImageIsCroppedToALandscapeBoxAtItsCentre() {
        val (w, h, left, top) = SoftBlur.coverWindow(342, 513, 72, 49, biasY = 0.5f)
        assertEquals(72, w)
        assertEquals(108, h)
        assertEquals(0, left)
        // 59 rows to spare, split at the middle and rounded half up.
        assertEquals(30, top)
    }

    @Test fun theWindowAlwaysFitsInsideTheScaledImage() {
        for ((iw, ih) in listOf(1 to 1, 1000 to 3, 3 to 1000, 640 to 360, 333 to 777)) {
            for ((bw, bh) in listOf(200 to 433, 72 to 49, 200 to 112, 1 to 1)) {
                val (w, h, left, top) = SoftBlur.coverWindow(iw, ih, bw, bh, biasY = 0.3f)
                assertTrue(left >= 0 && top >= 0)
                assertTrue("$iw×$ih in $bw×$bh", left + bw <= w && top + bh <= h)
            }
        }
    }
}
