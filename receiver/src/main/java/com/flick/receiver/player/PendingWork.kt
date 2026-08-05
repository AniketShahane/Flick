package com.flick.receiver.player

/**
 * One outstanding piece of main-thread work, owned by exactly one caller.
 *
 * This is a type rather than a pair of nullable fields because the two the
 * receiver keeps used to be one. The bounded auto-recovery and the picture-turn
 * fallback shared a slot, so every canceller of the recovery — `scheduleRecovery`
 * re-arming itself, the sideloaded-subtitle rollback, a rotation re-prepare —
 * removed whichever runnable happened to be in it. A `PlaybackException` already
 * queued when the turn was condemned therefore ran FIRST, cancelled the
 * fallback, and re-prepared the player still presenting through the condemned
 * surface: the film latched as un-turnable, the turn still in force, and nothing
 * on its way to take it off. The viewer sat on a frozen picture over a healthy
 * player, which is the exact outcome the fallback exists to prevent.
 *
 * Two instances cannot reach each other's work, so no future caller of either
 * [cancel] can collect the other's.
 *
 * Main-thread only. The two schedulers are injected rather than a `Handler`
 * because what this type is answerable for is ordering, and ordering is worth
 * exercising without a Looper.
 */
internal class PendingWork(
    private val schedule: (Runnable, Long) -> Unit,
    private val unschedule: (Runnable) -> Unit,
) {

    private var pending: Runnable? = null

    /** True from [post] until that work has run or been cancelled. */
    val isPending: Boolean get() = pending != null

    /**
     * Replace whatever this slot holds with [action]. The slot is cleared before
     * [action] runs, so work that re-posts — or that cancels the other slot —
     * sees the state it is about to create rather than the one it replaced.
     */
    fun post(delayMs: Long = 0L, action: () -> Unit) {
        cancel()
        lateinit var work: Runnable
        work = Runnable {
            // A queue that ran an entry after it was asked to drop it must not
            // reach the caller's action; the slot is the authority, not the queue.
            if (pending !== work) return@Runnable
            pending = null
            action()
        }
        pending = work
        schedule(work, delayMs)
    }

    fun cancel() {
        pending?.let(unschedule)
        pending = null
    }
}
