package com.flick.sender.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlickPadPolicyTest {

    @Test
    fun distanceAloneArmsAtTheThreshold() {
        assertFalse(FlickPadPolicy.armed(79.9f))
        assertTrue(FlickPadPolicy.armed(80f))
        assertTrue(FlickPadPolicy.sends(upDp = 80f, upVelocityDpPerS = 0f))
        // A slow drag that stops short springs back.
        assertFalse(FlickPadPolicy.sends(upDp = 60f, upVelocityDpPerS = 200f))
    }

    @Test
    fun aFastFlickSendsFromShorterTravel() {
        assertTrue(FlickPadPolicy.sends(upDp = 20f, upVelocityDpPerS = 1000f))
        assertFalse(FlickPadPolicy.sends(upDp = 20f, upVelocityDpPerS = 999f))
    }

    @Test
    fun aTwitchIsNotAFlickHoweverFast() {
        // A tap that skids sideways can report a large instantaneous velocity.
        assertFalse(FlickPadPolicy.sends(upDp = 19f, upVelocityDpPerS = 5000f))
        assertFalse(FlickPadPolicy.sends(upDp = -40f, upVelocityDpPerS = 5000f))
    }

    @Test
    fun aDownwardFlingNeverSends() {
        assertFalse(FlickPadPolicy.sends(upDp = 30f, upVelocityDpPerS = -2000f))
    }

    @Test
    fun downwardTravelIsHeavilyResistedAndUpwardIsFollowedExactly() {
        assertEquals(-120f, FlickPadPolicy.followY(-120f), EPS)
        assertEquals(20f, FlickPadPolicy.followY(100f), EPS)
        assertEquals(0f, FlickPadPolicy.followY(0f), EPS)
    }

    @Test
    fun tiltFollowsTheSideDriftButIsCapped() {
        assertEquals(0f, FlickPadPolicy.tiltDegrees(0f), EPS)
        assertTrue(FlickPadPolicy.tiltDegrees(FlickPadPolicy.followX(40f)) > 0f)
        assertTrue(FlickPadPolicy.tiltDegrees(FlickPadPolicy.followX(-40f)) < 0f)
        assertEquals(FlickPadPolicy.MAX_TILT_DEG, FlickPadPolicy.tiltDegrees(1000f), EPS)
        assertEquals(-FlickPadPolicy.MAX_TILT_DEG, FlickPadPolicy.tiltDegrees(-1000f), EPS)
    }
}

private const val EPS = 1e-4f
