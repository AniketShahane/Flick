package com.flick.sender.ui.screens

import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpressiveMotionPolicyTest {

    @Test
    fun physicalRouteDirectionMirrorsLogicalNavigationInRtl() {
        assertEquals(1, physicalRouteDirection(1, LayoutDirection.Ltr))
        assertEquals(-1, physicalRouteDirection(-1, LayoutDirection.Ltr))
        assertEquals(-1, physicalRouteDirection(1, LayoutDirection.Rtl))
        assertEquals(1, physicalRouteDirection(-1, LayoutDirection.Rtl))
        assertEquals(0, physicalRouteDirection(0, LayoutDirection.Rtl))
    }

    @Test
    fun compactRemoteLayoutProtectsBothShortWindowsAndLargeFonts() {
        assertTrue(needsCompactRemoteLayout(screenHeightDp = 480, fontScale = 1f))
        assertTrue(needsCompactRemoteLayout(screenHeightDp = 800, fontScale = 1.30f))
        assertFalse(needsCompactRemoteLayout(screenHeightDp = 800, fontScale = 1f))
        // The trigger is a height budget, not a flag: a tall phone still has the room
        // at 1.15x and must keep the one-handed remote.
        assertFalse(needsCompactRemoteLayout(screenHeightDp = 800, fontScale = 1.15f))
    }

    @Test
    fun bufferingNeverUsesTheUnboundedCompactScrollContainer() {
        assertTrue(usesCompactRemoteScroll(compactLayout = true, buffering = false))
        assertFalse(usesCompactRemoteScroll(compactLayout = true, buffering = true))
        assertFalse(usesCompactRemoteScroll(compactLayout = false, buffering = false))
    }
}
