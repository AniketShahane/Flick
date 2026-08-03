package com.flick.receiver.session

import androidx.media3.common.PlaybackException
import com.flick.receiver.net.CastFailureCode
import com.flick.receiver.net.ControlCastResult
import com.flick.receiver.net.ExternalSubtitle
import com.flick.receiver.net.ProbeResult
import com.flick.receiver.player.PlaybackFrame
import com.flick.receiver.player.SessionPlayer
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cast transaction a subtitle change takes. Attaching or removing one on a
 * running cast used to be routed through the cold-start transaction, which armed
 * an 18 s startup deadline, dropped the stage back to Checking — rebuilding the
 * PlayerView's SurfaceView under a player that was mid-prepare — and released the
 * instance that was presenting. No first frame was ever rendered, and the only
 * path that disarms the deadline is that frame, so a healthy cast died with a
 * `startup_timeout` 18 s after the user touched subtitles. Reproduced on a Google
 * TV Streamer both attaching and removing.
 */
class SessionReloadTest {

    @Test fun attachingASubtitleKeepsTheStageActiveAndTheVeryPlayerThatIsPresenting() = runTest {
        val player = RecordingPlayer()
        val session = activeSession(player)
        val instance = player.instance

        val result = session.onReloadMedia(LEASE, CAST, URL, TITLE, DURATION_MS, RESUME_MS, SUBTITLE)

        assertEquals(1, player.reloads)
        // The surface mode is derived from the stage, so Active is what keeps the
        // SurfaceView the live player is bound to from being rebuilt under it.
        assertEquals(MediaStage.Active(CAST, LEASE), session.stage)
        assertEquals(instance, player.instance)
        assertEquals(1, player.startups)
        assertEquals(0, player.stops)
        assertEquals(SUBTITLE, player.lastReloadSubtitle)
        // Identity has to survive, or the drop-failed-subtitle net rebuilds the
        // item under a media id nothing matches.
        assertEquals(player.lastStartupMediaId, player.lastReloadMediaId)
        // The cast never stopped being ready, so that stays the honest answer.
        assertTrue(result is ControlCastResult.Ready)
    }

    /** The device-confirmed failure carried extSub=false: it was a removal. */
    @Test fun removingASubtitleReloadsInPlaceExactlyTheSameWay() = runTest {
        val player = RecordingPlayer()
        val session = activeSession(player, SUBTITLE)

        session.onReloadMedia(LEASE, CAST, URL, TITLE, DURATION_MS, RESUME_MS, null)

        assertEquals(1, player.reloads)
        assertNull(player.lastReloadSubtitle)
        assertEquals(1, player.startups)
        assertEquals(MediaStage.Active(CAST, LEASE), session.stage)
    }

    @Test fun aReloadArmsNoStartupDeadlineSoTheCastOutlivesIt() = runTest {
        val player = RecordingPlayer()
        val session = activeSession(player)
        val failures = session.recordTerminals()

        session.onReloadMedia(LEASE, CAST, URL, TITLE, DURATION_MS, RESUME_MS, SUBTITLE)
        advanceTimeBy(60_000L)
        runCurrent()

        assertEquals(emptyList<ControlCastResult.Failed>(), failures)
        assertEquals(MediaStage.Active(CAST, LEASE), session.stage)
    }

    @Test fun theResumePositionAndSubtitleTheReloadCarriesAreTheOnesTheFrameAsksFor() = runTest {
        val player = RecordingPlayer()
        val session = activeSession(player)

        session.onReloadMedia(LEASE, CAST, URL, TITLE, DURATION_MS, RESUME_MS, SUBTITLE)

        assertEquals(RESUME_MS, player.lastReloadPositionMs)
        assertEquals(URL, player.lastReloadUrl)
        assertEquals(SUBTITLE, player.lastReloadSubtitle)
    }

    /** Without this the session still believes it holds the previous selection. */
    @Test fun theInPlacePathRecordsWhatTheSessionIsNowPreparedWith() = runTest {
        val player = RecordingPlayer()
        val session = activeSession(player)

        session.onReloadMedia(LEASE, CAST, URL, TITLE, DURATION_MS, RESUME_MS, SUBTITLE)
        val repeat = session.onReloadMedia(LEASE, CAST, URL, TITLE, DURATION_MS, RESUME_MS + 28_000L, SUBTITLE)

        assertNull(repeat)
        assertEquals(1, player.reloads)
    }

    @Test fun aRolledBackSubtitleCanBeRetriedIdentically() = runTest {
        val player = RecordingPlayer()
        val session = activeSession(player)

        session.onReloadMedia(LEASE, CAST, URL, TITLE, DURATION_MS, RESUME_MS, SUBTITLE)
        player.reportSubtitleDropped(player.lastReloadMediaId!!, SUBTITLE)
        val retry = session.onReloadMedia(
            LEASE,
            CAST,
            URL,
            TITLE,
            DURATION_MS,
            RESUME_MS,
            SUBTITLE,
        )

        assertTrue(retry is ControlCastResult.Ready)
        assertEquals(2, player.reloads)
    }

    @Test fun aStaleRollbackCannotClearANewerSubtitleInTheSameCast() = runTest {
        val player = RecordingPlayer()
        val session = activeSession(player)
        val newer = SUBTITLE.copy(url = "http://192.168.42.10:8080/s/new-token")

        session.onReloadMedia(LEASE, CAST, URL, TITLE, DURATION_MS, RESUME_MS, SUBTITLE)
        val mediaId = player.lastReloadMediaId!!
        session.onReloadMedia(LEASE, CAST, URL, TITLE, DURATION_MS, RESUME_MS, newer)
        player.reportSubtitleDropped(mediaId, SUBTITLE)

        assertNull(session.onReloadMedia(LEASE, CAST, URL, TITLE, DURATION_MS, RESUME_MS, newer))
        assertEquals(2, player.reloads)
    }

    @Test fun aStaleRollbackCannotClearANewerCastGeneration() = runTest {
        val player = RecordingPlayer()
        val session = activeSession(player)

        session.onReloadMedia(LEASE, CAST, URL, TITLE, DURATION_MS, RESUME_MS, SUBTITLE)
        val staleMediaId = player.lastReloadMediaId!!
        session.onLoadMedia(LEASE + 1L, CAST_B, URL, TITLE, DURATION_MS, 0L, SUBTITLE)
        runCurrent()
        player.renderFirstFrame()
        player.reportSubtitleDropped(staleMediaId, SUBTITLE)

        assertNull(
            session.onReloadMedia(
                LEASE + 1L,
                CAST_B,
                URL,
                TITLE,
                DURATION_MS,
                RESUME_MS,
                SUBTITLE,
            ),
        )
    }

    @Test fun anOrdinaryRetransmitStillReplaysAndNeverTouchesThePlayer() = runTest {
        val player = RecordingPlayer()
        val session = activeSession(player, SUBTITLE)

        assertNull(session.onReloadMedia(LEASE, CAST, URL, TITLE, DURATION_MS, RESUME_MS, SUBTITLE))

        assertEquals(0, player.reloads)
        assertEquals(1, player.startups)
    }

    @Test fun aReloadThatFailsIsAPlaybackErrorAndNeverAStartupFailure() = runTest {
        val player = RecordingPlayer()
        val session = activeSession(player)
        val failures = session.recordTerminals()

        session.onReloadMedia(LEASE, CAST, URL, TITLE, DURATION_MS, RESUME_MS, SUBTITLE)
        player.failPlayback(PlaybackException("io", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED))

        val failure = failures.single()
        assertNotEquals(CastFailureCode.STARTUP_TIMEOUT, failure.code)
        assertEquals(CastFailureCode.SENDER_NOT_SERVING, failure.code)
        // beforeReady=false is what the wire turns into `error` rather than
        // `loadFailed`: this cast demonstrably started.
        assertFalse(failure.beforeReady)
    }

    /** Active without a player cannot happen, but a full load is the safe answer. */
    @Test fun aReloadWithNoLivePlayerFallsBackToTheFullLoad() = runTest {
        val player = RecordingPlayer()
        val session = activeSession(player)
        player.live = false

        val result = session.onReloadMedia(LEASE, CAST, URL, TITLE, DURATION_MS, RESUME_MS, SUBTITLE)
        runCurrent()

        assertTrue(result is ControlCastResult.Accepted)
        assertEquals(2, player.startups)
        assertTrue(session.stage is MediaStage.Preparing)
    }

    // --- The cold start this must not regress --------------------------------

    @Test fun aColdStartForwardsTheResumePositionUnchanged() = runTest {
        val player = RecordingPlayer()
        val session = SessionController(player, backgroundScope, { true }, { ProbeResult.Ok(PROBE_MS) })

        session.onLoadMedia(LEASE, CAST, URL, TITLE, DURATION_MS, RESUME_MS, null)
        runCurrent()

        assertEquals(RESUME_MS, player.lastStartupPositionMs)
    }

    @Test fun aColdStartStillArmsTheDeadlineAndStillFailsWithoutAFirstFrame() = runTest {
        val player = RecordingPlayer()
        val session = SessionController(player, backgroundScope, { true }, { ProbeResult.Ok(PROBE_MS) })
        val failures = session.recordTerminals()

        session.onLoadMedia(LEASE, CAST, URL, TITLE, DURATION_MS, 0L, null)
        runCurrent()
        assertEquals(1, player.startups)
        assertTrue(session.stage is MediaStage.Preparing)

        advanceTimeBy(STARTUP_DEADLINE_MS + 1L)
        runCurrent()

        val failure = failures.single()
        assertEquals(CastFailureCode.STARTUP_TIMEOUT, failure.code)
        assertTrue(failure.beforeReady)
        assertTrue(session.stage is MediaStage.Error)
    }

    @Test fun aColdStartCarryingAnExternalSubtitleStillReachesActiveOnTheFirstFrame() = runTest {
        val player = RecordingPlayer()
        val session = SessionController(player, backgroundScope, { true }, { ProbeResult.Ok(PROBE_MS) })

        session.onLoadMedia(LEASE, CAST, URL, TITLE, DURATION_MS, 0L, SUBTITLE)
        runCurrent()

        assertEquals(SUBTITLE, player.lastStartupSubtitle)
        assertEquals(0, player.reloads)
        assertTrue(session.stage is MediaStage.Preparing)

        player.renderFirstFrame()

        assertEquals(MediaStage.Active(CAST, LEASE), session.stage)
    }

    // --- Picture orientation, driven from the phone --------------------------

    /**
     * The two halves of `setRotation` reach two different player entry points.
     * Auto is not a degree the session may pick on the phone's behalf: it is the
     * receiver re-reading the file, so it has to arrive as its own command.
     */
    @Test fun bothRotationCommandsReachThePlayerAsThemselves() = runTest {
        val player = RecordingPlayer()
        val session = activeSession(player)

        session.onSetRotation(CAST, 270)
        session.onSetAutoRotation(CAST)

        assertEquals(listOf(270), player.rotations)
        assertEquals(1, player.autoRotations)
    }

    /** Same cast guard as every other transport verb; a stale one turns nothing. */
    @Test fun aRotationForAnotherCastNeverReachesThePlayer() = runTest {
        val player = RecordingPlayer()
        val session = activeSession(player)

        session.onSetRotation(CAST_B, 90)
        session.onSetAutoRotation(CAST_B)

        assertEquals(emptyList<Int>(), player.rotations)
        assertEquals(0, player.autoRotations)
    }

    // --- Fixtures ------------------------------------------------------------

    private fun TestScope.activeSession(
        player: RecordingPlayer,
        subtitle: ExternalSubtitle? = null,
    ): SessionController {
        val session = SessionController(player, backgroundScope, { true }, { ProbeResult.Ok(PROBE_MS) })
        session.onLoadMedia(LEASE, CAST, URL, TITLE, DURATION_MS, 0L, subtitle)
        runCurrent()
        player.renderFirstFrame()
        check(session.stage is MediaStage.Active) { "fixture never reached Active" }
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
        const val RESUME_MS = 612_000L
        const val PROBE_MS = 7L
        const val STARTUP_DEADLINE_MS = 18_000L
        val SUBTITLE = ExternalSubtitle("http://192.168.42.10:8080/s/subtoken", "film.srt", "en")
    }
}

/**
 * Records the transactions the session issues. [instance] stands in for the
 * ExoPlayer object identity the real controller mints in `playStartup` and must
 * NOT mint in `reloadInPlace` — reusing it is what keeps the output surface, the
 * MediaSession and the track selection alive across a subtitle change.
 */
internal class RecordingPlayer : SessionPlayer {
    var instance = 0
    var live = true
    var startups = 0
    var reloads = 0
    var stops = 0
    var lastStartupSubtitle: ExternalSubtitle? = null
    var lastStartupMediaId: String? = null
    var lastStartupPositionMs = -1L
    var lastReloadUrl: String? = null
    var lastReloadPositionMs = -1L
    var lastReloadMediaId: String? = null
    var lastReloadSubtitle: ExternalSubtitle? = null
    val audioDelays = mutableListOf<Int>()

    private var firstFrame: (() -> Unit)? = null
    private var startupError: ((PlaybackException) -> Unit)? = null
    private var rotationRePrepare: (() -> Unit)? = null
    private var playbackFailure: ((PlaybackException) -> Unit)? = null
    private var subtitleDropped: ((String, ExternalSubtitle) -> Unit)? = null

    override fun setPlaybackFailureListener(listener: ((PlaybackException) -> Unit)?) {
        playbackFailure = listener
    }

    override fun setExternalSubtitleDroppedListener(
        listener: ((String, ExternalSubtitle) -> Unit)?,
    ) {
        subtitleDropped = listener
    }

    override fun recordProbeLatency(latencyMs: Long) = Unit

    override fun playStartup(
        url: String,
        startMs: Long,
        mediaId: String,
        subtitle: ExternalSubtitle?,
        onFirstFrame: () -> Unit,
        onError: (PlaybackException) -> Unit,
        onRotationRePrepare: () -> Unit,
    ) {
        instance++
        startups++
        lastStartupPositionMs = startMs
        lastStartupSubtitle = subtitle
        lastStartupMediaId = mediaId
        firstFrame = onFirstFrame
        startupError = onError
        rotationRePrepare = onRotationRePrepare
    }

    override fun reloadInPlace(
        url: String,
        positionMs: Long,
        mediaId: String,
        subtitle: ExternalSubtitle?,
    ): Boolean {
        if (!live) return false
        reloads++
        lastReloadUrl = url
        lastReloadPositionMs = positionMs
        lastReloadMediaId = mediaId
        lastReloadSubtitle = subtitle
        return true
    }

    override fun clearStartupListener() {
        firstFrame = null
        startupError = null
        rotationRePrepare = null
    }

    override fun stop() {
        stops++
        clearStartupListener()
    }

    override fun resume() = Unit
    override fun pause() = Unit
    override fun seekTo(posMs: Long) = Unit
    override fun seekBy(deltaMs: Long) = Unit
    override fun setVolume(level: Float) = Unit

    /** Records the quarter turns the session forwarded, in order. */
    val rotations = mutableListOf<Int>()
    override fun setVideoRotationDegrees(degrees: Int) { rotations += degrees }

    /** How many times the session handed the reading back to the receiver. */
    var autoRotations = 0
        private set
    override fun setAutoVideoRotation() { autoRotations++ }

    override fun setAudioDelay(delayMs: Int) { audioDelays += delayMs }
    override fun readPlaybackState(): PlaybackFrame = PlaybackFrame.IDLE

    /** The one signal that ends a startup transaction on real hardware. */
    fun renderFirstFrame() {
        val callback = firstFrame ?: return
        firstFrame = null
        startupError = null
        rotationRePrepare = null
        callback()
    }

    /**
     * A picture-orientation correction re-preparing the live player. The real
     * controller reports this from `rePrepareForRotation` and reads the same
     * startup callbacks, so a cast past its first frame reports nothing.
     */
    fun reportRotationRePrepare() {
        rotationRePrepare?.invoke()
    }

    fun failPlayback(error: PlaybackException) {
        playbackFailure?.invoke(error)
    }

    fun reportSubtitleDropped(mediaId: String, subtitle: ExternalSubtitle) {
        subtitleDropped?.invoke(mediaId, subtitle)
    }
}
