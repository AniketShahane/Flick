package com.flick.sender.net

import com.flick.sender.model.PlaybackPhase

/**
 * What one echo probe — a TCP connect to the TV's port 7 that provably left over the Wi-Fi
 * network holding the cast's serving address — says about the path to the TV. A refusal is
 * an answer: the TV heard the SYN.
 */
internal enum class EchoOutcome {
    ANSWERED,
    MISSED,

    /** No information: no path to probe that is provably the cast's Wi-Fi, or an unclassified error. */
    UNKNOWN,
}

/** How a probe reached the TV, as the journal names it. */
internal enum class EchoMode(val journalName: String) {
    /** A socket bound to the Wi-Fi network itself. */
    BOUND("bound"),

    /** An unbound socket whose kernel-chosen source was the serving address before and after. */
    ROUTED("routed"),

    /** Neither could be made to leave over the cast's Wi-Fi. */
    NONE("none"),
}

internal object EchoProbeRules {
    /**
     * A routed probe's outcome. [sourceBefore] and [sourceAfter] are the source address the
     * kernel would pick toward the TV, read before and after the connect: before, because
     * a route through a tunnel or mobile data could be answered by another host with the
     * same private address, or by nobody; after, because the default network can move
     * during the probe.
     */
    fun routedOutcome(sourceBefore: String?, raw: EchoOutcome, sourceAfter: String?, localIp: String): EchoOutcome =
        if (sourceBefore == localIp && sourceAfter == localIp) raw else EchoOutcome.UNKNOWN

    /** Whether a routed probe may be attempted at all, from the source read before it. */
    fun routedPossible(sourceBefore: String?, localIp: String): Boolean = sourceBefore == localIp
}

/**
 * When a cast's network echoes prove this phone holds a direct link the TV never finished.
 *
 * The ping watch ([DirectLinkWatchPolicy]) needs a ping to sit unanswered for six seconds
 * every ten, so a link that breaks mid-film is released 6–16 s after the break, close to
 * the TV's ~14 s buffer. An echo costs nothing on the control socket and goes every second,
 * so four unanswered in a row release about four to five seconds after the break; fewer
 * would release a link that is only busy for three seconds.
 *
 * Misses mean something only for a TV that answers echoes at all, so they act only while
 * that TV is trusted ([DirectLinkEchoTrust]). A release is verified by the echoes that
 * follow it; one that does not bring them back within [VERIFY_MS] gets one more, and a
 * second that does not either ends the episode unrepaired: the caller withdraws trust and
 * nothing is released again until an echo is answered. A release during a two-way outage
 * can leave the TV holding a link alone, which the TV repairs by itself, so the eagerness
 * here is bounded rather than avoided: [RELEASES_PER_CAST] in all.
 *
 * One instance per cast; its caller is single-threaded.
 */
internal class DirectLinkEchoPolicy {

    sealed interface Decision {
        /** Nothing to do. */
        data object Wait : Decision

        /** Release the link now; [attempt] is 1 or 2 within this episode. */
        data class Release(val misses: Int, val attempt: Int) : Decision

        /** Another path released within [RELEASE_DEDUPE_MS]; that release is verified instead. */
        data class Deduped(val misses: Int, val attempt: Int) : Decision

        /** An echo came back after a release; [sinceFirstMissMs] is the outage as seen here. */
        data class Repaired(val sinceFirstMissMs: Long) : Decision

        /** Both releases went unanswered: withdraw trust. */
        data class Unrepaired(val misses: Int) : Decision

        /** [RELEASES_PER_CAST] are spent; nothing more is released for this cast. */
        data object Capped : Decision
    }

    private var misses = 0
    private var firstMissAtMs = 0L
    private var verifying = false
    private var verifyStartedAtMs = 0L
    private var attempts = 0
    private var halted = false
    private var releases = 0
    private var capped = false
    private var unknownRun = 0

    /** No miss streak in progress and no release being verified. */
    val quiet: Boolean get() = misses == 0 && !verifying

    /**
     * Whether a miss streak starting now could still end in a release for a TV whose trust
     * is [trusted]. False once the cast's budget is spent or the episode is halted: from
     * then on the ping watch is the only path that can act.
     */
    fun canRelease(trusted: Boolean): Boolean = trusted && !halted && !capped && releases < RELEASES_PER_CAST

    /**
     * One probe's outcome. [lastReleaseAtMs] is the cast's shared stamp of the last release
     * from any path, 0 for none.
     *
     * [EchoOutcome.UNKNOWN] is neither a miss nor an answer. One or two in a row change
     * nothing; a run of [UNKNOWN_RUN_TO_FORGET] ends the current streak and any verification
     * without a verdict, since misses on either side of a gap that long (a Wi-Fi drop, a
     * VPN coming up) are not one outage. The episode's releases still count: one nothing
     * has answered since is not undone by a gap, so flapping Wi-Fi spends
     * [RELEASES_PER_EPISODE] and ends unrepaired like any other outage. Only an answer
     * resets the episode; the per-cast budget and the halt are kept.
     */
    fun onProbe(outcome: EchoOutcome, trusted: Boolean, nowMs: Long, lastReleaseAtMs: Long): Decision {
        if (outcome == EchoOutcome.UNKNOWN) {
            unknownRun += 1
            if (unknownRun == UNKNOWN_RUN_TO_FORGET) {
                misses = 0
                firstMissAtMs = 0L
                verifying = false
            }
            return Decision.Wait
        }
        unknownRun = 0
        if (outcome == EchoOutcome.ANSWERED) {
            val repaired = verifying
            val outageMs = nowMs - firstMissAtMs
            misses = 0
            verifying = false
            attempts = 0
            halted = false
            return if (repaired) Decision.Repaired(outageMs) else Decision.Wait
        }
        if (misses == 0) firstMissAtMs = nowMs
        misses += 1
        if (halted || capped || !trusted) return Decision.Wait
        if (!verifying) {
            if (misses < MISSES_TO_RELEASE) return Decision.Wait
            if (attempts < RELEASES_PER_EPISODE) return release(nowMs, lastReleaseAtMs)
            halted = true
            return Decision.Unrepaired(misses)
        }
        if (nowMs - verifyStartedAtMs < VERIFY_MS) return Decision.Wait
        if (attempts < RELEASES_PER_EPISODE) return release(nowMs, lastReleaseAtMs)
        verifying = false
        halted = true
        return Decision.Unrepaired(misses)
    }

    private fun release(nowMs: Long, lastReleaseAtMs: Long): Decision {
        if (!releaseAllowed(lastReleaseAtMs, nowMs)) {
            // Mid-episode, another path's recent release is waited out, not adopted.
            if (verifying) return Decision.Wait
            attempts += 1
            verifying = true
            verifyStartedAtMs = nowMs
            return Decision.Deduped(misses, attempts)
        }
        if (releases >= RELEASES_PER_CAST) {
            capped = true
            verifying = false
            return Decision.Capped
        }
        releases += 1
        attempts += 1
        verifying = true
        verifyStartedAtMs = nowMs
        return Decision.Release(misses, attempts)
    }

    companion object {
        /** About once a second: the next probe starts this long after the previous one started. */
        const val PROBE_PERIOD_MS = 1_000L

        /** The connect timeout; an unanswered probe takes this long. */
        const val PROBE_TIMEOUT_MS = 700

        const val MISSES_TO_RELEASE = 4
        const val UNKNOWN_RUN_TO_FORGET = 3
        const val RELEASES_PER_EPISODE = 2
        const val RELEASES_PER_CAST = 6

        /** No two releases from any combination of paths closer than this. */
        const val RELEASE_DEDUPE_MS = 5_000L

        /** A release's verification lasts exactly as long as the dedupe holds the next one back. */
        const val VERIFY_MS = RELEASE_DEDUPE_MS

        fun releaseAllowed(lastReleaseAtMs: Long, nowMs: Long): Boolean =
            lastReleaseAtMs <= 0L || nowMs - lastReleaseAtMs >= RELEASE_DEDUPE_MS

        /**
         * A failed connect's errno, as an echo outcome. The constants are passed in because
         * they are the platform's (`OsConstants`). A refusal is the TV's own RST; a timeout or
         * an unreachable host is the SYN or the neighbour lookup going unanswered. Anything
         * else — a local refusal such as `EPERM`, no route at all — says nothing about the TV.
         */
        fun outcomeForErrno(errno: Int, errnos: ConnectErrnos): EchoOutcome = when (errno) {
            errnos.refused -> EchoOutcome.ANSWERED
            errnos.timedOut, errnos.hostUnreachable -> EchoOutcome.MISSED
            else -> EchoOutcome.UNKNOWN
        }
    }

    /** The platform's `ECONNREFUSED`, `ETIMEDOUT` and `EHOSTUNREACH`. */
    data class ConnectErrnos(val refused: Int, val timedOut: Int, val hostUnreachable: Int)
}

/**
 * How often the echo watch probes. A playing or buffering film is probed every
 * [DirectLinkEchoPolicy.PROBE_PERIOD_MS], always: that is where a broken link costs the
 * film. A film that has sat paused or ended for [IDLE_SETTLE_MS], with no command from this
 * phone for as long, on a path whose last probe was answered, has no buffer to run out, so
 * it is probed every [IDLE_PROBE_PERIOD_MS]. Anything that is not that — a miss, an
 * unclassified probe, a release being verified, the phase leaving pause, a command sent, a
 * ping overdue — is back to the fast period at once; the caller makes the slow wait
 * interruptible so none of those waits out the rest of it.
 *
 * Slow only while the echo policy can still release ([DirectLinkEchoPolicy.canRelease]):
 * once it cannot, the ping watch is the only actor, and it weighs its "late, not lost"
 * proof off these echoes at the density it always had.
 *
 * A slow echo is usually older than that proof allows; [DirectLinkWatchPolicy.echoProofSparse]
 * is how the ping watch knows to ask for fresh ones instead.
 */
internal object DirectLinkEchoCadence {
    const val IDLE_PROBE_PERIOD_MS = 5_000L

    /** Long enough that a pause to fetch a drink settles, and a pause-play nudge never does. */
    const val IDLE_SETTLE_MS = 10_000L

    fun idlePhase(phase: PlaybackPhase): Boolean = phase == PlaybackPhase.PAUSED || phase == PlaybackPhase.ENDED

    /**
     * The period before the next probe. [phaseIdleNow] is [idlePhase] of the phase as it
     * stands; [idleSinceMs] is when the phase last entered pause or ended without leaving
     * since, 0 while it is anything else; [wokeAtMs] is the last command this phone sent
     * (or other wake), 0 for none; [lastOutcome] is the last probe's; [quiet] is
     * [DirectLinkEchoPolicy.quiet]; [canRelease] is [DirectLinkEchoPolicy.canRelease];
     * [pingOverdue] is a control ping on the cast's line past its answer deadline.
     */
    fun periodMs(
        phaseIdleNow: Boolean,
        idleSinceMs: Long,
        wokeAtMs: Long,
        lastOutcome: EchoOutcome,
        quiet: Boolean,
        canRelease: Boolean,
        pingOverdue: Boolean,
        nowMs: Long,
    ): Long =
        if (phaseIdleNow && idleSinceMs > 0L && nowMs - maxOf(idleSinceMs, wokeAtMs) >= IDLE_SETTLE_MS &&
            lastOutcome == EchoOutcome.ANSWERED && quiet && canRelease && !pingOverdue
        ) {
            IDLE_PROBE_PERIOD_MS
        } else {
            DirectLinkEchoPolicy.PROBE_PERIOD_MS
        }
}

/**
 * Which cadence changes reach the journal, which keeps only its last few hundred lines: at
 * most one per [MIN_GAP_MS], and the state that holds once a gap ends is written then, so a
 * run of brief wakes costs one line and the cadence that persisted is always the last one
 * recorded. Starts at the fast cadence, which a watch begins in and never journals.
 */
internal class EchoCadenceJournalGate {
    private var journaledSlow = false
    private var lastAtMs = 0L
    private var any = false

    /** Whether to journal the cadence, now [slow], at [nowMs]; asked on every probe and every change. */
    fun journals(slow: Boolean, nowMs: Long): Boolean {
        if (slow == journaledSlow) return false
        if (any && nowMs - lastAtMs < MIN_GAP_MS) return false
        journaledSlow = slow
        lastAtMs = nowMs
        any = true
        return true
    }

    companion object {
        const val MIN_GAP_MS = 30_000L
    }
}

/**
 * Which TVs have answered an echo, by stable TV id, for the life of the process. Trusted
 * once any probe is answered; withdrawn when an episode ends unrepaired, and back with the
 * next answer.
 */
internal class DirectLinkEchoTrust {
    private val trusted = HashSet<String>()

    @Synchronized
    fun isTrusted(tvId: String): Boolean = tvId in trusted

    /** True when this answer is what made [tvId] trusted. */
    @Synchronized
    fun answered(tvId: String): Boolean = trusted.add(tvId)

    /** True when [tvId] was trusted until now. */
    @Synchronized
    fun withdraw(tvId: String): Boolean = trusted.remove(tvId)
}
