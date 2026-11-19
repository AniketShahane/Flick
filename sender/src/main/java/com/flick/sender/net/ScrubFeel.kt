package com.flick.sender.net

/**
 * Which cue one move of a drag plays, if any.
 *
 * Kept apart from [PlaybackSession] for the same reason [SeekPolicy] is: elapsed time is
 * a parameter here, so the rate limit can be tested without a clock.
 */
internal class ScrubFeel {
    private var detent = 0
    private var lastCueAtMs = 0L
    private var atEdge = false

    /**
     * The grip counts as the last cue, so the jump a tap-to-seek makes on its first move
     * does not stack a tick on top of it. Starting at an end does not arm a knock; only
     * arriving at one does.
     */
    fun start(targetMs: Long, durationMs: Long, nowMs: Long) {
        detent = SeekPolicy.detentOf(targetMs, durationMs)
        atEdge = isEdge(targetMs, durationMs)
        lastCueAtMs = nowMs
    }

    /**
     * A notch crossed inside [MIN_TICK_GAP_MS] of the last cue is dropped, not queued: a
     * fast drag crosses notches faster than a vibrator can separate them, and replaying
     * them late would tick where the thumb no longer is.
     */
    fun move(targetMs: Long, durationMs: Long, nowMs: Long): HapticCue? {
        val newDetent = SeekPolicy.detentOf(targetMs, durationMs)
        val crossed = newDetent != detent
        detent = newDetent
        val edge = isEdge(targetMs, durationMs)
        val arrived = edge && !atEdge
        atEdge = edge
        val cue = when {
            arrived -> HapticCue.EDGE
            crossed && nowMs - lastCueAtMs >= MIN_TICK_GAP_MS -> HapticCue.DETENT
            else -> null
        }
        if (cue != null) lastCueAtMs = nowMs
        return cue
    }

    private fun isEdge(targetMs: Long, durationMs: Long): Boolean =
        durationMs > 0L && (targetMs <= 0L || targetMs >= SeekPolicy.scrubMaxMs(durationMs))

    companion object {
        const val MIN_TICK_GAP_MS = 45L
    }
}
