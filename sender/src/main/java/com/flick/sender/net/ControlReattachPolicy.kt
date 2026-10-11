package com.flick.sender.net

/** Where this phone's served address stands, read off the interface the cast is served on. */
internal enum class ServedAddress {
    /** The URL the TV holds still names this phone. */
    SAME,

    /** The interface holds no address right now: a blip that may yet come back unchanged. */
    ABSENT,

    /** The interface came back on another address, so the URL the TV holds names nobody. */
    CHANGED,
}

/** What one reattach dial left the loop able to do next. */
internal enum class ReattachDial { RESUMED, RETRY, REFUSED }

/**
 * Putting a lost control link back under a cast the TV is still playing.
 *
 * A Wi-Fi blip removes the address of whichever side blipped, and Android destroys every
 * TCP socket bound to it — the control WebSocket and the media connection alike — while
 * the TV holds tens of seconds of buffer. The receiver keeps an orphaned cast playing for
 * [RECEIVER_ORPHAN_GRACE_MS] and re-adopts it for a `loadMedia` from the same key naming
 * the same cast and URL, so nothing about the film has to restart: this phone only has to
 * get back in before that grace runs out. A receiver that predates the grace treats the
 * same frame as a new cast at the position sent, which is what [ControlRecoveryPolicy]'s
 * re-cast would have cost anyway.
 *
 * It sits in FRONT of [ControlRecoveryPolicy] and leaves it unchanged: every way out of a
 * reattach that did not land is that policy's terminal, re-cast or block wait.
 */
internal object ControlReattachPolicy {

    /** The receiver's orphan grace. The window below has to close inside it. */
    const val RECEIVER_ORPHAN_GRACE_MS = 45_000L

    /**
     * How long the phone keeps re-dialing. Five seconds under the receiver's grace, because
     * a dial that lands at the very end still has a `loadMedia` to deliver, and one that
     * arrives after the orphan was released starts the film again rather than re-adopting.
     */
    const val WINDOW_MS = 40_000L

    /** The first wait, doubling per dial up to [RETRY_CAP_MS]. */
    const val RETRY_BASE_MS = 500L

    /**
     * The ceiling on one wait. A blip is seconds long, and a wait longer than this inside a
     * forty-second window is a dial the window no longer has room for.
     */
    const val RETRY_CAP_MS = 8_000L

    /** Reattaches inside [ControlRecoveryPolicy.STREAK_WINDOW_MS]; past it a link that keeps dropping is surfaced. */
    const val MAX_ATTEMPTS = 3

    private const val RETRY_MAX_DOUBLINGS = 5

    /**
     * Whether a control link lost under [reachedActive] should be re-dialed in place.
     *
     * The media-serving witness [ControlRecoveryPolicy.mediaPathServing] is deliberately not
     * asked: a TV playing from its buffer pulls nothing for seconds at a time, and the blip
     * this exists for destroys the media connection too. [serverRunning] is the question
     * that matters here — this phone still serving the cast the TV holds a URL for.
     */
    fun reattaches(
        reachedActive: Boolean,
        serverRunning: Boolean,
        canDial: Boolean,
        address: ServedAddress,
        attempt: Int,
    ): Boolean =
        reachedActive && serverRunning && canDial && address != ServedAddress.CHANGED && attempt <= MAX_ATTEMPTS

    /** Whether the window opened at [armedAtMs] is still open at [nowMs]. */
    fun open(armedAtMs: Long, nowMs: Long): Boolean =
        armedAtMs > 0L && nowMs - armedAtMs < WINDOW_MS

    /**
     * [servedOwned] is whether this phone still holds the served address on any interface;
     * [interfaceAddress] is what the interface the cast was served on holds now, or null.
     */
    fun address(served: String, servedOwned: Boolean, interfaceAddress: String?): ServedAddress = when {
        servedOwned || interfaceAddress == served -> ServedAddress.SAME
        interfaceAddress == null -> ServedAddress.ABSENT
        else -> ServedAddress.CHANGED
    }

    /** Whether a dial is worth spending: from no address it only fails. */
    fun dials(address: ServedAddress): Boolean = address == ServedAddress.SAME

    /**
     * The wait before the next tick, after [dials] dials so far.
     *
     * An absent address is polled at the base rate whatever has gone before: its return is
     * the moment the dial becomes worth making, and a doubled wait would spend the window
     * sleeping past it.
     */
    fun nextDelayMs(dials: Int, addressAbsent: Boolean): Long {
        if (addressAbsent || dials <= 0) return RETRY_BASE_MS
        val doublings = (dials - 1).coerceIn(0, RETRY_MAX_DOUBLINGS)
        return (RETRY_BASE_MS shl doublings).coerceAtMost(RETRY_CAP_MS)
    }

    /** [delayMs], cut short so no wait sleeps past the window opened at [armedAtMs]. */
    fun delayWithinWindowMs(delayMs: Long, armedAtMs: Long, nowMs: Long): Long =
        delayMs.coerceAtMost(armedAtMs + WINDOW_MS - nowMs).coerceAtLeast(0L)

    /**
     * What a dial's answer means for the window.
     *
     * `busy` is retried rather than refused: a receiver without the same-key takeover still
     * holds this phone's own half-open socket, and one with it may be mid-handover. Every
     * other answer the TV gave on purpose — a credential it refused, a protocol it does not
     * speak — is the TV answering with a failure, and the window ends on it.
     */
    fun classify(result: ControlClient.Result): ReattachDial = when (result) {
        is ControlClient.Result.Resumed -> ReattachDial.RESUMED
        ControlClient.Result.Busy,
        is ControlClient.Result.Unreachable,
        is ControlClient.Result.TimedOut,
        is ControlClient.Result.RejectedByTv,
        -> ReattachDial.RETRY
        is ControlClient.Result.Denied ->
            if (result.reason in TRANSIENT_DENIALS) ReattachDial.RETRY else ReattachDial.REFUSED
        else -> ReattachDial.REFUSED
    }

    /**
     * The `startMs` the reattach load carries: the TV's last reported position, clamped to
     * the duration that goes on the wire, because the receiver answers startMs > durationMs
     * by closing the control socket. A receiver that re-adopts ignores it; one that does not
     * starts there.
     */
    fun loadStartMs(confirmedMs: Long, wireDurationMs: Long): Long {
        val position = confirmedMs.coerceAtLeast(0L)
        return if (wireDurationMs > 0L) position.coerceAtMost(wireDurationMs) else position
    }

    /**
     * Whether the TV answered the reattach load by starting the film afresh rather than
     * re-adopting it. A re-adopt reports no probe and no startup, since nothing was opened;
     * a fresh start reset everything the receiver keeps per cast.
     */
    fun restarted(probeLatencyMs: Long, startupMs: Long): Boolean = probeLatencyMs != 0L || startupMs != 0L

    /**
     * Whether the re-cast fallback may run once the window has given up. The serving witness
     * still gates it, read at either end of the window: bytes at the loss are the evidence
     * the old rule used, and bytes at the end are a TV that came back to pull them.
     */
    fun fallbackServing(servingAtLoss: Boolean, servingNow: Boolean): Boolean = servingAtLoss || servingNow

    private val TRANSIENT_DENIALS = setOf("surface", "locked", "busy", "storage")
}
