package com.flick.receiver.session

import androidx.media3.common.PlaybackException
import com.flick.receiver.net.CastFailureCode
import com.flick.receiver.net.ControlCastResult
import com.flick.receiver.net.ControlLossReason
import com.flick.receiver.net.PreflightLinkRepair
import com.flick.receiver.net.ProbeResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cast transaction's use of [DirectLinkGuard]: one link repair and one more
 * pre-flight when the phone that sent the cast cannot be reached, and a mid-film
 * watch that lives exactly as long as a playing cast.
 */
class DirectLinkGuardSessionTest {

    private class FakeGuard(var repairs: suspend () -> Boolean = { true }) : DirectLinkGuard {
        val watched = mutableListOf<Pair<String, String>>()
        var releases = 0
        var repairCalls = 0

        override fun watchCast(castId: String, mediaUrl: String) {
            watched += castId to mediaUrl
        }

        override fun releaseCast() {
            releases++
        }

        override suspend fun repairUnreachablePeer(mediaUrl: String): Boolean {
            repairCalls++
            return repairs()
        }
    }

    private class Harness(
        val session: SessionController,
        val player: RecordingPlayer,
        val guard: FakeGuard,
        val terminals: MutableList<ControlCastResult.Failed>,
        val probes: () -> Int,
    )

    private fun TestScope.harness(guard: FakeGuard = FakeGuard(), vararg results: ProbeResult): Harness {
        val player = RecordingPlayer()
        var calls = 0
        val session = SessionController(
            player,
            backgroundScope,
            { true },
            { results[calls++.coerceAtMost(results.lastIndex)] },
            clock = { testScheduler.currentTime },
            directLink = guard,
        )
        val terminals = mutableListOf<ControlCastResult.Failed>()
        session.attachTerminal { id, code, retryable, status, beforeReady ->
            terminals += ControlCastResult.Failed(id, code, retryable, status, beforeReady)
        }
        return Harness(session, player, guard, terminals) { calls }
    }

    private fun SessionController.load(castId: String = CAST) =
        onLoadMedia(LEASE, castId, URL, TITLE, DURATION_MS, 0L, null)

    @Test fun anUnreachablePhoneIsRepairedOnceAndTheCastCarriesOn() = runTest {
        val h = harness(FakeGuard(), ProbeResult.Unreachable, ProbeResult.Ok(PROBE_MS))

        h.session.load()
        runCurrent()

        assertEquals(1, h.guard.repairCalls)
        assertEquals(2, h.probes())
        assertTrue(h.session.stage is MediaStage.Preparing)
        assertEquals(emptyList<ControlCastResult.Failed>(), h.terminals)
    }

    @Test fun aRepairThatTearsNothingDownFailsExactlyAsBefore() = runTest {
        val h = harness(FakeGuard { false }, ProbeResult.Unreachable, ProbeResult.Ok(PROBE_MS))

        h.session.load()
        runCurrent()

        assertEquals(1, h.guard.repairCalls)
        assertEquals(1, h.probes())
        val failure = h.terminals.single()
        assertEquals(CastFailureCode.MEDIA_UNREACHABLE, failure.code)
        assertEquals(true, failure.retryable)
        assertEquals(true, failure.beforeReady)
    }

    @Test fun aLinkSlowToComeBackIsCaughtByTheSecondRecheck() = runTest {
        val h = harness(FakeGuard(), ProbeResult.Unreachable, ProbeResult.Unreachable, ProbeResult.Ok(PROBE_MS))

        h.session.load()
        runCurrent()
        assertEquals(2, h.probes())
        advanceTimeBy(1_499L)
        runCurrent()
        assertEquals(2, h.probes())
        advanceTimeBy(1L)
        runCurrent()

        assertEquals(1, h.guard.repairCalls)
        assertEquals(3, h.probes())
        assertTrue(h.session.stage is MediaStage.Preparing)
        assertEquals(emptyList<ControlCastResult.Failed>(), h.terminals)
    }

    @Test fun threeUnreachablePreflightsFailWithoutAnotherRepair() = runTest {
        val h = harness(FakeGuard(), ProbeResult.Unreachable)

        h.session.load()
        runCurrent()
        advanceTimeBy(1_500L)
        runCurrent()

        assertEquals(1, h.guard.repairCalls)
        assertEquals(3, h.probes())
        assertEquals(CastFailureCode.MEDIA_UNREACHABLE, h.terminals.single().code)
    }

    @Test fun anyOtherRecheckResultIsHandledAsBefore() = runTest {
        val h = harness(FakeGuard(), ProbeResult.Unreachable, ProbeResult.ConnectionRefused, ProbeResult.Ok(PROBE_MS))

        h.session.load()
        runCurrent()
        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals(2, h.probes())
        assertEquals(CastFailureCode.SENDER_NOT_SERVING, h.terminals.single().code)
    }

    @Test fun onlyAnUnreachablePhoneIsRepaired() = runTest {
        for (result in listOf(ProbeResult.ConnectionRefused, ProbeResult.HttpError(503), ProbeResult.BadResponse)) {
            val h = harness(FakeGuard(), result)
            h.session.load()
            runCurrent()
            assertEquals("$result", 0, h.guard.repairCalls)
            assertEquals("$result", 1, h.probes())
            assertEquals("$result", 1, h.terminals.size)
        }
    }

    /**
     * The phone probe is the real repair's, made uncancellable the way `isReachable`
     * is: the stop lands while it runs, and only the repair's own cancellation check
     * after it keeps the teardown from happening.
     */
    @Test fun aCastStoppedDuringTheRepairProbeIsNeitherTornDownNorProbedAgain() = runTest {
        val probeReturns = CompletableDeferred<Unit>()
        var teardowns = 0
        val guard = FakeGuard {
            PreflightLinkRepair.run(
                answers = {
                    withContext(NonCancellable) { probeReturns.await() }
                    false
                },
                tearDown = { teardowns++ },
            )
        }
        val h = harness(guard, ProbeResult.Unreachable, ProbeResult.Ok(PROBE_MS))

        h.session.load()
        runCurrent()
        assertTrue(h.session.onStop(CAST))
        probeReturns.complete(Unit)
        runCurrent()
        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals(0, teardowns)
        assertEquals(1, h.probes())
        assertEquals(0, h.player.startups)
        assertEquals(emptyList<ControlCastResult.Failed>(), h.terminals)
    }

    @Test fun theStartupDeadlineStillEndsARepairThatOutlastsIt() = runTest {
        val h = harness(FakeGuard { delay(30_000L); true }, ProbeResult.Unreachable, ProbeResult.Ok(PROBE_MS))

        h.session.load()
        runCurrent()
        advanceTimeBy(18_000L)
        runCurrent()

        assertEquals(CastFailureCode.STARTUP_TIMEOUT, h.terminals.single().code)
        advanceTimeBy(20_000L)
        runCurrent()
        assertEquals(1, h.probes())
        assertEquals(1, h.terminals.size)
    }

    @Test fun thePlayingFilmIsWatchedAgainstItsOwnUrl() = runTest {
        val h = harness(FakeGuard(), ProbeResult.Ok(PROBE_MS))

        h.session.load()
        runCurrent()
        assertEquals(emptyList<Pair<String, String>>(), h.guard.watched)
        h.player.renderFirstFrame()

        assertEquals(listOf(CAST to URL), h.guard.watched)
    }

    @Test fun theWatchIsReleasedWhenTheCastStopsFailsOrIsReplaced() = runTest {
        val h = harness(FakeGuard(), ProbeResult.Ok(PROBE_MS))
        h.session.load()
        runCurrent()
        h.player.renderFirstFrame()

        var before = h.guard.releases
        h.session.onStop(CAST)
        assertTrue(h.guard.releases > before)

        h.session.load(CAST_B)
        runCurrent()
        h.player.renderFirstFrame()
        before = h.guard.releases
        h.player.failPlayback(PlaybackException("io", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
        assertTrue(h.guard.releases > before)

        h.session.load(CAST)
        runCurrent()
        h.player.renderFirstFrame()
        before = h.guard.releases
        h.session.load(CAST_B)
        assertTrue(h.guard.releases > before)
    }

    @Test fun aHeldFilmKeepsItsWatchUntilTheHoldEnds() = runTest {
        val h = harness(FakeGuard(), ProbeResult.Ok(PROBE_MS))
        h.session.load()
        runCurrent()
        h.player.renderFirstFrame()
        val before = h.guard.releases

        h.session.onControlLost(LEASE, ControlLossReason.DROPPED, KEY)

        assertTrue(h.session.reconnecting)
        assertEquals(before, h.guard.releases)
        advanceTimeBy(CastReattachPolicy.ORPHAN_GRACE_MS)
        runCurrent()
        assertTrue(h.guard.releases > before)
    }

    private companion object {
        const val CAST = "cast-a"
        const val CAST_B = "cast-b"
        const val KEY = "key-a"
        const val LEASE = 1L
        const val URL = "http://192.168.42.10:8080/v/token"
        const val TITLE = "Film"
        const val DURATION_MS = 7_200_000L
        const val PROBE_MS = 7L
    }
}
