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
        // 2000 is deliberately undiagnosed — see PlaybackFailureClassifier.classify.
        assertEquals(CastFailureCode.UNKNOWN, failure.code)
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

    // --- What a fatal error is allowed to become -----------------------------

    /**
     * The stage was never the gate. A fatal error raised while the cast was still
     * Preparing used to be discarded outright, and the cast then expired as
     * `startup_timeout` eighteen seconds later under a screen still saying the film was
     * starting. The generation gate is what answers "is this callback still mine".
     */
    @Test fun aFatalErrorBeforeTheFirstFrameIsDiagnosedRatherThanWaitedOut() = runTest {
        val player = RecordingPlayer()
        val session = SessionController(player, backgroundScope, { true }, { ProbeResult.Ok(PROBE_MS) })
        val failures = session.recordTerminals()

        session.onLoadMedia(LEASE, CAST, URL, TITLE, DURATION_MS, 0L, null)
        runCurrent()
        player.failPlayback(
            PlaybackException("decoder", null, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED),
        )

        val failure = failures.single()
        assertEquals(CastFailureCode.DECODER_INIT, failure.code)
        assertTrue(failure.beforeReady)
        assertTrue(session.stage is MediaStage.Error)
    }

    /** The detail is the screen's; the wire carries the code it always carried. */
    @Test fun theLocalDetailReachesTheScreenWithoutReachingTheWire() = runTest {
        val player = RecordingPlayer()
        val session = activeSession(player)
        val failures = session.recordTerminals()

        player.failPlayback(
            PlaybackException("audio", null, PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED),
            ReceiverFaultDetail.AudioOutputRefused,
        )

        assertEquals(
            ReceiverErrorFace.AUDIO_OUTPUT_REFUSED,
            (session.stage as MediaStage.Error).face,
        )
        assertEquals(CastFailureCode.DECODER_INIT, failures.single().code)
    }

    /** A cast that ended still refuses a stale callback filed against it. */
    @Test fun anErrorForACastThisSessionNoLongerOwnsIsStillDropped() = runTest {
        val player = RecordingPlayer()
        val session = activeSession(player)
        val failures = session.recordTerminals()
        session.backToStandby()

        player.failPlayback(
            PlaybackException("io", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED),
        )

        assertEquals(emptyList<ControlCastResult.Failed>(), failures)
        assertEquals(MediaStage.None, session.stage)
    }

    // --- The film that plays silent ------------------------------------------

    @Test fun theFilmThatPlaysSilentTellsThePhoneOnceWithItsFormat() = runTest {
        val player = RecordingPlayer()
        val session = activeSession(player)
        val told = session.recordAudioSilent()

        player.reportSilentAudio(player.lastStartupMediaId!!, DTS)

        assertEquals(listOf(CAST to DTS), told)
    }

    /**
     * The case the session's own latch exists for. A subtitle attached mid-watch
     * re-prepares the identical film, which clears the PLAYER's once-per-media
     * latch and raises the identical reading a second time — same cast, same
     * silence, nothing new to say.
     */
    @Test fun attachingASubtitleMidWatchDoesNotSayItAgain() = runTest {
        val player = RecordingPlayer()
        val session = activeSession(player)
        val told = session.recordAudioSilent()

        player.reportSilentAudio(player.lastStartupMediaId!!, DTS)
        session.onReloadMedia(LEASE, CAST, URL, TITLE, DURATION_MS, RESUME_MS, SUBTITLE)
        player.reportSilentAudio(player.lastReloadMediaId!!, DTS)

        assertEquals(listOf(CAST to DTS), told)
    }

    /** A genuinely new cast is a new film, and gets its own one telling. */
    @Test fun theNextCastIsJudgedOnItsOwnSound() = runTest {
        val player = RecordingPlayer()
        val session = activeSession(player)
        val told = session.recordAudioSilent()

        player.reportSilentAudio(player.lastStartupMediaId!!, DTS)
        session.onLoadMedia(LEASE + 1L, CAST_B, URL, TITLE, DURATION_MS, 0L, null)
        runCurrent()
        player.renderFirstFrame()
        player.reportSilentAudio(player.lastStartupMediaId!!, DTS)

        assertEquals(listOf(CAST to DTS, CAST_B to DTS), told)
    }

    /** The stale-callback guard every other player-originated report carries. */
    @Test fun aReadingFiledAgainstAnOlderFilmIsNotSentAtAll() = runTest {
        val player = RecordingPlayer()
        val session = activeSession(player)
        val told = session.recordAudioSilent()
        val staleMediaId = player.lastStartupMediaId!!

        session.onLoadMedia(LEASE + 1L, CAST_B, URL, TITLE, DURATION_MS, 0L, null)
        runCurrent()
        player.renderFirstFrame()
        player.reportSilentAudio(staleMediaId, DTS)

        assertEquals(emptyList<Pair<String, String>>(), told)
    }

    /** Silence is never a cast failure: the picture is still worth watching. */
    @Test fun sayingItChangesNothingAboutThePlaybackItDescribes() = runTest {
        val player = RecordingPlayer()
        val session = activeSession(player)
        val failures = session.recordTerminals()
        session.recordAudioSilent()

        player.reportSilentAudio(player.lastStartupMediaId!!, DTS)
        advanceTimeBy(60_000L)
        runCurrent()

        assertEquals(emptyList<ControlCastResult.Failed>(), failures)
        assertEquals(MediaStage.Active(CAST, LEASE), session.stage)
        assertEquals(0, player.stops)
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

    /** Every `audio_silent` the session would put on the wire, in order. */
    private fun SessionController.recordAudioSilent(): List<Pair<String, String>> {
        val told = mutableListOf<Pair<String, String>>()
        attachAudioSilent { id, mime -> told += id to mime }
        return told
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
        const val DTS = "audio/vnd.dts"
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
    private var startupError: ((PlaybackException, ReceiverFaultDetail) -> Unit)? = null
    private var rotationRePrepare: (() -> Unit)? = null
    private var playbackFailure: ((PlaybackException, ReceiverFaultDetail) -> Unit)? = null
    private var subtitleDropped: ((String, ExternalSubtitle) -> Unit)? = null
    private var silentAudio: ((String, String) -> Unit)? = null

    override fun setPlaybackFailureListener(listener: ((PlaybackException, ReceiverFaultDetail) -> Unit)?) {
        playbackFailure = listener
    }

    override fun setExternalSubtitleDroppedListener(
        listener: ((String, ExternalSubtitle) -> Unit)?,
    ) {
        subtitleDropped = listener
    }

    override fun setSilentAudioListener(listener: ((String, String) -> Unit)?) {
        silentAudio = listener
    }

    override fun recordProbeLatency(latencyMs: Long) = Unit

    override fun playStartup(
        url: String,
        startMs: Long,
        mediaId: String,
        subtitle: ExternalSubtitle?,
        onFirstFrame: () -> Unit,
        onError: (PlaybackException, ReceiverFaultDetail) -> Unit,
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

    fun failPlayback(
        error: PlaybackException,
        detail: ReceiverFaultDetail = ReceiverFaultDetail.None,
    ) {
        playbackFailure?.invoke(error, detail)
    }

    /** The startup transaction's own error arm, which never reaches [failPlayback]. */
    fun failStartup(
        error: PlaybackException,
        detail: ReceiverFaultDetail = ReceiverFaultDetail.None,
    ) {
        startupError?.invoke(error, detail)
    }

    fun reportSubtitleDropped(mediaId: String, subtitle: ExternalSubtitle) {
        subtitleDropped?.invoke(mediaId, subtitle)
    }

    /**
     * The film has audio nothing here can play. The real controller raises this
     * from `onTracksChanged`, so it can arrive on any prepare — including the
     * re-prepare a mid-watch subtitle attach causes, which is the case the
     * session's own once-per-cast latch exists for.
     */
    fun reportSilentAudio(mediaId: String, mimeType: String) {
        silentAudio?.invoke(mediaId, mimeType)
    }
}
