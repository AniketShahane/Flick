package com.flick.receiver.ui.components

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.flick.receiver.ui.theme.FlickDimens
import com.flick.receiver.ui.theme.FlickMotion
import com.flick.receiver.ui.theme.FlickShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the traveling ring is painted at (spec §3a).
 *
 * The ring is stroked in the member's pre-scale space and put through a draw-phase
 * scale, so nothing in the draw block states its painted geometry directly — and
 * the bug this replaced was exactly a painted radius nobody could read off the
 * source. Everything below reconstructs that geometry from
 * [focusRingPreScaleInset] and the real `grownBy` corner arithmetic, and asserts
 * the closed form §3 asks for: `elementRadius × lift + offset`, on every side and
 * at any aspect ratio.
 */
class FocusBeaconRingGeometryTest {

    private val density = Density(2f)
    private val lift = FlickMotion.FOCUS_SCALE
    private val offsetPx = with(density) { FlickFocusRingOffset.toPx() }

    // The Settings "Device name" row, measured on the TV — the case that broke.
    private val rowWidth = with(density) { 800.dp.toPx() }
    private val rowHeight = with(density) { 69.dp.toPx() }

    /** `FlickTvIconButton`'s default side — square, where the old bug hid. */
    private val keySide = with(density) { 19.dp.toPx() }

    // A member publishes the shape its focus corner has SETTLED at, so both of
    // these carry the 4 dp focus corner growth already.
    private val rowShape = FlickShape.Md.grownBy(4.dp)
    private val keyShape = FlickShape.Sm.grownBy(4.dp)

    private fun cornerPx(shape: Shape, size: Size): Float =
        (shape as CornerBasedShape).topStart.toPx(size, density)

    /** The ring's corner as painted: grown pre-scale, then carried by the lift. */
    private fun paintedCorner(
        shape: Shape,
        width: Float,
        height: Float,
        bloom: Float = 1f,
        lift: Float = this.lift,
    ): Float {
        val inset = focusRingPreScaleInset(offsetPx, bloom, lift)
        val ring = shape.grownBy(with(density) { inset.toDp() })
        return cornerPx(ring, Size(width + inset * 2f, height + inset * 2f)) * lift
    }

    /** The gap between the ring's path and the edge of the *scaled* element. */
    private fun paintedGap(bloom: Float = 1f, lift: Float = this.lift): Float =
        focusRingPreScaleInset(offsetPx, bloom, lift) * lift

    @Test fun theRingSitsTheFullOffsetOutsideTheScaledElement() {
        assertEquals(offsetPx, paintedGap(), 0.001f)
    }

    @Test fun theCornerIsTheElementCornerLiftedPlusTheOffset() {
        val element = cornerPx(rowShape, Size(rowWidth, rowHeight))
        // 17 × 1.06 + 4.5 = 22.52 dp, against the 34.5 dp the mean inset drew.
        assertEquals(element * lift + offsetPx, paintedCorner(rowShape, rowWidth, rowHeight), 0.01f)
        assertEquals(with(density) { 22.52.dp.toPx() }, paintedCorner(rowShape, rowWidth, rowHeight), 0.05f)
    }

    @Test fun theCornerDoesNotDependOnTheAspectRatio() {
        // The one invariant the per-axis inset could not hold: at 11.6:1 its two
        // insets diverged by 22 dp, and their mean is what reached the corner.
        assertEquals(
            paintedCorner(keyShape, keySide, keySide),
            paintedCorner(keyShape, rowWidth, rowHeight),
            0.01f,
        )
    }

    @Test fun aSquareKeyIsLeftWhereItWas() {
        // The old error was a function of |dx - dy|, and a 19 dp square key has
        // none: the whole change there is (FOCUS_SCALE - 1) × (12 - 9.5) = 0.15 dp,
        // under a third of a physical pixel at TV density.
        val element = cornerPx(keyShape, Size(keySide, keySide))
        val perAxis = element + offsetPx + (lift - 1f) * 0.5f * keySide
        assertEquals(
            with(density) { 0.15.dp.toPx() },
            paintedCorner(keyShape, keySide, keySide) - perAxis,
            0.01f,
        )
    }

    @Test fun theRingBoxIsWhereThePerAxisConstructionPutIt() {
        // The rect was never wrong. Taking the element's left edge as the origin,
        // the ring's pre-scale edge at -inset scales about the element's centre to:
        val inset = focusRingPreScaleInset(offsetPx, 1f, lift)
        val paintedEdge = rowWidth / 2f - lift * (rowWidth / 2f + inset)
        assertEquals(-(offsetPx + (lift - 1f) * 0.5f * rowWidth), paintedEdge, 0.01f)
    }

    @Test fun reducedMotionRingsAtTheRestingOffsetWithNoLift() {
        assertEquals(offsetPx, paintedGap(lift = 1f), 0.001f)
        val element = cornerPx(rowShape, Size(rowWidth, rowHeight))
        assertEquals(element + offsetPx, paintedCorner(rowShape, rowWidth, rowHeight, lift = 1f), 0.01f)
    }

    @Test fun theArrivalBloomOnlyGrowsTowardsTheRestingOffset() {
        // RING_BLOOM_FLOOR. The reserve in `FlickDimens.FocusRingReserve` is derived
        // from the settled ring, so a bloom that overshot it would clip on the panel.
        val floor = paintedGap(bloom = 0.6f)
        assertEquals(offsetPx * 0.6f, floor, 0.001f)
        assertTrue(floor > 0f && floor < offsetPx)
    }

    @Test fun theRingStaysInsideTheReserveItsOffsetDocuments() {
        // The strokes are divided out before the scale too, so the painted extent
        // is the offset, half the ring stroke and the contour — none of them lifted
        // — plus what the lift adds to the element's own half-side. That is the
        // formula on [FlickFocusRingOffset] less the lift it puts on the first
        // three, which is the unhosted path's and therefore the worse of the two.
        val edge = with(density) { (FlickFocusRingWidth / 2f + FlickFocusRingContourWidth).toPx() }
        val side = with(density) { 103.dp.toPx() }
        val extent = paintedGap() + edge + (lift - 1f) * 0.5f * side
        val documented = lift * (offsetPx + edge) + (lift - 1f) * 0.5f * side
        assertTrue(extent < documented)
        assertTrue(documented <= with(density) { FlickDimens.FocusRingReserve.toPx() })
    }
}
