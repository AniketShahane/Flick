package com.flick.sender.net

import com.flick.sender.model.PlaybackPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A paused film settles into echoes every five seconds; a playing one never leaves the
 * one-second cadence, and nothing that could be the start of an outage waits out a slow one.
 */
class DirectLinkEchoCadenceTest {

    private val fast = DirectLinkEchoPolicy.PROBE_PERIOD_MS
    private val slow = DirectLinkEchoCadence.IDLE_PROBE_PERIOD_MS
    private val settle = DirectLinkEchoCadence.IDLE_SETTLE_MS
    private val pausedAt = 100_000L

    private fun period(
        idleSinceMs: Long,
        outcome: EchoOutcome = EchoOutcome.ANSWERED,
        quiet: Boolean = true,
        nowMs: Long,
        phaseIdleNow: Boolean = idleSinceMs > 0L,
        wokeAtMs: Long = 0L,
        canRelease: Boolean = true,
        pingOverdue: Boolean = false,
    ) = DirectLinkEchoCadence.periodMs(
        phaseIdleNow = phaseIdleNow,
        idleSinceMs = idleSinceMs,
        wokeAtMs = wokeAtMs,
        lastOutcome = outcome,
        quiet = quiet,
        canRelease = canRelease,
        pingOverdue = pingOverdue,
        nowMs = nowMs,
    )

    @Test fun theTuningIsFiveSecondsAfterTenOfPause() {
        assertEquals(5_000L, slow)
        assertEquals(10_000L, settle)
    }

    @Test fun onlyPausedAndEndedAreIdle() {
        assertTrue(DirectLinkEchoCadence.idlePhase(PlaybackPhase.PAUSED))
        assertTrue(DirectLinkEchoCadence.idlePhase(PlaybackPhase.ENDED))
        for (phase in listOf(PlaybackPhase.PLAYING, PlaybackPhase.BUFFERING, PlaybackPhase.IDLE, PlaybackPhase.ERROR)) {
            assertFalse(phase.name, DirectLinkEchoCadence.idlePhase(phase))
        }
    }

    @Test fun aPlayingFilmIsAlwaysProbedEverySecond() {
        assertEquals(fast, period(idleSinceMs = 0L, nowMs = pausedAt + 60 * 60_000L))
    }

    @Test fun aPauseSettlesAfterTenSecondsAndNotBefore() {
        assertEquals(fast, period(pausedAt, nowMs = pausedAt + settle - 1))
        assertEquals(slow, period(pausedAt, nowMs = pausedAt + settle))
        assertEquals(slow, period(pausedAt, nowMs = pausedAt + 60 * 60_000L))
    }

    @Test fun aMissOrAnUnclassifiedProbeIsBackToEverySecond() {
        assertEquals(fast, period(pausedAt, EchoOutcome.MISSED, nowMs = pausedAt + settle * 3))
        assertEquals(fast, period(pausedAt, EchoOutcome.UNKNOWN, nowMs = pausedAt + settle * 3))
    }

    @Test fun aStreakOrAReleaseUnderVerificationIsBackToEverySecond() {
        assertEquals(fast, period(pausedAt, quiet = false, nowMs = pausedAt + settle * 3))
    }

    @Test fun aPhaseThatHasLeftPauseIsFastEvenBeforeTheStampCatchesUp() {
        assertEquals(fast, period(pausedAt, phaseIdleNow = false, nowMs = pausedAt + settle * 3))
    }

    @Test fun aCommandWakesTheCadenceForAFreshSettlePeriod() {
        val commandAt = pausedAt + settle * 3
        assertEquals(fast, period(pausedAt, wokeAtMs = commandAt, nowMs = commandAt))
        assertEquals(fast, period(pausedAt, wokeAtMs = commandAt, nowMs = commandAt + settle - 1))
        assertEquals(slow, period(pausedAt, wokeAtMs = commandAt, nowMs = commandAt + settle))
        // A command sent before the pause settles nothing later than the pause itself.
        assertEquals(slow, period(pausedAt, wokeAtMs = pausedAt - 1_000L, nowMs = pausedAt + settle))
    }

    @Test fun aPolicyThatCanNoLongerReleaseKeepsEchoesDenseForThePingWatch() {
        assertEquals(fast, period(pausedAt, canRelease = false, nowMs = pausedAt + settle * 3))
    }

    @Test fun anOverduePingKeepsEchoesDense() {
        assertEquals(fast, period(pausedAt, pingOverdue = true, nowMs = pausedAt + settle * 3))
    }

    @Test fun thePolicyCanReleaseUntilTheCastBudgetIsSpentOrTheEpisodeHalts() {
        val policy = DirectLinkEchoPolicy()
        assertFalse(policy.canRelease(trusted = false))
        assertTrue(policy.canRelease(trusted = true))
        var nowMs = 10_000L
        var released = 0
        // Each episode: four misses release, the verification lapses into a second release,
        // and an answer repairs it. Six releases spend the cast's budget.
        while (released < DirectLinkEchoPolicy.RELEASES_PER_CAST) {
            val decision = policy.onProbe(EchoOutcome.MISSED, trusted = true, nowMs = nowMs, lastReleaseAtMs = 0L)
            if (decision is DirectLinkEchoPolicy.Decision.Release) {
                released += 1
                nowMs += DirectLinkEchoPolicy.RELEASE_DEDUPE_MS
                policy.onProbe(EchoOutcome.ANSWERED, trusted = true, nowMs = nowMs, lastReleaseAtMs = 0L)
            }
            nowMs += fast
            assertTrue(policy.canRelease(trusted = true) || released == DirectLinkEchoPolicy.RELEASES_PER_CAST)
        }
        assertFalse(policy.canRelease(trusted = true))
    }

    @Test fun aHaltedEpisodeCannotReleaseUntilAnAnswer() {
        val policy = DirectLinkEchoPolicy()
        var nowMs = 10_000L
        var halted = false
        repeat(40) {
            val decision = policy.onProbe(EchoOutcome.MISSED, trusted = true, nowMs = nowMs, lastReleaseAtMs = 0L)
            if (decision is DirectLinkEchoPolicy.Decision.Unrepaired) halted = true
            nowMs += fast
        }
        assertTrue(halted)
        assertFalse(policy.canRelease(trusted = true))
        policy.onProbe(EchoOutcome.ANSWERED, trusted = true, nowMs = nowMs, lastReleaseAtMs = 0L)
        assertTrue(policy.canRelease(trusted = true))
    }

    @Test fun theJournalTakesAtMostOneCadenceLinePerGapAndEndsOnTheStateThatHeld() {
        val gate = EchoCadenceJournalGate()
        val gap = EchoCadenceJournalGate.MIN_GAP_MS
        var nowMs = 1_000L
        // The fast cadence a watch starts in is never journaled.
        assertFalse(gate.journals(slow = false, nowMs = nowMs))
        assertTrue(gate.journals(slow = true, nowMs = nowMs))
        // Brief wakes inside the gap cost nothing.
        repeat(5) {
            nowMs += 3_000L
            assertFalse(gate.journals(slow = false, nowMs = nowMs))
            nowMs += 1_000L
            assertFalse(gate.journals(slow = true, nowMs = nowMs))
        }
        // A wake that persists past the gap is written once the gap ends, and only once.
        nowMs += 1_000L
        assertFalse(gate.journals(slow = false, nowMs = nowMs))
        nowMs = 1_000L + gap
        assertTrue(gate.journals(slow = false, nowMs = nowMs))
        assertFalse(gate.journals(slow = false, nowMs = nowMs + gap))
        // Back to the state already journaled inside the gap: nothing owed.
        assertFalse(gate.journals(slow = true, nowMs = nowMs + 1_000L))
        assertFalse(gate.journals(slow = false, nowMs = nowMs + 2_000L))
    }

    @Test fun thePolicyIsQuietOnlyWithNoStreakAndNothingToVerify() {
        val policy = DirectLinkEchoPolicy()
        var nowMs = 10_000L
        fun probe(outcome: EchoOutcome) = policy.onProbe(outcome, trusted = true, nowMs = nowMs, lastReleaseAtMs = 0L).also { nowMs += fast }
        assertTrue(policy.quiet)
        probe(EchoOutcome.MISSED)
        assertFalse(policy.quiet)
        probe(EchoOutcome.ANSWERED)
        assertTrue(policy.quiet)
        repeat(DirectLinkEchoPolicy.MISSES_TO_RELEASE) { probe(EchoOutcome.MISSED) }
        assertFalse(policy.quiet)
        // An unclassified run forgets the streak and the verification without a verdict.
        repeat(DirectLinkEchoPolicy.UNKNOWN_RUN_TO_FORGET) { probe(EchoOutcome.UNKNOWN) }
        assertTrue(policy.quiet)
    }

    @Test fun echoesAreSparseWhileSlowAndForTheFreshnessWindowAfter() {
        val fresh = DirectLinkWatchPolicy.ECHO_PROOF_FRESH_MS
        assertTrue(DirectLinkWatchPolicy.echoProofSparse(slowNow = true, slowEndedAtMs = 0L, nowMs = pausedAt))
        assertFalse(DirectLinkWatchPolicy.echoProofSparse(slowNow = false, slowEndedAtMs = 0L, nowMs = pausedAt))
        assertTrue(DirectLinkWatchPolicy.echoProofSparse(slowNow = false, slowEndedAtMs = pausedAt, nowMs = pausedAt + fresh - 1))
        assertFalse(DirectLinkWatchPolicy.echoProofSparse(slowNow = false, slowEndedAtMs = pausedAt, nowMs = pausedAt + fresh))
    }
}
