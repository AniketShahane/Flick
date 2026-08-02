package com.flick.receiver.player

import androidx.media3.common.C
import org.junit.Assert.assertEquals
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

    @Test fun videoKeepsTheExistingGenerousCappedBackoff() {
        assertEquals(2_000L, lanLoadRetryDelayMs(C.TRACK_TYPE_VIDEO, errorCount = 1))
        assertEquals(5_000L, lanLoadRetryDelayMs(C.TRACK_TYPE_VIDEO, errorCount = 4))
        assertEquals(5_000L, lanLoadRetryDelayMs(C.TRACK_TYPE_VIDEO, errorCount = 20))
    }

    @Test fun nonTextMediaKeepsTheSamePolicyAsVideo() {
        assertEquals(2_000L, lanLoadRetryDelayMs(C.TRACK_TYPE_AUDIO, errorCount = 1))
        assertEquals(5_000L, lanLoadRetryDelayMs(C.TRACK_TYPE_UNKNOWN, errorCount = 9))
    }
}
