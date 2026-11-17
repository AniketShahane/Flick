package com.flick.receiver.net

/**
 * When a playing cast may tear down a direct link this TV holds to its phone.
 *
 * The fault is a one-sided TDLS link this TV still holds after the phone dropped it:
 * the phone's frames still arrive through the router, everything this TV sends to
 * the phone is lost, so media stops (TCP ACKs never reach the phone) and pings go
 * unanswered. Only a teardown from this side cures it — but a teardown of a link
 * both sides hold is the ~32 s blackout `DirectLinkReset` describes, so an
 * unanswered probe is required, and an unanswered probe means something only for
 * a phone that is known to answer when healthy. That is what [isArmed] tracks:
 *
 * - Baseline probes run while media is not starved, the first as the watch starts
 *   (the first frame has just arrived, so the link is proven). An answered one
 *   arms; an unanswered one during proven flow (bytes arrived during the probe, so
 *   the link carried data both ways) disarms, which is how a phone that ignores
 *   pings is learned. An unanswered probe without that proof is inconclusive — a
 *   paused film with a full buffer moves no bytes whether or not the link is
 *   broken — so it keeps the previous verdict and restarts its age: the age limit
 *   counts only time in which no baseline could run at all.
 * - Arming is judged as of the moment starvation began, because no healthy probe
 *   can happen inside an episode and a long one must not disarm itself.
 * - An episode is reported once it has starved for [TEARDOWN_STARVED_MS], or, when
 *   armed, once a probe goes unanswered: a crawling stream starves past [STARVED_MS]
 *   every few seconds, and a phone that ignores echoes would otherwise report each.
 *
 * Probe timeouts are what `InetAddress.isReachable` is given; on Android one
 * unanswered call can take twice that (ICMP echo, then TCP port 7).
 *
 * One instance per cast, used from one thread.
 */
internal class MidFilmLinkPolicy(private val castStartedAtMs: Long) {

    enum class ProbeKind { BASELINE, STARVED, FOLLOW_UP }

    sealed interface Step {
        data class Sleep(val ms: Long) : Step
        data class Probe(val kind: ProbeKind, val timeoutMs: Int) : Step
        data class TearDown(val starvedMs: Long, val misses: Int) : Step
    }

    sealed interface Event {
        data class ArmedChanged(val armed: Boolean) : Event
        data class EpisodeBegan(val starvedMs: Long, val armed: Boolean) : Event
        data class EpisodeEnded(val lastedMs: Long, val probes: Int, val unanswered: Int, val teardowns: Int) : Event
    }

    private class Episode(val startedAtMs: Long, val armed: Boolean) {
        var reported = false
        var probes = 0
        var unanswered = 0
        var misses = 0
        var teardowns = 0
        var lastProbeStartedAtMs = NEVER
    }

    private var lastBaselineStartedAtMs = NEVER
    private var lastHealthyProbeAtMs = NEVER
    private var lastHealthyAnswered = false
    private var reportedArmed = false
    private var episode: Episode? = null
    private var teardowns = 0
    private var lastTeardownAtMs = NEVER
    private var followUpDueAtMs = NEVER
    private val events = ArrayList<Event>()

    val teardownCount: Int get() = teardowns

    fun isArmed(atMs: Long): Boolean =
        lastHealthyAnswered && lastHealthyProbeAtMs != NEVER && atMs - lastHealthyProbeAtMs <= ARMED_MAX_AGE_MS

    /** What to do now, given how long the player has wanted media without receiving any. */
    fun step(nowMs: Long, starvedMs: Long): Step {
        noteArmed(nowMs)
        if (followUpDueAtMs != NEVER && nowMs >= followUpDueAtMs) {
            followUpDueAtMs = NEVER
            return Step.Probe(ProbeKind.FOLLOW_UP, STARVED_PROBE_TIMEOUT_MS)
        }
        if (starvedMs >= STARVED_MS) return starvedStep(nowMs, starvedMs)
        episode?.let { ended ->
            episode = null
            if (ended.reported) {
                events += Event.EpisodeEnded(nowMs - ended.startedAtMs, ended.probes, ended.unanswered, ended.teardowns)
            }
        }
        val baselineDueAtMs = if (lastBaselineStartedAtMs == NEVER) {
            castStartedAtMs + FIRST_BASELINE_DELAY_MS
        } else {
            lastBaselineStartedAtMs + BASELINE_INTERVAL_MS
        }
        if (nowMs >= baselineDueAtMs && starvedMs < HEALTHY_MAX_STARVED_MS) {
            return Step.Probe(ProbeKind.BASELINE, BASELINE_PROBE_TIMEOUT_MS)
        }
        return sleepUntil(nowMs, baselineDueAtMs)
    }

    private fun starvedStep(nowMs: Long, starvedMs: Long): Step {
        val current = episode ?: Episode(nowMs - starvedMs, isArmed(nowMs - starvedMs)).also { episode = it }
        if (starvedMs >= TEARDOWN_STARVED_MS) report(current, starvedMs)
        val mayTearDown = current.armed && teardowns < MAX_TEARDOWNS_PER_CAST
        val cooled = lastTeardownAtMs == NEVER || nowMs - lastTeardownAtMs >= TEARDOWN_COOLDOWN_MS
        if (mayTearDown && cooled && starvedMs >= TEARDOWN_STARVED_MS && current.misses >= TEARDOWN_MISSES) {
            return Step.TearDown(starvedMs, current.misses)
        }
        val gapMs = if (mayTearDown) STARVED_PROBE_GAP_MS else HOPELESS_PROBE_GAP_MS
        val dueAtMs = if (current.lastProbeStartedAtMs == NEVER) nowMs else current.lastProbeStartedAtMs + gapMs
        if (nowMs >= dueAtMs) return Step.Probe(ProbeKind.STARVED, STARVED_PROBE_TIMEOUT_MS)
        return sleepUntil(nowMs, dueAtMs)
    }

    /**
     * [bytesDuringProbe] is whether a media byte arrived after the probe was sent;
     * [starvedAfterMs] is the starvation reading once it returned.
     */
    fun onProbed(
        kind: ProbeKind,
        answered: Boolean,
        startedAtMs: Long,
        starvedAfterMs: Long,
        bytesDuringProbe: Boolean,
    ) {
        when (kind) {
            ProbeKind.BASELINE -> {
                lastBaselineStartedAtMs = startedAtMs
                if (answered) {
                    lastHealthyProbeAtMs = startedAtMs
                    lastHealthyAnswered = true
                } else if (bytesDuringProbe && starvedAfterMs < HEALTHY_MAX_STARVED_MS) {
                    lastHealthyProbeAtMs = startedAtMs
                    lastHealthyAnswered = false
                } else if (lastHealthyProbeAtMs != NEVER) {
                    lastHealthyProbeAtMs = startedAtMs
                }
            }
            ProbeKind.STARVED -> episode?.let {
                it.probes++
                it.lastProbeStartedAtMs = startedAtMs
                if (answered) {
                    it.misses = 0
                } else {
                    it.misses++
                    it.unanswered++
                    if (it.armed) report(it, starvedAfterMs)
                }
            }
            ProbeKind.FOLLOW_UP -> Unit
        }
    }

    fun onTornDown(nowMs: Long) {
        teardowns++
        lastTeardownAtMs = nowMs
        followUpDueAtMs = nowMs + FOLLOW_UP_DELAY_MS
        episode?.let {
            it.teardowns++
            it.misses = 0
        }
    }

    /** Events since the last call, oldest first. */
    fun drainEvents(): List<Event> {
        if (events.isEmpty()) return emptyList()
        val drained = events.toList()
        events.clear()
        return drained
    }

    private fun report(episode: Episode, starvedMs: Long) {
        if (episode.reported) return
        episode.reported = true
        events += Event.EpisodeBegan(starvedMs, episode.armed)
    }

    private fun noteArmed(nowMs: Long) {
        val armed = isArmed(nowMs)
        if (armed == reportedArmed) return
        reportedArmed = armed
        events += Event.ArmedChanged(armed)
    }

    private fun sleepUntil(nowMs: Long, dueAtMs: Long): Step {
        var wakeAtMs = dueAtMs
        if (followUpDueAtMs != NEVER) wakeAtMs = minOf(wakeAtMs, followUpDueAtMs)
        return Step.Sleep((wakeAtMs - nowMs).coerceIn(MIN_SLEEP_MS, POLL_MS))
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE

        /** How often starvation is looked for; it is a pair of atomic reads. */
        const val POLL_MS = 500L
        const val MIN_SLEEP_MS = 50L

        const val FIRST_BASELINE_DELAY_MS = 0L
        const val BASELINE_INTERVAL_MS = 30_000L
        const val BASELINE_PROBE_TIMEOUT_MS = 1_000
        const val ARMED_MAX_AGE_MS = 90_000L

        /** A baseline probe needs the stream this close to flowing, or a fault can start under it. */
        const val HEALTHY_MAX_STARVED_MS = 500L

        const val STARVED_MS = 2_000L
        const val STARVED_PROBE_TIMEOUT_MS = 700
        const val STARVED_PROBE_GAP_MS = 1_000L

        /** No teardown is possible (unarmed or spent): probes go on only to be counted. */
        const val HOPELESS_PROBE_GAP_MS = 5_000L

        const val TEARDOWN_STARVED_MS = 5_000L
        const val TEARDOWN_MISSES = 3
        const val TEARDOWN_COOLDOWN_MS = 15_000L
        const val MAX_TEARDOWNS_PER_CAST = 3
        const val FOLLOW_UP_DELAY_MS = 2_000L
    }
}
