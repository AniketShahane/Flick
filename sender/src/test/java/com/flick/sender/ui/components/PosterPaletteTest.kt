package com.flick.sender.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The palette read against the prototype it was ported from: the expected values are what the
 * prototype's `paletteOf` and Chrome's `hsla()` give for the same pixels.
 */
class PosterPaletteTest {

    private fun image(kind: String): IntArray {
        var seed = 12345L
        fun rnd(): Double {
            seed = (seed * 16807L) % 2147483647L
            return seed / 2147483647.0
        }
        return IntArray(40 * 60) { i ->
            val (r, g, b) = if (kind == "noise") {
                Triple((rnd() * 256).toInt(), (rnd() * 256).toInt(), (rnd() * 256).toInt())
            } else {
                val y = i / 40
                val x = i % 40
                when {
                    x % 7 == 0 -> Triple(40, 220, 90)
                    y < 20 -> Triple(5, 30, 12)
                    else -> Triple(x * 2, 60 + y, 20)
                }
            }
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun assertRgb(message: String, expected: IntArray, argb: Int) {
        val actual = intArrayOf((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
        for (c in 0..2) {
            assertTrue("$message: ${expected.toList()} vs ${actual.toList()}", kotlin.math.abs(expected[c] - actual[c]) <= 1)
        }
    }

    @Test fun aGreenPosterReadsGreen() {
        val p = PosterPalette.of(image("green"))
        assertEquals(142.5f, p.hue, 1e-3f)
        assertEquals(136.667f, p.vibrantHue, 1e-2f)
        assertEquals(0.72f, p.vibrantSaturation, 1e-4f)
        assertEquals(0.0196f, p.dark.r, 1e-3f)
        assertEquals(0.1176f, p.dark.g, 1e-3f)
        assertEquals(0.8627f, p.light.g, 1e-3f)
        val inks = IntertitleColors.stainedGlass(p)
        assertRgb("ground", intArrayOf(2, 14, 5), inks.ground)
        assertRgb("film", intArrayOf(3, 21, 8), inks.film)
        assertRgb("rule", intArrayOf(123, 234, 155), inks.rule)
        assertRgb("glow", intArrayOf(58, 223, 104), inks.glow)
        assertRgb("accent", intArrayOf(167, 241, 188), inks.accent)
    }

    @Test fun noiseMatchesThePrototypeToo() {
        val p = PosterPalette.of(image("noise"))
        assertEquals(52.5f, p.hue, 1e-3f)
        assertEquals(172.017f, p.vibrantHue, 1e-2f)
        assertEquals(1f, p.vibrantSaturation, 1e-4f)
        val inks = IntertitleColors.stainedGlass(p)
        assertRgb("ground", intArrayOf(35, 35, 33), inks.ground)
        assertRgb("film", intArrayOf(54, 54, 52), inks.film)
        assertRgb("rule", intArrayOf(102, 255, 235), inks.rule)
        assertRgb("accent", intArrayOf(153, 255, 241), inks.accent)
    }

    @Test fun alphasAreTheThemesOwn() {
        val inks = IntertitleColors.stainedGlass(PosterPalette.of(image("green")))
        assertEquals(Math.round(0.95f * 255), inks.rule ushr 24)
        assertEquals(Math.round(0.35f * 255), inks.ruleSoft ushr 24)
        assertEquals(Math.round(0.35f * 255), inks.glow ushr 24)
        assertEquals(255, inks.accent ushr 24)
        assertEquals(255, inks.ground ushr 24)
    }

    @Test fun aGreyPosterFallsBackToItsDominantBinAndAFloorOfSaturation() {
        val grey = IntArray(2400) { (0xFF shl 24) or (0x80 shl 16) or (0x80 shl 8) or 0x80 }
        val p = PosterPalette.of(grey)
        // No bin collects anything: the first bin's centre, as the prototype's indexOf did.
        assertEquals(7.5f, p.hue, 0f)
        // Mid grey is in the vibrant band with zero saturation, so it is picked and floored.
        assertEquals(0.55f, p.vibrantSaturation, 0f)
    }

    @Test fun cssHslResolvesAsChromeDoes() {
        assertRgb("red", intArrayOf(255, 0, 0), PosterPalette.hsl(0f, 1f, 0.5f))
        assertRgb("steel", intArrayOf(102, 153, 204), PosterPalette.hsl(210f, 0.5f, 0.6f))
        assertRgb("wraps", intArrayOf(255, 0, 0), PosterPalette.hsl(360f, 1f, 0.5f))
    }

    @Test fun saturateIsTheFilterEffectsMatrix() {
        val px = intArrayOf((0xFF shl 24) or (200 shl 16) or (100 shl 8) or 50, 0x80808080.toInt())
        PosterPalette.saturate(px, 1f)
        assertEquals((0xFF shl 24) or (200 shl 16) or (100 shl 8) or 50, px[0])
        PosterPalette.saturate(px, 0f)
        // Fully desaturated: every channel is the pixel's luminance, alpha kept.
        val r = (px[0] shr 16) and 0xFF
        assertEquals(r, (px[0] shr 8) and 0xFF)
        assertEquals(r, px[0] and 0xFF)
        assertEquals(0x80, px[1] ushr 24)
        val vivid = intArrayOf((0xFF shl 24) or (150 shl 16) or (100 shl 8) or 100)
        PosterPalette.saturate(vivid, 1.5f)
        assertTrue("more red", ((vivid[0] shr 16) and 0xFF) > 150)
        assertTrue("less green", ((vivid[0] shr 8) and 0xFF) < 100)
    }

    @Test fun emptyInputIsSafe() {
        val p = PosterPalette.of(IntArray(0))
        assertEquals(0f, p.dark.r, 0f)
    }
}
