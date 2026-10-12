package com.flick.sender.ui.components

import com.flick.sender.ui.theme.DarkFlickColors
import com.flick.sender.ui.theme.LightFlickColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DockGlassTest {

    private val tints = listOf(
        glassFallbackTint(DarkFlickColors, DockBackdropVisibility).alpha,
        glassFallbackTint(LightFlickColors, DockBackdropVisibility).alpha,
        0f,
        0.5f,
    )
    private val steps = (0..100).map { it / 100f }

    @Test fun smoothstepIsFlatAtBothEndsAndHalfwayAtTheMiddle() {
        assertEquals(0f, smoothstep(0.2f, 0.6f, 0.1f), 0f)
        assertEquals(0f, smoothstep(0.2f, 0.6f, 0.2f), 0f)
        assertEquals(0.5f, smoothstep(0.2f, 0.6f, 0.4f), 1e-6f)
        assertEquals(1f, smoothstep(0.2f, 0.6f, 0.6f), 0f)
        assertEquals(1f, smoothstep(0.2f, 0.6f, 2f), 0f)
        for (i in 1 until steps.size) {
            assertTrue(smoothstep(0f, 1f, steps[i]) >= smoothstep(0f, 1f, steps[i - 1]))
        }
    }

    @Test fun growthIsMeasuredFromTheBarAndClamped() {
        assertEquals(0f, dockGrowth(50f, restPx = 66f, spanPx = 400f), 0f)
        assertEquals(0f, dockGrowth(66f, restPx = 66f, spanPx = 400f), 0f)
        assertEquals(0.5f, dockGrowth(266f, restPx = 66f, spanPx = 400f), 1e-6f)
        assertEquals(1f, dockGrowth(2000f, restPx = 66f, spanPx = 400f), 0f)
        // A degenerate span never divides by zero.
        assertEquals(1f, dockGrowth(67f, restPx = 66f, spanPx = 0f), 0f)
    }

    @Test fun atRestTheBarIsExactlyTheGlassItRidesWith() {
        assertEquals(1f, dockGlass(0f), 0f)
        assertEquals(1f, dockEdges(0f), 0f)
        assertEquals(0f, dockFillHue(0f), 0f)
        for (a in tints) assertEquals(0f, dockFillAlpha(a, 0f), 0f)
        assertTrue(dockBlurEnabled(available = true, p = 0f))
    }

    @Test fun theGlassIsGoneEarlyAndTheBlurStandsDownWithIt() {
        assertEquals(0f, dockGlass(DockGlassGoneAt), 0f)
        assertFalse(dockBlurEnabled(available = true, p = DockGlassGoneAt))
        assertFalse(dockBlurEnabled(available = true, p = 1f))
        for (p in steps) assertFalse(dockBlurEnabled(available = false, p = p))
        for (i in 1 until steps.size) assertTrue(dockGlass(steps[i]) <= dockGlass(steps[i - 1]))
    }

    @Test fun haloAndFillTogetherHideExactlyTheCoverage() {
        for (a in tints) for (p in steps) {
            val fill = dockFillAlpha(a, p)
            assertEquals("a=$a p=$p", dockCoverage(a, p), a + (1f - a) * fill, 1e-5f)
        }
    }

    @Test fun coverageHoldsOnceTheGlassHasGone() {
        for (a in tints) for (p in steps.filter { it >= DockGlassGoneAt }) {
            assertEquals(DockFlightCoverage, dockCoverage(a, p), 1e-6f)
        }
    }

    @Test fun coverageNeverDropsAsTheBarGrows() {
        for (a in tints) for (i in 1 until steps.size) {
            assertTrue(dockCoverage(a, steps[i]) >= dockCoverage(a, steps[i - 1]) - 1e-6f)
        }
    }

    @Test fun theHueTurnsOnlyAfterTheGlassHasMostlyGone() {
        assertEquals(0f, dockFillHue(DockHueFrom), 0f)
        assertEquals(1f, dockFillHue(DockHueTo), 0f)
        assertEquals(1f, dockFillHue(1f), 0f)
    }

    @Test fun edgesFadeOnlyOnTheLastStretch() {
        assertEquals(1f, dockEdges(DockEdgesFrom), 0f)
        assertEquals(0f, dockEdges(1f), 0f)
        for (i in 1 until steps.size) assertTrue(dockEdges(steps[i]) <= dockEdges(steps[i - 1]))
    }

    @Test fun anOpaqueTintNeedsNoFill() {
        assertEquals(0f, dockFillAlpha(1f, 0.5f), 0f)
    }
}
