package com.flick.sender.ui.components

import androidx.compose.ui.graphics.Color
import com.flick.sender.ui.theme.DarkFlickColors
import com.flick.sender.ui.theme.LightFlickColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class FlickBottomNavStyleTest {

    @Test fun darkNavigationCompositesItsHazeTintsInTheDrawOrder() {
        val c = DarkFlickColors
        val backdrops = listOf(
            "canvas" to c.canvas,
            "black still" to Color.Black,
            "white still" to Color.White,
        )

        for ((name, backdrop) in backdrops) {
            val drawn = navBarHazeTints(c).fold(backdrop) { base, tint -> tint.over(base) }
            assertTrue(
                "$name: inactive nav ink is ${contrast(navInactiveInk(c), drawn)} on the drawn glass",
                contrast(navInactiveInk(c), drawn) >= 4.5f,
            )
            assertTrue(
                "$name: active label ink is ${contrast(navActiveLabelInk(c), drawn)} on the glass",
                contrast(navActiveLabelInk(c), drawn) >= 4.5f,
            )
            assertTrue(
                "$name: gold selection is ${contrast(c.primary, drawn)} against the drawn glass",
                contrast(c.primary, drawn) >= 3f,
            )
        }
    }

    @Test fun darkNavigationUsesTranslucentStabilizerThenReadyBlue() {
        assertEquals(
            listOf(
                Color.Black.copy(alpha = 0.40f),
                DarkFlickColors.sparkInverse.copy(alpha = 0.60f),
            ),
            navBarHazeTints(DarkFlickColors),
        )
        assertEquals(Color.Transparent, navBarFill(DarkFlickColors))
        assertEquals(Color.White, navInactiveInk(DarkFlickColors))
        assertEquals(Color.White, navActiveLabelInk(DarkFlickColors))
        assertTrue(contrast(DarkFlickColors.onPrimary, DarkFlickColors.primary) >= 4.5f)
        assertTrue(!navShowsGlassSheen(DarkFlickColors))
    }

    @Test fun unsupportedBlurFallbackMatchesTheTintStackAndRemainsTranslucent() {
        val c = DarkFlickColors
        val fallback = navBarFallbackTint(c)
        assertTrue("fallback became opaque", fallback.alpha < 1f)
        assertTrue("fallback became too faint to carry controls", fallback.alpha >= 0.70f)

        for (backdrop in listOf(c.canvas, Color.Black, Color.White)) {
            val stacked = navBarHazeTints(c).fold(backdrop) { base, tint -> tint.over(base) }
            val fallbackDrawn = fallback.over(backdrop)
            assertColorNear(stacked, fallbackDrawn)
            assertTrue(contrast(navInactiveInk(c), fallbackDrawn) >= 4.5f)
            assertTrue(contrast(c.primary, fallbackDrawn) >= 3f)
        }
    }

    @Test fun lightNavigationKeepsTheExistingSharedGlassTreatment() {
        assertEquals(LightFlickColors.glass, navBarFill(LightFlickColors))
        assertEquals(emptyList<Color>(), navBarHazeTints(LightFlickColors))
        assertEquals(Color.Transparent, navBarFallbackTint(LightFlickColors))
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
