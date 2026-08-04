package com.flick.sender.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CastArtworkMatTest {

    @Test fun theGroundOutScoresASaturatedFrameForEveryShapeAFilmIsShotIn() {
        // The load-bearing number of the whole mat, stated as the comparison SystemUI
        // actually makes rather than as a bare share. Every shape a film is really shot in
        // has to beat a frame ENTIRELY filled with the loudest colour a real scene
        // quantizes to, or the media panel takes its colour from the footage.
        for ((name, shape) in Shapes) {
            val share = matPlacement(shape.first, shape.second).groundShare
            assertTrue("$name: $share", margin(share, LoudSceneChromaTerm) > 0f)
        }
    }

    @Test fun onlyAnUnreachablyPureFrameStillOutScoresTheGround() {
        // Recorded rather than defended. A frame that is 100% one hue at chroma 113 beats
        // the ground at 4:3, and no mat that leaves a picture changes that — 65 px would,
        // and the still is 318 px by then. What is asserted is the shape of the residue:
        // widescreen, which is nearly everything this app is pointed at, wins even there.
        assertTrue(margin(groundShareOf("16:9"), PureChromaTerm) > 0f)
        assertTrue(margin(groundShareOf("2.39:1"), PureChromaTerm) > 0f)
        assertTrue(margin(groundShareOf("4:3"), PureChromaTerm) < 0f)
        // And it takes most of the frame to do it: half a 4:3 frame in one pure hue loses.
        assertTrue(margin(groundShareOf("4:3"), PureChromaTerm, filled = 0.5f) > 0f)
    }

    @Test fun aSquareFilmIsTheOneShapeThatLeavesTheLeastGround() {
        // No film is shot this way, and the shape survives here because it is the largest
        // picture the box can produce — so it is the floor the rest of the rule sits above.
        val share = matPlacement(1080, 1080).groundShare
        assertTrue("$share", share in 0.43f..0.44f)
        assertTrue(margin(share, LoudSceneChromaTerm, filled = 0.35f) > 0f)
    }

    @Test fun theFrameIsCentredAndTheGroundIsClearOnEverySide() {
        val shapes = listOf(
            3840 to 2160,
            1080 to 1920,
            1440 to 1080,
            1080 to 1080,
            320 to 240,
        )
        for ((width, height) in shapes) {
            val placement = matPlacement(width, height)
            assertEquals(
                "$width x $height left",
                (ARTWORK_BOX_PX - placement.width) / 2,
                placement.left,
            )
            assertEquals(
                "$width x $height top",
                (ARTWORK_BOX_PX - placement.height) / 2,
                placement.top,
            )
            assertTrue("$width x $height left", placement.left >= ARTWORK_MAT_PX)
            assertTrue("$width x $height top", placement.top >= ARTWORK_MAT_PX)
            assertTrue(
                "$width x $height right",
                placement.left + placement.width <= ARTWORK_BOX_PX - ARTWORK_MAT_PX,
            )
            assertTrue(
                "$width x $height bottom",
                placement.top + placement.height <= ARTWORK_BOX_PX - ARTWORK_MAT_PX,
            )
        }
    }

    @Test fun aFrameLargerThanTheOpeningIsBroughtBackInsideIt() {
        // The provider's cached thumbnail is asked for a size and is free to answer with
        // another, so the mat may not assume the still already fits the hole cut for it.
        val placement = matPlacement(2000, 2000)
        assertEquals(ARTWORK_STILL_BOX_PX, placement.width)
        assertEquals(ARTWORK_STILL_BOX_PX, placement.height)
    }

    @Test fun aFilmSmallerThanTheOpeningKeepsItsOwnPixelsAndSitsInMoreGround() {
        val placement = matPlacement(320, 240)
        assertEquals(320, placement.width)
        assertEquals(240, placement.height)
        assertTrue("${placement.groundShare}", placement.groundShare > 0.6f)
    }

    @Test fun aFrameWithNoHeightLeftToScaleStillHasSomewhereToGo() {
        // An anamorphic container can report an aspect this side of absurd, and the fit
        // arithmetic would hand back a zero-pixel edge. The placement has to stay a rectangle
        // the canvas can draw into whatever it is given.
        val placement = matPlacement(4096, 1)
        assertTrue("${placement.width} x ${placement.height}", placement.height >= 1)
        assertEquals(ARTWORK_STILL_BOX_PX, placement.width)
        assertTrue("${placement.top}", placement.top >= ARTWORK_MAT_PX)
    }

    private fun groundShareOf(name: String): Float =
        Shapes.getValue(name).let { matPlacement(it.first, it.second).groundShare }

    /**
     * By how many points Flick's amber beats the film's loudest colour, where [filled] is the
     * fraction of the STILL that colour covers.
     *
     * SystemUI scores a swatch at about `70 x its share of the picture` plus `(chroma - 48) x
     * 0.3`, so the ground's whole advantage is area and its whole handicap is chroma: this
     * amber is HCT chroma 57 against the 88 a loud scene quantizes to and the 113 of a pure
     * primary.
     */
    private fun margin(groundShare: Float, frameChromaTerm: Float, filled: Float = 1f): Float =
        (ShareWeight * groundShare + SparkChromaTerm) -
            (ShareWeight * (1f - groundShare) * filled + frameChromaTerm)

    private companion object {
        val Shapes = mapOf(
            "2.39:1" to (3840 to 1608),
            "16:9" to (3840 to 2160),
            "16:9 HD" to (1920 to 1080),
            "9:16 phone" to (1080 to 1920),
            "4:3" to (1440 to 1080),
        )

        const val ShareWeight = 70f

        /** #FFB61E: HCT chroma 57.4, so `(57.4 - 48) x 0.3`. */
        const val SparkChromaTerm = 2.8f

        /** Chroma 88 — what a red-lit scene's dominant swatch reaches. */
        const val LoudSceneChromaTerm = 12.2f

        /** Chroma 113: a pure primary, which no photographic frame is made of. */
        const val PureChromaTerm = 19.6f
    }
}
