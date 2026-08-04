package com.flick.sender.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CastArtworkSizeTest {

    @Test fun theLongEdgeIsTheBoxWhicheverWayTheFilmWasShot() {
        // A still is scaled to fit INSIDE the box, so a landscape box bounds a portrait file
        // by its short edge: 1080x1920 used to land at 162x288 while 16:9 got the full 512.
        // Only a square box makes the long edge the bound for both.
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
                previewFrameSize(width, height, ARTWORK_STILL_BOX_PX, ARTWORK_STILL_BOX_PX)
            assertEquals("$width x $height", ARTWORK_STILL_BOX_PX, maxOf(outWidth, outHeight))
        }
    }

    @Test fun aPortraitFilmAndItsLandscapeTwinGetTheSamePicture() {
        assertEquals(
            ARTWORK_STILL_BOX_PX to 189,
            previewFrameSize(1920, 1080, ARTWORK_STILL_BOX_PX, ARTWORK_STILL_BOX_PX),
        )
        assertEquals(
            189 to ARTWORK_STILL_BOX_PX,
            previewFrameSize(1080, 1920, ARTWORK_STILL_BOX_PX, ARTWORK_STILL_BOX_PX),
        )
    }

    @Test fun aFilmSmallerThanTheBoxIsStillNeverUpscaled() {
        // The box is a ceiling, not a target: inventing pixels would cost memory on the one
        // bitmap this app holds live for the length of a cast.
        assertEquals(
            320 to 240,
            previewFrameSize(320, 240, ARTWORK_STILL_BOX_PX, ARTWORK_STILL_BOX_PX),
        )
    }

    @Test fun everyFilmFitsInABinderTransaction() {
        // This picture is parceled to SystemUI as the notification's large icon and again into
        // the session's metadata, and a transaction that overruns takes the notification with
        // it. The mat is what makes that a constant rather than a worst case, so the cost is
        // read back off the placement every shape produces — a film smaller than the opening
        // and an absurd anamorphic aspect included — rather than restated from the constant
        // the composer happens to allocate with.
        val shapes = listOf(
            3840 to 2160,
            1080 to 1920,
            1440 to 1080,
            1080 to 1080,
            320 to 240,
            4096 to 1,
        )
        for ((width, height) in shapes) {
            val placement = matPlacement(width, height)
            val bytes = placement.boxPx * placement.boxPx * BytesPerPixel
            assertEquals("$width x $height", 802_816, bytes)
            assertTrue("$width x $height: $bytes", bytes < BinderCeilingBytes - NotificationHeadroomBytes)
        }
    }

    private companion object {
        const val BytesPerPixel = 4
        const val BinderCeilingBytes = 1_048_576
        const val NotificationHeadroomBytes = 200_000
    }
}
