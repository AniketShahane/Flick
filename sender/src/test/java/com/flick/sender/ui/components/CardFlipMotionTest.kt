package com.flick.sender.ui.components

import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
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

    @Test fun theMarksRideTheCardWholeAtTheSlotAndGoneByAThirdOfTheWay() {
        assertEquals(1f, flipMarksAlpha(0f), 0f)
        assertEquals(1f, flipMarksAlpha(-0.01f), 0f)
        assertEquals(0f, flipMarksAlpha(CardFlipMotion.MarksTravel), 0f)
        assertEquals(0f, flipMarksAlpha(1f), 0f)
        assertEquals(0f, flipMarksAlpha(1.02f), 0f)
        // Still whole when the card counts as down, so the tile's own copy takes over unchanged.
        assertTrue(flipMarksAlpha(CardFlipMotion.LandedWithin) > 0.999f)
        for (i in 1 until steps.size) assertTrue(flipMarksAlpha(steps[i]) <= flipMarksAlpha(steps[i - 1]))
    }

    @Test fun theGlowUnderTheSeatIsGoneEarlyInTheTravelHome() {
        assertEquals(1f, flipGlowPresence(glow = 1f, move = 1f), 0f)
        assertEquals(0.4f, flipGlowPresence(glow = 0.4f, move = 1.01f), 1e-6f)
        for (m in steps.filter { it <= 1f - CardFlipMotion.GlowGoneTravel }) {
            assertEquals("move=$m", 0f, flipGlowPresence(glow = 1f, move = m), 0f)
        }
        assertTrue(CardFlipMotion.GlowGoneTravel <= 0.15f)
        for (i in 1 until steps.size) {
            assertTrue(flipGlowPresence(1f, steps[i]) >= flipGlowPresence(1f, steps[i - 1]))
        }
    }

    @Test fun aCardComingHomeIsFlatAtContactEvenWhenItsTurnLags() {
        // A reversed flight: the turn still well short of home, or past the seat's half-turn.
        for (turn in listOf(0f, 0.05f, 0.3f, 0.6f, 1.1f, -0.02f)) {
            assertEquals("turn=$turn", 0f, flipTurnPose(turn, move = 0f, backLeg = true), 0f)
            assertEquals("turn=$turn", 0f, flipTurnPose(turn, move = -0.01f, backLeg = true), 0f)
            // Untouched until the last of the travel, so turning round never jumps.
            assertEquals(turn, flipTurnPose(turn, CardFlipMotion.TurnSettleTravel, backLeg = true), 0f)
            assertEquals(turn, flipTurnPose(turn, 0.6f, backLeg = true), 0f)
            // Open is the turn as sprung.
            assertEquals(turn, flipTurnPose(turn, 0f, backLeg = false), 0f)
            // Down means flat: under half a degree of turn, so the roll is gone too.
            val atContact = flipTurnPose(turn, CardFlipMotion.LandedWithin, backLeg = true)
            assertTrue("turn=$turn", abs(180f * atContact) < 0.5f)
            assertTrue("turn=$turn", flipLanded(CardFlipMotion.LandedWithin, atContact))
            assertTrue("turn=$turn", abs(CardFlipMotion.TiltDegrees * sin(PI.toFloat() * atContact)) < 0.1f)
        }
        // On a fresh leg the turn follows the travel, and the pose only ever eases it.
        for (m in steps) assertTrue(abs(flipTurnPose(m, m, backLeg = true)) <= m + 1e-6f)
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

    @Test fun theCardThatIsDrawnTravelsOnTheSameSpringAsTheEndThatTakesItBack() {
        val hidden = listOf(EnterExitState.PostExit, EnterExitState.PreEnter)
        // Opening: the page heads for Visible while the grid leaves.
        assertEquals(CardFlipMotion.MoveOpen, seatBoundsTrack(EnterExitState.Visible))
        for (leaving in hidden) assertEquals(CardFlipMotion.MoveOpen, tileBoundsTrack(leaving))
        // Back: the page leaves while the grid heads for Visible.
        for (leaving in hidden) {
            val seat = seatBoundsTrack(leaving)
            assertEquals(tileBoundsTrack(EnterExitState.Visible), seat)
            // And the travel track the landing is judged on.
            assertEquals(CardFlipMotion.MoveBack, seat)
            assertEquals(1f, seat.dampingRatio, 0f)
        }
    }

    @Test fun theCardIsHandedBackOnlyAtTheFacesOwnSize() {
        val face = IntSize(412, 232)
        assertTrue(flipSeatFitsFace(IntSize(412, 232), face))
        assertFalse(flipSeatFitsFace(IntSize(411, 232), face))
        assertFalse(flipSeatFitsFace(IntSize(412, 230), face))
        assertFalse(flipSeatFitsFace(null, face))
        assertFalse(flipSeatFitsFace(face, null))
    }

    @Test fun theFaceIsHomeOnlyWithinHalfAPixelAtTheSlotsOwnSize() {
        val slot = IntSize(412, 232)
        assertTrue(flipFaceHome(Offset.Zero, slot, slot))
        assertTrue(flipFaceHome(Offset(0.5f, -0.5f), slot, slot))
        assertFalse(flipFaceHome(Offset(0f, 1f), slot, slot))
        assertFalse(flipFaceHome(Offset(-1f, 0f), slot, slot))
        assertFalse(flipFaceHome(Offset.Zero, IntSize(413, 232), slot))
        assertFalse(flipFaceHome(Offset.Zero, IntSize(412, 236), slot))
    }

    private fun FlipTrack.legMs(): Long =
        TargetBasedAnimation(springSpec(), Float.VectorConverter, 1f, 0f).durationNanos / 1_000_000

    @Test fun theBackLegOutlastsTheTravelSoTheBoundsArriveOnTheirOwn() {
        val move = CardFlipMotion.MoveBack.legMs()
        val longest = CardFlipMotion.BackTracks.maxOf { it.legMs() }
        assertEquals(CardFlipMotion.SettleBack.legMs(), longest)
        // The bounds start a frame or two after the travel and need a few more to close their
        // last pixels: at least six frames at 120 Hz.
        assertTrue("move=$move settle=$longest", longest - move >= 50)
        // Open, it ends inside the page's own tail.
        val open = CardFlipMotion.OpenTracks.maxOf { it.delayMs + it.legMs() }
        assertTrue(CardFlipMotion.SettleBack.legMs() < open)
    }
}
