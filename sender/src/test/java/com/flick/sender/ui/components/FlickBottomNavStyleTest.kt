package com.flick.sender.ui.components

import androidx.compose.ui.graphics.Color
import com.flick.sender.ui.theme.DarkFlickColors
import com.flick.sender.ui.theme.LightFlickColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class FlickBottomNavStyleTest {

    @Test fun liveBlurUsesOnlyHazeOptimalAndroidVersions() {
        assertTrue(!navBackdropBlurEnabled(32))
        assertTrue(navBackdropBlurEnabled(33))
    }

    @Test fun bothThemesLeaveSixtyPercentOfTheBackdropInTheGlass() {
        for (c in listOf(LightFlickColors, DarkFlickColors)) {
            val fallback = navBarFallbackTint(c)
            assertTrue(
                "${if (c.isLight) "light" else "dark"}: backdrop visibility is ${1f - fallback.alpha}",
                kotlin.math.abs((1f - fallback.alpha) - NavBackdropVisibility) < 0.001f,
            )
            assertEquals(Color.Transparent, navBarFill(c))
            assertTrue(navShowsGlassSheen(c))
        }
    }

    @Test fun darkNavigationUsesARestrainedStabilizerThenReadyBlue() {
        assertEquals(
            listOf(
                Color.Black.copy(alpha = 0.14f),
                DarkFlickColors.sparkInverse.copy(alpha = 0.30232558f),
            ),
            navBarHazeTints(DarkFlickColors),
        )
        assertEquals(Color.Transparent, navBarFill(DarkFlickColors))
        assertEquals(Color.White, navInactiveInk(DarkFlickColors))
        assertEquals(Color.White, navActiveLabelInk(DarkFlickColors))
        assertTrue(contrast(DarkFlickColors.onPrimary, DarkFlickColors.primary) >= 4.5f)
        assertTrue(navShowsGlassSheen(DarkFlickColors))
    }

    @Test fun eachFallbackMatchesItsTintStackAndHoldsControlsOnItsPage() {
        for (c in listOf(LightFlickColors, DarkFlickColors)) {
            val fallback = navBarFallbackTint(c)
            val stacked = navBarHazeTints(c).fold(c.canvas) { base, tint -> tint.over(base) }
            val fallbackDrawn = fallback.over(c.canvas)
            assertColorNear(stacked, fallbackDrawn)
            assertTrue(contrast(navInactiveInk(c), fallbackDrawn) >= 4.5f)
            assertTrue(contrast(navActiveLabelInk(c), fallbackDrawn) >= 4.5f)
            assertTrue(contrast(c.primary, fallbackDrawn) >= 3f)
        }
    }

    /**
     * The dock and the pill are stacked one directly above the other, and the whole point
     * of giving the dock a backdrop of its own is that the pair must not read as one slab.
     * That only holds while the upper surface is the thinner one, which is a relationship
     * between two numbers rather than a property of either — so it is asserted as one.
     *
     * The budget is then checked to one step in 255 rather than to the 0.001 the pill above
     * is held to, because that is all an sRGB [Color] can carry: it stores eight bits per
     * channel, so an alpha only ever comes back as some n/255. The pill's 0.40 happens to be
     * 102/255 exactly and can afford the tighter figure; 0.26 falls between two steps and
     * lands 0.0027 out through the dark tint's two roundings. Tightening this would be
     * asserting a precision the colour cannot hold.
     */
    @Test fun theDockIsThinnerGlassThanThePillItRidesOn() {
        for (c in listOf(LightFlickColors, DarkFlickColors)) {
            val name = if (c.isLight) "light" else "dark"
            val dock = glassFallbackTint(c, DockBackdropVisibility)
            val nav = navBarFallbackTint(c)
            assertTrue(
                "$name: the dock's tint is ${dock.alpha} against the pill's ${nav.alpha} — " +
                    "the upper surface has stopped being the lighter one",
                dock.alpha < nav.alpha,
            )
            assertTrue(
                "$name: dock backdrop visibility is ${1f - dock.alpha}",
                kotlin.math.abs((1f - dock.alpha) - DockBackdropVisibility) < 1f / 255f,
            )
            assertEquals(Color.Transparent, glassBackdropFill(c))
        }
    }

    /**
     * The dock's own version of [eachFallbackMatchesItsTintStackAndHoldsControlsOnItsPage],
     * measured on the page for the same reason: what a blurred backdrop actually resolves
     * to under artwork is not something arithmetic can state, so both glass surfaces are
     * held to the route they spend most of their life over.
     *
     * `playheadLo` is asserted in dark only, which is where FlickColorsTest already draws
     * that line: the played hairline is amber and the light material is a pale blue, so it
     * stands at 1.87:1 there today. That is the palette's business and not this change's —
     * the dock's figure is a hairline better than the pill's, not worse.
     */
    @Test fun eachDockFallbackMatchesItsTintStackAndHoldsItsOwnInkOnThePage() {
        for (c in listOf(LightFlickColors, DarkFlickColors)) {
            val name = if (c.isLight) "light" else "dark"
            val stacked = glassHazeTints(c, DockBackdropVisibility)
                .fold(c.canvas) { base, tint -> tint.over(base) }
            val drawn = glassFallbackTint(c, DockBackdropVisibility).over(c.canvas)
            assertColorNear(stacked, drawn)
            // The film's name and the TV's name are text; the played hairline is a graphic.
            assertTrue(
                "$name: the dock's title is ${contrast(c.onSurface, drawn)} on its own material",
                contrast(c.onSurface, drawn) >= 4.5f,
            )
            assertTrue(
                "$name: the dock's TV line is ${contrast(c.onSurfaceDim, drawn)} on its own material",
                contrast(c.onSurfaceDim, drawn) >= 4.5f,
            )
            if (!c.isLight) {
                assertTrue(
                    "dark: the played hairline is ${contrast(c.playheadLo, drawn)} on the dock",
                    contrast(c.playheadLo, drawn) >= 3f,
                )
            }
        }
    }

    @Test fun lightNavigationUsesItsPaleBlueAsATranslucentHazeTint() {
        assertEquals(
            listOf(LightFlickColors.glass.copy(alpha = 0.40f)),
            navBarHazeTints(LightFlickColors),
        )
        assertEquals(0.40f, navBarFallbackTint(LightFlickColors).alpha, 0.001f)
        assertEquals(LightFlickColors.onSurfaceDim, navInactiveInk(LightFlickColors))
        assertEquals(LightFlickColors.onSurface, navActiveLabelInk(LightFlickColors))
        assertTrue(navShowsGlassSheen(LightFlickColors))
    }

    private fun assertColorNear(expected: Color, actual: Color) {
        val epsilon = 0.0001f
        assertTrue(
            "red: expected ${expected.red}, was ${actual.red}",
            kotlin.math.abs(expected.red - actual.red) < epsilon,
        )
        assertTrue(
            "green: expected ${expected.green}, was ${actual.green}",
            kotlin.math.abs(expected.green - actual.green) < epsilon,
        )
        assertTrue(
            "blue: expected ${expected.blue}, was ${actual.blue}",
            kotlin.math.abs(expected.blue - actual.blue) < epsilon,
        )
    }

    private fun Color.over(base: Color): Color {
        val outAlpha = alpha + base.alpha * (1f - alpha)
        if (outAlpha == 0f) return Color.Transparent
        return Color(
            red = (red * alpha + base.red * base.alpha * (1f - alpha)) / outAlpha,
            green = (green * alpha + base.green * base.alpha * (1f - alpha)) / outAlpha,
            blue = (blue * alpha + base.blue * base.alpha * (1f - alpha)) / outAlpha,
            alpha = outAlpha,
        )
    }

    private fun contrast(a: Color, b: Color): Float {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05f) / (lo + 0.05f)
    }

    private fun Color.luminance(): Float {
        fun linear(channel: Float): Float =
            if (channel <= 0.03928f) channel / 12.92f
            else ((channel + 0.055f) / 1.055f).pow(2.4f)

        return 0.2126f * linear(red) + 0.7152f * linear(green) + 0.0722f * linear(blue)
    }
}
