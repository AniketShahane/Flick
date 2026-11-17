package com.flick.receiver.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStarvationTest {

    @Test fun aPlayerThatWantsNothingIsNeverStarved() {
        val s = MediaStarvation()
        assertEquals(0L, s.starvedMs(10_000L))
        s.onMediaBytes(1_000L)
        assertEquals(0L, s.starvedMs(60_000L))
    }

    @Test fun starvationCountsFromTheWantWhenNoByteHasArrivedSince() {
        val s = MediaStarvation()
        s.onMediaBytes(1_000L)
        s.setWantsData(true, 5_000L)
        assertEquals(2_500L, s.starvedMs(7_500L))
    }

    @Test fun starvationCountsFromTheLastByteOnceBytesFlow() {
        val s = MediaStarvation()
        s.setWantsData(true, 5_000L)
        s.onMediaBytes(6_000L)
        assertEquals(0L, s.starvedMs(6_000L))
        assertEquals(3_000L, s.starvedMs(9_000L))
    }

    @Test fun aRepeatedWantKeepsItsOriginalStart() {
        val s = MediaStarvation()
        s.setWantsData(true, 5_000L)
        s.setWantsData(true, 8_000L)
        assertEquals(5_000L, s.starvedMs(10_000L))
    }

    @Test fun losingTheWantClearsStarvationAndANewWantStartsFresh() {
        val s = MediaStarvation()
        s.setWantsData(true, 5_000L)
        s.setWantsData(false, 9_000L)
        assertEquals(0L, s.starvedMs(9_500L))
        s.setWantsData(true, 20_000L)
        assertEquals(1_000L, s.starvedMs(21_000L))
    }

    @Test fun aClockReadBehindTheLastByteIsNotNegative() {
        val s = MediaStarvation()
        s.setWantsData(true, 5_000L)
        s.onMediaBytes(6_000L)
        assertEquals(0L, s.starvedMs(5_999L))
    }

    @Test fun theLastByteIsUnsetUntilOneArrives() {
        val s = MediaStarvation()
        assertEquals(Long.MIN_VALUE, s.lastMediaByteAtMs())
        s.onMediaBytes(42L)
        assertEquals(42L, s.lastMediaByteAtMs())
    }

    @Test fun aStreakOpensOnceAndRecoversOnABytePastItsLastError() {
        val streak = LoadErrorStreak()
        assertTrue(streak.onError(1_000L))
        assertFalse(streak.onError(2_000L))
        assertFalse(streak.onError(4_000L))
        assertNull(streak.closeIfRecovered(lastMediaByteAtMs = 3_000L, nowMs = 4_500L))
        assertEquals(LoadErrorStreak.Recovered(streakMs = 4_000L, errors = 3), streak.closeIfRecovered(4_200L, 5_000L))
        assertNull(streak.closeIfRecovered(6_000L, 6_000L))
        assertTrue(streak.onError(7_000L))
    }

    @Test fun aClearedStreakNeverReportsARecovery() {
        val streak = LoadErrorStreak()
        streak.onError(1_000L)
        streak.clear()
        assertNull(streak.closeIfRecovered(5_000L, 5_000L))
        assertTrue(streak.onError(6_000L))
    }

    @Test fun onlyASubtitleRoutesFirstAttemptIsHeld() {
        val loads = SubtitleLoads()
        assertFalse(loads.started(1L, subtitleRoute = false, retryCount = 0))
        assertTrue(loads.isEmpty)
        assertTrue(loads.started(2L, subtitleRoute = true, retryCount = 0))
        assertFalse(loads.isEmpty)
        assertFalse(loads.started(2L, subtitleRoute = true, retryCount = 0))
    }

    @Test fun aRetryingSubtitleIsNeverHeldAgain() {
        val loads = SubtitleLoads()
        loads.started(7L, subtitleRoute = true, retryCount = 0)
        assertTrue(loads.ended(7L))
        assertFalse(loads.started(7L, subtitleRoute = true, retryCount = 1))
        assertTrue(loads.isEmpty)
    }

    @Test fun endingOrClearingReleasesTheHold() {
        val loads = SubtitleLoads()
        loads.started(1L, subtitleRoute = true, retryCount = 0)
        loads.started(2L, subtitleRoute = true, retryCount = 0)
        assertTrue(loads.ended(1L))
        assertFalse(loads.ended(1L))
        assertFalse(loads.isEmpty)
        loads.clear()
        assertTrue(loads.isEmpty)
        assertFalse(loads.ended(2L))
    }
}
