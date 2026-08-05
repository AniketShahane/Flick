package com.flick.sender.net

/**
 * What a control link that has gone quiet under a live cast is evidence of.
 *
 * Silence on the WebSocket is not silence on the LAN. The phone is the byte server, so
 * it knows independently whether the TV is still pulling the film — and a TV taking
 * tens of megabits a second off `/v/{token}` has demonstrably not gone away, whatever
 * Ktor's ping watchdog concluded. That watchdog is deliberately slow to fire
 * ([ControlClient.PING_INTERVAL_MS] tolerates 30 s of missing pong and notices a dead
 * TV between 30 s and 45 s), which is what makes the media path the better witness of
 * the two: it answers about the last second, not about the last minute.
 *
 * Nothing here recovers a cast whose bytes have stopped as well. That case is a TV that
 * really has gone, and its terminal is the behaviour this phone has always had.
 */
internal object ControlRecoveryPolicy {

    /**
     * How stale the last served byte may be and still count as a TV that is pulling.
     *
     * The upper bound is set by the watchdog this exists to second-guess: the control
     * link cannot be reported lost sooner than 30 s of missing pong, so any window
     * comfortably under that is one a TV that vanished has already fallen out of. The
     * lower bound is `DefaultLoadControl`: once the receiver's buffer reaches its byte
     * target the loader simply stops reading and the phone's writes block, which on a
     * fast link is several seconds of zero-byte samples with nothing whatever wrong. Ten
     * one-second samples clears that gap and is still a statement about now.
     */
    const val SERVING_WINDOW_MS = 10_000L

    /**
     * How long one recovery keeps counting against the next.
     *
     * A bound with no decay would be the wrong shape for a three-hour film: two losses an
     * hour apart are two incidents, and the second deserves the same chance as the first.
     * Two inside a minute are one broken link, and the third attempt would be this app
     * reconnecting in a loop the viewer cannot see or stop.
     */
    const val STREAK_WINDOW_MS = 60_000L

    /** Attempts allowed inside [STREAK_WINDOW_MS]; past it the failure is surfaced instead. */
    const val MAX_ATTEMPTS = 2

    /**
     * Whether the media socket was still carrying this cast as of [nowMs].
     *
     * [lastByteAtMs] is the monotonic stamp of the newest [LinkSample] that carried bytes;
     * zero means this cast has never moved one, which is a startup that never got going
     * rather than a link worth trusting.
     */
    fun mediaPathServing(lastByteAtMs: Long, nowMs: Long): Boolean =
        lastByteAtMs > 0L && nowMs - lastByteAtMs <= SERVING_WINDOW_MS

    /** Which attempt in the current run a loss at [nowMs] would be. */
    fun attempt(previousAttempts: Int, lastAttemptAtMs: Long, nowMs: Long): Int =
        if (lastAttemptAtMs > 0L && nowMs - lastAttemptAtMs <= STREAK_WINDOW_MS) previousAttempts + 1 else 1

    /**
     * Whether to re-establish rather than fail.
     *
     * [reachedActive] is the whole of the conservatism: only a cast the TV was already
     * playing has a position worth resuming and a receiver that proved it can play the
     * file. A startup that lost its control link never reached either, and re-dialing one
     * would be this phone retrying a cast the user has not yet seen work.
     *
     * [canDial] is the stored pairing and the cast request, both of which the re-cast
     * needs and neither of which this decision can conjure.
     */
    fun recovers(reachedActive: Boolean, mediaServing: Boolean, canDial: Boolean, attempt: Int): Boolean =
        reachedActive && mediaServing && canDial && attempt <= MAX_ATTEMPTS
}
