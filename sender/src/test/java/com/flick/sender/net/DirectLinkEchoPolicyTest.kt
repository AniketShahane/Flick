package com.flick.sender.net

import com.flick.sender.net.DirectLinkEchoPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A one-sided direct link loses every frame this phone sends, and the ping watch released
 * it 6–16 s after the break, close to the TV's buffer. Echo misses act in about four to
 * five seconds — but only for a TV that answers echoes, and never without bound.
 */
class DirectLinkEchoPolicyTest {

    private val policy = DirectLinkEchoPolicy()
    private var nowMs = 100_000L
    private var lastReleaseAtMs = 0L
    private val toRelease = DirectLinkEchoPolicy.MISSES_TO_RELEASE

    /** One probe a second; a [Decision.Release] stamps the shared release clock, as the caller does. */
    private fun probe(outcome: EchoOutcome, trusted: Boolean = true): Decision {
        val decision = policy.onProbe(outcome, trusted, nowMs, lastReleaseAtMs)
        if (decision is Decision.Release) lastReleaseAtMs = nowMs
        nowMs += DirectLinkEchoPolicy.PROBE_PERIOD_MS
        return decision
    }

    private fun answer() = probe(EchoOutcome.ANSWERED)
    private fun miss() = probe(EchoOutcome.MISSED)
    private fun misses(count: Int, trusted: Boolean = true) = List(count) { probe(EchoOutcome.MISSED, trusted) }

    @Test fun theTuningIsFourMissesOfSevenHundredMillisecondsOncePerSecond() {
        assertEquals(4, DirectLinkEchoPolicy.MISSES_TO_RELEASE)
        assertEquals(700, DirectLinkEchoPolicy.PROBE_TIMEOUT_MS)
        assertEquals(1_000L, DirectLinkEchoPolicy.PROBE_PERIOD_MS)
        assertTrue(DirectLinkEchoPolicy.PROBE_TIMEOUT_MS < DirectLinkEchoPolicy.PROBE_PERIOD_MS)
    }

    @Test fun anUntrustedTvNeverReleases() {
        assertTrue(misses(30, trusted = false).all { it == Decision.Wait })
    }

    @Test fun fourConsecutiveMissesRelease() {
        assertEquals(List(toRelease - 1) { Decision.Wait } + Decision.Release(misses = toRelease, attempt = 1), misses(toRelease))
    }

    @Test fun anAnswerResetsTheMissCount() {
        misses(toRelease - 1)
        assertEquals(Decision.Wait, answer())
        assertEquals(List(toRelease - 1) { Decision.Wait }, misses(toRelease - 1))
        assertEquals(Decision.Release(toRelease, 1), miss())
    }

    /** No information: neither a miss nor an answer, and the streak survives a short run of it. */
    @Test fun anUnknownOutcomeChangesNothing() {
        misses(toRelease - 1)
        assertTrue(List(2) { probe(EchoOutcome.UNKNOWN) }.all { it == Decision.Wait })
        assertEquals(Decision.Release(toRelease, 1), miss())
    }

    private fun unknowns(count: Int) = List(count) { probe(EchoOutcome.UNKNOWN) }

    /** Misses on either side of a long gap are not one outage. */
    @Test fun aLongRunOfUnknownEndsTheStreak() {
        miss()
        assertTrue(unknowns(600).all { it == Decision.Wait })
        assertEquals(List(toRelease - 1) { Decision.Wait }, misses(toRelease - 1))
        val firstMissAtMs = nowMs
        assertEquals(Decision.Release(toRelease, 1), miss())
        miss()
        val answeredAtMs = nowMs
        assertEquals(Decision.Repaired(answeredAtMs - firstMissAtMs + (toRelease - 1) * 1_000L), answer())
    }

    @Test fun twoUnknownInsideAStreakStillReleaseAtTheFourthMiss() {
        miss()
        unknowns(2)
        miss()
        unknowns(2)
        miss()
        assertEquals(Decision.Release(toRelease, 1), miss())
    }

    @Test fun exactlyThreeUnknownEndTheStreak() {
        misses(toRelease - 1)
        unknowns(DirectLinkEchoPolicy.UNKNOWN_RUN_TO_FORGET)
        assertEquals(List(toRelease - 1) { Decision.Wait }, misses(toRelease - 1))
        assertEquals(Decision.Release(toRelease, 1), miss())
    }

    /** A gap during verification ends it with no verdict from stale state. */
    @Test fun aLongRunOfUnknownDuringVerificationEndsItWithoutAVerdict() {
        misses(toRelease)
        // A gap mid-verification yields no Repaired and no Unrepaired from stale state...
        assertTrue(unknowns(10).all { it == Decision.Wait })
        // ...but the release nothing has answered since still counts against the episode.
        val second = misses(toRelease)
        assertEquals(List(toRelease - 1) { Decision.Wait }, second.take(toRelease - 1))
        assertEquals(Decision.Release(toRelease, 2), second.last())
        unknowns(10)
        val third = misses(toRelease)
        assertEquals(List(toRelease - 1) { Decision.Wait }, third.take(toRelease - 1))
        assertEquals(Decision.Unrepaired(toRelease), third.last())
        assertTrue(misses(20).all { it == Decision.Wait })
    }

    @Test fun anAnswerAfterAGapStillClearsTheEpisode() {
        misses(toRelease)
        unknowns(10)
        assertEquals(Decision.Wait, answer())
        assertEquals(Decision.Release(toRelease, 1), misses(toRelease).last())
    }

    /** Flapping Wi-Fi is one outage: two releases, then unrepaired, as without the gaps. */
    @Test fun flappingWifiSpendsOneEpisodeAndEndsUnrepaired() {
        val released = mutableListOf<Long>()
        var unrepairedAtMs = -1L
        val startMs = nowMs
        repeat(10) {
            repeat(toRelease) {
                val at = nowMs
                when (miss()) {
                    is Decision.Release -> released += at - startMs
                    is Decision.Unrepaired -> if (unrepairedAtMs < 0) unrepairedAtMs = at - startMs
                    Decision.Wait -> Unit
                    else -> throw AssertionError("unexpected decision")
                }
            }
            unknowns(DirectLinkEchoPolicy.UNKNOWN_RUN_TO_FORGET)
        }
        assertEquals(listOf(3_000L, 10_000L), released)
        assertEquals(17_000L, unrepairedAtMs)
        assertEquals(Decision.Wait, answer())
        assertEquals(Decision.Release(toRelease, 1), misses(toRelease).last())
    }

    /** Before, because a tunnel's route can reach another host; after, because the default can move. */
    @Test fun aRoutedProbeCountsOnlyWhenBothSourceReadingsAreTheServingAddress() {
        val local = "192.0.2.20"
        assertEquals(EchoOutcome.MISSED, EchoProbeRules.routedOutcome(local, EchoOutcome.MISSED, local, local))
        assertEquals(EchoOutcome.ANSWERED, EchoProbeRules.routedOutcome(local, EchoOutcome.ANSWERED, local, local))
        assertEquals(EchoOutcome.UNKNOWN, EchoProbeRules.routedOutcome("198.51.100.7", EchoOutcome.ANSWERED, local, local))
        assertEquals(EchoOutcome.UNKNOWN, EchoProbeRules.routedOutcome(local, EchoOutcome.MISSED, "198.51.100.7", local))
        assertEquals(EchoOutcome.UNKNOWN, EchoProbeRules.routedOutcome(local, EchoOutcome.ANSWERED, null, local))
        assertEquals(EchoOutcome.UNKNOWN, EchoProbeRules.routedOutcome(null, EchoOutcome.MISSED, local, local))
        assertTrue(EchoProbeRules.routedPossible(local, local))
        assertFalse(EchoProbeRules.routedPossible("198.51.100.7", local))
        assertFalse(EchoProbeRules.routedPossible(null, local))
    }

    @Test fun echoModesJournalUnderStableNames() {
        assertEquals(listOf("bound", "routed", "none"), EchoMode.entries.map { it.journalName })
    }

    @Test fun unknownOutcomesAloneNeverRelease() {
        assertTrue(List(30) { probe(EchoOutcome.UNKNOWN) }.all { it == Decision.Wait })
    }

    @Test fun aShortRunOfUnknownEndsNoVerification() {
        misses(toRelease)
        assertTrue(List(2) { probe(EchoOutcome.UNKNOWN) }.all { it == Decision.Wait })
        assertTrue(answer() is Decision.Repaired)
    }

    @Test fun anAnswerAfterAReleaseIsARepairTimedFromTheFirstMiss() {
        val firstMissAtMs = nowMs
        misses(toRelease)
        miss()
        val answeredAtMs = nowMs
        assertEquals(Decision.Repaired(answeredAtMs - firstMissAtMs), answer())
        // A fresh episode needs a fresh streak.
        assertEquals(List(toRelease - 1) { Decision.Wait }, misses(toRelease - 1))
    }

    @Test fun anAnswerWithoutAReleaseIsNoRepair() {
        miss()
        assertEquals(Decision.Wait, answer())
    }

    /** The verification is the dedupe: the second release comes exactly when both allow it. */
    @Test fun anUnansweredVerificationReleasesOnceMore() {
        assertEquals(DirectLinkEchoPolicy.RELEASE_DEDUPE_MS, DirectLinkEchoPolicy.VERIFY_MS)
        misses(toRelease)
        val releasedAtMs = lastReleaseAtMs
        val next = misses(5)
        assertEquals(List(4) { Decision.Wait }, next.take(4))
        assertEquals(Decision.Release(misses = toRelease + 5, attempt = 2), next[4])
        assertEquals(DirectLinkEchoPolicy.VERIFY_MS, lastReleaseAtMs - releasedAtMs)
    }

    @Test fun anEpisodeThatEndsUnrepairedHaltsUntilAnAnswer() {
        misses(toRelease)
        misses(5)
        val verify = misses(5)
        assertEquals(List(4) { Decision.Wait }, verify.take(4))
        assertEquals(Decision.Unrepaired(misses = toRelease + 10), verify[4])
        assertTrue(misses(20).all { it == Decision.Wait })
        assertEquals(Decision.Wait, answer())
        assertEquals(Decision.Release(toRelease, 1), misses(toRelease).last())
    }

    @Test fun releasesAreCappedPerCast() {
        var released = 0
        var cappedSeen = false
        repeat(10) {
            for (decision in misses(toRelease + 10)) {
                if (decision is Decision.Release) released++
                if (decision == Decision.Capped) cappedSeen = true
            }
            answer()
        }
        assertEquals(DirectLinkEchoPolicy.RELEASES_PER_CAST, released)
        assertTrue(cappedSeen)
    }

    @Test fun aReleaseFromAnotherPathIsVerifiedRatherThanRepeated() {
        misses(toRelease - 1)
        lastReleaseAtMs = nowMs - 1_000L
        assertEquals(Decision.Deduped(misses = toRelease, attempt = 1), miss())
        assertEquals(Decision.Repaired(toRelease * 1_000L), answer())
    }

    /** Mid-episode, another path's release holds the second one back until the dedupe allows it. */
    @Test fun aReleaseFromAnotherPathMidEpisodeIsWaitedOut() {
        misses(toRelease)
        misses(3)
        lastReleaseAtMs = nowMs
        val otherAtMs = nowMs
        val next = misses(7)
        val secondAt = next.indexOfFirst { it is Decision.Release }
        assertEquals(DirectLinkEchoPolicy.RELEASE_DEDUPE_MS, secondAt * 1_000L)
        assertTrue(lastReleaseAtMs - otherAtMs >= DirectLinkEchoPolicy.RELEASE_DEDUPE_MS)
    }

    @Test fun theSharedDedupeHoldsFiveSeconds() {
        assertTrue(DirectLinkEchoPolicy.releaseAllowed(0L, 1_000L))
        assertFalse(DirectLinkEchoPolicy.releaseAllowed(10_000L, 14_999L))
        assertTrue(DirectLinkEchoPolicy.releaseAllowed(10_000L, 15_000L))
    }

    @Test fun trustIsGainedOnceAndWithdrawnOnce() {
        val trust = DirectLinkEchoTrust()
        assertFalse(trust.isTrusted("tv"))
        assertTrue(trust.answered("tv"))
        assertFalse(trust.answered("tv"))
        assertTrue(trust.isTrusted("tv"))
        assertTrue(trust.withdraw("tv"))
        assertFalse(trust.withdraw("tv"))
        assertFalse(trust.isTrusted("tv"))
        assertFalse(trust.isTrusted("other"))
    }

    /** At most one every five seconds, from the first miss to the end of the episode. */
    @Test fun theEchoNeverReleasesFasterThanTheDedupe() {
        val stamps = mutableListOf<Long>()
        repeat(40) {
            val at = nowMs
            if (miss() is Decision.Release) stamps += at
        }
        stamps.zipWithNext().forEach { (a, b) -> assertTrue(b - a >= DirectLinkEchoPolicy.RELEASE_DEDUPE_MS) }
    }

    /** Linux's values, as `OsConstants` reports them on Android. */
    private val linux = DirectLinkEchoPolicy.ConnectErrnos(refused = 111, timedOut = 110, hostUnreachable = 113)

    @Test fun aRefusalIsTheTvAnswering() {
        assertEquals(EchoOutcome.ANSWERED, DirectLinkEchoPolicy.outcomeForErrno(111, linux))
    }

    @Test fun aTimeoutOrAnUnreachableHostIsAMiss() {
        assertEquals(EchoOutcome.MISSED, DirectLinkEchoPolicy.outcomeForErrno(110, linux))
        assertEquals(EchoOutcome.MISSED, DirectLinkEchoPolicy.outcomeForErrno(113, linux))
    }

    /** EPERM (a VPN lockdown), ENETUNREACH, EADDRNOTAVAIL and the rest say nothing about the TV. */
    @Test fun everyOtherErrnoIsUnknown() {
        for (errno in listOf(1, 13, 99, 101, 104, 0, -1)) {
            assertEquals("$errno", EchoOutcome.UNKNOWN, DirectLinkEchoPolicy.outcomeForErrno(errno, linux))
        }
    }
}
