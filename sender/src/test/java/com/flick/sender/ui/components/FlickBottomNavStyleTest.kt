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
