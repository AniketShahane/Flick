package com.flick.sender.net

import com.flick.sender.net.DirectLinkEchoPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A one-sided direct link loses every frame this phone sends, and the ping watch released
 * it 6–16 s after the break, close to the TV's buffer. Echo misses act in about three
 * seconds — but only for a TV that answers echoes, and never without bound.
 */
class DirectLinkEchoPolicyTest {

    private val policy = DirectLinkEchoPolicy()
    private var nowMs = 100_000L
    private var lastReleaseAtMs = 0L

    /** One probe a second; a [Decision.Release] stamps the shared release clock, as the caller does. */
    private fun probe(answered: Boolean, trusted: Boolean = true): Decision {
        val decision = policy.onProbe(answered, trusted, nowMs, lastReleaseAtMs)
        if (decision is Decision.Release) lastReleaseAtMs = nowMs
        nowMs += DirectLinkEchoPolicy.PROBE_PERIOD_MS
        return decision
    }

    private fun misses(count: Int, trusted: Boolean = true) = List(count) { probe(answered = false, trusted = trusted) }

    @Test fun anUntrustedTvNeverReleases() {
        assertTrue(misses(30, trusted = false).all { it == Decision.Wait })
    }

    @Test fun threeConsecutiveMissesRelease() {
        assertEquals(listOf(Decision.Wait, Decision.Wait, Decision.Release(misses = 3, attempt = 1)), misses(3))
    }

    @Test fun anAnswerResetsTheMissCount() {
        misses(2)
        assertEquals(Decision.Wait, probe(answered = true))
        assertEquals(listOf(Decision.Wait, Decision.Wait), misses(2))
        assertEquals(Decision.Release(3, 1), probe(answered = false))
    }

    @Test fun anAnswerAfterAReleaseIsARepairTimedFromTheFirstMiss() {
        val firstMissAtMs = nowMs
        misses(3)
        misses(1)
        val answeredAtMs = nowMs
        assertEquals(Decision.Repaired(answeredAtMs - firstMissAtMs), probe(answered = true))
        // A fresh episode needs three new misses.
        assertEquals(listOf(Decision.Wait, Decision.Wait), misses(2))
    }

    @Test fun anAnswerWithoutAReleaseIsNoRepair() {
        misses(1)
        assertEquals(Decision.Wait, probe(answered = true))
    }

    /** The second release waits out both the 4 s verification and the 5 s dedupe. */
    @Test fun anUnansweredVerificationReleasesOnceMore() {
        misses(3)
        val releasedAtMs = lastReleaseAtMs
        val next = misses(5)
        assertEquals(List(4) { Decision.Wait }, next.take(4))
        assertEquals(Decision.Release(misses = 8, attempt = 2), next[4])
        assertEquals(DirectLinkEchoPolicy.RELEASE_DEDUPE_MS, lastReleaseAtMs - releasedAtMs)
    }

    @Test fun anEpisodeThatEndsUnrepairedHaltsUntilAnAnswer() {
        misses(3)
        misses(5)
        val verify = misses(4)
        assertEquals(List(3) { Decision.Wait }, verify.take(3))
        assertEquals(Decision.Unrepaired(misses = 12), verify[3])
        assertTrue(misses(20).all { it == Decision.Wait })
        assertEquals(Decision.Wait, probe(answered = true))
        assertEquals(Decision.Release(3, 1), misses(3).last())
    }

    @Test fun releasesAreCappedPerCast() {
        var released = 0
        var cappedSeen = false
        repeat(10) {
            for (decision in misses(12)) {
                if (decision is Decision.Release) released++
                if (decision == Decision.Capped) cappedSeen = true
            }
            probe(answered = true)
        }
        assertEquals(DirectLinkEchoPolicy.RELEASES_PER_CAST, released)
        assertTrue(cappedSeen)
    }

    @Test fun aReleaseFromAnotherPathIsVerifiedRatherThanRepeated() {
        misses(2)
        lastReleaseAtMs = nowMs - 1_000L
        assertEquals(Decision.Deduped(misses = 3, attempt = 1), probe(answered = false))
        assertEquals(Decision.Repaired(3_000L), probe(answered = true))
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
            if (probe(answered = false) is Decision.Release) stamps += at
        }
        stamps.zipWithNext().forEach { (a, b) -> assertTrue(b - a >= DirectLinkEchoPolicy.RELEASE_DEDUPE_MS) }
    }
}
