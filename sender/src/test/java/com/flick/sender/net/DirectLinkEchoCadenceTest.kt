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

    private fun period(idleSinceMs: Long, outcome: EchoOutcome = EchoOutcome.ANSWERED, quiet: Boolean = true, nowMs: Long) =
        DirectLinkEchoCadence.periodMs(idleSinceMs, outcome, quiet, nowMs)

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
