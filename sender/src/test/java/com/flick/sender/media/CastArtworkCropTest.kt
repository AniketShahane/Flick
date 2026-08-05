package com.flick.sender.media

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CastArtworkCropTest {

    @Test fun aFilmInsideTheBoundsKeepsItsWholeFrame() {
        // The headline of the whole file: the artwork is the film, at the film's own shape, with
        // nothing added round it and nothing taken off a shape the media surfaces can show.
        for (name in listOf("16:9", "16:9 HD", "4:3", "small")) {
            val crop = artworkOf(name)
            assertEquals("$name left", 0, crop.left)
            assertEquals("$name top", 0, crop.top)
            assertEquals("$name width", stillOf(name).first, crop.sourceWidth)
            assertEquals("$name height", stillOf(name).second, crop.sourceHeight)
        }
    }

    @Test fun theDrawnPictureIsTheShapeOfTheFrameItWasTakenFrom() {
        // Rounding to whole pixels is the only thing allowed to move the aspect, and only by a
        // fraction of a percent — there is no letterbox, no pillarbox and no squeeze anywhere.
        for (name in Videos.keys) {
            val crop = artworkOf(name)
            val source = crop.sourceWidth.toFloat() / crop.sourceHeight
            val drawn = crop.width.toFloat() / crop.height
            assertTrue("$name: $source vs $drawn", abs(drawn / source - 1f) < 0.01f)
        }
    }

    @Test fun aScopeFrameIsCentreCroppedToTheWideBound() {
        val crop = artworkOf("2.39:1")
        val (width, height) = stillOf("2.39:1")
        assertEquals("nothing comes off the top or bottom", height, crop.sourceHeight)
        assertEquals(0, crop.top)
        assertEquals("trimmed equally from both sides", width - crop.sourceWidth, crop.left * 2)
        assertAspect(WideBound, crop.sourceWidth, crop.sourceHeight)
    }

    @Test fun theWideBoundBitesInProportionToHowFarPastItTheFilmIs() {
        // What the bound actually costs, stated as the share of the width that survives it: a
        // flat 1.85:1 frame barely notices, and scope — the shape no slot could show whole in
        // any case — gives up a quarter.
        assertTrue("${keptWidth("1.85:1")}", keptWidth("1.85:1") > 0.95f)
        assertTrue("${keptWidth("2:1")}", keptWidth("2:1") in 0.85f..0.9f)
        assertTrue("${keptWidth("2.39:1")}", keptWidth("2.39:1") in 0.73f..0.76f)
    }

    @Test fun aPhoneClipIsCentreCroppedToTheTallBoundAndComesOutLandscape() {
        val crop = artworkOf("9:16")
        val (width, height) = stillOf("9:16")
        assertEquals("nothing comes off the sides", width, crop.sourceWidth)
        assertEquals(0, crop.left)
        assertEquals("trimmed equally from top and bottom", height - crop.sourceHeight, crop.top * 2)
        assertAspect(TallBound, crop.sourceWidth, crop.sourceHeight)
        // What the bound costs the tallest thing there is, stated outright: a 9:16 clip keeps a
        // little over two fifths of its height. Those rows were being carried rather than seen —
        // the media card in the shade is about 2:1 and crops to centre, so it never showed them.
        assertTrue("${crop.sourceHeight}", crop.sourceHeight.toFloat() / height in 0.41f..0.43f)
    }

    @Test fun nothingThisComposesIsEverTallerThanItIsWide() {
        // The band is landscape end to end, which is the property the tall bound exists to give:
        // a portrait frame reaching a landscape slot is spending its budget on rows that slot
        // discards, and there is no slot anywhere in this app's reach that is taller than square.
        for (name in Videos.keys) {
            val crop = artworkOf(name)
            assertTrue("$name: ${crop.width} x ${crop.height}", crop.width >= crop.height)
            assertTrue("$name source", crop.sourceWidth >= crop.sourceHeight)
        }
    }

    @Test fun everyShapeLandsInsideTheBandWhateverItWasShotAt() {
        for (name in Videos.keys) {
            val aspect = artworkOf(name).let { it.sourceWidth.toFloat() / it.sourceHeight }
            assertTrue("$name is $aspect", aspect in TallBound * 0.99f..WideBound * 1.01f)
        }
    }

    @Test fun aFrameShotExactlyAtABoundIsNotTrimmedByARoundingError() {
        for ((width, height) in listOf(1920 to 1080, 640 to 360, 1440 to 1080, 640 to 480)) {
            val crop = artworkCrop(width, height)
            assertEquals("$width x $height", width, crop.sourceWidth)
            assertEquals("$width x $height", height, crop.sourceHeight)
        }
    }

    @Test fun aFilmSmallerThanTheBudgetKeepsItsOwnPixels() {
        // The budget is a ceiling, not a target: inventing pixels would cost memory on the one
        // bitmap this app holds live for the length of a cast and buy no detail at all.
        // Measured against the rectangle the BOUNDS left, not against the still: a shape outside
        // the band is cropped whatever its size, and what this is about is the scaling after it.
        for ((width, height) in listOf(320 to 240, 100 to 100, 400 to 300, 240 to 320)) {
            val crop = artworkCrop(width, height)
            assertEquals("$width x $height", crop.sourceWidth, crop.width)
            assertEquals("$width x $height", crop.sourceHeight, crop.height)
        }
    }

    @Test fun theCropIsCentredAndNeverLeavesTheStill() {
        for (name in Videos.keys) {
            val (width, height) = stillOf(name)
            val crop = artworkOf(name)
            assertEquals("$name left", (width - crop.sourceWidth) / 2, crop.left)
            assertEquals("$name top", (height - crop.sourceHeight) / 2, crop.top)
            assertTrue("$name left", crop.left >= 0)
            assertTrue("$name top", crop.top >= 0)
            assertTrue("$name right", crop.left + crop.sourceWidth <= width)
            assertTrue("$name bottom", crop.top + crop.sourceHeight <= height)
        }
    }

    @Test fun aFrameWithNothingLeftToCropIsStillARectangle() {
        // An anamorphic container can report an aspect this side of absurd, and the bound
        // arithmetic would hand back a zero-pixel edge. What comes out has to stay a rectangle
        // the canvas can draw into, whatever it is given.
        for ((width, height) in listOf(4096 to 1, 1 to 4096, 1 to 1, 0 to 0, -1 to 720)) {
            val crop = artworkCrop(width, height)
            assertTrue("$width x $height", crop.width >= 1 && crop.height >= 1)
            assertTrue("$width x $height", crop.sourceWidth >= 1 && crop.sourceHeight >= 1)
            assertTrue("$width x $height", crop.left >= 0 && crop.top >= 0)
        }
    }

    /** The frame the artwork is taken from: the source video, decoded into the box it is asked for. */
    private fun stillOf(name: String): Pair<Int, Int> = Videos.getValue(name).let {
        previewFrameSize(it.first, it.second, ARTWORK_SOURCE_BOX_PX, ARTWORK_SOURCE_BOX_PX)
    }

    private fun artworkOf(name: String): ArtworkCrop =
        stillOf(name).let { artworkCrop(it.first, it.second) }

    private fun keptWidth(name: String): Float =
        artworkOf(name).sourceWidth.toFloat() / stillOf(name).first

    private fun assertAspect(bound: Float, width: Int, height: Int) {
        val aspect = width.toFloat() / height
        assertTrue("$width x $height is $aspect, not $bound", abs(aspect / bound - 1f) < 0.01f)
    }

    private companion object {
        /** Source videos, as they are shot rather than as they are decoded. */
        val Videos = mapOf(
            "16:9" to (3840 to 2160),
            "16:9 HD" to (1920 to 1080),
            "1.85:1" to (3840 to 2076),
            "2:1" to (3840 to 1920),
            "2.39:1" to (3840 to 1608),
            "4:3" to (1440 to 1080),
            "3:4" to (768 to 1024),
            "1:1" to (1080 to 1080),
            "9:16" to (1080 to 1920),
            "small" to (320 to 240),
        )

        val WideBound = ARTWORK_WIDE_BOUND_W.toFloat() / ARTWORK_WIDE_BOUND_H
        val TallBound = ARTWORK_TALL_BOUND_W.toFloat() / ARTWORK_TALL_BOUND_H
    }
}
