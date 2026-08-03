package com.flick.sender.net

import org.junit.Assert.assertEquals
import org.junit.Test

class CastRetryPolicyTest {
    @Test fun startupResumePreservesTheOriginalRequest() {
        assertEquals(
            RetryStart(20 * MINUTE_MS, startOver = false),
            retryStart(20 * MINUTE_MS, startOver = false, active = false, confirmedMs = 0L),
        )
    }

    @Test fun startupStartOverPreservesTheClearIntent() {
        assertEquals(
            RetryStart(0L, startOver = true),
            retryStart(0L, startOver = true, active = false, confirmedMs = 0L),
        )
    }

    @Test fun activeResumeRetriesFromTheLatestConfirmedPosition() {
        assertEquals(
            RetryStart(50 * MINUTE_MS, startOver = false),
            retryStart(20 * MINUTE_MS, startOver = false, active = true, confirmedMs = 50 * MINUTE_MS),
        )
    }

    @Test fun activeStartOverBecomesAResumeFromLatestConfirmedPosition() {
        assertEquals(
            RetryStart(50 * MINUTE_MS, startOver = false),
            retryStart(0L, startOver = true, active = true, confirmedMs = 50 * MINUTE_MS),
        )
    }

    @Test fun activeEarlyOrNearEndPositionFallsBackToPlainStart() {
        assertEquals(
            RetryStart(0L, startOver = false),
            retryStart(20 * MINUTE_MS, startOver = false, active = true, confirmedMs = 2_000L),
        )
        assertEquals(
            RetryStart(0L, startOver = false),
            retryStart(20 * MINUTE_MS, startOver = false, active = true, confirmedMs = DURATION_MS - 10_000L),
        )
    }

    private fun retryStart(
        originalStartMs: Long,
        startOver: Boolean,
        active: Boolean,
        confirmedMs: Long,
    ) = CastRetryPolicy.start(originalStartMs, startOver, active, confirmedMs, DURATION_MS)

    private companion object {
        const val MINUTE_MS = 60_000L
        const val DURATION_MS = 120 * MINUTE_MS
    }
}
