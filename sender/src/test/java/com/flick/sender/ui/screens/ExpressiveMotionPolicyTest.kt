package com.flick.sender.ui.screens

import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
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
}
