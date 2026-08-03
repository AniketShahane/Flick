package com.flick.receiver.session

import com.flick.receiver.net.ExternalSubtitle
import com.flick.receiver.net.ProbeResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How long a dialled-in A/V nudge lives.
 *
 * It belongs to the film, so a genuinely new cast starts in sync — and nothing
 * else may reset it. The three paths that would silently undo the viewer's work
 * are the ones tested here: the subtitle swap that falls back to the cold-start
 * transaction because the first frame has not landed yet, the in-place reload on
 * a running cast, and an ordinary seek.
 */
class AudioDelayLifetimeTest {

    @Test fun aNewCastStartsInSync() = runTest {
        val player = RecordingPlayer()
        val session = SessionController(player, backgroundScope, { true }, { ProbeResult.Ok(PROBE_MS) })

        session.onLoadMedia(LEASE, CAST, URL, TITLE, DURATION_MS, 0L, null)
        runCurrent()

        assertEquals(listOf(0), player.audioDelays)
    }

    @Test fun theSecondFilmStartsInSyncAgain() = runTest {
        val player = RecordingPlayer()
        val session = activeSession(player)

        session.onSetAudioDelay(CAST, 250)
        session.onLoadMedia(LEASE, CAST_B, URL, TITLE, DURATION_MS, 0L, null)
        runCurrent()

        assertEquals(listOf(0, 250, 0), player.audioDelays)
    }

    @Test fun aSubtitleSwapBeforeTheFirstFrameKeepsIt() = runTest {
        val player = RecordingPlayer()
        val session = SessionController(player, backgroundScope, { true }, { ProbeResult.Ok(PROBE_MS) })
        session.onLoadMedia(LEASE, CAST, URL, TITLE, DURATION_MS, 0L, null)
        runCurrent()

        session.onSetAudioDelay(CAST, 150)
        session.onReloadMedia(LEASE, CAST, URL, TITLE, DURATION_MS, 0L, SUBTITLE)
        runCurrent()

        // The reload re-entered the cold-start transaction for the SAME cast.
        assertEquals(2, player.startups)
        assertEquals(listOf(0, 150), player.audioDelays)
    }

    @Test fun anInPlaceSubtitleSwapOnALiveCastKeepsIt() = runTest {
        val player = RecordingPlayer()
        val session = activeSession(player)

        session.onSetAudioDelay(CAST, -175)
        session.onReloadMedia(LEASE, CAST, URL, TITLE, DURATION_MS, RESUME_MS, SUBTITLE)
        runCurrent()

        assertEquals(1, player.reloads)
        assertEquals(listOf(0, -175), player.audioDelays)
    }

    @Test fun aSeekKeepsIt() = runTest {
        val player = RecordingPlayer()
        val session = activeSession(player)

        session.onSetAudioDelay(CAST, 75)
        session.onSeek(CAST, RESUME_MS)
        session.onSkip(CAST, 10_000L)

        assertEquals(listOf(0, 75), player.audioDelays)
    }

    @Test fun aDelayAimedAtAnotherCastNeverReachesThePlayer() = runTest {
        val player = RecordingPlayer()
        val session = activeSession(player)

        session.onSetAudioDelay(CAST_B, 300)

        assertEquals(listOf(0), player.audioDelays)
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
        const val CAST_B = "cast-b"
        const val LEASE = 1L
        const val URL = "http://192.168.42.10:8080/v/token"
        const val TITLE = "Film"
        const val DURATION_MS = 7_200_000L
        const val RESUME_MS = 612_000L
        const val PROBE_MS = 7L
        val SUBTITLE = ExternalSubtitle("http://192.168.42.10:8080/s/subtoken", "film.srt", "en")
    }
}
