package com.flick.receiver.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleLinkChecksTest {

    @Test fun theScheduleIsDenseEarlyAndRunsTenMinutes() {
        assertArrayEquals(
            longArrayOf(45, 60, 75, 90, 105, 120, 150, 180, 240, 300, 450, 600).map { it * 1_000L }.toLongArray(),
            IdleLinkChecks.DELAYS_MS,
        )
    }

    @Test fun theFirstCheckWaitsOutThePhonesOwnIdleTeardown() {
        assertTrue(IdleLinkChecks.DELAYS_MS.first() > IdleLinkChecks.PHONE_IDLE_TEARDOWN_MAX_MS)
    }

    @Test fun theChecksAreStrictlyAscending() {
        val delays = IdleLinkChecks.DELAYS_MS
        for (i in 1 until delays.size) assertTrue(delays[i] > delays[i - 1])
    }

    @Test fun aSeriesEndsOnlyOnceItsTeardownHasBeenFollowedByAnAnswer() {
        assertTrue(IdleLinkChecks.seriesDone(tornDown = true, phoneAnswered = true))
        assertFalse(IdleLinkChecks.seriesDone(tornDown = false, phoneAnswered = true))
        assertFalse(IdleLinkChecks.seriesDone(tornDown = true, phoneAnswered = false))
    }
}
