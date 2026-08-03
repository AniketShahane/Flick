package com.flick.receiver.session

/**
 * The one thing the startup budget is allowed to forgive.
 *
 * The deadline answers exactly one question — can this link carry this file —
 * and it answers it by measuring wall clock. A rotation re-prepare is not
 * evidence about the link: it is Flick deciding, on its own initiative, to
 * configure the decoder a second time because `MediaFormat.KEY_ROTATION` is read
 * only at codec configuration. That discards the fill and re-fetches it, and
 * charging the link for work the receiver chose to do turns a cosmetic fault
 * into a fatal one — a sideways-filed film on a marginal link times out with
 * `startup_timeout`, and because the automatic verdict recomputes identically,
 * every retry the phone offers fails the same way.
 *
 * The grant is bounded and at most once per cast, because a link that genuinely
 * cannot carry the file still has to fail. [ROTATION_EXTENSION_MS] is sized on
 * what the re-prepare actually has to buy back: `bufferForPlaybackMs` of media,
 * which `BufferBudgetPolicy` caps at 2.5 s on every device tier, plus a codec
 * teardown and configure and one byte-range round trip. Six seconds covers that
 * re-fetch down to about half the file's bitrate — and a link delivering less
 * than half cannot direct-play the file at all, so failing it is the right
 * answer rather than a missed rescue. Against the 18 s budget it is a third
 * more waiting in the worst case, not a doubling.
 */
object StartupDeadlinePolicy {
    const val ROTATION_EXTENSION_MS = 6_000L

    /**
     * How long the re-armed startup timer may run, or null when nothing is
     * granted — an extension already spent, no startup outstanding, or a budget
     * that has already expired and may not be revived.
     *
     * It returns the whole remaining budget rather than the extension alone so
     * the caller re-arms one timer from one number; the resulting deadline is
     * the original plus [ROTATION_EXTENSION_MS], never a fresh full budget.
     */
    fun budgetAfterRotationRePrepare(
        deadlineElapsedMs: Long,
        alreadyExtended: Boolean,
        nowElapsedMs: Long,
    ): Long? {
        if (alreadyExtended) return null
        if (deadlineElapsedMs <= 0L) return null
        val remainingMs = deadlineElapsedMs - nowElapsedMs
        if (remainingMs <= 0L) return null
        return remainingMs + ROTATION_EXTENSION_MS
    }
}
