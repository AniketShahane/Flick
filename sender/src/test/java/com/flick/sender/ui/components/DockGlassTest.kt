package com.flick.sender.ui.components

import com.flick.sender.ui.theme.DarkFlickColors
import com.flick.sender.ui.theme.LightFlickColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DockGlassTest {

    @Test fun theBarGoesFlatOnlyForAFlightItCanBlurThrough() {
        assertEquals(DockGlassFlat, dockGlassTarget(morphing = true, blurEnabled = true), 0f)
        assertEquals(DockGlassBlurred, dockGlassTarget(morphing = false, blurEnabled = true), 0f)
        // Below the blur floor the style's fallback tint is already the flat slab.
        assertEquals(DockGlassBlurred, dockGlassTarget(morphing = true, blurEnabled = false), 0f)
        assertEquals(DockGlassBlurred, dockGlassTarget(morphing = false, blurEnabled = false), 0f)
    }

    @Test fun theFlatTintIsTheWholeMaterialAtOneEndAndNothingAtTheOther() {
        for (c in listOf(LightFlickColors, DarkFlickColors)) {
            val tint = glassFallbackTint(c, DockBackdropVisibility).alpha
            assertEquals(tint, dockFlatTintAlpha(tint, DockGlassFlat), 1e-6f)
            assertEquals(0f, dockFlatTintAlpha(tint, DockGlassBlurred), 1e-6f)
        }
    }

    @Test fun theMaterialKeepsItsWeightThroughTheWholeFade() {
        // Flat tint at t over a blur layer at g that carries the same tint at a: the tint
        // covers t + (1 - t)·g·a of the backdrop, and that must stay a all the way across,
        // or the bar visibly thins in the middle of the fade.
        for (c in listOf(LightFlickColors, DarkFlickColors)) {
            val a = glassFallbackTint(c, DockBackdropVisibility).alpha
            for (step in 0..20) {
                val g = step / 20f
                val t = dockFlatTintAlpha(a, g)
                assertEquals("glass=$g", a, t + (1f - t) * g * a, 1e-5f)
            }
        }
    }

    @Test fun theFlatTintOnlyEverRecedesAsTheGlassComesIn() {
        val a = glassFallbackTint(DarkFlickColors, DockBackdropVisibility).alpha
        var previous = Float.MAX_VALUE
        for (step in 0..20) {
            val t = dockFlatTintAlpha(a, step / 20f)
            assertTrue("step $step: $t after $previous", t <= previous)
            previous = t
        }
    }

    @Test fun aSpringPastEitherEndIsClamped() {
        val a = 0.26f
        assertEquals(a, dockFlatTintAlpha(a, -0.1f), 1e-6f)
        assertEquals(0f, dockFlatTintAlpha(a, 1.1f), 1e-6f)
    }

    @Test fun anOpaqueTintNeverDividesByZero() {
        assertEquals(0f, dockFlatTintAlpha(1f, 1f), 0f)
        assertEquals(1f, dockFlatTintAlpha(1f, 0.5f), 1e-6f)
    }

    /** The route flip's own frame has nothing in the air yet, so it is no hand-back. */
    @Test fun theWaitOutlastsTheFrameBeforeTheFlightBegins() {
        assertTrue(dockAwaitingFlight(transitionActive = false, morphing = true))
        assertFalse(dockAwaitingFlight(transitionActive = true, morphing = true))
        // The latch released first: there is nothing left to wait for.
        assertFalse(dockAwaitingFlight(transitionActive = false, morphing = false))
    }

    /** Landing hands the bar back, and the latch is the fallback for a landing never seen. */
    @Test fun theBarComesBackOnLandingOrOnTheLatch() {
        assertFalse(dockHandedBack(transitionActive = true, morphing = true))
        assertTrue(dockHandedBack(transitionActive = false, morphing = true))
        assertTrue(dockHandedBack(transitionActive = true, morphing = false))
        assertTrue(dockHandedBack(transitionActive = false, morphing = false))
    }
}
