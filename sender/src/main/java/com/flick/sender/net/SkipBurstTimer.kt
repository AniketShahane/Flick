package com.flick.sender.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The quiet period that ends a run of ±10s taps (design: one intent, one seek).
 *
 * Each tap re-arms, so the window measures time since the LAST tap and a run of any
 * length commits exactly once. Kept apart from [PlaybackSession] so the timing is
 * testable on virtual time with no clock and no `org.json` on the classpath.
 *
 * A cancelled or elapsed window can never fire twice, but a stale fire is not defended
 * against here on purpose: the authority on whether a run is still owed a seek is the
 * session's own state, so [PlaybackSession] makes that check where the state lives.
 */
internal class SkipBurstTimer(
    private val scope: CoroutineScope,
    private val windowMs: Long = SeekPolicy.QUIET_WINDOW_MS,
    private val onCommit: () -> Unit,
) {
    private var job: Job? = null

    /** Whether a commit is still queued. */
    val armed: Boolean get() = job?.isActive == true

    /** Register a tap: restart the window, discarding the one the previous tap armed. */
    fun arm() {
        job?.cancel()
        job = scope.launch {
            delay(windowMs)
            onCommit()
        }
    }

    /** Abandon a queued commit — the run was superseded, or its cast is gone. */
    fun cancel() {
        job?.cancel()
        job = null
    }

    /** Spend a queued commit now rather than waiting out the window. */
    fun commitNow() {
        if (!armed) return
        cancel()
        onCommit()
    }
}
