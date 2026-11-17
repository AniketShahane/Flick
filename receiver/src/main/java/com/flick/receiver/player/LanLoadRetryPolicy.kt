package com.flick.receiver.player

import androidx.media3.common.C

/** Delay policy after Media3 has already classified the exception as retriable. */
internal fun lanLoadRetryDelayMs(trackType: Int, errorCount: Int): Long {
    if (trackType == C.TRACK_TYPE_TEXT) {
        return if (errorCount == 1) TEXT_LOAD_RETRY_DELAY_MS else C.TIME_UNSET
    }
    return MEDIA_LOAD_RETRY_DELAYS_MS[(errorCount - 1).coerceIn(0, MEDIA_LOAD_RETRY_DELAYS_MS.lastIndex)]
}

/**
 * The one HTTP status the load policy retries. The phone answers 503 while it is
 * too busy to serve a range (a moment, not a verdict); every other status is the
 * phone saying no, and retrying a refusal only delays the diagnosis.
 */
internal fun isRetriableHttpStatus(status: Int): Boolean = status == 503

internal const val TEXT_LOAD_RETRY_DELAY_MS = 250L

/**
 * Short first steps so a stall a teardown or a router blip has already cured is
 * re-fetched within a second; the cap keeps one round of quiet retrying at about
 * 100 s together with `PlayerController.MAX_LOAD_RETRY_COUNT`.
 */
private val MEDIA_LOAD_RETRY_DELAYS_MS = longArrayOf(500L, 1_000L, 2_000L, 3_000L)
internal val MAX_MEDIA_LOAD_RETRY_DELAY_MS = MEDIA_LOAD_RETRY_DELAYS_MS.last()
