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
            val (outWidth, outHeight) = previewFrameSize(width, height, ARTWORK_BOX_PX, ARTWORK_BOX_PX)
            assertEquals("$width x $height", ARTWORK_BOX_PX, maxOf(outWidth, outHeight))
        }
    }

    @Test fun aPortraitFilmAndItsLandscapeTwinGetTheSamePicture() {
        assertEquals(ARTWORK_BOX_PX to 252, previewFrameSize(1920, 1080, ARTWORK_BOX_PX, ARTWORK_BOX_PX))
        assertEquals(252 to ARTWORK_BOX_PX, previewFrameSize(1080, 1920, ARTWORK_BOX_PX, ARTWORK_BOX_PX))
    }

    @Test fun aFilmSmallerThanTheBoxIsStillNeverUpscaled() {
        // The box is a ceiling, not a target: inventing pixels would cost memory on the one
        // bitmap this app holds live for the length of a cast.
        assertEquals(320 to 240, previewFrameSize(320, 240, ARTWORK_BOX_PX, ARTWORK_BOX_PX))
    }

    @Test fun theSquarestFilmStillFitsInABinderTransaction() {
        // This picture is parceled to SystemUI as the notification's large icon, and a
        // transaction that overruns takes the notification with it. A square film is the
        // largest thing a square box can produce.
        val (width, height) = previewFrameSize(1080, 1080, ARTWORK_BOX_PX, ARTWORK_BOX_PX)
        val bytes = width * height * BytesPerPixel
        assertEquals(802_816, bytes)
        assertTrue("$bytes", bytes < BinderCeilingBytes - NotificationHeadroomBytes)
    }

    private companion object {
        const val BytesPerPixel = 4
        const val BinderCeilingBytes = 1_048_576
        const val NotificationHeadroomBytes = 200_000
    }
}
