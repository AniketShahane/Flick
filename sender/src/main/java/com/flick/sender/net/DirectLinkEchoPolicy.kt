package com.flick.sender.net

/**
 * When a cast's network echoes prove this phone holds a direct link the TV never finished.
 *
 * The ping watch ([DirectLinkWatchPolicy]) needs a ping to sit unanswered for six seconds
 * every ten, so a link that breaks mid-film is released 6–16 s after the break, close to
 * the TV's ~14 s buffer. An echo costs nothing on the control socket and goes every second,
 * so three unanswered in a row release in about three to four seconds.
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

    /**
     * One probe's outcome. [lastReleaseAtMs] is the cast's shared stamp of the last release
     * from any path, 0 for none.
     */
    fun onProbe(answered: Boolean, trusted: Boolean, nowMs: Long, lastReleaseAtMs: Long): Decision {
        if (answered) {
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
        if (!verifying) return if (misses >= MISSES_TO_RELEASE) release(nowMs, lastReleaseAtMs) else Decision.Wait
        if (nowMs - verifyStartedAtMs < VERIFY_MS) return Decision.Wait
        if (attempts < RELEASES_PER_EPISODE) return release(nowMs, lastReleaseAtMs)
        verifying = false
        halted = true
        return Decision.Unrepaired(misses)
    }

    private fun release(nowMs: Long, lastReleaseAtMs: Long): Decision {
        if (!releaseAllowed(lastReleaseAtMs, nowMs)) {
            // Mid-episode, the release being waited out is usually this episode's own.
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

        /** `InetAddress.isReachable`; an unanswered call can take twice this (ICMP, then TCP 7). */
        const val PROBE_TIMEOUT_MS = 600

        const val MISSES_TO_RELEASE = 3
        const val VERIFY_MS = 4_000L
        const val RELEASES_PER_EPISODE = 2
        const val RELEASES_PER_CAST = 6

        /** No two releases from any combination of paths closer than this. */
        const val RELEASE_DEDUPE_MS = 5_000L

        fun releaseAllowed(lastReleaseAtMs: Long, nowMs: Long): Boolean =
            lastReleaseAtMs <= 0L || nowMs - lastReleaseAtMs >= RELEASE_DEDUPE_MS
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
