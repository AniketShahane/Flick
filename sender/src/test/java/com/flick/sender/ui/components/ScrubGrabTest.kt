package com.flick.sender.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A grabbed thumb follows the finger from where it was taken, without jumping, and still
 * reaches both ends of the bar however far off-centre it was grabbed.
 */
class ScrubGrabTest {

    @Test fun aTouchNearTheThumbIsAGrab() {
        assertTrue(ScrubGrab.isThumbGrab(downX = 520f, thumbX = 500f, grabRadiusPx = 24f))
        assertTrue(ScrubGrab.isThumbGrab(downX = 476f, thumbX = 500f, grabRadiusPx = 24f))
        assertFalse(ScrubGrab.isThumbGrab(downX = 525f, thumbX = 500f, grabRadiusPx = 24f))
    }

    @Test fun theAnchorIsWhereTheSlopWasCrossed() {
        assertEquals(516f, ScrubGrab.anchorX(500f, 540f, 16f, W), 0f)
        assertEquals(484f, ScrubGrab.anchorX(500f, 400f, 16f, W), 0f)
        assertEquals(W, ScrubGrab.anchorX(W - 4f, W, 16f, W), 0f)
        assertEquals(0f, ScrubGrab.anchorX(4f, 0f, 16f, W), 0f)
    }

    @Test fun thereIsNoJumpAtTheAnchor() {
        for ((ax, af) in ANCHORS) {
            assertEquals("ax=$ax af=$af", af, ScrubGrab.fraction(ax, ax, af, W), 1e-6f)
            val after = ScrubGrab.fraction(ax + 1f, ax, af, W)
            val before = ScrubGrab.fraction(ax - 1f, ax, af, W)
            assertTrue("ax=$ax af=$af after=$after", after - af in 0f..0.01f)
            assertTrue("ax=$ax af=$af before=$before", af - before in 0f..0.01f)
        }
    }

    @Test fun bothEndsAreExactlyReachable() {
        for ((ax, af) in ANCHORS) {
            assertEquals("ax=$ax af=$af", 0f, ScrubGrab.fraction(0f, ax, af, W), 0f)
            assertEquals("ax=$ax af=$af", 1f, ScrubGrab.fraction(W, ax, af, W), 0f)
            assertEquals("ax=$ax af=$af", 0f, ScrubGrab.fraction(-50f, ax, af, W), 0f)
            assertEquals("ax=$ax af=$af", 1f, ScrubGrab.fraction(W + 50f, ax, af, W), 0f)
        }
    }

    @Test fun theMapRisesMonotonicallyAcrossTheBar() {
        for ((ax, af) in ANCHORS + DEGENERATE) {
            var prev = -1f
            var x = 0f
            while (x <= W) {
                val f = ScrubGrab.fraction(x, ax, af, W)
                assertTrue("ax=$ax af=$af x=$x f=$f prev=$prev", f >= prev && f in 0f..1f)
                prev = f
                x += 0.25f
            }
        }
    }

    /** A thumb grabbed from its right near the end of a film: the end must still be there. */
    @Test fun aThumbNearTheEndGrabbedFromItsRightReachesTheEnd() {
        val af = 0.995f
        val ax = af * W + 20f
        assertEquals(1f, ScrubGrab.fraction(W, ax, af, W), 0f)
        assertTrue(ScrubGrab.fraction(W - 1f, ax, af, W) < 1f)
    }

    @Test fun degenerateAnchorsDoNotDivideByNothing() {
        for ((ax, af) in DEGENERATE) {
            for (x in floatArrayOf(0f, 0.25f, 0.5f, 1f, W / 2f, W - 1f, W - 0.5f, W)) {
                val f = ScrubGrab.fraction(x, ax, af, W)
                assertTrue("ax=$ax af=$af x=$x f=$f", !f.isNaN() && f in 0f..1f)
            }
            assertEquals(0f, ScrubGrab.fraction(0f, ax, af, W), 0f)
            assertEquals(1f, ScrubGrab.fraction(W, ax, af, W), 0f)
        }
    }

    private companion object {
        const val W = 1000f

        /** Anchors at 0.0, 0.5 and 0.995, each grabbed from the thumb's left and its right. */
        val ANCHORS: List<Pair<Float, Float>> = listOf(0f, 0.5f, 0.995f).flatMap { af ->
            listOf(af * W - 20f, af * W + 20f)
                .map { it.coerceIn(0f, W) }
                .filter { it > 1f && it < W - 1f }
                .map { it to af }
        } + listOf(20f to 0f, 30f to 0f, 480f to 0.5f, 520f to 0.5f, 975f to 0.995f, 990f to 0.995f)

        val DEGENERATE: List<Pair<Float, Float>> = listOf(
            0f to 0f, 0.5f to 0f, 0.5f to 0.02f, 1f to 0.01f,
            W to 1f, W - 0.5f to 0.995f, W - 1f to 0.99f, W to 0.97f,
        )
    }
}
