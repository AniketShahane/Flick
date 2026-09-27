package com.flick.sender.net

/** Why a startup was handed back to a fresh dial instead of surfacing a face. */
internal enum class StartupRetryReason(val logName: String) {
    /** The accept wait ran out and nothing at all came back on the socket after the load. */
    ACCEPT_SILENT("accept_silent"),

    /** The control socket went down before the cast reached Active. */
    CONTROL_LOST("control_lost"),
}

/**
 * How long a cast startup waits on the TV, and when a failed one is quietly tried again.
 *
 * Measured, not theorised: a TV that had booted four minutes earlier, with a load average
 * of eight on four cores and the platform full-compacting another app's heap at that very
 * second, answered a ping in 23 ms and then took more than two seconds to adopt a
 * `loadMedia` its healthy self finishes in two. The phone called that `startup_timeout`,
 * the next tap nine seconds later played normally, and the viewer saw an error face for a
 * TV that was never at fault. A system-wide stall is a transient of the TV, not a verdict
 * on the link or the film, and the two budgets here are sized to outlast one.
 */
internal object CastStartupPolicy {

    /**
     * The `loadAccepted` wait.
     *
     * Acceptance is the receiver's main thread adopting the load, which is the first thing
     * a stalled TV stops doing, so the wait is sized for the stall rather than for the
     * two-millisecond healthy path. The measured stall overran two seconds and was gone
     * nine seconds after the load; ten covers it and is still well inside the first-frame
     * wait behind it. The connecting screen — with Cancel under the viewer's thumb — stays
     * up throughout, so patience here costs a viewer nothing they cannot end.
     */
    const val ACCEPT_TIMEOUT_MS = 10_000L

    /** The `loadReady` wait, unchanged: reaching it took an accept, which is the TV answering. */
    const val FIRST_FRAME_TIMEOUT_MS = 18_000L

    /** The old accept budget: an acceptance slower than this is logged, since it used to fail. */
    const val SLOW_ACCEPT_MS = 2_000L

    /**
     * The accept wait expiring, as a phone-LOCAL code. Never on the wire: the frozen
     * vocabulary's `startup_timeout` is what the receiver raises for a first frame it never
     * reached, and its face says the TV "accepted the cast" — which is exactly what this
     * failure proves did not happen.
     */
    const val ACCEPT_TIMEOUT_CODE = "load_unanswered"

    /** Silent retries per cast the user started. One is a second chance; two would be a loop. */
    const val RETRIES_PER_USER_CAST = 1

    fun acceptWasSlow(waitedMs: Long): Boolean = waitedMs > SLOW_ACCEPT_MS

    /**
     * What a startup that failed with [code] at [stage] indicts, where it is worth a second
     * dial at all.
     *
     * Only the accept wait, and only a silent one. Any frame after the load is a receiver
     * that is present and chose not to answer, and every receiver-reported failure arrives
     * as `loadFailed` or `error` with a verdict a second attempt would only repeat — codec,
     * HTTP, a TV app in the background, another session holding the TV. Faults this phone
     * raised about its own server or file never reach this either: nothing a re-dial does
     * changes them.
     */
    fun reasonForFailure(code: String, stage: StartupStage, heardSinceLoad: Boolean): StartupRetryReason? =
        if (code == ACCEPT_TIMEOUT_CODE && stage == StartupStage.AWAITING_ACCEPTANCE && !heardSinceLoad) {
            StartupRetryReason.ACCEPT_SILENT
        } else {
            null
        }

    /**
     * What a control socket lost under a cast indicts. A cast that reached Active is
     * [ControlRecoveryPolicy]'s to weigh against the media path; one that never did has no
     * media path to weigh, and a TV whose main thread stalled past its own adoption budget
     * answers the load by closing this very socket.
     */
    fun reasonForControlLoss(reachedActive: Boolean): StartupRetryReason? =
        if (reachedActive) null else StartupRetryReason.CONTROL_LOST

    /**
     * Whether to re-run the same request once through a fresh dial rather than raise a face.
     *
     * [retriesSpent] is counted per cast the user started — a recovery re-cast and the
     * block-wait window both inherit it — so a TV that fails the same way twice is shown to
     * the viewer rather than hidden behind a third dial. [canDial] is the paired record a
     * resume needs. [phoneOnLan] keeps a phone that has itself left the network on the face
     * that says so: a dial from nowhere only delays it. [foregroundAllowed] is whether the
     * platform would let the re-dial start its media service: the retry's teardown stops the
     * one this cast held, and a backgrounded phone would trade a retryable face for a
     * refusal that blames the phone.
     */
    fun retries(
        reason: StartupRetryReason?,
        retriesSpent: Int,
        canDial: Boolean,
        phoneOnLan: Boolean,
        foregroundAllowed: Boolean,
    ): Boolean =
        reason != null && retriesSpent < RETRIES_PER_USER_CAST && canDial && phoneOnLan && foregroundAllowed

    /**
     * How long the re-dial for [reason] waits behind the connecting screen before it goes.
     *
     * A silent accept is a TV whose socket worker has not run for ten seconds, so the load,
     * the stop and the close this phone sent are still queued on it. When it catches up it
     * reads the load first and holds the TV for up to its own adoption budget (4 s) before
     * it reaches the stop; a resume that lands inside that window is answered `busy` over
     * this phone's own abandoned load. A lost socket needs no hold: the receiver closes it
     * only after its adoption has already let go.
     */
    fun redialHoldMs(reason: StartupRetryReason): Long = when (reason) {
        StartupRetryReason.ACCEPT_SILENT -> REDIAL_HOLD_MS
        StartupRetryReason.CONTROL_LOST -> 0L
    }

    /** The receiver's 4 s adoption budget, plus a margin for the TV that is only now waking. */
    const val REDIAL_HOLD_MS = 5_000L

    /** The face the startup [reason] would have raised had it not been retried. */
    fun faceCode(reason: StartupRetryReason): String = when (reason) {
        StartupRetryReason.ACCEPT_SILENT -> ACCEPT_TIMEOUT_CODE
        StartupRetryReason.CONTROL_LOST -> "control_disconnected"
    }

    /**
     * The code a `busy` answer is shown as. During a retry's dial the session the TV is
     * busy with is, as far as this phone can know, its own abandoned load — and "another
     * phone is casting" would be the false sentence the retry exists to avoid. The original
     * failure is what the viewer is owed.
     */
    fun busyFaceCode(retrying: StartupRetryReason?): String =
        retrying?.let(::faceCode) ?: "active_cast_busy"

    /**
     * Whether [state] is a startup face left behind by a cast that no longer exists. A
     * retry and a control recovery both keep the connecting face on a cast id they have
     * already torn down, and a dial that then ends without starting a cast must release
     * it: every Flick button reads a committing state as a cast still on its way.
     */
    fun orphanedStart(state: CastStartState, currentCastId: String?): Boolean =
        currentCastId == null && state !is CastStartState.Idle && state !is CastStartState.Failed
}
