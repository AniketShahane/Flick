package com.flick.sender.net

import com.flick.sender.WifiBand
import com.flick.sender.WifiLinkInfo
import com.flick.sender.model.PlaybackPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic and the refusals behind under-capacity detection.
 *
 * Most of what is asserted here is the policy declining to have an opinion, because a
 * false positive is worse than the stutter it would explain: this feature only ever
 * changes the FACE of a cast that was already failing.
 */
class LinkCapacityPolicyTest {

    // --- required bitrate ---------------------------------------------------

    @Test fun metadataThatCannotSupportTheArithmeticYieldsNoRequirementRatherThanAGuess() {
        assertNull(LinkCapacityPolicy.requiredBitrateBps(sizeBytes = 8_000_000_000L, durationMs = 0L))
        assertNull(LinkCapacityPolicy.requiredBitrateBps(sizeBytes = 0L, durationMs = 7_200_000L))
        assertNull(LinkCapacityPolicy.requiredBitrateBps(sizeBytes = -1L, durationMs = 7_200_000L))
        assertNull(LinkCapacityPolicy.requiredBitrateBps(sizeBytes = 8_000_000_000L, durationMs = -1L))
    }

    /** A 30 GB two-hour remux: container bytes over container seconds, and nothing else. */
    @Test fun thirtyGigabytesOverTwoHoursIsExact() {
        assertEquals(
            33_333_333L,
            LinkCapacityPolicy.requiredBitrateBps(sizeBytes = 30_000_000_000L, durationMs = 7_200_000L),
        )
    }

    @Test fun aRequirementOutsideThePlausibleBandIsAMetadataBugAndNotAFilm() {
        // MediaStore reporting a two-hour remux as one minute long: 4 Gbps of "requirement".
        assertNull(LinkCapacityPolicy.requiredBitrateBps(sizeBytes = 30_000_000_000L, durationMs = 60_000L))
        // A 1 MB clip stretched across an hour.
        assertNull(LinkCapacityPolicy.requiredBitrateBps(sizeBytes = 1_000_000L, durationMs = 3_600_000L))
        // Both bounds are inclusive: they exclude nonsense, not content.
        assertEquals(
            LinkCapacityPolicy.MIN_PLAUSIBLE_BPS,
            LinkCapacityPolicy.requiredBitrateBps(sizeBytes = 12_500_000L, durationMs = 1_000_000L),
        )
        assertEquals(
            LinkCapacityPolicy.MAX_PLAUSIBLE_BPS,
            LinkCapacityPolicy.requiredBitrateBps(sizeBytes = 50_000_000_000L, durationMs = 1_000_000L),
        )
    }

    @Test fun realTimeFactorNeverDividesByZero() {
        assertEquals(0f, LinkCapacityPolicy.realTimeFactor(50_000_000L, 0L), 0f)
        assertEquals(0f, LinkCapacityPolicy.realTimeFactor(0L, 0L), 0f)
        assertEquals(1.25f, LinkCapacityPolicy.realTimeFactor(50_000_000L, 40_000_000L), 0f)
    }

    // --- verdict ------------------------------------------------------------

    @Test fun aWindowShorterThanTheMinimumIsAMomentAndNeverStarvation() {
        val window = LinkWindow(List(5) { second(STARVED_BPS) }, REQUIRED_BPS, demandsBytes = true)
        assertEquals(LinkVerdict.Unknown, LinkCapacityPolicy.verdict(window, alreadyProven = false))
    }

    /**
     * The Kodi failure mode, and the reason this policy is one-sided.
     *
     * "Source too slow for continuous playback" fires on a cache level below zero, so users
     * on gigabit LANs get it with nothing stuttering (xbmc#22332). One second at 1.25x is
     * proof the path carried the film; the throttled seconds after it are the TV's buffer
     * being full, which is the healthy state and must never read as starvation.
     */
    @Test fun oneProvingSampleInsideAnOtherwiseStarvedWindowIsProvenAndNotStarved() {
        val samples = List(9) { second(STARVED_BPS) } + second(52_000_000L)
        val verdict = LinkCapacityPolicy.verdict(
            LinkWindow(samples, REQUIRED_BPS, demandsBytes = true),
            alreadyProven = false,
        )
        assertEquals(LinkVerdict.Proven(52_000_000L), verdict)
    }

    /**
     * The peak quoted here is this WINDOW's best, which under stickiness is a throttled
     * second — the policy holds no memory. The rate that earned the proof is kept by
     * [LinkCapacityMonitor], which owns the cast's peak and re-quotes it.
     */
    @Test fun proofOfCapacityIsStickyForTheCast() {
        val window = LinkWindow(List(10) { second(STARVED_BPS) }, REQUIRED_BPS, demandsBytes = true)
        assertEquals(
            LinkVerdict.Proven(STARVED_BPS),
            LinkCapacityPolicy.verdict(window, alreadyProven = true),
        )
    }

    @Test fun aTvThatStoppedAskingIsNotASlowLink() {
        val idle = LinkWindow(
            List(10) { LinkSample(bytes = 0L, elapsedMs = 1_000L, inFlight = 0) },
            REQUIRED_BPS,
            demandsBytes = true,
        )
        assertEquals(LinkVerdict.Unknown, LinkCapacityPolicy.verdict(idle, alreadyProven = false))

        // One gap is enough: the aggregate across it measures the gap, not the link.
        val gapped = LinkWindow(
            List(9) { second(STARVED_BPS) } + LinkSample(bytes = 0L, elapsedMs = 1_000L, inFlight = 0),
            REQUIRED_BPS,
            demandsBytes = true,
        )
        assertEquals(LinkVerdict.Unknown, LinkCapacityPolicy.verdict(gapped, alreadyProven = false))
    }

    /**
     * The same failure mode as the gap above, and the one `inFlight` cannot see: Media3
     * holds its range request open across a full buffer, so the transfer stays counted
     * while the server writes at the current scene's rate — or at nothing at all while the
     * user pauses. Both are healthy casts, and both aggregate under real time.
     */
    @Test fun aFullBufferThrottlingUnderRealTimeIsNotStarvation() {
        val throttled = LinkWindow(List(10) { second(STARVED_BPS) }, REQUIRED_BPS, demandsBytes = false)
        assertEquals(LinkVerdict.Unknown, LinkCapacityPolicy.verdict(throttled, alreadyProven = false))

        // A six-second pause: the transfer is open and nothing crosses it.
        val paused = LinkWindow(
            List(6) { LinkSample(bytes = 0L, elapsedMs = 1_000L, inFlight = 1) },
            REQUIRED_BPS,
            demandsBytes = false,
        )
        assertEquals(LinkVerdict.Unknown, LinkCapacityPolicy.verdict(paused, alreadyProven = false))
    }

    /**
     * Hunger is the receiver's own reserve, and the ceilings it is read against are the
     * receiver's: `BufferBudget.protectionSecondsAt` holds 39.5 s of media at 40 Mbps and
     * 10.6 s at 128 Mbps, so a full buffer sits far above the floor while a buffer near
     * the level playback resumes from sits under it.
     */
    @Test fun onlyAReceiverShortOfMediaIsAskingForBytes() {
        // The opening fill and every rebuffer: playback has stopped for want of bytes.
        assertTrue(LinkCapacityPolicy.demandsBytes(PlaybackPhase.BUFFERING, reserveMs = 0L))
        assertTrue(LinkCapacityPolicy.demandsBytes(PlaybackPhase.BUFFERING, reserveMs = 30_000L))
        // A cast in its healthy steady state, at either end of the bitrate range.
        assertFalse(LinkCapacityPolicy.demandsBytes(PlaybackPhase.PLAYING, reserveMs = 39_500L))
        assertFalse(LinkCapacityPolicy.demandsBytes(PlaybackPhase.PLAYING, reserveMs = 10_600L))
        assertTrue(LinkCapacityPolicy.demandsBytes(PlaybackPhase.PLAYING, reserveMs = 2_000L))
        // Paused with the buffer full is the quietest wire there is, and says nothing.
        assertFalse(LinkCapacityPolicy.demandsBytes(PlaybackPhase.PAUSED, reserveMs = 30_000L))
        assertTrue(LinkCapacityPolicy.demandsBytes(PlaybackPhase.PAUSED, reserveMs = 1_000L))
        // Nothing is loading in any of these, whatever the reserve reads.
        assertFalse(LinkCapacityPolicy.demandsBytes(PlaybackPhase.IDLE, reserveMs = 0L))
        assertFalse(LinkCapacityPolicy.demandsBytes(PlaybackPhase.ENDED, reserveMs = 0L))
        assertFalse(LinkCapacityPolicy.demandsBytes(PlaybackPhase.ERROR, reserveMs = 0L))
    }

    /**
     * The single most likely false positive: on a light file the TV's buffer sits at its
     * ceiling and throughput reads as the CONTENT bitrate, so rtf ~= 1.0 is the healthy
     * steady state. Marginal is excluded from the UI by construction, so the boundary has
     * to fall on its side.
     */
    @Test fun exactlyRealTimeIsMarginalAndNotStarved() {
        val window = LinkWindow(List(6) { second(REQUIRED_BPS) }, REQUIRED_BPS, demandsBytes = true)
        assertEquals(
            LinkVerdict.Marginal(REQUIRED_BPS, REQUIRED_BPS),
            LinkCapacityPolicy.verdict(window, alreadyProven = false),
        )
    }

    @Test fun exactlyTheHeadroomIsProvenAndNotMarginal() {
        val window = LinkWindow(List(6) { second(PROVING_BPS) }, REQUIRED_BPS, demandsBytes = true)
        assertEquals(
            LinkVerdict.Proven(PROVING_BPS),
            LinkCapacityPolicy.verdict(window, alreadyProven = false),
        )
    }

    @Test fun aSustainedWindowUnderRealTimeIsStarvation() {
        val window = LinkWindow(List(6) { second(STARVED_BPS) }, REQUIRED_BPS, demandsBytes = true)
        assertEquals(
            LinkVerdict.Starved(STARVED_BPS, REQUIRED_BPS),
            LinkCapacityPolicy.verdict(window, alreadyProven = false),
        )
    }

    @Test fun anUnmeasurableFileNeverReachesAVerdict() {
        val window = LinkWindow(List(10) { second(STARVED_BPS) }, requiredBps = null, demandsBytes = true)
        assertEquals(LinkVerdict.Unknown, LinkCapacityPolicy.verdict(window, alreadyProven = false))
        assertEquals(LinkVerdict.Unknown, LinkCapacityPolicy.verdict(window, alreadyProven = true))
    }

    @Test fun samplesInsideTheWarmupAreExcludedFromTheWindow() {
        // 1 Hz stamps, measured from the first served byte.
        val stamps = listOf(0L, 1_000L, 1_999L, 2_000L, 3_000L)
        assertEquals(
            listOf(2_000L, 3_000L),
            stamps.filter { LinkCapacityPolicy.retainSample(it) },
        )
        // What warm-up leaves behind on a cast that has only just started: nothing to say.
        assertEquals(
            LinkVerdict.Unknown,
            LinkCapacityPolicy.verdict(
                LinkWindow(emptyList(), REQUIRED_BPS, demandsBytes = true),
                alreadyProven = false,
            ),
        )
    }

    // --- rebuffer episodes --------------------------------------------------

    @Test fun aRefillTheUsersOwnSeekPaidForIsNotAnEpisode() {
        assertFalse(
            LinkCapacityPolicy.countsAsEpisode(
                startedAtMs = 100_000L,
                lastSeekCommitMs = 98_000L,
                durationMs = 3_000L,
            ),
        )
        assertTrue(
            LinkCapacityPolicy.countsAsEpisode(
                startedAtMs = 100_000L,
                lastSeekCommitMs = 94_000L,
                durationMs = 3_000L,
            ),
        )
        // The seek's own frames re-date the grace window from inside the refill they are
        // paying for — the phone holds the seek outstanding until the fill ends — so a
        // stamp LATER than the refill's start is still the seek's, never the link's.
        assertFalse(
            LinkCapacityPolicy.countsAsEpisode(
                startedAtMs = 100_000L,
                lastSeekCommitMs = 103_000L,
                durationMs = 4_000L,
            ),
        )
        // A hiccup the buffer swallowed.
        assertFalse(
            LinkCapacityPolicy.countsAsEpisode(
                startedAtMs = 100_000L,
                lastSeekCommitMs = 0L,
                durationMs = 900L,
            ),
        )
        assertTrue(
            LinkCapacityPolicy.countsAsEpisode(
                startedAtMs = 100_000L,
                lastSeekCommitMs = 0L,
                durationMs = LinkCapacityPolicy.MIN_EPISODE_MS,
            ),
        )
    }

    @Test fun threeStallsInsideTwoMinutesEscalateAndThreeSpreadWiderDoNot() {
        assertFalse(LinkCapacityPolicy.shouldEscalate(listOf(0L, 30_000L), nowMs = 60_000L))
        assertTrue(LinkCapacityPolicy.shouldEscalate(listOf(0L, 30_000L, 60_000L), nowMs = 60_000L))
        assertFalse(LinkCapacityPolicy.shouldEscalate(listOf(0L, 100_000L, 200_000L), nowMs = 200_000L))
    }

    // --- pre-cast advisory --------------------------------------------------

    /**
     * Link rate is a PHY negotiation, not a measurement. A 72 Mbps 2.4 GHz link cannot
     * realistically carry a 40 Mbps film; an 866 Mbps 5 GHz link carries a 60 Mbps one with
     * room to spare even after the fraction has taken more than half of it away.
     */
    @Test fun theAdvisoryReadsLinkRateGenerouslyInBothDirections() {
        assertTrue(LinkCapacityPolicy.usableThroughputBps(72) < 40_000_000L)
        assertTrue(LinkCapacityPolicy.usableThroughputBps(866) > 60_000_000L)

        assertNotNull(
            LinkCapacityPolicy.preCastAdvisory(
                40_000_000L,
                WifiLinkInfo(WifiBand.GHZ_24, frequencyMhz = 2_437, linkSpeedMbps = 72, rssiDbm = -55),
            ),
        )
        assertNull(
            LinkCapacityPolicy.preCastAdvisory(
                60_000_000L,
                WifiLinkInfo(WifiBand.GHZ_5, frequencyMhz = 5_180, linkSpeedMbps = 866, rssiDbm = -50),
            ),
        )
    }

    @Test fun anUnmeasuredFileIsNeverWarnedAbout() {
        val link = WifiLinkInfo(WifiBand.GHZ_24, frequencyMhz = 2_437, linkSpeedMbps = 72, rssiDbm = -55)
        assertNull(LinkCapacityPolicy.preCastAdvisory(requiredBps = null, link = link))
        // And a phone that is not on Wi-Fi has read nothing to warn from.
        assertNull(LinkCapacityPolicy.preCastAdvisory(requiredBps = 40_000_000L, link = null))
    }

    /** One second of wire at [bps], with a transfer open across it. */
    private fun second(bps: Long, inFlight: Int = 1) =
        LinkSample(bytes = bps / 8L, elapsedMs = 1_000L, inFlight = inFlight)

    private companion object {
        /** A 40 Mbps film — mid-range for a UHD remux, and divisible so the fixtures are exact. */
        const val REQUIRED_BPS = 40_000_000L
        const val STARVED_BPS = 12_000_000L   // 0.3x
        const val PROVING_BPS = 50_000_000L   // exactly 1.25x
    }
}
