package com.flick.receiver.player

/** The numbers a phone drag is played with; see `PlayerController.scrubTo`. */
internal object ScrubPolicy {
    /**
     * How far before its target a scrub frame may land. Covers x265's default
     * 250-frame GOP at 24 fps, so every scrub frame is the keyframe at or before the
     * target, and the exact landing after it only ever decodes forward.
     */
    const val TOLERANCE_BEFORE_MS = 15_000L

    /**
     * A drag with no further seek for this long shows its exact frame. The mode stays
     * on, so a finger still resting on the bar does not restart the film.
     */
    const val QUIET_MS = 1_000L

    /**
     * A drag with no further seek for this long is landed without the phone's
     * end-of-drag verb, which the phone always sends; this only covers its loss.
     */
    const val SAFETY_MS = 6_000L

    /**
     * [TOLERANCE_BEFORE_MS] as the fraction of the film Media3 takes it in, capped at
     * the whole film; null when the duration is unknown, which leaves seeks exact.
     */
    fun toleranceBeforeFraction(durationMs: Long): Double? {
        if (durationMs <= 0L) return null
        return minOf(1.0, TOLERANCE_BEFORE_MS.toDouble() / durationMs.toDouble())
    }
}

/** What one `scrubTo` asks of the player. */
internal enum class ScrubStep {
    /** The target is already the one shown or queued. */
    NONE,

    /** A keyframe-tolerant seek. */
    SEEK,

    /** The hold left the parameters exact: make them tolerant again, then seek. */
    RESTORE_TOLERANCE_THEN_SEEK,
}

/**
 * The bookkeeping of one drag, apart from the player it drives: which target is
 * newest, and whether the seek parameters are exact for it.
 */
internal class ScrubTrack {
    var lastTargetMs: Long = NO_TARGET
        private set
    var seeks: Int = 0
        private set

    /** The target the parameters were made exact for, or [NO_TARGET] while they are tolerant. */
    private var exactTargetMs: Long = NO_TARGET

    fun onScrub(targetMs: Long): ScrubStep {
        if (targetMs == lastTargetMs) return ScrubStep.NONE
        lastTargetMs = targetMs
        seeks++
        val wasExact = exactTargetMs != NO_TARGET
        exactTargetMs = NO_TARGET
        return if (wasExact) ScrubStep.RESTORE_TOLERANCE_THEN_SEEK else ScrubStep.SEEK
    }

    /** The target the quiet hold lands exactly, or null when it already has. */
    fun onHold(): Long? {
        if (lastTargetMs == NO_TARGET || exactTargetMs == lastTargetMs) return null
        exactTargetMs = lastTargetMs
        return lastTargetMs
    }

    /** The exact seek the drag's landing still needs, or null when the hold already made it. */
    fun landingSeekMs(): Long? =
        lastTargetMs.takeIf { it != NO_TARGET && it != exactTargetMs }

    companion object {
        const val NO_TARGET = -1L
    }
}
