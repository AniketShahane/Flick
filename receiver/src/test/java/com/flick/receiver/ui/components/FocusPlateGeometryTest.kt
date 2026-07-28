package com.flick.receiver.ui.components

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.flick.receiver.ui.theme.FlickDimens
import com.flick.receiver.ui.theme.FlickShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The corner arithmetic `flickPlate` draws from.
 *
 * The plate resolves the focus corner ease in the DRAW phase, so `grownBy` is now
 * handed a **negative** offset — the half-stroke inset that keeps the hairline
 * where `Modifier.border` put it. A corner that resolves below zero throws inside
 * `CornerBasedShape.createOutline`, so the facts below are what stop a focused
 * control from crashing the panel it sits in.
 */
class FocusPlateGeometryTest {

    private val density = Density(2f)
    private val control = Size(120f, 48f)

    private fun cornerPx(shape: Shape, size: Size = control): Float =
        (shape as CornerBasedShape).topStart.toPx(size, density)

    @Test fun growingACornerAddsExactlyTheOffset() {
        val grown = with(density) { 4.dp.toPx() }
        assertEquals(
            cornerPx(FlickShape.Md) + grown,
            cornerPx(FlickShape.Md.grownBy(4.dp)),
            0.001f,
        )
    }

    @Test fun theStrokeInsetTakesHalfItsWidthOffTheRadius() {
        val half = FlickDimens.Hairline / 2f
        val inset = cornerPx(FlickShape.Md.grownBy(0.dp - half))
        assertEquals(
            cornerPx(FlickShape.Md) - with(density) { half.toPx() },
            inset,
            0.001f,
        )
        assertTrue(inset > 0f)
    }

    @Test fun aCornerSmallerThanItsOwnInsetClampsRatherThanGoingNegative() {
        // The square affordances take an 8 dp corner, but nothing stops a caller
        // pairing a 0 dp corner with the 2 dp outline stroke.
        val square = RoundedCornerShape(0.dp)
        assertEquals(0f, cornerPx(square.grownBy(0.dp - FlickOutlinedChromeBorderWidth / 2f)), 0f)
    }

    @Test fun everyControlShapeStillOutlinesAtItsInsetSize() {
        val stroke = with(density) { FlickDimens.Hairline.toPx() }
        val inset = Size(control.width - stroke, control.height - stroke)
        val shapes = listOf(
            FlickShape.Sm,
            FlickShape.Md,
            FlickShape.Lg,
            FlickShape.Play,
            FlickShape.Pill,
        )
        shapes.forEach { shape ->
            // Throws on a negative resolved radius — which is the whole point.
            shape.grownBy(0.dp - FlickDimens.Hairline / 2f)
                .createOutline(inset, LayoutDirection.Ltr, density)
        }
    }
}
