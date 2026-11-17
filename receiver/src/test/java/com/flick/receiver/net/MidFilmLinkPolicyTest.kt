package com.flick.receiver.net

import com.flick.receiver.net.MidFilmLinkPolicy.Event
import com.flick.receiver.net.MidFilmLinkPolicy.ProbeKind
import com.flick.receiver.net.MidFilmLinkPolicy.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MidFilmLinkPolicyTest {

    /**
     * Drives the policy the way the guard does. [starvedSinceMs] is when media last
     * flowed (null = flowing); [answers] decides each probe; an unanswered probe
     * takes twice its timeout, as `isReachable` can on Android.
     */
    private class World(var nowMs: Long = START_MS) {
        val policy = MidFilmLinkPolicy(START_MS)
        var starvedSinceMs: Long? = null
        var answers: (ProbeKind) -> Boolean = { true }
        val probes = mutableListOf<Pair<ProbeKind, Boolean>>()
        val teardowns = mutableListOf<Step.TearDown>()
        val teardownAtMs = mutableListOf<Long>()
        val events = mutableListOf<Event>()

        fun starved(atMs: Long = nowMs): Long = starvedSinceMs?.let { (atMs - it).coerceAtLeast(0L) } ?: 0L

        fun runUntil(endMs: Long) {
            while (nowMs < endMs) {
                when (val step = policy.step(nowMs, starved())) {
                    is Step.Sleep -> nowMs += step.ms
                    is Step.Probe -> {
                        val startedAt = nowMs
                        val answered = answers(step.kind)
                        nowMs += if (answered) 5L else 2L * step.timeoutMs
                        probes += step.kind to answered
                        policy.onProbed(step.kind, answered, startedAt, starved(), bytesDuringProbe = starvedSinceMs == null)
                    }
                    is Step.TearDown -> {
                        teardowns += step
                        teardownAtMs += nowMs
                        policy.onTornDown(nowMs)
                    }
                }
                events += policy.drainEvents()
            }
        }

        fun count(kind: ProbeKind) = probes.count { it.first == kind }
    }

    @Test fun nothingIsProbedBeforeTheFirstBaselineIsDue() {
        val w = World()
        w.runUntil(START_MS + MidFilmLinkPolicy.FIRST_BASELINE_DELAY_MS - 1L)
        assertEquals(emptyList<Pair<ProbeKind, Boolean>>(), w.probes)
    }

    @Test fun aHealthyCastIsProbedAboutEveryThirtySecondsAndStaysArmed() {
        val w = World()
        w.runUntil(START_MS + 125_000L)
        // 5 s, 35 s, 65 s, 95 s, 125 s (the last starts at the boundary)
        assertEquals(listOf(ProbeKind.BASELINE), w.probes.map { it.first }.distinct())
        assertTrue(w.count(ProbeKind.BASELINE) in 4..5)
        assertTrue(w.policy.isArmed(w.nowMs))
        assertEquals(listOf<Event>(Event.ArmedChanged(true)), w.events)
    }

    @Test fun aTvOnlyLinkIsTornDownOnceStarvedFiveSecondsWithThreeMisses() {
        val w = World()
        w.runUntil(START_MS + 40_000L)
        w.starvedSinceMs = w.nowMs
        w.answers = { false }
        val faultAt = w.nowMs
        w.runUntil(faultAt + 7_000L)

        val teardown = w.teardowns.single()
        assertEquals(MidFilmLinkPolicy.TEARDOWN_MISSES, teardown.misses)
        assertTrue(teardown.starvedMs >= MidFilmLinkPolicy.TEARDOWN_STARVED_MS)
        assertEquals(Event.EpisodeBegan(MidFilmLinkPolicy.STARVED_MS, armed = true), w.events.filterIsInstance<Event.EpisodeBegan>().single())
    }

    @Test fun noProbeAndNoTeardownBeforeTwoSecondsOfStarvation() {
        val w = World()
        w.runUntil(START_MS + 40_000L)
        val before = w.probes.size
        w.starvedSinceMs = w.nowMs
        w.answers = { false }
        w.runUntil(w.nowMs + MidFilmLinkPolicy.STARVED_MS - 100L)
        assertEquals(before, w.probes.size)
        assertEquals(emptyList<Step.TearDown>(), w.teardowns)
    }

    @Test fun aPhoneThatAnswersWhileStarvedIsNeverTornDown() {
        val w = World()
        w.runUntil(START_MS + 40_000L)
        w.starvedSinceMs = w.nowMs
        w.runUntil(w.nowMs + 60_000L)
        assertEquals(emptyList<Step.TearDown>(), w.teardowns)
        assertTrue(w.count(ProbeKind.STARVED) > 3)
    }

    @Test fun oneAnsweredProbeResetsTheMissCount() {
        val w = World()
        w.runUntil(START_MS + 40_000L)
        w.starvedSinceMs = w.nowMs
        var n = 0
        // miss, miss, answer, miss, miss, answer ...
        w.answers = { kind -> if (kind == ProbeKind.STARVED) (++n % 3 == 0) else true }
        w.runUntil(w.nowMs + 30_000L)
        assertEquals(emptyList<Step.TearDown>(), w.teardowns)
    }

    @Test fun aPhoneThatIgnoresPingsWhileMediaFlowsIsNeverTornDown() {
        val w = World()
        w.answers = { false }
        w.runUntil(START_MS + 40_000L)
        assertFalse(w.policy.isArmed(w.nowMs))
        w.starvedSinceMs = w.nowMs
        w.runUntil(w.nowMs + 60_000L)
        assertEquals(emptyList<Step.TearDown>(), w.teardowns)
        assertEquals(Event.EpisodeBegan(MidFilmLinkPolicy.STARVED_MS, armed = false), w.events.filterIsInstance<Event.EpisodeBegan>().single())
    }

    @Test fun anUnansweredBaselineDisarmsUntilALaterOneIsAnswered() {
        val w = World()
        w.runUntil(START_MS + 10_000L)
        assertTrue(w.policy.isArmed(w.nowMs))
        w.answers = { false }
        w.runUntil(START_MS + 40_000L)
        assertFalse(w.policy.isArmed(w.nowMs))
        w.answers = { true }
        w.runUntil(START_MS + 70_000L)
        assertTrue(w.policy.isArmed(w.nowMs))
        assertEquals(
            listOf(Event.ArmedChanged(true), Event.ArmedChanged(false), Event.ArmedChanged(true)),
            w.events.filterIsInstance<Event.ArmedChanged>(),
        )
    }

    @Test fun anUnansweredBaselineWithoutProvenFlowIsDiscarded() {
        val p = MidFilmLinkPolicy(START_MS)
        p.onProbed(ProbeKind.BASELINE, answered = true, startedAtMs = START_MS, starvedAfterMs = 0L, bytesDuringProbe = true)
        p.onProbed(ProbeKind.BASELINE, answered = false, startedAtMs = START_MS + 30_000L, starvedAfterMs = 0L, bytesDuringProbe = false)
        assertTrue(p.isArmed(START_MS + 31_000L))
        p.onProbed(ProbeKind.BASELINE, answered = false, startedAtMs = START_MS + 60_000L, starvedAfterMs = 900L, bytesDuringProbe = true)
        assertTrue(p.isArmed(START_MS + 61_000L))
        p.onProbed(ProbeKind.BASELINE, answered = false, startedAtMs = START_MS + 61_000L, starvedAfterMs = 0L, bytesDuringProbe = true)
        assertFalse(p.isArmed(START_MS + 62_000L))
    }

    @Test fun armingExpiresNinetySecondsAfterTheLastAnsweredBaseline() {
        val p = MidFilmLinkPolicy(START_MS)
        p.onProbed(ProbeKind.BASELINE, answered = true, startedAtMs = START_MS, starvedAfterMs = 0L, bytesDuringProbe = true)
        assertTrue(p.isArmed(START_MS + MidFilmLinkPolicy.ARMED_MAX_AGE_MS))
        assertFalse(p.isArmed(START_MS + MidFilmLinkPolicy.ARMED_MAX_AGE_MS + 1L))
    }

    @Test fun noBaselineRunsWhileTheStreamIsShortOfData() {
        val w = World()
        w.starvedSinceMs = START_MS
        w.runUntil(START_MS + MidFilmLinkPolicy.STARVED_MS - 1L)
        assertEquals(0, w.count(ProbeKind.BASELINE))
    }

    @Test fun armingIsJudgedWhenStarvationBeganSoALongEpisodeKeepsIt() {
        val w = World()
        w.runUntil(START_MS + 6_000L)
        // Last answered baseline at 5 s; starvation from 90 s, inside the 90 s window.
        w.runUntil(START_MS + 34_000L)
        w.answers = { kind -> kind != ProbeKind.STARVED }
        w.starvedSinceMs = START_MS + 34_000L
        w.runUntil(START_MS + 34_000L + 120_000L)
        assertEquals(MidFilmLinkPolicy.MAX_TEARDOWNS_PER_CAST, w.teardowns.size)
    }

    @Test fun teardownsAreFifteenSecondsApartAndAtMostThreePerCast() {
        val w = World()
        w.runUntil(START_MS + 40_000L)
        w.starvedSinceMs = w.nowMs
        w.answers = { kind -> kind != ProbeKind.STARVED }
        w.runUntil(START_MS + 400_000L)
        val times = w.teardownAtMs
        assertEquals(MidFilmLinkPolicy.MAX_TEARDOWNS_PER_CAST, times.size)
        for (i in 1 until times.size) assertTrue(times[i] - times[i - 1] >= MidFilmLinkPolicy.TEARDOWN_COOLDOWN_MS)
    }

    @Test fun eachTeardownIsFollowedByOneProbeAboutTwoSecondsLater() {
        val w = World()
        w.runUntil(START_MS + 40_000L)
        w.starvedSinceMs = w.nowMs
        w.answers = { kind -> kind != ProbeKind.STARVED }
        w.runUntil(w.nowMs + 12_000L)
        assertEquals(1, w.teardowns.size)
        assertEquals(1, w.count(ProbeKind.FOLLOW_UP))
    }

    @Test fun aFollowUpAnswerDoesNotCountTowardTheNextTeardown() {
        val p = MidFilmLinkPolicy(START_MS)
        p.onProbed(ProbeKind.BASELINE, answered = true, startedAtMs = START_MS, starvedAfterMs = 0L, bytesDuringProbe = true)
        val t0 = START_MS + 10_000L
        p.step(t0, MidFilmLinkPolicy.STARVED_MS)
        repeat(3) { p.onProbed(ProbeKind.STARVED, false, t0 + it * 1_400L, 3_000L, false) }
        assertEquals(Step.TearDown(6_000L, 3), p.step(t0 + 4_000L, 6_000L))
        p.onTornDown(t0 + 4_000L)
        p.onProbed(ProbeKind.FOLLOW_UP, answered = true, startedAtMs = t0 + 6_000L, starvedAfterMs = 8_000L, bytesDuringProbe = false)
        assertEquals(1, p.teardownCount)
    }

    @Test fun recoveryEndsTheEpisodeWithItsCounts() {
        val w = World()
        w.runUntil(START_MS + 40_000L)
        w.starvedSinceMs = w.nowMs
        w.answers = { kind -> kind != ProbeKind.STARVED }
        w.runUntil(w.nowMs + 8_000L)
        w.starvedSinceMs = null
        w.runUntil(w.nowMs + 1_000L)
        val ended = w.events.filterIsInstance<Event.EpisodeEnded>().single()
        assertEquals(1, ended.teardowns)
        assertTrue(ended.probes >= 3)
        assertTrue(ended.unanswered >= 3)
        assertTrue(ended.lastedMs >= MidFilmLinkPolicy.TEARDOWN_STARVED_MS)
    }

    @Test fun anUnarmedEpisodeProbesOnlySparsely() {
        val w = World()
        w.answers = { false }
        w.runUntil(START_MS + 40_000L)
        w.starvedSinceMs = w.nowMs
        val before = w.count(ProbeKind.STARVED)
        w.runUntil(w.nowMs + 30_000L)
        val probes = w.count(ProbeKind.STARVED) - before
        assertTrue("probes=$probes", probes in 4..7)
    }

    @Test fun sleepsNeverOutlastThePoll() {
        val p = MidFilmLinkPolicy(START_MS)
        val step = p.step(START_MS, 0L) as Step.Sleep
        assertEquals(MidFilmLinkPolicy.POLL_MS, step.ms)
    }

    private companion object {
        const val START_MS = 1_000_000L
    }
}
