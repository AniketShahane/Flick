package com.flick.receiver.session

import com.flick.receiver.net.CastFailureCode
import com.flick.receiver.net.ControlCastResult
import com.flick.receiver.net.ProbeResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the startup budget charges a picture-orientation correction for.
 *
 * `KEY_ROTATION` reaches the decoder only at codec configuration, so an
 * automatic correction re-prepares the live player — a second configure plus an
 * HTTP refill, inside the 18 s window the cast is being judged in. On a healthy
 * 5 GHz LAN that is a second of an 18 s budget; on a marginal link the refill
 * runs near real time, and the cast that would have made 18 s times out with
 * `startup_timeout` instead. The automatic verdict then recomputes identically,
 * so every retry the phone offers fails the same way and a film that merely
 * looked sideways cannot be cast at all.
 *
 * These exercise the deadline as the cast transaction actually arms it: the job
 * is what fails a cast, so a fix that moved only the timestamp would pass a test
 * written against the timestamp and still tear the cast down on time.
 */
class StartupDeadlineExtensionTest {

    @Test fun aCastWithNoCorrectionKeepsTheOriginalDeadlineExactly() = runTest {
        val player = RecordingPlayer()
        val session = startingSession(player)
        val failures = session.recordTerminals()

        advanceTimeBy(STARTUP_DEADLINE_MS - 1L)
        runCurrent()
        assertEquals(emptyList<ControlCastResult.Failed>(), failures)

        advanceTimeBy(2L)
        runCurrent()

        assertEquals(CastFailureCode.STARTUP_TIMEOUT, failures.single().code)
        assertTrue(failures.single().beforeReady)
    }

    @Test fun oneCorrectionCarriesTheCastPastTheOriginalDeadline() = runTest {
        val player = RecordingPlayer()
        val session = startingSession(player)
        val failures = session.recordTerminals()

        player.reportRotationRePrepare()
        runCurrent()

        advanceTimeBy(STARTUP_DEADLINE_MS + 1L)
        runCurrent()

        assertEquals(emptyList<ControlCastResult.Failed>(), failures)
        assertTrue(session.stage is MediaStage.Preparing)

        // And the frame that arrives inside the granted time still readies the cast.
        player.renderFirstFrame()
        assertEquals(MediaStage.Active(CAST, LEASE), session.stage)
    }

    @Test fun aGenuinelySlowLinkStillTimesOutOnceTheGrantIsSpent() = runTest {
        val player = RecordingPlayer()
        val session = startingSession(player)
        val failures = session.recordTerminals()

        player.reportRotationRePrepare()
        runCurrent()

        advanceTimeBy(EXTENDED_DEADLINE_MS - 1L)
        runCurrent()
        assertEquals(emptyList<ControlCastResult.Failed>(), failures)

        advanceTimeBy(2L)
        runCurrent()

        val failure = failures.single()
        assertEquals(CastFailureCode.STARTUP_TIMEOUT, failure.code)
        assertTrue(failure.beforeReady)
        assertTrue(failure.retryable)
        assertTrue(session.stage is MediaStage.Error)
    }

    @Test fun aSecondCorrectionBuysNoMoreTime() = runTest {
        val player = RecordingPlayer()
        val session = startingSession(player)
        val failures = session.recordTerminals()

        player.reportRotationRePrepare()
        runCurrent()
        advanceTimeBy(5_000L)
        runCurrent()
        // A second grant would push the deadline out again and this cast would
        // still be preparing when the assertion below fires.
        player.reportRotationRePrepare()
        runCurrent()

        advanceTimeBy(EXTENDED_DEADLINE_MS - 5_000L + 1L)
        runCurrent()

        assertEquals(CastFailureCode.STARTUP_TIMEOUT, failures.single().code)
    }

    /** The panel's orientation row lives on an Active cast; there is no budget left to move. */
    @Test fun aCorrectionAfterTheFirstFrameTouchesNothing() = runTest {
        val player = RecordingPlayer()
        val session = startingSession(player)
        val failures = session.recordTerminals()
        player.renderFirstFrame()

        player.reportRotationRePrepare()
        advanceTimeBy(EXTENDED_DEADLINE_MS * 2)
        runCurrent()

        assertEquals(emptyList<ControlCastResult.Failed>(), failures)
        assertEquals(MediaStage.Active(CAST, LEASE), session.stage)
    }

    /** The next film is judged on its own link, with its own grant to spend. */
    @Test fun aRetargetStartsFromACleanBudget() = runTest {
        val player = RecordingPlayer()
        val session = startingSession(player)
        val failures = session.recordTerminals()
        player.reportRotationRePrepare()
        runCurrent()

        session.onLoadMedia(LEASE, CAST_B, URL, TITLE, DURATION_MS, 0L, null)
        runCurrent()
        player.reportRotationRePrepare()
        runCurrent()

        // A grant that leaked from the previous cast would fail this one on time.
        advanceTimeBy(EXTENDED_DEADLINE_MS - 1L)
        runCurrent()
        assertEquals(emptyList<ControlCastResult.Failed>(), failures)

        advanceTimeBy(2L)
        runCurrent()
        assertEquals(CastFailureCode.STARTUP_TIMEOUT, failures.single().code)
    }

    // --- Fixtures ------------------------------------------------------------

    /** A cast past the probe, prepared, with the first frame still outstanding. */
    private fun TestScope.startingSession(player: RecordingPlayer): SessionController {
        val session = SessionController(player, backgroundScope, { true }, { ProbeResult.Ok(PROBE_MS) })
        session.onLoadMedia(LEASE, CAST, URL, TITLE, DURATION_MS, 0L, null)
        runCurrent()
        check(session.stage is MediaStage.Preparing) { "fixture never reached Preparing" }
        return session
    }

    private fun SessionController.recordTerminals(): List<ControlCastResult.Failed> {
        val failures = mutableListOf<ControlCastResult.Failed>()
        attachTerminal { id, code, retryable, status, beforeReady ->
            failures += ControlCastResult.Failed(id, code, retryable, status, beforeReady)
        }
        return failures
    }

    private companion object {
        const val CAST = "cast-a"
        const val CAST_B = "cast-b"
        const val LEASE = 1L
        const val URL = "http://192.168.42.10:8080/v/token"
        const val TITLE = "Film"
        const val DURATION_MS = 7_200_000L
        const val PROBE_MS = 7L

        /** Both private to the cast transaction; duplicated exactly as SessionReloadTest does. */
        const val STARTUP_DEADLINE_MS = 18_000L
        const val EXTENDED_DEADLINE_MS = STARTUP_DEADLINE_MS + StartupDeadlinePolicy.ROTATION_EXTENSION_MS
    }
}
