package com.flick.sender.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CastArtworkSizeTest {

    @Test fun theLongEdgeIsTheBoxWhicheverWayTheFilmWasShot() {
        // A still is scaled to fit INSIDE the box it is asked for, so a landscape box bounds a
        // portrait file by its short edge: 1080x1920 used to land at 162x288 while 16:9 got the
        // full 512. Only a square box makes the long edge the bound for both.
        val shapes = listOf(
            3840 to 2160,
            1920 to 1080,
            1080 to 1920,
            2160 to 3840,
            1440 to 1080,
            1080 to 1080,
        )
        for ((width, height) in shapes) {
            val (outWidth, outHeight) =
                previewFrameSize(width, height, ARTWORK_SOURCE_BOX_PX, ARTWORK_SOURCE_BOX_PX)
            assertEquals("$width x $height", ARTWORK_SOURCE_BOX_PX, maxOf(outWidth, outHeight))
        }
    }

    @Test fun aFilmSmallerThanTheBoxIsStillNeverUpscaled() {
        assertEquals(
            320 to 240,
            previewFrameSize(320, 240, ARTWORK_SOURCE_BOX_PX, ARTWORK_SOURCE_BOX_PX),
        )
    }

    @Test fun everyFilmFitsInABinderTransaction() {
        // This picture is parceled to SystemUI as the notification's large icon and again into
        // the session's metadata, and a transaction that overruns takes the notification with it.
        // The artwork is no longer one SIZE, so the cost is read back off the geometry every
        // shape actually produces rather than restated from a constant the composer allocates
        // with — a film smaller than the box and an absurd anamorphic aspect included.
        for ((name, video) in Videos) {
            val crop = artworkOf(video)
            assertTrue("$name: ${crop.width}x${crop.height} = ${crop.bytes}", crop.bytes <= MaxBytes)
            assertTrue("$name: ${crop.bytes}", crop.bytes < BinderCeilingBytes - NotificationHeadroomBytes)
        }
    }

    @Test fun theTallBoundIsTheWorstCaseAndStillCostsLessThanTheOldSquare() {
        // The budget was chosen as the old matted square's own area, and no shape can reach that
        // area any more: a square still is cropped to the tall bound before it is scaled, so the
        // most expensive shape there is now is 4:3. The Binder margin only ever grew.
        val crop = artworkCrop(ARTWORK_SOURCE_BOX_PX, ARTWORK_SOURCE_BOX_PX)
        assertEquals(517 to 388, crop.width to crop.height)
        assertTrue("${crop.bytes} vs $MaxBytes", crop.bytes <= MaxBytes)
    }

    @Test fun theShapesTheBudgetIsSpentAsAreTheOnesTheCommentsName() {
        assertEquals("16:9", 597 to 336, artworkOf(3840 to 2160).let { it.width to it.height })
        assertEquals("4:3", 517 to 388, artworkOf(1440 to 1080).let { it.width to it.height })
        // Both shapes outside the band land INSIDE the budget rather than on it: the crop takes
        // them below it, and nothing here is ever scaled up to spend a budget it has not reached.
        assertEquals("2.39:1", 476 to 268, artworkOf(3840 to 1608).let { it.width to it.height })
        assertEquals("9:16", 360 to 270, artworkOf(1080 to 1920).let { it.width to it.height })
    }

    @Test fun aStillTheProviderSizedItselfIsHeldToTheSameCeiling() {
        // MediaStore's cached thumbnail is asked for a size and is free to answer with another,
        // so the ceiling may not rest on the box the still was requested in.
        for ((width, height) in listOf(4000 to 4000, 8000 to 2000, 2000 to 8000, 4096 to 1, 1 to 1)) {
            val crop = artworkCrop(width, height)
            assertTrue("$width x $height: ${crop.bytes}", crop.bytes <= MaxBytes)
        }
    }

    @Test fun theCeilingIsAProofAndNotAnEstimate() {
        // Rounding the scaled edges to whole pixels is allowed to carry the pair past the budget
        // — over five thousand shapes in this sweep alone do — and the geometry has to hand back
        // the overshoot rather than leave it to a shape nobody thought to name.
        for (width in 1..800) {
            for (height in 1..800 step 11) {
                val crop = artworkCrop(width, height)
                assertTrue("$width x $height: ${crop.bytes}", crop.bytes <= MaxBytes)
                assertTrue("$width x $height", crop.width >= 1 && crop.height >= 1)
            }
        }
    }

    private fun artworkOf(video: Pair<Int, Int>): ArtworkCrop =
        previewFrameSize(video.first, video.second, ARTWORK_SOURCE_BOX_PX, ARTWORK_SOURCE_BOX_PX)
            .let { artworkCrop(it.first, it.second) }

    private companion object {
        /** Source videos, as they are shot rather than as they are decoded. */
        val Videos = mapOf(
            "16:9" to (3840 to 2160),
            "2.39:1" to (3840 to 1608),
            "9:16" to (1080 to 1920),
            "4:3" to (1440 to 1080),
            "1:1" to (1080 to 1080),
            "small" to (320 to 240),
            "anamorphic" to (4096 to 1),
        )

        /** 448 x 448 at four bytes a pixel: the cost of the artwork before its shape was the film's. */
        const val MaxBytes = 802_816
        const val BinderCeilingBytes = 1_048_576
        const val NotificationHeadroomBytes = 200_000
    }
}
