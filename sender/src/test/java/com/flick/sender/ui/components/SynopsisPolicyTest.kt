package com.flick.sender.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SynopsisPolicyTest {

    @Test fun aClearlySidewaysRightwardDragReveals() {
        assertTrue(FlickPadPolicy.reveals(10f, 0f))
        assertTrue(FlickPadPolicy.reveals(9f, 6f))
        assertTrue(FlickPadPolicy.reveals(9f, -6f))
    }

    @Test fun everythingElseStaysTheThrows() {
        assertFalse("up", FlickPadPolicy.reveals(0f, -10f))
        assertFalse("down", FlickPadPolicy.reveals(0f, 10f))
        assertFalse("left", FlickPadPolicy.reveals(-10f, 0f))
        assertFalse("steep diagonal up", FlickPadPolicy.reveals(6f, -6f))
        assertFalse("just past the lock", FlickPadPolicy.reveals(8.9f, -6f))
    }

    @Test fun anUpwardThrowIsNeverReadAsARevealWhateverItsDrift() {
        // A thrown poster drifts sideways as the thumb arcs; up must stay up.
        for (dx in listOf(0f, 3f, 6f, 9f, 12f)) assertFalse(FlickPadPolicy.reveals(dx, -12f))
    }

    @Test fun anOpeningPullSettlesByDistanceOrSpeed() {
        assertFalse(SynopsisPolicy.settlesOpen(0.34f, 0f, opening = true))
        assertTrue(SynopsisPolicy.settlesOpen(0.35f, 0f, opening = true))
        assertTrue(SynopsisPolicy.settlesOpen(0.1f, 500f, opening = true))
        assertFalse(SynopsisPolicy.settlesOpen(0.9f, -500f, opening = true))
        // A fling never opens a panel that never moved.
        assertFalse(SynopsisPolicy.settlesOpen(0f, 2000f, opening = true))
    }

    @Test fun aClosingPushNeedsMoreThanAWobble() {
        assertTrue(SynopsisPolicy.settlesOpen(0.8f, 0f, opening = false))
        assertTrue(SynopsisPolicy.settlesOpen(0.65f, 0f, opening = false))
        assertFalse(SynopsisPolicy.settlesOpen(0.64f, 0f, opening = false))
        assertFalse(SynopsisPolicy.settlesOpen(0.95f, -500f, opening = false))
    }

    @Test fun progressFollowsTheFingerAndStaysInRange() {
        assertEquals(0.5f, SynopsisPolicy.progress(0f, 150f, 300f), 1e-6f)
        assertEquals(0.75f, SynopsisPolicy.progress(1f, -75f, 300f), 1e-6f)
        assertEquals(1f, SynopsisPolicy.progress(0.9f, 300f, 300f), 0f)
        assertEquals(0f, SynopsisPolicy.progress(0.1f, -300f, 300f), 0f)
    }

    @Test fun panelLeavesAPeekAndStopsAtAReadingWidth() {
        assertEquals(336f, SynopsisPolicy.panelWidthDp(384f), 0f)
        assertEquals(SynopsisPolicy.MAX_WIDTH_DP, SynopsisPolicy.panelWidthDp(900f), 0f)
    }

    @Test fun linesArriveInOrderAndAllLandBeforeThePanelDoes() {
        for (index in 0..4) {
            assertEquals(0f, SynopsisPolicy.lineIn(SynopsisPolicy.STAGGER_START, index), 0f)
            assertEquals("line $index whole at rest", 1f, SynopsisPolicy.lineIn(1f, index), 0f)
            if (index > 0) assertTrue(SynopsisPolicy.lineIn(0.6f, index) < SynopsisPolicy.lineIn(0.6f, index - 1))
        }
    }

    @Test fun predictiveBackOnlyLeansTheWayHome() {
        assertEquals(1f, SynopsisPolicy.backHold(0f), 0f)
        assertEquals(1f - SynopsisPolicy.BACK_TRAVEL, SynopsisPolicy.backHold(1f), 1e-6f)
        assertTrue(SynopsisPolicy.backHold(1f) > SynopsisPolicy.CLOSE_AT - 0.5f)
    }
}
