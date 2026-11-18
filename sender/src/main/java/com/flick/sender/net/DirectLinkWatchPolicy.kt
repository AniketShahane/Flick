package com.flick.sender.net

/**
 * When a playing cast checks that the TV still hears this phone.
 *
 * The first check lands after the phone's driver has had time to set up a direct link
 * under the film's opening burst (measured starting 1–2 s in and failing about 5 s
 * later); the period keeps a link that fails later from outlasting the TV's buffer.
 * One ping per check, and none while one is unanswered, so it stays inside
 * [ControlLiveness.PINGS_PER_WINDOW] alongside a replacing cast's own check.
 */
internal object DirectLinkWatchPolicy {
    /** After the first frame. */
    const val FIRST_CHECK_MS = 8_000L

    /** Between the end of one check and the start of the next. */
    const val PERIOD_MS = 10_000L

    /** Longer than the receiver's 4 s main-thread adoption of a mid-film `loadMedia`. */
    const val UNANSWERED_PROOF_MS = 6_000L

    /**
     * Whether a ping still unanswered proves the link one-sided. An echo answered after the
     * ping was sent proves the path both ways — after a long outage TCP's back-off can hold
     * the pong past the echo watch's repair — so that pong is only late. That proof holds
     * only while it is fresh: a link can turn one-sided after the echo and the echo then
     * stop being usable, and no second ping goes out behind this one. All stamps are on the
     * monotonic clock; [lastEchoAnsweredAtMs] is 0 for none.
     */
    fun releasesFor(pingSentAtMs: Long, lastEchoAnsweredAtMs: Long, nowMs: Long): Boolean =
        lastEchoAnsweredAtMs <= pingSentAtMs || nowMs - lastEchoAnsweredAtMs > ECHO_PROOF_FRESH_MS

    /** Two and a half echo periods: the path answered within the last couple of probes. */
    const val ECHO_PROOF_FRESH_MS = 2_500L

    /**
     * Whether the echoes [releasesFor] would weigh were taken at the paused cadence
     * ([DirectLinkEchoCadence]), so their absence of a fresh answer is a gap in sampling
     * rather than evidence. True while the slow cadence holds ([slowNow]) and for
     * [ECHO_PROOF_FRESH_MS] after it ended at [slowEndedAtMs] (0 for never): only then does
     * the window [releasesFor] reads hold as many probes as it does at the fast cadence.
     *
     * See [verdict] for what the caller does about it.
     */
    fun echoProofSparse(slowNow: Boolean, slowEndedAtMs: Long, nowMs: Long): Boolean =
        slowNow || (slowEndedAtMs > 0L && nowMs - slowEndedAtMs < ECHO_PROOF_FRESH_MS)

    enum class Verdict {
        RELEASE,

        /** An echo answered after the ping, and fresh: the pong is only late. */
        HOLD_ECHO,

        /** Echoes too sparse to weigh: the echo watch is woken and the ping weighed again after [SPARSE_RECHECK_MS]. */
        HOLD_SPARSE,
    }

    /**
     * What the check does about a ping unanswered past [UNANSWERED_PROOF_MS].
     *
     * Held as sparse only while the echo path can still release by itself ([echoCanRelease]),
     * and once per ping ([sparseHeldAlready]): the caller wakes the echo watch to the fast
     * cadence, which an overdue ping then keeps it at, so the recheck weighs this ping on
     * echoes at the usual density, by [releasesFor] exactly as it always has. When the echo
     * path cannot act, nothing is held for density: the ping watch is the only actor.
     */
    fun verdict(
        pingSentAtMs: Long,
        lastEchoAnsweredAtMs: Long,
        echoSparse: Boolean,
        echoCanRelease: Boolean,
        sparseHeldAlready: Boolean,
        nowMs: Long,
    ): Verdict = when {
        echoSparse && echoCanRelease && !sparseHeldAlready -> Verdict.HOLD_SPARSE
        releasesFor(pingSentAtMs, lastEchoAnsweredAtMs, nowMs) -> Verdict.RELEASE
        else -> Verdict.HOLD_ECHO
    }

    /** How long until the next check after one that reached [verdict]; null when it did not. */
    fun nextCheckMs(verdict: Verdict?): Long = if (verdict == Verdict.HOLD_SPARSE) SPARSE_RECHECK_MS else PERIOD_MS

    /**
     * After a sparse hold: the freshness window refilled at the fast cadence, plus one probe's
     * timeout so the probe that closes it has had time to answer.
     */
    const val SPARSE_RECHECK_MS = ECHO_PROOF_FRESH_MS + DirectLinkEchoPolicy.PROBE_TIMEOUT_MS
}
