package com.flick.sender.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponsiveLayoutTest {
    @Test
    fun compactHeightTargetsShortLandscapeWindowsWithoutChangingPortraitLayouts() {
        assertTrue(isCompactHeight(384))
        assertFalse(isCompactHeight(480))
        assertFalse(isCompactHeight(800))
    }

    @Test
    fun compactWidthShortensDenseHeadersOnlyOnNarrowPhones() {
        assertTrue(isCompactWidth(360))
        assertFalse(isCompactWidth(380))
        assertFalse(isCompactWidth(411))
    }
}
