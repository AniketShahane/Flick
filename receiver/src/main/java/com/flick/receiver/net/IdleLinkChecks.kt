package com.flick.receiver.net

/**
 * When `DirectLinkReset` looks for an orphaned direct link after a cast goes quiet.
 *
 * The first check must come after the phone's own idle teardown (10–32 s after the
 * stream stops): before it, the link is still held on both sides and a teardown
 * from here is the blackout that class exists to avoid. After it, the sooner the
 * better — an orphan blocks the next cast until it is found — so the checks are
 * dense early and thin out over ten minutes.
 */
internal object IdleLinkChecks {
    val DELAYS_MS = longArrayOf(
        45_000L, 60_000L, 75_000L, 90_000L, 105_000L, 120_000L,
        150_000L, 180_000L, 240_000L, 300_000L, 450_000L, 600_000L,
    )

    /** The latest the phone's own idle teardown was measured to happen. */
    const val PHONE_IDLE_TEARDOWN_MAX_MS = 32_000L

    /**
     * Whether the rest of a series may be skipped: a teardown it already requested was
     * accepted and the phone now answers, so the link is clear.
     */
    fun seriesDone(tornDown: Boolean, phoneAnswered: Boolean): Boolean = tornDown && phoneAnswered
}
