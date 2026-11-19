package com.flick.receiver.session

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
 * Which player verb a phone `seek` becomes. The wire carries no drag verb, so the
 * session reads a drag from the seeks' cadence and hands it to the player's scrubbing
 * path; a lone seek, a Replay and anything local stay ordinary exact seeks.
 */
class SessionScrubRoutingTest {

    @Test fun aLoneSeekIsAnOrdinarySeek() = runTest {
        val player = RecordingPlayer()
        val session = activeSession(player)

        session.onSeek(CAST, 600_000L)

        assertEquals(listOf("seekTo:600000"), player.transport)
    }

    @Test fun aSecondPhoneSeekInsideTheDragWindowScrubs() = runTest {
        val player = RecordingPlayer()
        val session = activeSession(player)

        session.onSeek(CAST, 600_000L)
        advanceTimeBy(50L)
        session.onSeek(CAST, 610_000L)
        advanceTimeBy(50L)
        session.onSeek(CAST, 620_000L)

        assertEquals(listOf("seekTo:600000", "scrubTo:610000", "scrubTo:620000"), player.transport)
    }

    @Test fun seeksFurtherApartThanTheWindowStayOrdinary() = runTest {
        val player = RecordingPlayer()
        val session = activeSession(player)

        session.onSeek(CAST, 600_000L)
        advanceTimeBy(ScrubCadence.DRAG_WINDOW_MS + 1L)
        session.onSeek(CAST, 610_000L)

        assertEquals(listOf("seekTo:600000", "seekTo:610000"), player.transport)
    }

    /** A finger that paused past the window is still the same drag until it is landed. */
    @Test fun aSeekWhileThePlayerIsStillScrubbingScrubs() = runTest {
        val player = RecordingPlayer()
        val session = activeSession(player)
        session.onSeek(CAST, 600_000L)
        advanceTimeBy(50L)
        session.onSeek(CAST, 610_000L)
        check(player.isScrubbing)

        advanceTimeBy(800L)
        session.onSeek(CAST, 620_000L)

        assertEquals("scrubTo:620000", player.transport.last())
    }

    @Test fun replayNeverScrubsEvenRightAfterAPhoneSeek() = runTest {
        val player = RecordingPlayer()
        val session = activeSession(player)

        session.onSeek(CAST, 600_000L)
        advanceTimeBy(50L)
        session.onSeek(0L)
        advanceTimeBy(50L)
        session.onSeek(0L)

        assertEquals(listOf("seekTo:600000", "seekTo:0", "seekTo:0"), player.transport)
    }

    @Test fun replayDuringADragIsAnOrdinarySeekThatEndsIt() = runTest {
        val player = RecordingPlayer()
        val session = activeSession(player)
        session.onSeek(CAST, 600_000L)
        advanceTimeBy(50L)
        session.onSeek(CAST, 610_000L)

        session.onSeek(0L)
        session.onPlay()

        assertEquals(
            listOf("seekTo:600000", "scrubTo:610000", "seekTo:0", "landScrub", "resume"),
            player.transport,
        )
    }

    @Test fun playLandsTheDragBeforeResuming() = runTest {
        val player = RecordingPlayer()
        val session = activeSession(player)
        session.onSeek(CAST, 600_000L)
        advanceTimeBy(50L)
        session.onSeek(CAST, 610_000L)

        session.onPlay(CAST)

        assertEquals(listOf("landScrub", "resume"), player.transport.takeLast(2))
    }

    @Test fun pauseLandsTheDragBeforePausing() = runTest {
        val player = RecordingPlayer()
        val session = activeSession(player)
        session.onSeek(CAST, 600_000L)
        advanceTimeBy(50L)
        session.onSeek(CAST, 610_000L)

        session.onPause(CAST)

        assertEquals(listOf("landScrub", "pause"), player.transport.takeLast(2))
    }

    /** The TV remote's play/pause goes through the same local wrappers. */
    @Test fun theLocalPlayAndPauseWrappersLandToo() = runTest {
        val player = RecordingPlayer()
        val session = activeSession(player)

        session.onPause()
        session.onPlay()

        assertEquals(listOf("landScrub", "pause", "landScrub", "resume"), player.transport)
    }

    @Test fun aVerbForAnotherCastLandsNothing() = runTest {
        val player = RecordingPlayer()
        val session = activeSession(player)

        session.onPlay(CAST_B)
        session.onPause(CAST_B)
        session.onSeek(CAST_B, 1_000L)

        assertEquals(emptyList<String>(), player.transport)
    }

    @Test fun aNewCastStartsWithAFreshCadence() = runTest {
        val player = RecordingPlayer()
        val session = activeSession(player)
        session.onSeek(CAST, 600_000L)

        session.onLoadMedia(LEASE + 1L, CAST_B, URL, TITLE, DURATION_MS, 0L, null)
        runCurrent()
        player.renderFirstFrame()
        check(session.stage is MediaStage.Active)
        player.transport.clear()
        advanceTimeBy(50L)
        session.onSeek(CAST_B, 1_000L)

        assertEquals(listOf("seekTo:1000"), player.transport)
    }

    @Test fun aStoppedCastLeavesNoCadenceForTheNextOne() = runTest {
        val player = RecordingPlayer()
        val session = activeSession(player)
        session.onSeek(CAST, 600_000L)

        assertTrue(session.onStop(CAST))
        session.onLoadMedia(LEASE + 1L, CAST_B, URL, TITLE, DURATION_MS, 0L, null)
        runCurrent()
        player.renderFirstFrame()
        player.transport.clear()
        session.onSeek(CAST_B, 1_000L)

        assertEquals(listOf("seekTo:1000"), player.transport)
    }

    /**
     * A drag's frames are keyframes short of its target, and the masked target reads as
     * reported: neither may end the seek, however long the finger stays down.
     */
    @Test fun aSeekNeverLandsWhileThePlayerIsScrubbing() = runTest {
        val player = RecordingPlayer()
        val session = activeSession(player)
        session.onSeek(CAST, 600_000L)
        advanceTimeBy(50L)
        session.onSeek(CAST, 610_000L)
        check(player.isScrubbing)

        repeat(40) {
            advanceTimeBy(100L)
            session.syncTick(if (it % 2 == 0) 610_000L else 598_000L)
            assertTrue("landed at tick $it", session.seeking)
            assertEquals(610_000L, session.seekTargetMs)
        }
    }

    /** MIN_VISIBLE counts from the end of the drag, not from the seek that started it. */
    @Test fun afterTheDragEndsTheSeekLandsPerTheReconciler() = runTest {
        val player = RecordingPlayer()
        val session = activeSession(player)
        session.onSeek(CAST, 600_000L)
        advanceTimeBy(50L)
        session.onSeek(CAST, 610_000L)
        repeat(20) {
            advanceTimeBy(100L)
            session.syncTick(610_000L)
        }

        session.onPlay(CAST)
        check(!player.isScrubbing)
        advanceTimeBy(100L)
        session.syncTick(610_000L)
        assertTrue("settling right after the drag", session.seeking)
        advanceTimeBy(SeekReconciler.MIN_VISIBLE_MS)
        session.syncTick(610_100L)

        assertFalse(session.seeking)
        assertEquals(610_100L, session.seekTargetMs)
    }

    /** DEADLINE counts from the end of the drag too. */
    @Test fun anUnreportedLandingGivesUpADeadlineAfterTheDragEnds() = runTest {
        val player = RecordingPlayer()
        val session = activeSession(player)
        session.onSeek(CAST, 600_000L)
        advanceTimeBy(50L)
        session.onSeek(CAST, 610_000L)
        repeat(30) {
            advanceTimeBy(100L)
            session.syncTick(598_000L)
        }

        session.onPause(CAST)
        advanceTimeBy(SeekReconciler.DEADLINE_MS - 100L)
        session.syncTick(598_000L)
        assertTrue(session.seeking)
        advanceTimeBy(100L)
        session.syncTick(598_000L)

        assertFalse(session.seeking)
        assertEquals(598_000L, session.seekTargetMs)
    }

    private fun TestScope.activeSession(player: RecordingPlayer): SessionController {
        val session = SessionController(
            player,
            backgroundScope,
            { true },
            { ProbeResult.Ok(PROBE_MS) },
            clock = { testScheduler.currentTime },
        )
        session.onLoadMedia(LEASE, CAST, URL, TITLE, DURATION_MS, 0L, null)
        runCurrent()
        player.renderFirstFrame()
        check(session.stage is MediaStage.Active) { "fixture never reached Active" }
        player.transport.clear()
        return session
    }

    private companion object {
        const val CAST = "cast-a"
        const val CAST_B = "cast-b"
        const val LEASE = 1L
        const val URL = "http://192.168.42.10:8080/v/token"
        const val TITLE = "Film"
        const val DURATION_MS = 7_200_000L
        const val PROBE_MS = 7L
    }
}
