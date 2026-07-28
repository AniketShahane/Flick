package com.flick.sender.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectingLayoutTest {
    @Test
    fun aPhoneWindowKeepsTheFullStackAtTheDefaultTypeScale() {
        assertTrue(connectingIsRoomy(viewportHeightDp = 694f, fontScale = 1f))
        assertTrue(connectingIsRoomy(viewportHeightDp = 520f, fontScale = 1f))
        assertFalse(connectingIsRoomy(viewportHeightDp = 519f, fontScale = 1f))
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
        assertFalse(connectingIsRoomy(viewportHeightDp = 500f, fontScale = 0.85f))
        assertTrue(connectingIsRoomy(viewportHeightDp = 520f, fontScale = 0.85f))
    }
}
