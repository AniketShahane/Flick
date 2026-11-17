package com.flick.receiver.player

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanLoadRetryPolicyTest {
    @Test fun aTextLoadGetsOnlyOneShortRetry() {
        assertEquals(
            TEXT_LOAD_RETRY_DELAY_MS,
            lanLoadRetryDelayMs(C.TRACK_TYPE_TEXT, errorCount = 1),
        )
        assertEquals(C.TIME_UNSET, lanLoadRetryDelayMs(C.TRACK_TYPE_TEXT, errorCount = 2))
        assertEquals(C.TIME_UNSET, lanLoadRetryDelayMs(C.TRACK_TYPE_TEXT, errorCount = 20))
    }

    @Test fun videoRetriesQuicklyAtFirstThenCapsAtThreeSeconds() {
        assertEquals(500L, lanLoadRetryDelayMs(C.TRACK_TYPE_VIDEO, errorCount = 1))
        assertEquals(1_000L, lanLoadRetryDelayMs(C.TRACK_TYPE_VIDEO, errorCount = 2))
        assertEquals(2_000L, lanLoadRetryDelayMs(C.TRACK_TYPE_VIDEO, errorCount = 3))
        assertEquals(3_000L, lanLoadRetryDelayMs(C.TRACK_TYPE_VIDEO, errorCount = 4))
        assertEquals(3_000L, lanLoadRetryDelayMs(C.TRACK_TYPE_VIDEO, errorCount = 34))
        assertEquals(MAX_MEDIA_LOAD_RETRY_DELAY_MS, lanLoadRetryDelayMs(C.TRACK_TYPE_VIDEO, errorCount = 1_000))
    }

    @Test fun nonTextMediaKeepsTheSamePolicyAsVideo() {
        assertEquals(500L, lanLoadRetryDelayMs(C.TRACK_TYPE_AUDIO, errorCount = 1))
        assertEquals(3_000L, lanLoadRetryDelayMs(C.TRACK_TYPE_UNKNOWN, errorCount = 9))
    }

    /** The retry count and the ladder together keep one quiet round near 100 s. */
    @Test fun oneRoundOfRetriesStillLastsAboutOneHundredSeconds() {
        val totalMs = (1..PlayerController.MAX_LOAD_RETRY_COUNT).sumOf { lanLoadRetryDelayMs(C.TRACK_TYPE_VIDEO, it) }
        assertTrue("totalMs=$totalMs", totalMs in 90_000L..110_000L)
    }

    @Test fun onlyServiceUnavailableIsARetriableStatus() {
        assertTrue(isRetriableHttpStatus(503))
        for (status in listOf(400, 403, 404, 410, 416, 500, 502, 504)) assertFalse("$status", isRetriableHttpStatus(status))
    }
}
