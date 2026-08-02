package com.flick.receiver.player

import androidx.media3.common.C

/** Delay policy after Media3 has already classified the exception as retriable. */
internal fun lanLoadRetryDelayMs(trackType: Int, errorCount: Int): Long {
    if (trackType == C.TRACK_TYPE_TEXT) {
        return if (errorCount == 1) TEXT_LOAD_RETRY_DELAY_MS else C.TIME_UNSET
    }
    return minOf(1_000L * (errorCount + 1), MAX_MEDIA_LOAD_RETRY_DELAY_MS)
}

internal const val TEXT_LOAD_RETRY_DELAY_MS = 250L
internal const val MAX_MEDIA_LOAD_RETRY_DELAY_MS = 5_000L
