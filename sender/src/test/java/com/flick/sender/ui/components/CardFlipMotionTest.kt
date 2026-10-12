package com.flick.sender.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardFlipMotionTest {

    private val steps = (0..100).map { it / 100f }

    @Test fun openingTheLiftIsWholeBeforeTheCardTravelsAndGoneWhenItArrives() {
        assertEquals(1f + CardFlipMotion.LiftScale, flipLiftScale(lift = 1f, move = 0f, backLeg = false), 1e-6f)
        assertEquals(1f, flipLiftScale(lift = 1f, move = 1f, backLeg = false), 0f)
        // The opening lift is underdamped on purpose; its overshoot reaches the scale.
        assertTrue(flipLiftScale(lift = 1.03f, move = 0f, backLeg = false) > 1f + CardFlipMotion.LiftScale)
    }

    @Test fun comingBackTheCardMeetsTheTileAtTheTilesSize() {
        for (lift in listOf(0f, 0.5f, 1f, 1.2f, -0.1f)) {
            assertEquals("lift=$lift", 1f, flipLiftScale(lift, move = 0f, backLeg = true), 0f)
            // And it leaves the seat at the seat's size.
            assertEquals("lift=$lift", 1f, flipLiftScale(lift, move = 1f, backLeg = true), 0f)
        }
        // A travel spring that dips under home is still clamped to contact.
        assertEquals(1f, flipLiftScale(lift = 1f, move = -0.01f, backLeg = true), 0f)
        for (m in steps) {
            assertTrue(flipLiftScale(1f, m, backLeg = true) <= flipLiftScale(1f, m, backLeg = false) + 1e-6f)
        }
    }

    @Test fun theSlotIsWholeAtContactAndGoneWhileTheCardIsAway() {
        assertEquals(0f, flipBadgeHidden(0f), 0f)
        assertEquals(1f, flipBadgeHidden(CardFlipMotion.BadgeReturnTravel), 0f)
        assertEquals(1f, flipBadgeHidden(1f), 0f)
        for (i in 1 until steps.size) assertTrue(flipBadgeHidden(steps[i]) >= flipBadgeHidden(steps[i - 1]))
    }

    @Test fun theCardIsDownOnlyWhenBothTheTravelAndTheTurnAre() {
        assertTrue(flipLanded(0f, 0f))
        assertTrue(flipLanded(CardFlipMotion.LandedWithin, -CardFlipMotion.LandedWithin))
        assertFalse(flipLanded(0.01f, 0f))
        assertFalse(flipLanded(0f, 0.01f))
        // The chrome is released before the card is down, never after.
        assertFalse(flipLanded(CardFlipMotion.ChromeRiseTravel, 0f))
        assertTrue(CardFlipMotion.ChromeRiseTravel > CardFlipMotion.LandedWithin)
        // And the slot is whole by the time the card counts as down.
        assertTrue(flipBadgeHidden(CardFlipMotion.LandedWithin) < 0.05f)
    }

    @Test fun aLegStartsFreshOnlyFromAPoseAtOneEnd() {
        assertTrue(flipPoseAtRest(emptyList()))
        assertTrue(flipPoseAtRest(listOf(0f, 0f, 0.001f)))
        assertTrue(flipPoseAtRest(listOf(1f, 0.999f, 1f)))
        assertFalse(flipPoseAtRest(listOf(0f, 1f)))
        assertFalse(flipPoseAtRest(listOf(1f, 0.6f, 1f)))
        assertFalse(flipPoseAtRest(listOf(1.04f, 1f)))
    }

    @Test fun theStaggerReleasesEachDistinctDelayOnceInOrder() {
        assertEquals(
            listOf(60, 260, 290, 320, 350, 380, 460, 480),
            flipStaggerSteps(CardFlipMotion.OpenTracks),
        )
        assertEquals(emptyList<Int>(), flipStaggerSteps(CardFlipMotion.BackTracks))
        assertEquals(listOf(10, 20), flipStaggerSteps(listOf(FlipTrack(20, 1f, 1f), FlipTrack(10, 1f, 1f), FlipTrack(0, 1f, 1f))))
    }

    @Test fun backHasNoTailAndTheTravelHasNoDelay() {
        for (track in CardFlipMotion.BackTracks + CardFlipMotion.BadgeBack) assertEquals(0, track.delayMs)
        assertEquals(0, CardFlipMotion.MoveOpen.delayMs)
        // No-bounce home: an underdamped spring would carry the card past the tile.
        for (track in CardFlipMotion.BackTracks) assertEquals(1f, track.dampingRatio, 0f)
    }
}
