package com.flick.receiver.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lit room is read from a sofa, so every ink it sets in copy has to clear the
 * body floor on every bed it can land on: the canvas, a card, a nested row, and the
 * amber selected fill. Amber itself is deliberately absent — it is a fill and a
 * focus colour here, never text (see [FlickRoom]).
 */
class RoomContrastTest {

    private val inks = mapOf(
        "OnSurface" to FlickRoom.OnSurface,
        "OnSurfaceDim" to FlickRoom.OnSurfaceDim,
        "OnSurfaceMuted" to FlickRoom.OnSurfaceMuted,
        "OnSurfaceFaint" to FlickRoom.OnSurfaceFaint,
        "OnSurfaceSoft" to FlickRoom.OnSurfaceSoft,
        "OnChrome" to FlickRoom.OnChrome,
        "SparkBright" to FlickRoom.SparkBright,
        "Primary" to FlickRoom.Primary,
        "Live" to FlickRoom.Live,
        "Caution" to FlickRoom.Caution,
        "Trouble" to FlickRoom.Trouble,
    )

    private val beds = mapOf(
        "Canvas" to FlickRoom.Canvas,
        "CanvasPair" to FlickRoom.CanvasPair,
        "Surface" to FlickRoom.Surface,
        "SurfaceRaised" to FlickRoom.SurfaceRaised,
        "SelectedFill on Surface" to FlickRoom.SelectedFill.over(FlickRoom.Surface),
        "a pressed control" to FlickRoom.ControlFillStrong.over(FlickRoom.Canvas),
        "a control on a nested row" to FlickRoom.ControlFill.over(FlickRoom.SurfaceRaised),
    )

    @Test fun everyRoomInkHoldsTheBodyFloorOnEveryRoomBed() {
        for ((inkName, ink) in inks) {
            for ((bedName, bed) in beds) {
                val measured = contrast(ink, bed)
                assertTrue(
                    "$inkName on $bedName measured %.2f:1, below %.1f:1".format(measured, BODY_FLOOR),
                    measured >= BODY_FLOOR,
                )
            }
        }
    }

    @Test fun inkOnAnAmberFillHoldsTheBodyFloor() {
        assertTrue(contrast(FlickRoom.OnSpark, FlickRoom.Spark) >= BODY_FLOOR)
    }

    @Test fun theRingAroundAnAmberFillReadsAgainstTheFill() {
        // White, the cinema's choice, would vanish into the canvas around the button.
        assertTrue(contrast(FlickRoom.FocusRingOnSpark, FlickRoom.Spark) >= GRAPHIC_FLOOR)
        assertTrue(contrast(FlickRoom.FocusRingOnSpark, FlickRoom.Canvas) >= GRAPHIC_FLOOR)
    }

    @Test fun theFocusRingsContourCarriesItOnTheLitBed() {
        // Amber alone is 1.6:1 here; the dark contour the ring already wears is the read.
        val contour = FlickColor.FocusRingContour.over(FlickRoom.Canvas)
        assertTrue(contrast(contour, FlickRoom.Canvas) >= GRAPHIC_FLOOR)
    }

    /** Source-over onto an opaque backdrop, on gamma-encoded sRGB channels. */
    private fun Color.over(backdrop: Color): Color = Color(
        red = alpha * red + (1f - alpha) * backdrop.red,
        green = alpha * green + (1f - alpha) * backdrop.green,
        blue = alpha * blue + (1f - alpha) * backdrop.blue,
    )

    private fun luminance(color: Color): Float =
        0.2126f * linear(color.red) + 0.7152f * linear(color.green) + 0.0722f * linear(color.blue)

    private fun linear(channel: Float): Float =
        if (channel <= 0.04045f) {
            channel / 12.92f
        } else {
            Math.pow(((channel + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        }

    private fun contrast(a: Color, b: Color): Float {
        val first = luminance(a)
        val second = luminance(b)
        return (maxOf(first, second) + 0.05f) / (minOf(first, second) + 0.05f)
    }

    private companion object {
        const val BODY_FLOOR = 4.5f

        /** WCAG 1.4.11 — the floor for a graphical object rather than for copy. */
        const val GRAPHIC_FLOOR = 3f
    }
}
