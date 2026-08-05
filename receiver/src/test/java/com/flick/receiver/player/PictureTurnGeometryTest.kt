package com.flick.receiver.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where the turned picture actually lands.
 *
 * This is the half of the feature a viewer can see being wrong, and every way it
 * can be wrong is silent: a picture stretched to the wrong proportions, a picture
 * letterboxed inside a box of the wrong shape, or a picture turned the wrong way.
 * None of them raise anything, and none of them are visible in a log, so the
 * arithmetic is pinned here rather than on a panel.
 *
 * The tests deliberately do not assert the five numbers the transform is made of.
 * They rebuild what `android.graphics.Matrix` would do with them — rotate about
 * the pivot, then scale about the pivot, in that order — and assert about the
 * PICTURE that comes out: its proportions, its size and which way up it is. A
 * scale factor is only ever right or wrong by what it does to the frame.
 */
class PictureTurnGeometryTest {

    /** A 16:9 panel, in the pixels the verified hardware lays a full-screen view out at. */
    private val panelWidth = 1920
    private val panelHeight = 1080

    /** The corners of the texture, which a `TextureView` stretches across its whole bounds. */
    private fun textureCorners(viewWidth: Int, viewHeight: Int) = listOf(
        0f to 0f,
        viewWidth.toFloat() to 0f,
        viewWidth.toFloat() to viewHeight.toFloat(),
        0f to viewHeight.toFloat(),
    )

    /** `Matrix.postRotate(deg, pivot)` followed by `Matrix.postScale(s, pivot)`. */
    private fun mapPoint(t: SurfaceTurnTransform, point: Pair<Float, Float>): Pair<Float, Float> {
        val radians = Math.toRadians(t.rotationDegrees.toDouble())
        val cosine = cos(radians).toFloat()
        val sine = sin(radians).toFloat()
        val dx = point.first - t.pivotX
        val dy = point.second - t.pivotY
        val rotatedX = dx * cosine - dy * sine
        val rotatedY = dx * sine + dy * cosine
        return (t.pivotX + rotatedX * t.scaleX) to (t.pivotY + rotatedY * t.scaleY)
    }

    private class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val aspect: Float get() = width / height
    }

    /** Where the picture ends up on screen, in the view's own pixels. */
    private fun presented(
        viewWidth: Int = panelWidth,
        viewHeight: Int = panelHeight,
        pictureWidth: Int,
        pictureHeight: Int,
        pixelWidthHeightRatio: Float = 1f,
        turnDegrees: Int,
    ): Rect {
        val transform = surfaceTurnTransform(
            viewWidthPx = viewWidth,
            viewHeightPx = viewHeight,
            pictureWidthPx = pictureWidth,
            pictureHeightPx = pictureHeight,
            pixelWidthHeightRatio = pixelWidthHeightRatio,
            turnDegrees = turnDegrees,
        )
        val mapped = textureCorners(viewWidth, viewHeight).map { mapPoint(transform, it) }
        return Rect(
            left = mapped.minOf { it.first },
            top = mapped.minOf { it.second },
            right = mapped.maxOf { it.first },
            bottom = mapped.maxOf { it.second },
        )
    }

    private fun assertClose(expected: Float, actual: Float, what: String) {
        assertTrue("$what: expected $expected, was $actual", abs(expected - actual) < 0.5f)
    }

    // --- The proportions, which is the whole of "does it look right" ----------

    /**
     * A quarter turn swaps the displayed aspect, and the picture that comes out
     * has to have exactly the swapped one. Anything else is a stretched face.
     */
    @Test fun aQuarterTurnSwapsTheDisplayedAspectAndNeverDistorts() {
        for (turn in listOf(90, 270)) {
            val rect = presented(pictureWidth = 1920, pictureHeight = 1080, turnDegrees = turn)
            assertClose(9f / 16f, rect.aspect, "aspect at $turn")
        }
    }

    /** A half turn changes nothing about the shape, only about which way up it is. */
    @Test fun aHalfTurnKeepsTheDisplayedAspect() {
        val rect = presented(pictureWidth = 1920, pictureHeight = 1080, turnDegrees = 180)
        assertClose(16f / 9f, rect.aspect, "aspect at 180")
    }

    /**
     * The sample aspect applies to the width, so a 1440×1080 frame at 1.333 is a
     * 16:9 picture. Reading it as 4:3 would turn it into a picture 25 % too
     * narrow, which is exactly the failure this ratio exists to prevent.
     */
    @Test fun theSampleAspectIsPartOfThePicturesShape() {
        val rect = presented(
            pictureWidth = 1440,
            pictureHeight = 1080,
            pixelWidthHeightRatio = 4f / 3f,
            turnDegrees = 90,
        )
        assertClose(9f / 16f, rect.aspect, "anamorphic aspect at 90")
    }

    // --- The size, which is the whole of "does it fill the screen" ------------

    /**
     * Turned, and as large as a turned picture can be: bounded by the panel's
     * height, centred, and touching top and bottom. The letterboxing left and
     * right is the shape of the film, not slack.
     */
    @Test fun aTurnedPictureFillsTheScreenInTheDirectionThatBoundsIt() {
        val rect = presented(pictureWidth = 1920, pictureHeight = 1080, turnDegrees = 90)
        assertClose(0f, rect.top, "top")
        assertClose(panelHeight.toFloat(), rect.bottom, "bottom")
        assertClose(panelHeight * 9f / 16f, rect.width, "width")
        // Centred: the same slack on each side.
        assertClose(panelWidth - rect.right, rect.left, "centring")
    }

    /**
     * The case that fitting into the view rect gets wrong. A 21:9 film's content
     * frame is letterboxed to 1920×823 before any turn, so a transform that only
     * maps the rotated rect back onto its view — media3's own deleted helper —
     * would fit the turned picture into 823 px of height and lose a quarter of it.
     * Under FILL the view is the whole panel and the picture takes the full 1080.
     */
    @Test fun aTurnedPictureIsNotBoundedByTheShapeOfTheFilmItCameFrom() {
        val rect = presented(pictureWidth = 2560, pictureHeight = 1080, turnDegrees = 90)
        assertClose(panelHeight.toFloat(), rect.height, "height")
        assertClose(panelHeight * 1080f / 2560f, rect.width, "width")
    }

    /**
     * A picture wider than the panel once turned is bounded the other way: a
     * portrait-coded film stood on its side is a very wide landscape picture, and
     * it has to letterbox top and bottom rather than run off the edges.
     */
    @Test fun aTurnedPictureWiderThanThePanelIsBoundedByTheWidth() {
        val rect = presented(pictureWidth = 1080, pictureHeight = 3840, turnDegrees = 90)
        assertClose(panelWidth.toFloat(), rect.width, "width")
        assertClose(panelWidth * 1080f / 3840f, rect.height, "height")
        assertTrue("stays inside the panel", rect.top >= -0.5f && rect.bottom <= panelHeight + 0.5f)
    }

    /**
     * The transform fits under FILL rather than merely un-stretching, so a film
     * whose own shape does not match the panel is letterboxed by the matrix even
     * at a turn of 180 — the content frame is full-bleed and no longer doing it.
     */
    @Test fun aHalfTurnedPictureIsStillFittedToThePanel() {
        val rect = presented(pictureWidth = 1440, pictureHeight = 1080, turnDegrees = 180)
        assertClose(panelHeight * 4f / 3f, rect.width, "width")
        assertClose(panelHeight.toFloat(), rect.height, "height")
    }

    // --- Which way up --------------------------------------------------------

    /**
     * Clockwise, in the same sense `Format.rotationDegrees` states and
     * `Matrix.postRotate` applies: after a 90 the film's LEFT edge is along the
     * top of the screen. Getting this backwards is a working feature installed
     * upside down, and it looks identical in every other assertion here.
     */
    @Test fun ninetyDegreesTurnsThePictureClockwise() {
        val transform = surfaceTurnTransform(
            viewWidthPx = panelWidth,
            viewHeightPx = panelHeight,
            pictureWidthPx = 1920,
            pictureHeightPx = 1080,
            pixelWidthHeightRatio = 1f,
            turnDegrees = 90,
        )
        val leftEdgeMidpoint = mapPoint(transform, 0f to panelHeight / 2f)
        assertTrue("the film's left edge is above centre", leftEdgeMidpoint.second < panelHeight / 2f)
        val topEdgeMidpoint = mapPoint(transform, panelWidth / 2f to 0f)
        assertTrue("the film's top edge is right of centre", topEdgeMidpoint.first > panelWidth / 2f)
    }

    /** And 270 is the other way, or the two rows of the panel would do the same thing. */
    @Test fun twoSeventyDegreesTurnsThePictureTheOtherWay() {
        val transform = surfaceTurnTransform(
            viewWidthPx = panelWidth,
            viewHeightPx = panelHeight,
            pictureWidthPx = 1920,
            pictureHeightPx = 1080,
            pixelWidthHeightRatio = 1f,
            turnDegrees = 270,
        )
        val leftEdgeMidpoint = mapPoint(transform, 0f to panelHeight / 2f)
        assertTrue("the film's left edge is below centre", leftEdgeMidpoint.second > panelHeight / 2f)
    }

    // --- The turn that is not one --------------------------------------------

    /**
     * The invariant the whole product rests on, at this level: a film nobody has
     * turned asks for nothing. No rotation, no scale, no pivot — this transform is
     * never even reached for such a film, because it stays on the `SurfaceView`,
     * and if it ever is, it must do nothing.
     */
    @Test fun aFilmNobodyHasTurnedGetsNoTransformAtAll() {
        assertEquals(
            SurfaceTurnTransform.IDENTITY,
            surfaceTurnTransform(
                viewWidthPx = panelWidth,
                viewHeightPx = panelHeight,
                pictureWidthPx = 3840,
                pictureHeightPx = 2160,
                pixelWidthHeightRatio = 1f,
                turnDegrees = 0,
            ),
        )
    }

    /**
     * A turn off the quarter-turn grid has no honest answer here either — the
     * decoder is honouring the file and the surface must not disagree with it.
     */
    @Test fun aTurnOffTheQuarterGridIsRefused() {
        assertEquals(
            SurfaceTurnTransform.IDENTITY,
            surfaceTurnTransform(panelWidth, panelHeight, 1920, 1080, 1f, turnDegrees = 45),
        )
    }

    /**
     * Nothing is on screen before the first layout or before the renderer has
     * published a size, so an unanswerable question is answered with the identity
     * rather than a guess. The layout that follows asks again.
     */
    @Test fun aSizeThatIsNotKnownYetGetsTheIdentity() {
        assertEquals(
            SurfaceTurnTransform.IDENTITY,
            surfaceTurnTransform(0, 0, 1920, 1080, 1f, turnDegrees = 90),
        )
        assertEquals(
            SurfaceTurnTransform.IDENTITY,
            surfaceTurnTransform(panelWidth, panelHeight, 0, 0, 1f, turnDegrees = 90),
        )
    }

    /** A missing sample aspect is square pixels, not a picture of no width. */
    @Test fun anUnsetSampleAspectIsTreatedAsSquarePixels() {
        assertEquals(16f / 9f, displayAspectRatio(1920, 1080, 0f)!!, 0.001f)
        assertEquals(16f / 9f, displayAspectRatio(1920, 1080, 1f)!!, 0.001f)
    }
}
