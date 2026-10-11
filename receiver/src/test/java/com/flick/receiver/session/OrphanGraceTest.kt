package com.flick.receiver.session

import androidx.media3.common.PlaybackException
import com.flick.receiver.net.CastFailureCode
import com.flick.receiver.net.ControlCastResult
import com.flick.receiver.net.ControlLossReason
import com.flick.receiver.net.ProbeResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A playing film outlives its control socket for long enough for its phone to come back.
 *
 * The receipts are the player's own counters: a re-adopted film must cost no startup and
 * no stop, because the whole point of the hold is that the viewer never sees a re-buffer.
 */
class OrphanGraceTest {

    @Test fun aDroppedSocketLeavesTheFilmPlaying() = runTest {
        val h = held()

        assertEquals(MediaStage.Active(CAST, LEASE), h.session.stage)
        assertEquals(0, h.player.stops)
        assertTrue(h.session.reconnecting)
        assertEquals(KEY, h.session.reconnectingKeyId)
    }

    @Test fun theSamePhoneReadoptsWithoutTouchingThePlayer() = runTest {
        val h = held()
        advanceTimeBy(10_000L)

        val answer = h.session.onReadoptMedia(LEASE + 1L, KEY, CAST, URL)

        assertEquals(ControlCastResult.Ready(CAST, 0L, 0L), answer)
        assertEquals(MediaStage.Active(CAST, LEASE + 1L), h.session.stage)
        assertEquals(1, h.player.startups)
        assertEquals(0, h.player.stops)
        assertFalse(h.session.reconnecting)
        assertNull(h.session.reconnectingKeyId)
    }

    /** Re-adopted means owned again: the old expiry must never end it. */
    @Test fun aReadoptedFilmOutlivesTheOldHold() = runTest {
        val h = held()
        h.session.onReadoptMedia(LEASE + 1L, KEY, CAST, URL)

        settle(HOLD_MS + ANNOUNCE_DELAY_MS)

        assertEquals(MediaStage.Active(CAST, LEASE + 1L), h.session.stage)
        assertEquals(0, h.player.stops)
    }

    /** The lease it now runs under is the new one, and a loss of the old one is a stranger's. */
    @Test fun aReadoptedFilmAnswersToItsNewLeaseOnly() = runTest {
        val h = held()
        h.session.onReadoptMedia(LEASE + 1L, KEY, CAST, URL)

        h.session.onControlLost(LEASE, ControlLossReason.CLOSED, KEY)
        assertEquals(MediaStage.Active(CAST, LEASE + 1L), h.session.stage)

        h.session.onControlLost(LEASE + 1L, ControlLossReason.DROPPED, KEY)
        assertTrue(h.session.reconnecting)
    }

    @Test fun anotherPhoneCannotReadopt() = runTest {
        val h = held()

        assertNull(h.session.onReadoptMedia(LEASE + 1L, "other-key", CAST, URL))
        assertTrue(h.session.reconnecting)
    }

    /** Refused, then handled as a load: someone else's frame supersedes the held film. */
    @Test fun anotherPhonesLoadSupersedesTheHeldFilm() = runTest {
        val h = held()

        assertNull(h.session.onReadoptMedia(LEASE + 1L, "other-key", CAST, URL))
        val answer = h.session.onLoadMedia(LEASE + 1L, CAST, URL, TITLE, DURATION_MS, 0L, null)

        // A fresh transaction, never the held film's retained `loadReady`.
        assertEquals(ControlCastResult.Accepted(CAST), answer)
        assertTrue(h.session.stage is MediaStage.Checking)
        assertFalse(h.session.reconnecting)
        runCurrent()
        assertEquals(2, h.player.startups)
    }

    @Test fun aDifferentUrlIsANewCast() = runTest {
        val h = held()

        assertNull(h.session.onReadoptMedia(LEASE + 1L, KEY, CAST, OTHER_URL))
        h.session.onLoadMedia(LEASE + 1L, CAST, OTHER_URL, TITLE, DURATION_MS, 60_000L, null)
        runCurrent()

        assertEquals(2, h.player.startups)
        assertEquals(60_000L, h.player.lastStartupPositionMs)
    }

    @Test fun aDifferentCastSupersedesTheHeldFilm() = runTest {
        val h = held()

        assertNull(h.session.onReadoptMedia(LEASE + 1L, KEY, CAST_B, URL))
        h.session.onLoadMedia(LEASE + 1L, CAST_B, URL, TITLE, DURATION_MS, 0L, null)
        runCurrent()
        h.player.renderFirstFrame()
        settle(HOLD_MS + ANNOUNCE_DELAY_MS)

        assertEquals(MediaStage.Active(CAST_B, LEASE + 1L), h.session.stage)
    }

    /** A hold that runs out ends exactly where a dropped socket always ended. */
    @Test fun anExpiredHoldEndsInTodaysFace() = runTest {
        val h = held()

        settle(HOLD_MS)
        assertEquals(MediaStage.None, h.session.stage)
        assertEquals(1, h.player.stops)
        assertFalse(h.session.reconnecting)
        assertNull(h.session.onReadoptMedia(LEASE + 1L, KEY, CAST, URL))

        settle(ANNOUNCE_DELAY_MS)
        val stage = h.session.stage as MediaStage.Error
        assertEquals(CastFailureCode.CONTROL_DISCONNECTED, stage.code)
        assertFalse(stage.beforeReady)
    }

    @Test fun aLateReturnCannotReadopt() = runTest {
        val h = held()
        settle(HOLD_MS)

        assertNull(h.session.onReadoptMedia(LEASE + 1L, KEY, CAST, URL))
    }

    /** Home or Back end the held film on the spot, as they end any film. */
    @Test fun leavingTheTvEndsTheHold() = runTest {
        val h = held()

        val teardown = h.session.forceLocalTeardown()

        assertEquals(CAST, teardown.castId)
        assertEquals(MediaStage.None, h.session.stage)
        assertFalse(h.session.reconnecting)
        assertNull(h.session.onReadoptMedia(LEASE + 1L, KEY, CAST, URL))
        settle(HOLD_MS + ANNOUNCE_DELAY_MS)
        assertEquals(MediaStage.None, h.session.stage)
    }

    @Test fun backOnTheRemoteStopsTheHeldFilm() = runTest {
        val h = held()

        assertEquals(ControlCastResult.Stopped(CAST), h.session.backToStandby())
        assertFalse(h.session.reconnecting)
        assertEquals(MediaStage.None, h.session.stage)
    }

    @Test fun aPlaybackFailureEndsTheHold() = runTest {
        val h = held()

        h.player.failPlayback(PlaybackException("io", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))

        assertTrue(h.session.stage is MediaStage.Error)
        assertFalse(h.session.reconnecting)
        assertNull(h.session.onReadoptMedia(LEASE + 1L, KEY, CAST, URL))
    }

    @Test fun forgettingTheHeldPhoneEndsTheHold() = runTest {
        val h = held()

        h.session.onPhoneForgotten("other-key")
        assertTrue(h.session.reconnecting)

        h.session.onPhoneForgotten(KEY)
        assertFalse(h.session.reconnecting)
        assertEquals(MediaStage.None, h.session.stage)
    }

    /** Before the first frame there is nothing playing to keep: today's teardown. */
    @Test fun aLossWhileStartingIsNotHeld() = runTest {
        val player = RecordingPlayer()
        val session = session(player)
        session.onLoadMedia(LEASE, CAST, URL, TITLE, DURATION_MS, 0L, null)
        runCurrent()

        session.onControlLost(LEASE, ControlLossReason.DROPPED, KEY)

        assertFalse(session.reconnecting)
        assertEquals(MediaStage.None, session.stage)
    }

    @Test fun aLossWhileBackgroundedIsNotHeld() = runTest {
        var started = true
        val player = RecordingPlayer()
        val session = SessionController(player, backgroundScope, { started }, { ProbeResult.Ok(PROBE_MS) }, clock = { testScheduler.currentTime })
        session.onLoadMedia(LEASE, CAST, URL, TITLE, DURATION_MS, 0L, null)
        runCurrent()
        player.renderFirstFrame()

        started = false
        session.onControlLost(LEASE, ControlLossReason.DROPPED, KEY)

        assertFalse(session.reconnecting)
        assertEquals(MediaStage.None, session.stage)
    }

    // --- Same-phone takeover ---------------------------------------------------

    @Test fun aTakeoverMovesTheCastToTheNewLease() = runTest {
        val h = active()

        h.session.onControlLeaseMoved(LEASE, LEASE + 1L)

        assertEquals(MediaStage.Active(CAST, LEASE + 1L), h.session.stage)
        // The displaced socket's lease is now a stranger's.
        h.session.onControlLost(LEASE, ControlLossReason.DROPPED, KEY)
        assertEquals(MediaStage.Active(CAST, LEASE + 1L), h.session.stage)
        assertFalse(h.session.reconnecting)
    }

    @Test fun aMoveFromALeaseThatDoesNotOwnTheCastIsIgnored() = runTest {
        val h = active()

        h.session.onControlLeaseMoved(LEASE + 7L, LEASE + 8L)

        assertEquals(MediaStage.Active(CAST, LEASE), h.session.stage)
    }

    @Test fun theTakenOverSocketsReattachIsAnsweredWithoutAReload() = runTest {
        val h = active()
        h.session.onControlLeaseMoved(LEASE, LEASE + 1L)

        assertEquals(ControlCastResult.Ready(CAST, 0L, 0L), h.session.onReadoptMedia(LEASE + 1L, KEY, CAST, URL))
        assertEquals(1, h.player.startups)
        assertEquals(0, h.player.reloads)
        assertEquals(0, h.player.stops)
    }

    @Test fun aReattachOnALeaseThatDoesNotOwnTheCastIsRefused() = runTest {
        val h = active()

        assertNull(h.session.onReadoptMedia(LEASE + 1L, KEY, CAST, URL))
    }

    /** A takeover mid-start keeps the start: its own first frame lands on the new lease. */
    @Test fun aTakeoverWhileStartingFinishesOnTheNewLease() = runTest {
        val player = RecordingPlayer()
        val session = session(player)
        session.onLoadMedia(LEASE, CAST, URL, TITLE, DURATION_MS, 0L, null)
        runCurrent()

        session.onControlLeaseMoved(LEASE, LEASE + 1L)
        assertEquals(MediaStage.Preparing(CAST, LEASE + 1L), session.stage)
        assertNull(session.onReadoptMedia(LEASE + 1L, KEY, CAST, URL))

        player.renderFirstFrame()
        assertEquals(MediaStage.Active(CAST, LEASE + 1L), session.stage)
    }

    private class Harness(val session: SessionController, val player: RecordingPlayer)

    private fun TestScope.session(player: RecordingPlayer) =
        SessionController(player, backgroundScope, { true }, { ProbeResult.Ok(PROBE_MS) }, clock = { testScheduler.currentTime })

    private fun TestScope.active(): Harness {
        val player = RecordingPlayer()
        val session = session(player)
        session.onLoadMedia(LEASE, CAST, URL, TITLE, DURATION_MS, 0L, null)
        runCurrent()
        player.renderFirstFrame()
        check(session.stage is MediaStage.Active) { "fixture never reached Active" }
        return Harness(session, player)
    }

    private fun TestScope.held(): Harness {
        val h = active()
        h.session.onControlLost(LEASE, ControlLossReason.DROPPED, KEY)
        runCurrent()
        return h
    }

    private fun TestScope.settle(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private companion object {
        const val KEY = "key-a"
        const val CAST = "cast-a"
        const val CAST_B = "cast-b"
        const val LEASE = 1L
        const val HOLD_MS = CastReattachPolicy.ORPHAN_GRACE_MS
        /** SessionController.CONTROL_LOSS_ANNOUNCE_DELAY_MS, which is private to it. */
        const val ANNOUNCE_DELAY_MS = 6_000L
        const val URL = "http://192.168.42.17:8080/v/token"
        const val OTHER_URL = "http://192.168.42.18:8080/v/token"
        const val TITLE = "Film"
        const val DURATION_MS = 7_200_000L
        const val PROBE_MS = 7L
    }
}
