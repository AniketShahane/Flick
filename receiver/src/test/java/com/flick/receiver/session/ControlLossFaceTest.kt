package com.flick.receiver.session

import com.flick.receiver.net.CastFailureCode
import com.flick.receiver.net.ControlLossReason
import com.flick.receiver.net.ProbeResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A film that vanishes mid-scene has to say why.
 *
 * The socket closing used to drop the session straight to standby, so the room went
 * from a playing film to "Ready for a flick" with nothing in between — and a diagnosis
 * already on screen was replaced by it mid-read.
 *
 * The sentence waits, because the sender answers a lost link with a bounded automatic
 * re-cast: a card saying "nothing here resumes on its own" that a returning film
 * contradicts seconds later would be worse than the standby it replaced.
 */
class ControlLossFaceTest {

    @Test fun aDroppedSocketMidFilmLeavesTheSentenceOnScreen() = runTest {
        val player = RecordingPlayer()
        val session = activeSession(player)

        session.onControlLost(LEASE, ControlLossReason.DROPPED)
        settleAnnounce()

        val stage = session.stage as MediaStage.Error
        assertEquals(CastFailureCode.CONTROL_DISCONNECTED, stage.code)
        assertEquals(ReceiverErrorFace.LINK_LOST, stage.face)
        // The film demonstrably started, so the card may say the link dropped mid-film.
        assertFalse(stage.beforeReady)
    }

    /** The window the phone's own recovery runs in: nothing is claimed inside it. */
    @Test fun nothingIsSaidWhileTheSenderMayStillBeRescuingTheCast() = runTest {
        val player = RecordingPlayer()
        val session = activeSession(player)

        session.onControlLost(LEASE, ControlLossReason.DROPPED)

        assertEquals(MediaStage.None, session.stage)
        advanceTimeBy(ANNOUNCE_DELAY_MS - 1L)
        runCurrent()
        assertEquals(MediaStage.None, session.stage)
    }

    /** A re-cast that lands inside the window owns the screen, and the card never speaks. */
    @Test fun aRescuedCastIsNeverContradictedByTheCard() = runTest {
        val player = RecordingPlayer()
        val session = activeSession(player)

        session.onControlLost(LEASE, ControlLossReason.DROPPED)
        advanceTimeBy(ANNOUNCE_DELAY_MS / 2)
        runCurrent()
        session.onLoadMedia(LEASE + 1L, "cast-b", URL, TITLE, DURATION_MS, 0L, null)
        runCurrent()
        player.renderFirstFrame()
        settleAnnounce()

        assertEquals(MediaStage.Active("cast-b", LEASE + 1L), session.stage)
    }

    @Test fun aDroppedSocketBeforeTheFirstFrameSaysSo() = runTest {
        val player = RecordingPlayer()
        val session = SessionController(player, backgroundScope, { true }, { ProbeResult.Ok(PROBE_MS) })
        session.onLoadMedia(LEASE, CAST, URL, TITLE, DURATION_MS, 0L, null)
        runCurrent()
        check(session.stage is MediaStage.Preparing)

        session.onControlLost(LEASE, ControlLossReason.DROPPED)
        settleAnnounce()

        assertTrue((session.stage as MediaStage.Error).beforeReady)
    }

    /** A Forget aimed at the casting phone is the owner's own action, not a fault. */
    @Test fun anOwnersOwnRevokeIsNeverDressedAsANetworkFault() = runTest {
        for (reason in listOf(
            ControlLossReason.FORGOTTEN,
            ControlLossReason.REVOKED,
            ControlLossReason.SUPERSEDED,
            ControlLossReason.CLOSED,
        )) {
            val player = RecordingPlayer()
            val session = activeSession(player)

            session.onControlLost(LEASE, reason)

            assertEquals(reason.wire, MediaStage.None, session.stage)
        }
    }

    /**
     * A rendered diagnosis holds no player, no probe job and no ownership, and its
     * terminal frame was already delivered — so the socket closing behind it must not
     * take the one explanation the room ever got.
     */
    @Test fun aDiagnosisAlreadyOnScreenSurvivesTheSocketClosing() = runTest {
        val player = RecordingPlayer()
        val session = activeSession(player)
        session.onControlLost(LEASE, ControlLossReason.DROPPED)
        settleAnnounce()
        val diagnosed = session.stage as MediaStage.Error

        session.onControlLost(LEASE, ControlLossReason.DROPPED)
        settleAnnounce()

        assertEquals(diagnosed, session.stage)
    }

    @Test fun aLossForACastThisSessionNeverOwnedChangesNothing() = runTest {
        val player = RecordingPlayer()
        val session = activeSession(player)

        session.onControlLost(LEASE + 9L, ControlLossReason.DROPPED)
        settleAnnounce()

        assertEquals(MediaStage.Active(CAST, LEASE), session.stage)
    }

    /** Nothing goes on the wire: the socket that would have carried it just closed. */
    @Test fun theFaceCostsNoTerminalFrame() = runTest {
        val player = RecordingPlayer()
        val session = activeSession(player)
        val terminals = mutableListOf<CastFailureCode>()
        session.attachTerminal { _, code, _, _, _ -> terminals += code }

        session.onControlLost(LEASE, ControlLossReason.DROPPED)
        settleAnnounce()

        assertEquals(emptyList<CastFailureCode>(), terminals)
    }

    // --- The LAN reconcile's own face ----------------------------------------

    @Test fun theTvLosingItsAddressIsSaidOnThisScreenToo() = runTest {
        val player = RecordingPlayer()
        val session = activeSession(player)

        val teardown = session.forceLocalTeardown()
        session.raiseNetworkChanged(teardown.castId!!, teardown.beforeReady)

        val stage = session.stage as MediaStage.Error
        assertEquals(CastFailureCode.NO_COMPATIBLE_LAN, stage.code)
        assertEquals(ReceiverErrorFace.TV_NETWORK_CHANGED, stage.face)
    }

    /** Past the window the sender's own recovery is given, so the sentence may be read. */
    private fun TestScope.settleAnnounce() {
        advanceTimeBy(ANNOUNCE_DELAY_MS)
        runCurrent()
    }

    private fun TestScope.activeSession(player: RecordingPlayer): SessionController {
        val session = SessionController(player, backgroundScope, { true }, { ProbeResult.Ok(PROBE_MS) })
        session.onLoadMedia(LEASE, CAST, URL, TITLE, DURATION_MS, 0L, null)
        runCurrent()
        player.renderFirstFrame()
        check(session.stage is MediaStage.Active) { "fixture never reached Active" }
        return session
    }

    private companion object {
        const val CAST = "cast-a"
        const val LEASE = 1L
        /** SessionController.CONTROL_LOSS_ANNOUNCE_DELAY_MS, which is private to it. */
        const val ANNOUNCE_DELAY_MS = 6_000L
        const val URL = "http://192.168.42.17:8080/v/token"
        const val TITLE = "Film"
        const val DURATION_MS = 7_200_000L
        const val PROBE_MS = 7L
    }
}
