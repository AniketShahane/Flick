package com.flick.sender.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectingLayoutTest {
    @Test
    fun aPhoneWindowKeepsTheFullStackAtTheDefaultTypeScale() {
        assertTrue(connectingIsRoomy(viewportHeightDp = 694f, fontScale = 1f))
        assertTrue(connectingIsRoomy(viewportHeightDp = 488f, fontScale = 1f))
        assertFalse(connectingIsRoomy(viewportHeightDp = 487f, fontScale = 1f))
    }

    @Test
    fun theBudgetGrowsWithTypeBecauseEveryLineInTheStackDoes() {
        // The window the report caught: 540 dp was judged roomy at any type scale, kept
        // the decorative diagram, and put Cancel under the status pill at 1.5x.
        assertTrue(connectingIsRoomy(viewportHeightDp = 540f, fontScale = 1f))
        assertFalse(connectingIsRoomy(viewportHeightDp = 540f, fontScale = 1.5f))
        assertFalse(connectingIsRoomy(viewportHeightDp = 640f, fontScale = 1.5f))
        assertTrue(connectingIsRoomy(viewportHeightDp = 800f, fontScale = 1.5f))
    }

    @Test
    fun aSmallTypeScaleBuysNoExtraSpacing() {
        // Spacing below the threshold is the design's decision, not the viewport's: a
        // 0.85x scale must not promote a window the layout was never drawn for.
        assertFalse(connectingIsRoomy(viewportHeightDp = 470f, fontScale = 0.85f))
        assertTrue(connectingIsRoomy(viewportHeightDp = 488f, fontScale = 0.85f))
    }

    @Test
    fun aTallPhoneKeepsTheCopyJustUnderTheVanishingPoint() {
        assertEquals(890f * 0.38f + 72f, connectingCopyTopDp(890f, bottomInsetDp = 24f, gapDp = 72f, fontScale = 1f), EPS)
    }

    @Test
    fun aShortWindowLiftsTheCopySoCancelStaysAboveTheFold() {
        // Landscape: at the fixed 38% the copy would start at 196 dp and run ~200 dp past a
        // 411 dp window's pill band.
        val landscape = connectingCopyTopDp(411f, bottomInsetDp = 24f, gapDp = 40f, fontScale = 1f)
        assertEquals(411f - 78f - 24f - 212f, landscape, EPS)
        assertTrue(landscape + 212f <= 411f - 78f - 24f + EPS)
        // The smallest roomy window, behind a three-button bar.
        val roomy = connectingCopyTopDp(520f, bottomInsetDp = 48f, gapDp = 72f, fontScale = 1f)
        assertTrue(roomy + 212f <= 520f - 78f - 48f + EPS)
        assertTrue(roomy - 72f > 0f)
    }

    @Test
    fun largerTypeLiftsTheCopyFurther() {
        val atOne = connectingCopyTopDp(700f, bottomInsetDp = 24f, gapDp = 40f, fontScale = 1f)
        val atTwo = connectingCopyTopDp(700f, bottomInsetDp = 24f, gapDp = 40f, fontScale = 2f)
        assertTrue(atTwo < atOne)
    }

    @Test
    fun theVanishingPointNeverLeavesTheWindow() {
        // Split screen at large type: no room at all, so the copy scrolls under a seat that
        // still sits inside the window.
        val top = connectingCopyTopDp(300f, bottomInsetDp = 0f, gapDp = 40f, fontScale = 2f)
        assertEquals(40f + 24f, top, EPS)
    }
}

private const val EPS = 1e-3f
