package com.flick.sender.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SynopsisPolicyTest {

    @Test fun aClearlySidewaysLeftwardDragReveals() {
        assertTrue(FlickPadPolicy.reveals(-10f, 0f))
        assertTrue(FlickPadPolicy.reveals(-9f, 6f))
        assertTrue(FlickPadPolicy.reveals(-9f, -6f))
    }

    @Test fun everythingElseStaysTheThrows() {
        assertFalse("up", FlickPadPolicy.reveals(0f, -10f))
        assertFalse("down", FlickPadPolicy.reveals(0f, 10f))
        assertFalse("right is the throw's sideways follow again", FlickPadPolicy.reveals(10f, 0f))
        assertFalse("steep diagonal up", FlickPadPolicy.reveals(-6f, -6f))
        assertFalse("just past the lock", FlickPadPolicy.reveals(-8.9f, -6f))
    }

    @Test fun anUpwardThrowIsNeverReadAsARevealWhateverItsDrift() {
        // A thrown poster drifts sideways as the thumb arcs; up must stay up.
        for (dx in listOf(-12f, -9f, -6f, -3f, 0f, 3f, 6f, 9f, 12f)) assertFalse(FlickPadPolicy.reveals(dx, -12f))
    }

    @Test fun anOpeningPullSettlesByDistanceOrSpeed() {
        assertFalse(SynopsisPolicy.settlesOpen(0.34f, 0f, opening = true))
        assertTrue(SynopsisPolicy.settlesOpen(0.35f, 0f, opening = true))
        assertTrue(SynopsisPolicy.settlesOpen(0.1f, 500f, opening = true))
        assertFalse(SynopsisPolicy.settlesOpen(0.9f, -500f, opening = true))
        // A fling never opens a story that never moved.
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

    @Test fun theWholeRevealIsSeventyTwoPercentOfThePhonesWidth() {
        assertEquals(360f * 0.72f, SynopsisPolicy.travelDp(360f), 1e-3f)
        assertEquals(SynopsisPolicy.MAX_TRAVEL_DP, SynopsisPolicy.travelDp(900f), 0f)
    }

    @Test fun theChromeIsGoneBeforeTheCardArrives() {
        assertEquals(1f, SynopsisPolicy.chromeAlpha(0f), 0f)
        assertEquals(0f, SynopsisPolicy.chromeAlpha(1f / SynopsisPolicy.CHROME_FADE), 1e-6f)
        assertEquals(0f, SynopsisPolicy.cardAlpha(0.45f), 0f)
        assertTrue(SynopsisPolicy.chromeAlpha(0.5f) < 0.25f)
    }

    @Test fun thePosterLandsOnTheStripAsAFrameAndOnlyThenFades() {
        val end = SynopsisPolicy.FRAME_WIDTH_DP / 230f
        assertEquals(1f, SynopsisPolicy.posterScale(0f, end), 0f)
        assertEquals(end, SynopsisPolicy.posterScale(1f, end), 1e-6f)
        // The prototype's 230 dp poster lands at 1 − 0.68 of itself.
        assertEquals(0.32f, SynopsisPolicy.posterScale(1f, end), 0.005f)
        assertEquals(1f, SynopsisPolicy.posterAlpha(0.55f), 0f)
        assertEquals(0f, SynopsisPolicy.posterAlpha(0.85f), 1e-6f)
        assertEquals(66f, SynopsisPolicy.STRIP_CENTER_DP, 0f)
        assertEquals(134f, SynopsisPolicy.CARD_START_DP, 0f)
    }

    @Test fun theStripComesInFromOffTheEdge() {
        assertEquals(-SynopsisPolicy.STRIP_FROM_DP, SynopsisPolicy.stripShiftDp(0f), 0f)
        assertEquals(0f, SynopsisPolicy.stripShiftDp(1f), 0f)
        assertEquals(0f, SynopsisPolicy.stripAlpha(0.2f), 0f)
        assertEquals(1f, SynopsisPolicy.stripAlpha(0.6f), 1e-6f)
    }

    @Test fun holesAndFramesRepeatOnOnePitch() {
        // One layer translation, modulo one pitch, runs both: the holes must divide it.
        assertEquals(123f, SynopsisPolicy.FRAME_PITCH_DP, 0f)
        val holePitch = SynopsisPolicy.FRAME_PITCH_DP / SynopsisPolicy.SPROCKETS_PER_FRAME
        assertTrue("about 16 dp apart", holePitch in 15f..17f)
        assertTrue(holePitch > SynopsisPolicy.SPROCKET_HEIGHT_DP)
    }

    @Test fun linesArriveInOrderAndAllLandBeforeTheStoryDoes() {
        for (index in 0..4) {
            assertEquals(0f, SynopsisPolicy.lineIn(SynopsisPolicy.LINES_START + SynopsisPolicy.LINES_STEP * index, index), 0f)
            assertEquals("line $index whole at rest", 1f, SynopsisPolicy.lineIn(1f, index), 0f)
            if (index > 0) assertTrue(SynopsisPolicy.lineIn(0.85f, index) < SynopsisPolicy.lineIn(0.85f, index - 1))
        }
    }

    @Test fun easingsMeetTheirEnds() {
        assertEquals(0f, SynopsisPolicy.easeInOutCubic(0f), 0f)
        assertEquals(0.5f, SynopsisPolicy.easeInOutCubic(0.5f), 1e-6f)
        assertEquals(1f, SynopsisPolicy.easeInOutCubic(1f), 0f)
        assertEquals(1f, SynopsisPolicy.easeInOutCubic(1.08f), 0f)
        assertEquals(0.875f, SynopsisPolicy.easeOutCubic(0.5f), 1e-6f)
    }

    @Test fun longCopyStepsDown() {
        assertEquals(32f, SynopsisPolicy.titleSp("The Matrix"), 0f)
        assertEquals(26f, SynopsisPolicy.titleSp("Blade Runner 2049"), 0f)
        assertEquals(16f, SynopsisPolicy.overviewSp("x".repeat(240)), 0f)
        assertEquals(14.5f, SynopsisPolicy.overviewSp("x".repeat(241)), 0f)
    }

    @Test fun predictiveBackOnlyLeansTheWayHome() {
        assertEquals(1f, SynopsisPolicy.backHold(0f), 0f)
        assertEquals(1f - SynopsisPolicy.BACK_TRAVEL, SynopsisPolicy.backHold(1f), 1e-6f)
        assertTrue(SynopsisPolicy.backHold(1f) > SynopsisPolicy.CLOSE_AT - 0.5f)
    }
}
