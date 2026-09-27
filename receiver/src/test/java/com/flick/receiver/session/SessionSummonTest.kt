package com.flick.receiver.session

import com.flick.receiver.net.CastFailureCode
import com.flick.receiver.net.ControlCastResult
import com.flick.receiver.net.ControlLossReason
import com.flick.receiver.net.ExternalSubtitle
import com.flick.receiver.net.ProbeResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A cast adopted while the Activity is stopped, through the [ForegroundRequest] seam.
 *
 * Every "advance" here is advanceTimeBy followed by runCurrent: advanceTimeBy does
 * not run tasks scheduled exactly at the new time, and the budgets under test land
 * exactly on their boundaries.
 */
class SessionSummonTest {

    private var started = false

    /**
     * Stands in for ForegroundSummoner. The wait resolves on [startedSignal], which a
     * test completes only after setting [started], mirroring the order the real
     * Lifecycle gives: the observer runs before currentStateFlow emits.
     */
    private class FakeForegroundRequest(var grant: Boolean, private val now: () -> Long) : ForegroundRequest {
        var holds = 0
        var requests = 0
        var misses = 0
        var cancelledWaits = 0
        val released = mutableListOf<Long>()
        val releasedAtMs = mutableListOf<Long>()
        val budgets = mutableListOf<Long>()
        val startedSignal = CompletableDeferred<Unit>()

        override fun holdAwake(): Long = (++holds).toLong()

        override fun release(token: Long) {
            released += token
            releasedAtMs += now()
        }

        override fun request(): Boolean {
            requests++
            return grant
        }

        override suspend fun awaitStarted(timeoutMs: Long): Boolean {
            budgets += timeoutMs
            try {
                return withTimeoutOrNull(timeoutMs) { startedSignal.await() } != null
            } catch (e: CancellationException) {
                cancelledWaits++
                throw e
            }
        }

        override fun missed() {
            misses++
        }

        /** Every hold taken was released exactly once, so nothing is held now. */
        fun assertBalanced() {
            assertEquals((1L..holds.toLong()).toList(), released.filter { it != 0L }.sorted())
        }
    }

    private fun open(fake: FakeForegroundRequest) {
        started = true
        fake.startedSignal.complete(Unit)
    }

    private class Harness(
        val session: SessionController,
        val player: RecordingPlayer,
        val fake: FakeForegroundRequest,
        val terminals: MutableList<ControlCastResult.Failed>,
        val readies: MutableList<String>,
    )

    private fun TestScope.harness(
        grant: Boolean = true,
        probe: suspend (String) -> ProbeResult = { ProbeResult.Ok(PROBE_MS) },
    ): Harness {
        val player = RecordingPlayer()
        val fake = FakeForegroundRequest(grant) { testScheduler.currentTime }
        val session = SessionController(
            player,
            backgroundScope,
            { started },
            probe,
            foreground = fake,
            clock = { testScheduler.currentTime },
        )
        val terminals = mutableListOf<ControlCastResult.Failed>()
        session.attachTerminal { id, code, retryable, status, beforeReady ->
            terminals += ControlCastResult.Failed(id, code, retryable, status, beforeReady)
        }
        val readies = mutableListOf<String>()
        session.attachReady { id, _, _ -> readies += id }
        return Harness(session, player, fake, terminals, readies)
    }

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun SessionController.load(castId: String = CAST, lease: Long = LEASE) =
        onLoadMedia(lease, castId, URL, TITLE, DURATION_MS, 0L, null)

    private fun assertTvBackgrounded(failure: ControlCastResult.Failed) {
        assertEquals(CastFailureCode.TV_BACKGROUNDED, failure.code)
        assertEquals(false, failure.retryable)
        assertEquals(true, failure.beforeReady)
    }

    // --- a-d: the ordinary paths ----------------------------------------------

    @Test fun aStartedTvNeverAsksAndNeverHolds() = runTest {
        started = true
        val h = harness()

        h.session.load()
        runCurrent()

        assertEquals(0, h.fake.holds)
        assertEquals(0, h.fake.requests)
        assertEquals(listOf(0L), h.fake.released)
        assertTrue(h.session.stage is MediaStage.Preparing)
        h.fake.assertBalanced()
    }

    @Test fun aRefusedRequestFailsExactlyAsToday() = runTest {
        val h = harness(grant = false)

        h.session.load()
        runCurrent()

        assertTvBackgrounded(h.terminals.single())
        val stage = h.session.stage as MediaStage.Error
        assertEquals(CastFailureCode.TV_BACKGROUNDED, stage.code)
        assertEquals(0, h.fake.misses)
        assertEquals(1, h.fake.holds)
        assertEquals(listOf(1L), h.fake.released)
        h.fake.assertBalanced()
    }

    @Test fun nothingSummonRelatedRunsInsideTheAdoption() = runTest {
        val h = harness()

        val result = h.session.load()

        assertEquals(ControlCastResult.Accepted(CAST), result)
        assertEquals(0, h.fake.holds)
        assertEquals(0, h.fake.requests)

        runCurrent()

        assertEquals(1, h.fake.holds)
        assertEquals(1, h.fake.requests)
    }

    @Test fun aSummonThatOpensPlays() = runTest {
        val h = harness()

        h.session.load()
        runCurrent()
        assertTrue(h.session.stage is MediaStage.Checking)
        open(h.fake)
        runCurrent()

        assertTrue(h.session.stage is MediaStage.Preparing)
        h.player.renderFirstFrame()
        assertEquals(MediaStage.Active(CAST, LEASE), h.session.stage)
        assertEquals(listOf(CAST), h.readies)
        assertEquals(emptyList<ControlCastResult.Failed>(), h.terminals)
        h.fake.assertBalanced()
    }

    // --- e-g: the miss and the budget -----------------------------------------

    @Test fun aSummonThatNeverOpensFailsUnrenderedOnceTheBudgetEnds() = runTest {
        val h = harness()

        h.session.load()
        runCurrent()
        advance(7_999L)
        assertEquals(emptyList<ControlCastResult.Failed>(), h.terminals)
        advance(1L)

        assertTvBackgrounded(h.terminals.single())
        assertEquals(1, h.fake.misses)
        assertEquals(MediaStage.None, h.session.stage)
        assertNull(h.session.title)
        assertEquals(0L, h.session.seekTargetMs)
        val retained = h.session.replayResult(CAST) as ControlCastResult.Failed
        assertEquals(CastFailureCode.TV_BACKGROUNDED, retained.code)
        h.fake.assertBalanced()
    }

    @Test fun aFastProbeWaitsTheFullBudget() = runTest {
        val h = harness()

        h.session.load()
        runCurrent()

        assertEquals(listOf(8_000L), h.fake.budgets)
    }

    @Test fun aSlowProbeLeavesTheFirstFrameItsReserve() = runTest {
        val h = harness(probe = { delay(6_000L); ProbeResult.Ok(PROBE_MS) })

        h.session.load()
        runCurrent()
        advance(6_000L)

        assertEquals(listOf(4_000L), h.fake.budgets)
    }

    @Test fun aProbeThatSpentTheBudgetNeverAsks() = runTest {
        val h = harness(probe = { delay(10_500L); ProbeResult.Ok(PROBE_MS) })

        h.session.load()
        runCurrent()
        advance(10_500L)

        assertEquals(0, h.fake.requests)
        assertTvBackgrounded(h.terminals.single())
        assertTrue(h.session.stage is MediaStage.Error)
        h.fake.assertBalanced()
    }

    // --- h: a failed probe never summons --------------------------------------

    @Test fun aFailedProbeSendsItsOwnCodeAndNeverSummons() = runTest {
        val cases = listOf(
            ProbeResult.Unreachable to CastFailureCode.MEDIA_UNREACHABLE,
            ProbeResult.ConnectionRefused to CastFailureCode.SENDER_NOT_SERVING,
            ProbeResult.HttpError(404) to CastFailureCode.HTTP_REJECTED,
        )
        for ((result, code) in cases) {
            val h = harness(probe = { result })

            h.session.load()
            runCurrent()

            assertEquals(0, h.fake.requests)
            assertEquals(code, h.terminals.single().code)
            assertEquals(listOf(1L), h.fake.released)
            h.fake.assertBalanced()
        }
    }

    // --- i-l: cancellation during the wait ------------------------------------

    @Test fun cancellingDuringTheWaitAbandonsItSilently() = runTest {
        val h = harness()

        h.session.load()
        runCurrent()
        h.session.onCancelLoad(CAST)
        runCurrent()

        assertEquals(1, h.fake.cancelledWaits)
        assertEquals(emptyList<ControlCastResult.Failed>(), h.terminals)
        assertEquals(0, h.fake.misses)
        assertEquals(MediaStage.None, h.session.stage)
        h.fake.assertBalanced()

        advance(30_000L)

        assertEquals(emptyList<ControlCastResult.Failed>(), h.terminals)
        assertEquals(0, h.fake.misses)
        assertEquals(MediaStage.None, h.session.stage)
    }

    @Test fun stoppingDuringTheWaitIsAStopNotABackgrounding() = runTest {
        val h = harness()

        h.session.load()
        runCurrent()
        h.session.onStop(CAST)
        runCurrent()
        advance(30_000L)

        assertEquals(emptyList<ControlCastResult.Failed>(), h.terminals)
        assertEquals(ControlCastResult.Stopped(CAST), h.session.replayResult(CAST))
        assertEquals(1, h.fake.cancelledWaits)
        h.fake.assertBalanced()
    }

    @Test fun losingControlDuringTheWaitSendsNothing() = runTest {
        val h = harness()

        h.session.load()
        runCurrent()
        h.session.onControlLost(LEASE, ControlLossReason.DROPPED)
        runCurrent()
        advance(30_000L)

        assertEquals(emptyList<ControlCastResult.Failed>(), h.terminals)
        assertEquals(0, h.fake.misses)
        assertEquals(1, h.fake.cancelledWaits)
        h.fake.assertBalanced()
    }

    @Test fun aNewCastDuringTheWaitSupersedesItAndSummonsForItself() = runTest {
        val h = harness()

        h.session.load()
        runCurrent()
        h.session.load(CAST_B, LEASE + 1L)
        runCurrent()

        assertEquals(1, h.fake.cancelledWaits)
        assertEquals(2, h.fake.requests)
        assertEquals(emptyList<ControlCastResult.Failed>(), h.terminals)

        open(h.fake)
        runCurrent()

        assertEquals(MediaStage.Preparing(CAST_B, LEASE + 1L), h.session.stage)
        h.player.renderFirstFrame()
        assertEquals(listOf(CAST_B), h.readies)
        assertEquals(emptyList<ControlCastResult.Failed>(), h.terminals)
        h.fake.assertBalanced()
    }

    // --- m-o: after the verdict -----------------------------------------------

    @Test fun aMissIsTheOnlyTerminalEvenPastTheStartupDeadline() = runTest {
        val h = harness()

        h.session.load()
        runCurrent()
        advance(8_000L)
        advance(20_000L)

        assertTvBackgrounded(h.terminals.single())
        h.fake.assertBalanced()
    }

    @Test fun anActivityThatStartsAfterAMissPlaysNothing() = runTest {
        val h = harness()

        h.session.load()
        runCurrent()
        advance(8_000L)
        open(h.fake)
        runCurrent()

        assertEquals(0, h.player.startups)
        assertEquals(MediaStage.None, h.session.stage)
        assertEquals(1, h.terminals.size)
    }

    @Test fun startedLandingWithTheTimeoutStillPlays() = runTest {
        val h = harness()

        h.session.load()
        runCurrent()
        advance(3_000L)
        // Started, but the wait never hears of it and times out on its own.
        started = true
        advance(5_000L)

        assertTrue(h.session.stage is MediaStage.Preparing)
        assertEquals(1, h.player.startups)
        assertEquals(0, h.fake.misses)
        assertEquals(emptyList<ControlCastResult.Failed>(), h.terminals)
        h.fake.assertBalanced()
    }

    // --- p-r: reloads, the default seam, and the deadline ---------------------

    @Test fun aSubtitleReloadDuringTheWaitSummonsOncePerGeneration() = runTest {
        val h = harness()

        h.session.load()
        runCurrent()
        h.session.onReloadMedia(LEASE, CAST, URL, TITLE, DURATION_MS, 0L, SUBTITLE)
        runCurrent()

        assertEquals(1, h.fake.cancelledWaits)
        assertEquals(2, h.fake.requests)
        assertEquals(emptyList<ControlCastResult.Failed>(), h.terminals)

        open(h.fake)
        runCurrent()

        assertTrue(h.session.stage is MediaStage.Preparing)
        assertEquals(SUBTITLE, h.player.lastStartupSubtitle)
        assertEquals(emptyList<ControlCastResult.Failed>(), h.terminals)
        h.fake.assertBalanced()
    }

    @Test fun theDefaultSeamFailsAStoppedCastExactlyAsBefore() = runTest {
        val player = RecordingPlayer()
        val session = SessionController(
            player,
            backgroundScope,
            { false },
            { ProbeResult.Ok(PROBE_MS) },
            foreground = ForegroundRequest.None,
        )
        val terminals = mutableListOf<ControlCastResult.Failed>()
        session.attachTerminal { id, code, retryable, status, beforeReady ->
            terminals += ControlCastResult.Failed(id, code, retryable, status, beforeReady)
        }

        session.load()
        runCurrent()

        assertTvBackgrounded(terminals.single())
        assertEquals(CastFailureCode.TV_BACKGROUNDED, (session.stage as MediaStage.Error).code)
        assertEquals(0, player.startups)
    }

    @Test fun theStartupDeadlineEndsAProbeThatOutlastsIt() = runTest {
        val h = harness(probe = { delay(20_000L); ProbeResult.Ok(PROBE_MS) })

        h.session.load()
        runCurrent()
        advance(18_000L)

        assertEquals(CastFailureCode.STARTUP_TIMEOUT, h.terminals.single().code)
        assertEquals(listOf(1L), h.fake.released)
        assertEquals(listOf(18_000L), h.fake.releasedAtMs)
        assertEquals(0, h.fake.requests)

        advance(10_000L)

        assertEquals(1, h.terminals.size)
        assertEquals(0, h.fake.requests)
        h.fake.assertBalanced()
    }

    private companion object {
        const val CAST = "cast-a"
        const val CAST_B = "cast-b"
        const val LEASE = 1L
        const val URL = "http://192.168.42.10:8080/v/token"
        const val TITLE = "Film"
        const val DURATION_MS = 7_200_000L
        const val PROBE_MS = 7L
        val SUBTITLE = ExternalSubtitle("http://192.168.42.10:8080/s/subtoken", "film.srt", "en")
    }
}
