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
 * Nothing here re-dials a cast whose bytes have stopped as well. That case is a TV that
 * really has gone, and its terminal is the behaviour this phone has always had — but it
 * is no longer the end of it. The measured home-LAN fault (research/03) is a router that
 * stops carrying traffic between exactly these two devices while both stay healthy on the
 * same radio, for thirteen to twenty minutes, and then clears itself. Every dial through
 * one returns EHOSTUNREACH in about 40 ms, so the four-candidate sweep spends a second and
 * surrenders. [waitsOn] and [retryDelayMs] are the other half of this object: the terminal
 * is still surfaced, and this phone goes on checking behind it.
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

    /**
     * How long this phone keeps checking after it has surrendered the cast.
     *
     * The two blocks that were measured end to end lasted 13 m 43 s and about 20 m, and
     * nothing an unprivileged app does shortens one — so the window is sized to outlast the
     * fault rather than to fight it. Past it the error face is all that is left, which is
     * why the copy under this window may promise the checking and never a duration.
     */
    const val BLOCK_WINDOW_MS = 20L * 60L * 1000L

    /** First wait, doubling per attempt up to [BLOCK_RETRY_CAP_MS]. */
    const val BLOCK_RETRY_BASE_MS = 5_000L

    /**
     * The ceiling on one wait, and the number this whole cadence turns on.
     *
     * A dial is a few packets and the fault outlasts any plausible backoff, so the only
     * thing a long plateau buys is a late resume. Matter plateaus at 32 s; pychromecast
     * caps at 300 s and Jellyfin at 5 minutes, and both of those would be sitting at their
     * plateau at the moment a fourteen-minute block ends — a viewer waiting minutes for a
     * path that came back while they were watching the screen.
     */
    const val BLOCK_RETRY_CAP_MS = 30_000L

    /** The fraction of a wait that [retryDelayMs] may shorten it by. */
    const val BLOCK_RETRY_JITTER = 0.25

    /** Doublings past this overflow nothing and change nothing: the cap has long since won. */
    private const val BLOCK_RETRY_MAX_DOUBLINGS = 6

    /**
     * Whether a cast this phone has just given up on is worth waiting out at all.
     *
     * [reachedActive] carries [recovers]' conservatism unchanged: only a cast the TV was
     * playing has a position to put back and a receiver that proved it can play the file.
     * [canDial] is the stored pairing and the request, neither of which this can conjure.
     *
     * It deliberately says nothing about the fault, because at the moment a control socket
     * dies there is no fault to read — a dial has not been attempted yet. That gate is
     * [waitsOn], one sweep later.
     */
    fun waitsOutLoss(reachedActive: Boolean, canDial: Boolean): Boolean = reachedActive && canDial

    /** Whether the window opened at [armedAtMs] is still open at [nowMs]. */
    fun waiting(armedAtMs: Long, nowMs: Long): Boolean =
        armedAtMs > 0L && nowMs - armedAtMs < BLOCK_WINDOW_MS

    /**
     * Whether to keep waiting after a candidate sweep that ended on [fault].
     *
     * The measured fingerprint is two facts and takes both, because at socket level a
     * pair-scoped block and a dead host are the same errno: a TV that was unplugged loses
     * its neighbour entry on this phone within minutes, and the kernel then answers
     * connect() with the block's own EHOSTUNREACH. [freshlyAdvertised] is the record
     * re-resolved in this same sweep — multicast crosses a block while unicast does not, so
     * a TV still answering one is a TV being kept apart from this phone, and a TV answering
     * nothing is off. It is [dialPlacesTv]'s evidence, and the face and the wait have to
     * rest on the same of it: the copy under an armed window promises the film back.
     *
     * A receiver that is not listening answers with an RST, which is a path that forwards
     * and a TV that will not play — waiting on one would be this phone dialing nobody.
     */
    fun waitsOn(fault: DialFault?, freshlyAdvertised: Boolean, armedAtMs: Long, nowMs: Long): Boolean =
        fault == DialFault.NO_ROUTE && freshlyAdvertised && waiting(armedAtMs, nowMs)

    /**
     * The wait before attempt [attempt], counted from one.
     *
     * [jitter] is a 0..1 draw and only ever SHORTENS the wait: the cap is a promise about
     * the worst case, and a phone whose ticks fell into step with a periodic timer on the
     * far side would sample the same phase of it every time.
     */
    fun retryDelayMs(attempt: Int, jitter: Double): Long {
        val doublings = (attempt - 1).coerceIn(0, BLOCK_RETRY_MAX_DOUBLINGS)
        val backoff = (BLOCK_RETRY_BASE_MS shl doublings).coerceAtMost(BLOCK_RETRY_CAP_MS)
        val spread = (backoff * BLOCK_RETRY_JITTER * jitter.coerceIn(0.0, 1.0)).toLong()
        return backoff - spread
    }
}
