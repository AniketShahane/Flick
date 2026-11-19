package com.flick.receiver.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrubPolicyTest {

    @Test fun aClipNoLongerThanTheToleranceMayLandAnywhereBefore() {
        assertEquals(1.0, ScrubPolicy.toleranceBeforeFraction(15_000L)!!, 0.0)
        assertEquals(1.0, ScrubPolicy.toleranceBeforeFraction(4_000L)!!, 0.0)
    }

    @Test fun aTwoHourFilmGetsFifteenSecondsOfItsDuration() {
        val durationMs = 7_200_000L
        val fraction = ScrubPolicy.toleranceBeforeFraction(durationMs)!!
        assertEquals(15_000.0 / 7_200_000.0, fraction, 1e-12)
        // Media3 floors fraction × duration in microseconds; that must still be ~15 s.
        val toleranceUs = Math.floor(fraction * durationMs * 1_000L).toLong()
        assertEquals(15_000_000.0, toleranceUs.toDouble(), 1.0)
    }

    @Test fun anUnknownDurationGivesNoToleranceSoSeeksStayExact() {
        assertNull(ScrubPolicy.toleranceBeforeFraction(0L))
        assertNull(ScrubPolicy.toleranceBeforeFraction(-1L))
        // C.TIME_UNSET, spelled out so this test needs no Media3 on the classpath.
        assertNull(ScrubPolicy.toleranceBeforeFraction(Long.MIN_VALUE + 1))
    }

    @Test fun theSafetyLandingComesWellAfterTheHold() {
        assertTrue(ScrubPolicy.SAFETY_MS > ScrubPolicy.QUIET_MS)
    }

    @Test fun aDragSeeksTolerantlyAndLandsItsNewestTargetExactly() {
        val track = ScrubTrack()
        assertEquals(ScrubStep.SEEK, track.onScrub(600_000L))
        assertEquals(ScrubStep.SEEK, track.onScrub(610_000L))
        assertEquals(2, track.seeks)
        assertEquals(610_000L, track.landingSeekMs())
    }

    @Test fun anUnchangedTargetIsNoSeek() {
        val track = ScrubTrack()
        track.onScrub(600_000L)
        assertEquals(ScrubStep.NONE, track.onScrub(600_000L))
        assertEquals(1, track.seeks)
    }

    @Test fun theHoldLandsTheNewestTargetOnceAndTheLandingSkipsItsSeek() {
        val track = ScrubTrack()
        track.onScrub(600_000L)
        assertEquals(600_000L, track.onHold())
        assertNull(track.onHold())
        assertNull(track.landingSeekMs())
    }

    @Test fun aSeekAfterTheHoldRestoresTheToleranceAndNeedsALandingAgain() {
        val track = ScrubTrack()
        track.onScrub(600_000L)
        track.onHold()
        assertEquals(ScrubStep.RESTORE_TOLERANCE_THEN_SEEK, track.onScrub(620_000L))
        assertEquals(ScrubStep.SEEK, track.onScrub(630_000L))
        assertEquals(630_000L, track.landingSeekMs())
        assertEquals(630_000L, track.onHold())
    }

    /** Back on the held frame: still exact, so neither the parameters nor the landing change. */
    @Test fun returningToTheHeldTargetKeepsItExact() {
        val track = ScrubTrack()
        track.onScrub(600_000L)
        track.onHold()
        assertEquals(ScrubStep.NONE, track.onScrub(600_000L))
        assertNull(track.landingSeekMs())
    }

    @Test fun aTrackWithNoTargetHasNothingToHoldOrLand() {
        val track = ScrubTrack()
        assertNull(track.onHold())
        assertNull(track.landingSeekMs())
    }
}
