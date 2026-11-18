package com.flick.sender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The CPU lock outlives a cast's last served byte by at most six hours, and a serving cast keeps it. */
class WakeLockCeilingTest {
    private val hour = 60L * 60L * 1000L

    @Test fun theTuning() {
        assertEquals(2 * hour, WakeLockCeiling.TIMEOUT_MS)
        assertEquals(hour, WakeLockCeiling.RENEW_MS)
        assertEquals(4 * hour, WakeLockCeiling.ACTIVITY_WINDOW_MS)
    }

    @Test fun renewsOnlyWhileABytesWasServedWithinTheWindow() {
        val servedAt = 10 * hour
        assertTrue(WakeLockCeiling.renews(servedAt, servedAt + hour))
        assertTrue(WakeLockCeiling.renews(servedAt, servedAt + 4 * hour - 1))
        assertFalse(WakeLockCeiling.renews(servedAt, servedAt + 4 * hour))
    }

    @Test fun anIdleCastLapsesWithinSixHoursOfItsLastByte() {
        val startAt = 1_000L
        val lastByteAt = startAt + 30L * 60L * 1000L
        var expiresAt = startAt + WakeLockCeiling.TIMEOUT_MS
        var tickAt = startAt + WakeLockCeiling.RENEW_MS
        while (WakeLockCeiling.renews(lastByteAt, tickAt)) {
            expiresAt = tickAt + WakeLockCeiling.TIMEOUT_MS
            tickAt += WakeLockCeiling.RENEW_MS
        }
        assertTrue(expiresAt - lastByteAt <= 6 * hour)
        assertTrue(expiresAt - lastByteAt > 4 * hour)
    }

    @Test fun aCastThatKeepsServingKeepsItsLock() {
        val startAt = 1_000L
        var tickAt = startAt + WakeLockCeiling.RENEW_MS
        repeat(24) {
            assertTrue(WakeLockCeiling.renews(lastActivityAtMs = tickAt - 1_000L, nowMs = tickAt))
            tickAt += WakeLockCeiling.RENEW_MS
        }
    }

    @Test fun servingReacquiresOnlyForAnOwnerWithNoRenewalRunning() {
        assertTrue(WakeLockCeiling.reacquires(owned = true, renewing = false))
        assertFalse(WakeLockCeiling.reacquires(owned = true, renewing = true))
        assertFalse(WakeLockCeiling.reacquires(owned = false, renewing = false))
        assertFalse(WakeLockCeiling.reacquires(owned = false, renewing = true))
    }
}
