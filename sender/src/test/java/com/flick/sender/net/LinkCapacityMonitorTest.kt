package com.flick.sender.net

import com.flick.sender.model.PlaybackPhase
import com.flick.sender.model.PlaybackUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The collector, on a clock the test moves by hand.
 *
 * Nothing here needs `runTest`: the monitor owns no timer, only an injected clock, which
 * is what lets a two-minute escalation window be exercised in a few lines. The samples are
 * shaped the way [com.flick.sender.TransferTelemetry] takes them — one second of socket
 * writes with the open-transfer count across it.
 */
class LinkCapacityMonitorTest {

    private var now = 0L
    private val monitor = LinkCapacityMonitor { now }

    /**
     * A fast link fills the TV's buffer in seconds and then throttles to the CONTENT rate.
     * The peak is the tightest lower bound on capacity there is, and it has to survive the
     * throttle that follows it — a mean would under-report this link by an order of
     * magnitude and warn about a cast that never stuttered.
     */
    @Test fun theProvingPeakSurvivesTheThrottleThatFollowsIt() {
        monitor.beginCast(CAST_ID, REQUIRED_BPS)
        warmUp()

        sample(200_000_000L)
        assertEquals(LinkVerdict.Proven(200_000_000L), monitor.verdict.value)

        // Twelve seconds at the content rate: the fast second is long out of the window.
        repeat(12) { sample(REQUIRED_BPS) }

        assertEquals(LinkVerdict.Proven(200_000_000L), monitor.verdict.value)
    }

    @Test fun aLateProvingSampleClearsAStarvedVerdict() {
        monitor.beginCast(CAST_ID, REQUIRED_BPS)
        warmUp()

        repeat(8) { sample(STARVED_BPS) }
        assertEquals(LinkVerdict.Starved(STARVED_BPS, REQUIRED_BPS), monitor.verdict.value)

        sample(60_000_000L)

        assertEquals(LinkVerdict.Proven(60_000_000L), monitor.verdict.value)
    }

    @Test fun theWarmupSecondsNeverReachTheVerdict() {
        monitor.beginCast(CAST_ID, REQUIRED_BPS)
        // Two seconds of slow start would read as starvation if they were retained.
        repeat(2) { sample(1_000_000L) }
        repeat(8) { sample(REQUIRED_BPS) }

        assertEquals(LinkVerdict.Marginal(REQUIRED_BPS, REQUIRED_BPS), monitor.verdict.value)
    }

    @Test fun aFileWithNoMeasurableRequirementNeverLeavesUnknown() {
        monitor.beginCast(CAST_ID, requiredBps = null)
        warmUp()
        repeat(10) { sample(STARVED_BPS) }

        assertEquals(LinkVerdict.Unknown, monitor.verdict.value)
    }

    @Test fun samplesFromAFullBufferCannotStarveANewlyHungryWindow() {
        monitor.beginCast(CAST_ID, REQUIRED_BPS)
        warmUp()
        monitor.onPlayback(frameWithReserve(PlaybackPhase.PLAYING, reserveMs = 10_000L))
        repeat(8) { sample(STARVED_BPS) }
        assertEquals(LinkVerdict.Unknown, monitor.verdict.value)

        monitor.onPlayback(frameWithReserve(PlaybackPhase.PLAYING, reserveMs = 0L))
        repeat(5) { sample(STARVED_BPS) }

        assertEquals(LinkVerdict.Unknown, monitor.verdict.value)
        sample(STARVED_BPS)
        assertEquals(LinkVerdict.Starved(STARVED_BPS, REQUIRED_BPS), monitor.verdict.value)
    }

    @Test fun aNewCastInheritsNothingFromTheOneBeforeIt() {
        monitor.beginCast(CAST_ID, REQUIRED_BPS)
        warmUp()
        sample(200_000_000L)
        play()
        rebufferAt(10_000L)
        rebufferAt(20_000L)
        rebufferAt(30_000L)
        assertTrue(monitor.stall.value.raised)
        assertEquals(LinkVerdict.Proven(200_000_000L), monitor.verdict.value)

        monitor.beginCast("cast-b", REQUIRED_BPS)

        assertEquals(LinkVerdict.Unknown, monitor.verdict.value)
        assertEquals(LinkStall(), monitor.stall.value)
    }

    @Test fun teardownLeavesNoVerdictBehind() {
        monitor.beginCast(CAST_ID, REQUIRED_BPS)
        warmUp()
        sample(200_000_000L)

        monitor.reset()

        assertEquals(LinkVerdict.Unknown, monitor.verdict.value)
        // And a sample that arrives after teardown belongs to no cast.
        sample(200_000_000L)
        assertEquals(LinkVerdict.Unknown, monitor.verdict.value)
    }

    // --- rebuffer episodes --------------------------------------------------

    @Test fun threeStallsInsideTwoMinutesRaiseTheCardAndAgeOutOfIt() {
        monitor.beginCast(CAST_ID, REQUIRED_BPS)
        play()
        rebufferAt(10_000L)
        rebufferAt(20_000L)
        assertFalse(monitor.stall.value.raised)

        rebufferAt(30_000L)
        assertTrue(monitor.stall.value.raised)
        assertEquals(3, monitor.stall.value.episodes)

        // Two minutes past the first stall: it is no longer part of "three in two minutes".
        now = 131_000L
        play()
        assertEquals(2, monitor.stall.value.episodes)
        assertFalse(monitor.stall.value.raised)
    }

    /** The startup fill has its own face and its own timeout; it is not a rebuffer. */
    @Test fun theOpeningBufferIsNotAnEpisode() {
        monitor.beginCast(CAST_ID, REQUIRED_BPS)
        // Eleven seconds of buffering before the TV has ever reached a first frame.
        now = 1_000L
        bufferFrame()
        now = 12_000L
        bufferFrame()

        assertEquals(0, monitor.stall.value.episodes)
        assertFalse(monitor.stall.value.raised)
    }

    @Test fun refillsTheUsersOwnSeekPaidForAreNotCounted() {
        monitor.beginCast(CAST_ID, REQUIRED_BPS)
        play()
        repeat(3) { index ->
            val at = 10_000L + index * 10_000L
            now = at
            // The seek lands, then the decoder refills from the new byte offset.
            monitor.onPlayback(PlaybackUiState(phase = PlaybackPhase.PLAYING, playing = true, syncing = true))
            rebufferAt(at + 1_000L)
        }

        assertEquals(0, monitor.stall.value.episodes)
        assertFalse(monitor.stall.value.raised)
    }

    @Test fun theRefillASubtitleSwapCostsIsNotTheLinksFault() {
        monitor.beginCast(CAST_ID, REQUIRED_BPS)
        play()
        repeat(3) { index ->
            val at = 10_000L + index * 10_000L
            now = at
            monitor.onReload()
            rebufferAt(at + 1_000L)
        }

        assertEquals(0, monitor.stall.value.episodes)
        assertFalse(monitor.stall.value.raised)
    }

    /**
     * The trigger is the stall count ALONE. A link that proved itself and still stalled
     * three times is a VBR-peak starvation the average-bitrate arithmetic cannot see, and
     * the user watched it happen either way — the verdict only decides whether the card
     * quotes numbers.
     */
    @Test fun aProvenLinkThatStillStallsThreeTimesStillRaisesTheCard() {
        monitor.beginCast(CAST_ID, REQUIRED_BPS)
        warmUp()
        sample(200_000_000L)
        play()
        rebufferAt(10_000L)
        rebufferAt(20_000L)
        rebufferAt(30_000L)

        assertTrue(monitor.stall.value.raised)
        assertEquals(LinkVerdict.Proven(200_000_000L), monitor.stall.value.verdict)
        assertFalse(monitor.stall.value.quotesNumbers)
    }

    /**
     * Marginal is at or above real time, so its two figures would read "needs 40.0 Mbps,
     * carrying 44.0 Mbps" under a title asserting the link is short. The stalls are real and
     * the card stays raised; only the numbers, which would be arguing with it, are withheld.
     */
    @Test fun aMarginalLinkRaisesTheCardButQuotesNothing() {
        monitor.beginCast(CAST_ID, REQUIRED_BPS)
        warmUp()
        repeat(8) { sample(REQUIRED_BPS) }
        assertEquals(LinkVerdict.Marginal(REQUIRED_BPS, REQUIRED_BPS), monitor.verdict.value)

        play()
        rebufferAt(10_000L)
        rebufferAt(20_000L)
        rebufferAt(30_000L)

        assertTrue(monitor.stall.value.raised)
        assertFalse(monitor.stall.value.quotesNumbers)
        assertEquals(null, monitor.stall.value.measuredBps)
        assertEquals(null, monitor.stall.value.requiredBps)
    }

    @Test fun aStarvedCardCarriesBothBitratesForTheCopy() {
        monitor.beginCast(CAST_ID, REQUIRED_BPS)
        warmUp()
        repeat(8) { sample(STARVED_BPS) }
        play()
        rebufferAt(10_000L)
        rebufferAt(20_000L)
        rebufferAt(30_000L)

        assertTrue(monitor.stall.value.quotesNumbers)
        assertEquals(STARVED_BPS, monitor.stall.value.measuredBps)
        assertEquals(REQUIRED_BPS, monitor.stall.value.requiredBps)
    }

    @Test fun dismissingTheCardKeepsItDownForTheRestOfTheCast() {
        monitor.beginCast(CAST_ID, REQUIRED_BPS)
        play()
        rebufferAt(10_000L)
        rebufferAt(20_000L)
        rebufferAt(30_000L)
        assertTrue(monitor.stall.value.raised)

        monitor.dismissStall()
        assertFalse(monitor.stall.value.raised)

        rebufferAt(40_000L)
        assertFalse(monitor.stall.value.raised)
        // The stalls themselves are still true; only the card was answered.
        assertEquals(4, monitor.stall.value.episodes)
    }

    @Test fun aTerminalForThisCastFreezesTheVerdictForTheErrorFace() {
        monitor.beginCast(CAST_ID, REQUIRED_BPS)
        warmUp()
        repeat(8) { sample(STARVED_BPS) }
        assertEquals(LinkVerdict.Starved(STARVED_BPS, REQUIRED_BPS), monitor.verdict.value)

        monitor.onCastStart(CastStartState.Failed(CAST_ID, "startup_timeout"))
        sample(200_000_000L)

        assertEquals(LinkVerdict.Starved(STARVED_BPS, REQUIRED_BPS), monitor.verdict.value)
    }

    @Test fun aTerminalFromASupersededCastCannotSilenceThisOne() {
        monitor.beginCast(CAST_ID, REQUIRED_BPS)
        monitor.onCastStart(CastStartState.Failed("cast-older", "startup_timeout"))
        warmUp()
        repeat(8) { sample(STARVED_BPS) }

        assertEquals(LinkVerdict.Starved(STARVED_BPS, REQUIRED_BPS), monitor.verdict.value)
    }

    // --- fixtures -----------------------------------------------------------

    /** One second of socket writes at [bps], with a transfer open across it. */
    private fun sample(bps: Long, inFlight: Int = 1) {
        now += 1_000L
        monitor.onSample(LinkSample(bytes = bps / 8L, elapsedMs = 1_000L, inFlight = inFlight))
    }

    /**
     * The opening of a cast: the TV filling — which is what has it pulling bytes at all,
     * and no rate reading means anything without it — and the two seconds of slow start
     * and range probes the policy discards.
     */
    private fun warmUp() {
        bufferFrame()
        repeat(2) { sample(REQUIRED_BPS) }
    }

    /** A refill still waiting on a seek: `syncing` is up while the seek is outstanding. */
    private fun seekingBufferFrame() = monitor.onPlayback(
        PlaybackUiState(phase = PlaybackPhase.BUFFERING, playing = false, syncing = true),
    )

    /**
     * A TV holding [reserveMs] of media ahead of its playhead. The receiver reports an
     * absolute buffered position, so the reserve is the gap between the two figures.
     */
    private fun frameWithReserve(phase: PlaybackPhase, reserveMs: Long) = PlaybackUiState(
        phase = phase,
        playing = phase == PlaybackPhase.PLAYING,
        confirmedMs = 60_000L,
        bufferedMs = 60_000L + reserveMs,
    )

    private fun play() =
        monitor.onPlayback(PlaybackUiState(phase = PlaybackPhase.PLAYING, playing = true))

    /**
     * A buffering frame exactly as the receiver sends it: `playing` is ExoPlayer's
     * isPlaying, which is false for the whole of any buffer whether the user paused or
     * the bytes ran out. A gate that read this flag would count nothing, ever.
     */
    private fun bufferFrame() =
        monitor.onPlayback(PlaybackUiState(phase = PlaybackPhase.BUFFERING, playing = false))

    /** One rebuffer the user watched: the phase enters buffering and stays there past the floor. */
    private fun rebufferAt(startMs: Long) {
        now = startMs
        bufferFrame()
        now = startMs + LinkCapacityPolicy.MIN_EPISODE_MS
        bufferFrame()
        play()
    }

    private companion object {
        const val CAST_ID = "cast-a"
        const val REQUIRED_BPS = 40_000_000L
        const val STARVED_BPS = 12_000_000L
    }
}
