package com.flick.sender.ui.screens

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryControlsMotionPolicyTest {

    @Test fun folderCapLeavesTheWholeControlClusterOnAnOrdinaryPhoneRow() {
        // 360 − 48 search − 64 sort − 8 × 2 gaps, which is under the three-quarter ceiling.
        assertEquals(232.dp, libraryFolderWidthCap(360.dp))
    }

    @Test fun folderCapAlwaysLeavesBothControlsAndTheirGapsOnANarrowRow() {
        assertEquals(52.dp, libraryFolderWidthCap(180.dp))
        assertEquals(0.dp, libraryFolderWidthCap(40.dp))
    }

    @Test fun folderCapNeverExceedsThreeQuartersOfAWideRow() {
        assertEquals(720.dp, libraryFolderWidthCap(960.dp))
    }

    @Test fun theSortControlSitsOneGapInFromTheSearchTarget() {
        // Its seat ends exactly where the search target's gap begins: 240 + 64 + 8 = 312.
        assertEquals(240.dp, librarySortSeat(360.dp))
        assertEquals(0.dp, librarySortSeat(100.dp))
    }

    @Test fun theFolderChipStopsExactlyOneGapShortOfTheSortControl() {
        listOf(360.dp, 411.dp, 600.dp).forEach { row ->
            val clear = librarySortSeat(row) - libraryFolderWidthCap(row)
            assertTrue("folder and sort collide at $row", clear >= 8.dp)
        }
    }

    @Test fun searchKickUsesTheQualityChipsWideAndShortPose() {
        assertEquals(1f, searchKickScaleX(0f), 0.0001f)
        assertEquals(1f, searchKickScaleY(0f), 0.0001f)
        assertEquals(1.10f, searchKickScaleX(1f), 0.0001f)
        assertEquals(0.92f, searchKickScaleY(1f), 0.0001f)
    }

    @Test fun searchTravelArcIsDecorativeAndReturnsToRestAtBothSeats() {
        assertEquals(0f, searchTravelArc(0f), 0.0001f)
        assertEquals(1f, searchTravelArc(0.5f), 0.0001f)
        assertEquals(0f, searchTravelArc(1f), 0.0001f)
        assertEquals(0f, searchTravelArc(-0.2f), 0.0001f)
        assertEquals(0f, searchTravelArc(1.2f), 0.0001f)
    }
}
