package com.flick.sender.net

/**
 * What a held control socket is known to be, as far as a single check can say.
 *
 * [UNKNOWN] is not a soft [ALIVE]. It is the answer a check gives when it was not allowed
 * to ask — the ping budget was spent — and the caller then trusts the socket exactly as it
 * did before the check existed, because a cast handed to a dead socket still ends in a
 * startup failure that hangs the line up (see [ControlLiveness.hangsUpAfterStartupFailure]).
 */
internal enum class Liveness { ALIVE, DEAD, UNKNOWN }

/** How far a cast startup had got with the control socket when it failed. */
internal enum class StartupStage { PREPARING, AWAITING_ACCEPTANCE, AWAITING_FIRST_FRAME }

/**
 * Whether a control socket this phone is holding still has a receiver on the other end.
 *
 * The TV's Back does not finish its root activity on Android 12+: it moves the task to the
 * back, and the receiver deliberately keeps the control socket open across `ON_STOP`. A TV
 * app that is then frozen or killed by the platform stops answering, and the only thing on
 * this phone that notices is Ktor's ping watchdog — 30 to 45 seconds later, and only while
 * this process is awake. A cast handed to that socket in the meantime is queued, never
 * delivered, and fails as a startup timeout; every retry reused the same dead socket until
 * the watchdog finally fired. This is the check that stands in front of that.
 *
 * It uses the authenticated `ping`/`pong` pair the wire already carries and nothing else:
 * the control wire is frozen, and a frame the receiver does not know closes the socket.
 */
internal object ControlLiveness {

    /**
     * How recently an inbound frame proves the socket without asking.
     *
     * A live cast carries `state` at 10 Hz, so a replacing cast never pays a round trip;
     * two seconds is twenty of those frames missing, which a healthy feed does not do.
     */
    const val FRESH_MS = 2_000L

    /**
     * How long a pong may take before the socket is called dead.
     *
     * It is the whole of the delay a tap can see, so it stays short. A false verdict costs
     * one re-dial of a TV that was there — the resume path, a few hundred milliseconds on a
     * working LAN — while a true one saves the startup timeout and every retry behind it.
     */
    const val PONG_TIMEOUT_MS = 1_500L

    /**
     * The receiver's `PingGate`: five pings per ten-second window per connection, and the
     * sixth is answered by closing the socket. Mirrored rather than imported — the two apps
     * share no code — so this phone's own ceiling can be held under it in a test.
     */
    const val RECEIVER_PINGS_PER_WINDOW = 5
    const val RECEIVER_PING_WINDOW_MS = 10_000L

    /**
     * This phone's own ceiling, stricter than the receiver's on purpose.
     *
     * The receiver counts arrivals in a fixed window and this phone counts sends in a
     * rolling one, and those are not the same clock. Pings are also strictly sequential —
     * [step] never puts a second one on the wire while one is unanswered — so each ping
     * arrives before the next is sent, and three per rolling window then lands at most four
     * inside any window the receiver can draw. Four is under five with room to spare.
     */
    const val PINGS_PER_WINDOW = 3
    const val PING_WINDOW_MS = 10_000L

    /** What a check does next with the socket's record. */
    enum class Step {
        /** A frame arrived inside [FRESH_MS]; no ping is spent. */
        TRUST,

        /** A ping is already out and still inside its timeout; wait on that one. */
        AWAIT,

        /** A ping is already out and its pong is overdue; the socket is dead. */
        OVERDUE,

        /** Nothing recent and nothing out: ask. */
        PING,

        /** Nothing recent, and the budget forbids asking. */
        UNBUDGETED,
    }

    /** Whether a frame stamped [lastInboundAtMs] is recent enough to answer for the socket. */
    fun heardRecently(lastInboundAtMs: Long, nowMs: Long): Boolean =
        lastInboundAtMs > 0L && nowMs - lastInboundAtMs in 0L..FRESH_MS

    /**
     * The next move for a check at [nowMs], given the newest inbound frame, the send time
     * of the ping still awaiting its pong (null when none is), and whether the budget
     * allows another.
     *
     * An unanswered ping is judged before the budget is consulted, and that order is what
     * keeps pings sequential: a check whose caller walked away leaves its ping on the wire,
     * and the next check waits on that one rather than sending a second behind it.
     */
    fun step(lastInboundAtMs: Long, outstandingSentAtMs: Long?, budgetAvailable: Boolean, nowMs: Long): Step = when {
        heardRecently(lastInboundAtMs, nowMs) -> Step.TRUST
        outstandingSentAtMs != null ->
            if (nowMs - outstandingSentAtMs >= PONG_TIMEOUT_MS) Step.OVERDUE else Step.AWAIT
        !budgetAvailable -> Step.UNBUDGETED
        else -> Step.PING
    }

    /**
     * Whether a cast startup that failed with [code] at [stage] proved the socket dead.
     *
     * Only the failures that were waiting on the socket itself qualify: a load that never
     * left, and an acceptance that never came back. The first-frame wait is excluded
     * because reaching it took a `loadAccepted`, which is the TV answering. And
     * [heardSinceLoad] overrules all of them — any frame after the load is a receiver that
     * is still there, whatever else went wrong.
     */
    fun hangsUpAfterStartupFailure(code: String, stage: StartupStage, heardSinceLoad: Boolean): Boolean {
        if (heardSinceLoad) return false
        return when (code) {
            "load_not_sent" -> true
            "startup_timeout" -> stage == StartupStage.AWAITING_ACCEPTANCE
            else -> false
        }
    }

    /**
     * Whether the app coming to the foreground may check the socket it is holding.
     *
     * Everything that already owns the socket's fate is excluded: a cast live or starting
     * (its own frames and the recovery path answer for it), a cast queued behind a check or
     * a dial, a dial in flight (it closes the socket itself), and a check already running
     * — the Connect screen and the activity both report the foreground, and two checks at
     * once would spend the budget twice to learn one thing.
     */
    fun checksHeldLine(
        held: Boolean,
        castLive: Boolean,
        castQueued: Boolean,
        dialing: Boolean,
        checking: Boolean,
    ): Boolean = held && !castLive && !castQueued && !dialing && !checking
}

/**
 * Pings sent on one socket, counted over a rolling [windowMs].
 *
 * One per socket and never shared: the receiver's gate is per connection, so a re-dial
 * starts both ends from zero together.
 */
internal class PingBudget(
    private val limit: Int = ControlLiveness.PINGS_PER_WINDOW,
    private val windowMs: Long = ControlLiveness.PING_WINDOW_MS,
) {
    private val sentAtMs = ArrayDeque<Long>()

    fun available(nowMs: Long): Boolean {
        prune(nowMs)
        return sentAtMs.size < limit
    }

    /** Records a ping at [nowMs], answering false and recording nothing when none is left. */
    fun tryAcquire(nowMs: Long): Boolean {
        if (!available(nowMs)) return false
        sentAtMs.addLast(nowMs)
        return true
    }

    private fun prune(nowMs: Long) {
        while (sentAtMs.isNotEmpty() && nowMs - sentAtMs.first() >= windowMs) sentAtMs.removeFirst()
    }
}
