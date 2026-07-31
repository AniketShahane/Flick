package com.flick.sender.ui.screens

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryControlsMotionPolicyTest {

    @Test fun folderCapUsesThreeQuartersOnAnOrdinaryPhoneRow() {
        assertEquals(270.dp, libraryFolderWidthCap(360.dp))
    }

    @Test fun folderCapAlwaysLeavesTheSearchTargetAndGapOnANarrowRow() {
        assertEquals(124.dp, libraryFolderWidthCap(180.dp))
        assertEquals(0.dp, libraryFolderWidthCap(40.dp))
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
