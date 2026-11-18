package com.flick.sender.net

/**
 * How stale the TV's `state` feed may get before a scrub shows the syncing shimmer.
 *
 * The receiver sends `state` every 100 ms only while it is "hot" — for 3 s after any
 * command this phone sends other than `ping`, and around its own changes — and every
 * second otherwise. A fixed 250 ms grace read that slow rhythm as a hiccup: a scrub's
 * first touch flashed the shimmer, and so did moving again after holding the thumb still
 * past the TV's window. Healthy sync must stay invisible (control-channel.md §4).
 *
 * So the tight grace applies only while this phone can prove the TV is hot from its own
 * sends: the current run of commands began at least [ROUND_TRIP_ALLOWANCE_MS] ago, so the
 * TV has heard one and answered at the fast rhythm, and the last of them went less than
 * [HOT_TRUST_MS] ago, inside the TV's window with margin for the trip. Staleness is then
 * measured from no earlier than the moment fast frames became due, so the switch to the
 * tight grace never convicts a gap the slow rhythm explains. Anything else gets the slow
 * rhythm plus jitter. A drag sends a seek every 50 ms, so once its first 400 ms are past a
 * feed that stops still lights the shimmer 250 ms later, as it always has.
 *
 * All stamps are on the monotonic clock; one instance per control client.
 */
internal class TvFrameRhythm {

    private var runStartedAtMs = 0L
    private var lastSentAtMs = 0L

    /** A command other than `ping` was handed to the socket at [nowMs]. */
    @Synchronized
    fun onCommandSent(nowMs: Long) {
        if (lastSentAtMs <= 0L || nowMs - lastSentAtMs >= HOT_TRUST_MS) runStartedAtMs = nowMs
        lastSentAtMs = nowMs
    }

    /** Whether the TV is provably sending at its fast rhythm because of this phone's own sends. */
    @Synchronized
    fun provablyHot(nowMs: Long): Boolean =
        lastSentAtMs > 0L && nowMs - lastSentAtMs < HOT_TRUST_MS && nowMs - runStartedAtMs >= ROUND_TRIP_ALLOWANCE_MS

    /** Whether a feed last heard from at [lastConfirmedAtMs] (0 for never) has gone quiet for longer than its rhythm explains. */
    @Synchronized
    fun syncing(nowMs: Long, lastConfirmedAtMs: Long): Boolean =
        if (provablyHot(nowMs)) {
            nowMs - maxOf(lastConfirmedAtMs, runStartedAtMs + ROUND_TRIP_ALLOWANCE_MS) > HOT_SYNC_GRACE_MS
        } else {
            nowMs - lastConfirmedAtMs > COLD_SYNC_GRACE_MS
        }

    companion object {
        /** The hiccup threshold against the TV's 100 ms rhythm. */
        const val HOT_SYNC_GRACE_MS = 250L

        /** The TV's 1 s rhythm plus jitter. */
        const val COLD_SYNC_GRACE_MS = 1_500L

        /** A command's trip to the TV and the first fast frame's trip back. */
        const val ROUND_TRIP_ALLOWANCE_MS = 400L

        /** Inside the TV's 3 s hot window, leaving it room for the command's trip. */
        const val HOT_TRUST_MS = 2_500L
    }
}
