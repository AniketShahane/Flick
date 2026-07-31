package com.flick.sender.ui.components

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlickBottomNavMotionPolicyTest {

    private val library = Rect(left = 12f, top = 0f, right = 88f, bottom = 56f)
    private val devices = Rect(left = 126f, top = 0f, right = 202f, bottom = 56f)
    private val betweenLibraryAndDevices = Rect(left = 64f, top = 0f, right = 140f, bottom = 56f)

    @Test fun aRapidTapBackToTheSelectedSeatReversesAnInFlightTrip() {
        assertTrue(
            navShouldStartTravel(
                indicator = betweenLibraryAndDevices,
                isRunning = true,
                target = devices,
                destination = library,
            ),
        )
    }

    @Test fun aRapidTapBackReversesBeforeTheFirstAnimatedFrameMovesTheIndicator() {
        assertTrue(
            navShouldStartTravel(
                indicator = library,
                isRunning = true,
                target = devices,
                destination = library,
            ),
        )
    }

    @Test fun aRefusedNavigationCanStillBeCorrectedBackToTheResolvedSeat() {
        assertTrue(
            navShouldStartTravel(
                indicator = devices,
                isRunning = false,
                target = devices,
                destination = library,
            ),
        )
    }

    @Test fun retappingTheCommandedSeatDoesNotRestartTheSpring() {
        assertFalse(
            navShouldStartTravel(
                indicator = betweenLibraryAndDevices,
                isRunning = true,
                target = devices,
                destination = devices,
            ),
        )
    }

    @Test fun aSeatAlreadyReachedDoesNotStartAnotherTrip() {
        assertFalse(
            navShouldStartTravel(
                indicator = library,
                isRunning = false,
                target = library,
                destination = library,
            ),
        )
    }

    @Test fun anInterruptedTripToTheRecordedTargetCanAlwaysRestart() {
        assertTrue(
            navShouldStartTravel(
                indicator = betweenLibraryAndDevices,
                isRunning = false,
                target = devices,
                destination = devices,
            ),
        )
    }

    @Test fun firstPlacementStillBelongsToTheLongLivedController() {
        assertFalse(navShouldStartTravel(Rect.Zero, false, null, devices))
    }
}
