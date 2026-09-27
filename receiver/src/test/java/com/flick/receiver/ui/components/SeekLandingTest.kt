package com.flick.receiver.ui.components

import com.flick.receiver.session.SeekReconciler
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the end of a seek may be drawn as heard. `seeking` also falls on the
 * reconciler's deadline and on teardown, where the player never reported the
 * target; only a real landing earns the ring.
 */
class SeekLandingTest {

    @Test fun aForwardLandingWithinToleranceIsHeard() {
        assertTrue(seekLandingConfirmed(targetMs = 60_000L, originMs = 50_000L, confirmedMs = 59_800L))
    }

    @Test fun aForwardOvershootBeyondToleranceIsHeard() {
        val past = 60_000L + SeekReconciler.TOLERANCE_MS + 600L
        assertTrue(seekLandingConfirmed(targetMs = 60_000L, originMs = 50_000L, confirmedMs = past))
    }

    @Test fun aForwardDeadlineIsNotHeard() {
        // The film ran on for 1.2 s and the player never moved to the target.
        assertFalse(seekLandingConfirmed(targetMs = 60_000L, originMs = 50_000L, confirmedMs = 51_200L))
    }

    @Test fun aBackwardLandingIsHeard() {
        assertTrue(seekLandingConfirmed(targetMs = 40_000L, originMs = 50_000L, confirmedMs = 39_000L))
    }

    @Test fun aBackwardSeekThatNeverLandedIsNotHeard() {
        assertFalse(seekLandingConfirmed(targetMs = 40_000L, originMs = 50_000L, confirmedMs = 51_000L))
    }

    @Test fun aTargetAtTheOriginIsHeard() {
        assertTrue(seekLandingConfirmed(targetMs = 50_000L, originMs = 50_000L, confirmedMs = 50_000L))
    }

    @Test fun aRingAroundAFocusedKnobStartsOutsideTheFocusRing() {
        // The §3 ring's outer contour edge around a fully swollen 8 dp knob.
        val focusFootprint = 8.dp + FlickFocusRingOffset + FlickFocusRingWidth / 2 + FlickFocusRingContourWidth
        val landingInnerEdge = FocusedLandingRingStart - LandingRingStroke / 2 - FlickFocusRingContourWidth
        assertTrue(landingInnerEdge >= focusFootprint)
    }

    @Test fun aFocusedRingKeepsTheSameTravel() {
        val unfocused = landingRingRadius(11f, 17f, 16.5f, q = 1f, lift = 0f) -
            landingRingRadius(11f, 17f, 16.5f, q = 0f, lift = 0f)
        val focused = landingRingRadius(11f, 17f, 16.5f, q = 1f, lift = 1f) -
            landingRingRadius(11f, 17f, 16.5f, q = 0f, lift = 1f)
        assertEquals(unfocused, focused, 1e-4f)
        assertEquals(16.5f, landingRingRadius(11f, 17f, 16.5f, q = 0f, lift = 1f), 1e-4f)
    }
}
