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
    fun aDismissedOverlayIsDisposedWithoutASecondFullScreenExit() {
        assertFalse(shouldFadeOverlayExit(initialPresent = true, targetPresent = false))
        assertFalse(shouldFadeOverlayExit(initialPresent = false, targetPresent = true))
        assertTrue(shouldFadeOverlayExit(initialPresent = true, targetPresent = true))
    }
}
