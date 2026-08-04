package com.flick.sender.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PreviewFrameSizeTest {

    @Test
    fun a4kFrameLandsInThePreviewBoxRatherThanAtItsOwnSize() {
        // The whole point of the pre-27 path: one 3840x2160 frame is ~33 MB as ARGB_8888,
        // and the preview it feeds is 116x65dp.
        assertEquals(PREVIEW_WIDTH_PX to PREVIEW_HEIGHT_PX, previewFrameSize(3840, 2160))
        assertEquals(PREVIEW_WIDTH_PX to PREVIEW_HEIGHT_PX, previewFrameSize(1920, 1080))
    }

    @Test
    fun nothingIsEverUpscaled() {
        // Scaling up would spend memory to invent pixels; the newer platform call does not
        // do it either.
        assertEquals(100 to 50, previewFrameSize(100, 50))
        assertEquals(16 to 9, previewFrameSize(16, 9))
        assertEquals(
            PREVIEW_WIDTH_PX to PREVIEW_HEIGHT_PX,
            previewFrameSize(PREVIEW_WIDTH_PX, PREVIEW_HEIGHT_PX),
        )
    }

    @Test
    fun aFrameThatIsNot16By9FitsInsideTheBoxInBothAxes() {
        val sources = listOf(
            3840 to 2160,
            1440 to 1080,
            2160 to 3840,
            1080 to 1920,
            1000 to 100,
            100 to 1000,
        )
        for ((width, height) in sources) {
            val (outWidth, outHeight) = previewFrameSize(width, height)
            assertTrue("$width x $height -> $outWidth", outWidth in 1..PREVIEW_WIDTH_PX)
            assertTrue("$width x $height -> $outHeight", outHeight in 1..PREVIEW_HEIGHT_PX)
        }
    }

    @Test
    fun theSourcesAspectRatioSurvivesTheScale() {
        val sources = listOf(3840 to 2160, 1440 to 1080, 2160 to 3840, 1000 to 100)
        for ((width, height) in sources) {
            val (outWidth, outHeight) = previewFrameSize(width, height)
            val source = width.toFloat() / height
            val scaled = outWidth.toFloat() / outHeight
            // A pixel of rounding on a 90 px-tall still is the whole tolerance there is.
            assertTrue("$width x $height -> $outWidth x $outHeight", abs(source - scaled) < source * 0.03f)
        }
    }

    @Test
    fun aNamedBoxIsHonouredInsteadOfThePreviewOne() {
        // The tile search decodes into the size the still will be SHOWN at, and below API
        // 27 that box is reached by this scale rather than by the platform's.
        assertEquals(512 to 288, previewFrameSize(3840, 2160, 512, 288))
        assertEquals(384 to 288, previewFrameSize(1440, 1080, 512, 288))
        assertEquals(200 to 100, previewFrameSize(200, 100, 512, 288))
    }

    @Test
    fun aFrameThatReportsNoSizeStillAsksForTheBox() {
        // A retriever that hands back a bitmap with no usable dimensions must not produce a
        // zero-sized or negative-sized scale request, which throws.
        assertEquals(PREVIEW_WIDTH_PX to PREVIEW_HEIGHT_PX, previewFrameSize(0, 0))
        assertEquals(PREVIEW_WIDTH_PX to PREVIEW_HEIGHT_PX, previewFrameSize(-1, 720))
        assertEquals(PREVIEW_WIDTH_PX to PREVIEW_HEIGHT_PX, previewFrameSize(1280, 0))
    }
}
