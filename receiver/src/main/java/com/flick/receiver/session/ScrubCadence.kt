package com.flick.receiver.session

/**
 * Whether a phone `seek` is part of a drag on the phone's progress bar.
 *
 * The wire has no drag verb and its capability list is frozen, so the cadence is the
 * only evidence: the phone sends a seek at most every 50 ms while a finger is down, and
 * a lone tap or a Replay sends one. Main thread; the clock is passed in.
 */
internal class ScrubCadence {
    private var lastSeekAtMs = NONE

    /** True when the previous phone seek arrived within [DRAG_WINDOW_MS]; records [nowMs]. */
    fun onPhoneSeek(nowMs: Long): Boolean {
        val previous = lastSeekAtMs
        lastSeekAtMs = nowMs
        return previous != NONE && nowMs - previous in 0..DRAG_WINDOW_MS
    }

    fun reset() {
        lastSeekAtMs = NONE
    }

    companion object {
        /** Five phone send intervals: wide enough for Wi-Fi jitter, far under a human double tap. */
        const val DRAG_WINDOW_MS = 250L

        private const val NONE = Long.MIN_VALUE
    }
}
