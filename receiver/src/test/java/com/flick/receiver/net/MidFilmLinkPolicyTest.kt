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
     * Drives the policy the way the guard does. [starvedSinceMs] is when the player
     * began wanting media it never got (null = not starved); [flowing] is whether
     * bytes are arriving (false for a pause on a full buffer); [answers] decides each
     * probe; an unanswered probe takes twice its timeout, as `isReachable` can.
     */
    private class World(var nowMs: Long = START_MS) {
        val policy = MidFilmLinkPolicy(START_MS)
        var starvedSinceMs: Long? = null
        var flowing = true
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
                        policy.onProbed(step.kind, answered, startedAt, starved(), bytesDuringProbe = flowing && starvedSinceMs == null)
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

    @Test fun theFirstBaselineRunsAsTheWatchStarts() {
        val w = World()
        w.runUntil(START_MS + 1L)
        assertEquals(listOf(ProbeKind.BASELINE to true), w.probes)
        assertTrue(w.policy.isArmed(w.nowMs))
    }

    @Test fun aFaultFourSecondsAfterTheWatchStartsIsStillRepaired() {
        val w = World()
        w.runUntil(START_MS + 4_000L)
        w.starvedSinceMs = w.nowMs
        w.answers = { false }
        w.runUntil(w.nowMs + 8_000L)
        assertEquals(1, w.teardowns.size)
        assertEquals(true, w.events.filterIsInstance<Event.EpisodeBegan>().single().armed)
    }

    @Test fun aHealthyCastIsProbedAboutEveryThirtySecondsAndStaysArmed() {
        val w = World()
        w.runUntil(START_MS + 125_000L)
        // 0 s, 30 s, 60 s, 90 s, 120 s
        assertEquals(listOf(ProbeKind.BASELINE), w.probes.map { it.first }.distinct())
        assertTrue(w.count(ProbeKind.BASELINE) in 5..6)
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
        assertEquals(true, w.events.filterIsInstance<Event.EpisodeBegan>().single().armed)
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
        assertEquals(false, w.events.filterIsInstance<Event.EpisodeBegan>().single().armed)
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

    @Test fun anUnansweredBaselineWithoutProvenFlowDoesNotDisarm() {
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
        w.starvedSinceMs = START_MS - MidFilmLinkPolicy.HEALTHY_MAX_STARVED_MS
        w.runUntil(START_MS + MidFilmLinkPolicy.STARVED_MS - MidFilmLinkPolicy.HEALTHY_MAX_STARVED_MS - 1L)
        assertEquals(0, w.count(ProbeKind.BASELINE))
    }

    /**
     * The last answered baseline is at 0 s and starvation begins at 80 s, inside the
     * 90 s window; the second and third teardowns come after 90 s, so arming
     * re-judged at each step would refuse them.
     */
    @Test fun armingIsJudgedWhenStarvationBeganSoALongEpisodeKeepsIt() {
        val p = MidFilmLinkPolicy(START_MS)
        p.onProbed(ProbeKind.BASELINE, answered = true, startedAtMs = START_MS, starvedAfterMs = 0L, bytesDuringProbe = true)
        val starvedFrom = START_MS + 80_000L
        val teardownAt = mutableListOf<Long>()
        var now = starvedFrom + MidFilmLinkPolicy.STARVED_MS
        while (now < starvedFrom + 120_000L) {
            when (val step = p.step(now, now - starvedFrom)) {
                is Step.Sleep -> now += step.ms
                is Step.Probe -> {
                    val started = now
                    now += 2L * step.timeoutMs
                    p.onProbed(step.kind, answered = false, startedAtMs = started, starvedAfterMs = now - starvedFrom, bytesDuringProbe = false)
                }
                is Step.TearDown -> {
                    teardownAt += now
                    p.onTornDown(now)
                }
            }
        }
        assertEquals(MidFilmLinkPolicy.MAX_TEARDOWNS_PER_CAST, teardownAt.size)
        assertTrue(teardownAt.last() > START_MS + MidFilmLinkPolicy.ARMED_MAX_AGE_MS)
        assertFalse(p.isArmed(teardownAt.last()))
    }

    @Test fun aPauseKeepsArmingSoAFaultDuringItIsRepairedOnResume() {
        val w = World()
        w.runUntil(START_MS + 40_000L)
        w.flowing = false
        w.runUntil(START_MS + 60_000L)
        w.answers = { false }
        w.runUntil(START_MS + 200_000L)
        w.starvedSinceMs = w.nowMs
        w.runUntil(w.nowMs + 10_000L)
        assertEquals(true, w.events.filterIsInstance<Event.EpisodeBegan>().single().armed)
        assertEquals(1, w.teardowns.size)
    }

    @Test fun aPhoneThatStopsAnsweringWhileBytesFlowIsStillDisarmed() {
        val w = World()
        w.runUntil(START_MS + 40_000L)
        w.answers = { false }
        w.runUntil(START_MS + 100_000L)
        assertFalse(w.policy.isArmed(w.nowMs))
    }

    @Test fun anInconclusiveBaselineKeepsTheVerdictAndRestartsItsAge() {
        val p = MidFilmLinkPolicy(START_MS)
        p.onProbed(ProbeKind.BASELINE, answered = true, startedAtMs = START_MS, starvedAfterMs = 0L, bytesDuringProbe = true)
        p.onProbed(ProbeKind.BASELINE, answered = false, startedAtMs = START_MS + 80_000L, starvedAfterMs = 0L, bytesDuringProbe = false)
        assertTrue(p.isArmed(START_MS + 80_000L + MidFilmLinkPolicy.ARMED_MAX_AGE_MS))
        assertFalse(p.isArmed(START_MS + 80_001L + MidFilmLinkPolicy.ARMED_MAX_AGE_MS))
    }

    @Test fun anInconclusiveBaselineNeverArmsAPhoneNobodyHeardFrom() {
        val p = MidFilmLinkPolicy(START_MS)
        p.onProbed(ProbeKind.BASELINE, answered = false, startedAtMs = START_MS, starvedAfterMs = 0L, bytesDuringProbe = false)
        assertFalse(p.isArmed(START_MS + 1_000L))
    }

    @Test fun aCrawlingStreamReportsNoEpisodes() {
        val w = World()
        w.runUntil(START_MS + 10_000L)
        val end = START_MS + 130_000L
        while (w.nowMs < end) {
            w.starvedSinceMs = w.nowMs
            w.runUntil(w.nowMs + 2_500L)
            w.starvedSinceMs = null
            w.runUntil(w.nowMs + 1L)
        }
        assertEquals(emptyList<Event>(), w.events.filter { it !is Event.ArmedChanged })
        assertEquals(emptyList<Step.TearDown>(), w.teardowns)
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

    @Test fun anUnarmedCrawlingStreamReportsNoEpisodes() {
        val w = World()
        w.answers = { false }
        w.runUntil(START_MS + 10_000L)
        assertFalse(w.policy.isArmed(w.nowMs))
        val end = w.nowMs + 120_000L
        while (w.nowMs < end) {
            w.starvedSinceMs = w.nowMs
            w.runUntil(w.nowMs + 4_000L)
            w.starvedSinceMs = null
            w.runUntil(w.nowMs + 1L)
        }
        assertTrue(w.count(ProbeKind.STARVED) >= 20)
        assertEquals(emptyList<Event>(), w.events)
        assertEquals(emptyList<Step.TearDown>(), w.teardowns)
    }

    @Test fun anUnarmedEpisodeIsReportedOnceItReachesFiveSeconds() {
        val w = World()
        w.answers = { false }
        w.runUntil(START_MS + 10_000L)
        w.starvedSinceMs = w.nowMs
        w.runUntil(w.nowMs + MidFilmLinkPolicy.TEARDOWN_STARVED_MS - 100L)
        assertEquals(emptyList<Event>(), w.events)
        w.runUntil(w.nowMs + 1_000L)
        assertEquals(false, w.events.filterIsInstance<Event.EpisodeBegan>().single().armed)
    }

    @Test fun sleepsNeverOutlastThePoll() {
        val p = MidFilmLinkPolicy(START_MS)
        p.onProbed(ProbeKind.BASELINE, answered = true, startedAtMs = START_MS, starvedAfterMs = 0L, bytesDuringProbe = true)
        val step = p.step(START_MS + 10L, 0L) as Step.Sleep
        assertEquals(MidFilmLinkPolicy.POLL_MS, step.ms)
    }

    private companion object {
        const val START_MS = 1_000_000L
    }
}
