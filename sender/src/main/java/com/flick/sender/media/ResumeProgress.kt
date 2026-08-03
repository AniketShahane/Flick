package com.flick.sender.media

/**
 * A resume that is actually on offer, and how far into the film it lands. Both figures
 * come out of one call so a surface cannot show the second without the first being true.
 */
data class ResumeProgress(val positionMs: Long, val fraction: Float)

/**
 * Where a resume would start for the media identified by [fingerprint], or null when none
 * is offered at all.
 *
 * The one rule behind both the detail sheet's resume CTA and the library tile's progress
 * line: the coordinator's `resumePosition` resolves through here and so does
 * [resumeProgress], because a tile drawing a line for a resume the sheet does not offer —
 * or the reverse — is the bug this arrangement exists to make unrepresentable.
 */
internal fun resumePositionMs(
    state: PlaybackProgressState,
    fingerprint: String,
    durationMs: Long,
): Long? {
    val ready = state as? PlaybackProgressState.Ready ?: return null
    val checkpoint = ready.checkpoints[fingerprint] ?: return null
    return PlaybackResumePolicy.eligiblePosition(checkpoint.positionMs, durationMs)
}

/**
 * The watched share a library tile draws under its still, or null when it draws none.
 *
 * Null in exactly the cases [resumePositionMs] is, plus one more: a duration MediaStore
 * never measured. A resume is still offered there — the checkpoint is a position, and a
 * position needs no duration — but a fraction has no denominator, so the line is withheld
 * rather than drawn against a guess.
 */
internal fun resumeProgress(
    state: PlaybackProgressState,
    fingerprint: String,
    durationMs: Long,
): ResumeProgress? {
    if (durationMs <= 0L) return null
    val positionMs = resumePositionMs(state, fingerprint, durationMs) ?: return null
    return ResumeProgress(
        positionMs = positionMs,
        fraction = (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0).toFloat(),
    )
}
