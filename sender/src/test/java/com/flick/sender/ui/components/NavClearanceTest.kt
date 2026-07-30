package com.flick.sender.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavClearanceTest {

    @Test
    fun nominalBarReservesTheRoomTheHandTunedLiteralDid() {
        // 116 dp was written into three routes by hand. The derivation has to agree with it
        // at the scale it was measured on, or this is a redesign rather than a fix.
        assertEquals(116.dp, navBottomClearance(NavMetrics().height, dockLive = false))
    }

    @Test
    fun aBarGrownByTheFontScaleReservesMoreThanTheLiteralEverCould() {
        // labelSmall is 11.5 sp on one line: at a 2.0 accessibility scale its line box
        // alone adds ~14 dp to the bar, which the literal could not see.
        val grown = NavMetrics().height + 14.dp
        val clearance = navBottomClearance(grown, dockLive = false)
        assertTrue("$clearance", clearance > 116.dp)
        assertEquals(130.dp, clearance)
    }

    @Test
    fun everyBarHeightKeepsItsMarginAndItsGap() {
        // Whatever the bar measures, the last row of a route clears it and is not left
        // touching it: the shell's own margin plus the design's gap, never less.
        for (height in listOf(0.dp, 48.dp, 78.dp, 96.dp, 120.dp, 240.dp)) {
            val clearance = navBottomClearance(height, dockLive = false)
            assertEquals("$height", 38.dp, clearance - height)
        }
    }

    @Test
    fun clearanceOnlyEverGrowsWithTheBar() {
        val heights = listOf(0.dp, 48.dp, 78.dp, 96.dp, 120.dp, 240.dp)
        val clearances = heights.map { navBottomClearance(it, dockLive = false) }
        assertEquals(clearances.sortedBy { it.value }, clearances)
    }

    @Test
    fun aLiveCastAddsExactlyTheDocksOwnClearance() {
        // The dock rides above the bar on the same stack, so it is part of one reservation
        // — and it is the dock's own published clearance, not a second guess at it.
        for (height in listOf(0.dp, 78.dp, 120.dp)) {
            val navOnly = navBottomClearance(height, dockLive = false)
            val navAndDock = navBottomClearance(height, dockLive = true)
            assertEquals("$height", NowPlayingDockClearance, navAndDock - navOnly)
        }
    }

    @Test
    fun theUnmeasuredBarIsAtLeastAsTallAsTheSeatItHolds() {
        // The nominal height is only ever used on the frame before the bar is measured, so
        // it has to be a floor rather than a guess: a seat is a legal 48 dp touch target
        // and the row pads it, so nothing shorter than that can be reserved.
        val metrics = NavMetrics()
        assertTrue("${metrics.height}", metrics.height >= 48.dp)
        metrics.height = 132.dp
        assertEquals(170.dp, navBottomClearance(metrics.height, dockLive = false))
    }
}
