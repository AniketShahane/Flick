package com.flick.sender.ui.components

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameFlightDirectionTest {

    /** A library cell on a 412 dp frame, and the hero it opens into. */
    private val tile = Rect(left = 20f, top = 300f, right = 196f, bottom = 399f)
    private val hero = Rect(left = 0f, top = 0f, right = 412f, bottom = 309f)

    @Test
    fun aFrameGoingBackToItsGridCellIsAReturn() {
        assertTrue(frameReturning(from = hero, to = tile))
    }

    @Test
    fun aFrameOpeningOutOfItsGridCellIsNot() {
        assertFalse(frameReturning(from = tile, to = hero))
    }

    @Test
    fun aReturnThatIsSentForwardAgainMidFlightPicksTheOutwardSpring() {
        // An interrupted flight hands the spring its CURRENT bounds, not the seat it set
        // out from — so a half-shrunk frame asked back to the hero has to read as outward.
        val halfway = Rect(left = 10f, top = 150f, right = 304f, bottom = 354f)
        assertFalse(frameReturning(from = halfway, to = hero))
        assertTrue(frameReturning(from = halfway, to = tile))
    }

    @Test
    fun twoSeatsOfTheSameSizeAreNotAReturn() {
        // Nothing pairs two equal seats today. If something ever does, the flight out is
        // the safe answer: it is the slower of the two and cannot read as a rushed exit.
        assertFalse(frameReturning(from = hero, to = hero.translate(0f, 40f)))
    }

    @Test
    fun aSeatThatIsWiderButMuchShorterStillCountsAsSmaller() {
        // Area, not width: the connecting card and the cast poster do not share an aspect,
        // and a test on one edge alone would call a shrink an expansion.
        val letterbox = Rect(left = 0f, top = 0f, right = 460f, bottom = 60f)
        assertTrue(frameReturning(from = hero, to = letterbox))
    }
}
