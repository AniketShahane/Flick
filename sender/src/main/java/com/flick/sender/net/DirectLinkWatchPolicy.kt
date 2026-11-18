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
     * While sparse, the caller asks for one immediate probe and releases on nothing it
     * says: an answer is the proof [releasesFor] holds on, and a miss or no information
     * puts the echo watch back at the fast cadence, under which the next check decides
     * exactly as it always has. No release happens here that would not have happened
     * with echoes every second.
     */
    fun echoProofSparse(slowNow: Boolean, slowEndedAtMs: Long, nowMs: Long): Boolean =
        slowNow || (slowEndedAtMs > 0L && nowMs - slowEndedAtMs < ECHO_PROOF_FRESH_MS)
}
