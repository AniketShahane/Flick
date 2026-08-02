package com.flick.receiver.player

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.StuckPlayerException
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionResult
import com.flick.receiver.net.ExternalSubtitle
import com.flick.receiver.util.FlickLog
import com.flick.receiver.util.WifiTelemetry
import java.io.IOException

/**
 * Everything `SessionController` is allowed to do to the player, and nothing
 * else. It is an interface because the cast transaction's rules — what arms the
 * startup deadline, what may replace the ExoPlayer instance, which failures are
 * startup failures — decide the outcome of a cast, and none of them need a
 * decoder, a surface or a LAN to be exercised.
 */
interface SessionPlayer {
    fun setPlaybackFailureListener(listener: ((PlaybackException) -> Unit)?)
    fun setExternalSubtitleDroppedListener(listener: ((String, ExternalSubtitle) -> Unit)?)
    fun recordProbeLatency(latencyMs: Long)

    /** Cold start: adopts a NEW player instance and reports the exact first frame. */
    fun playStartup(
        url: String,
        startMs: Long,
        mediaId: String,
        subtitle: ExternalSubtitle?,
        onFirstFrame: () -> Unit,
        onError: (PlaybackException) -> Unit,
    )

    /**
     * Re-prepare the LIVE player against a changed sideloaded subtitle. False
     * means there was no live player to reload, which leaves the caller a full
     * load as the only honest option.
     */
    fun reloadInPlace(
        url: String,
        positionMs: Long,
        mediaId: String,
        subtitle: ExternalSubtitle?,
    ): Boolean

    fun clearStartupListener()
    fun stop()
    fun resume()
    fun pause()
    fun seekTo(posMs: Long)
    fun seekBy(deltaMs: Long)
    fun setVolume(level: Float)
    fun readPlaybackState(): PlaybackFrame
}

/**
 * Owns the ExoPlayer lifecycle and all playback instrumentation for the
 * Phase 0 direct-play spike.
 *
 * The player is built for LAN direct-play of large 4K files: hardware decoding
 * only (no transcode, no software fallback rendering), a generous time- and
 * byte-based buffer, and an HTTP data source with byte-range support (ExoPlayer
 * issues Range requests against the sender's GET /v/{token} endpoint automatically).
 *
 * Lifecycle: [onStart]/[onStop] follow the Media3 recommendation for API 24+
 * (minSdk here is 26) — the decoder is released whenever the Activity is
 * stopped and rebuilt (restoring URL + position) when it starts again, so the
 * hardware decoder is never held while backgrounded. [release] is the terminal
 * teardown for onDestroy/onDispose.
 *
 * The exposed [player] is Compose state so the [androidx.media3.ui.PlayerView]
 * rebinds automatically each time the instance is recreated.
 */
class PlayerController(context: Context) : SessionPlayer {

    private val appContext = context.applicationContext

    /** Shared across player rebuilds so bandwidth history and metrics survive backgrounding. */
    val bandwidthMeter: DefaultBandwidthMeter = DefaultBandwidthMeter.Builder(appContext).build()

    private val instrumentation = InstrumentationState()

    /** Compose-observable current player instance (null while released/stopped). */
    var player by mutableStateOf<ExoPlayer?>(null)
        private set

    /** Platform media-button owner for the current foreground player. */
    private var mediaSession: MediaSession? = null

    private val mediaSessionCallback = object : MediaSession.Callback {
        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent,
        ): Boolean {
            @Suppress("DEPRECATION")
            val keyEvent = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
            val button = when (keyEvent?.keyCode) {
                KeyEvent.KEYCODE_MEDIA_STOP -> MediaButtonKind.STOP
                KeyEvent.KEYCODE_MEDIA_NEXT -> MediaButtonKind.NEXT
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> MediaButtonKind.PREVIOUS
                else -> MediaButtonKind.OTHER
            }
            // Returning true blocks Media3's default Player.stop()/playlist
            // mutation path, which would bypass SessionController terminal state.
            return consumesUnsupportedMediaButton(button)
        }

        override fun onPlayerCommandRequest(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            playerCommand: Int,
        ): Int = if (rejectsExternalPlayerCommand(playerCommand)) {
            SessionResult.RESULT_ERROR_NOT_SUPPORTED
        } else {
            SessionResult.RESULT_SUCCESS
        }
    }

    private var currentUrl: String? = null
    private var pendingPlayWhenReady: Boolean = false
    private var savedPositionMs: Long = 0L

    // --- Sideloaded subtitle state (main thread only) -------------------------

    /** The external subtitle merged into the current media item; null when none. */
    private var currentSubtitle: ExternalSubtitle? = null

    /** Media id of the current session, so a rebuilt item keeps its first-frame identity. */
    private var currentMediaId: String? = null

    private val subtitleFailureState = ExternalSubtitleFailureState()

    /** One-shot deadline for an Active cast's in-place external-subtitle attach/swap. */
    private val subtitleReloadWatchdog = SubtitleReloadWatchdog()
    private var nextSubtitleReloadAttemptToken = 0L
    private var pendingSubtitleReloadDeadline: Runnable? = null
    private var pendingSubtitleReloadAttemptToken: Long? = null

    // --- Bounded auto-recovery state (all touched on the main thread only) ------
    private val recoveryHandler = Handler(Looper.getMainLooper())
    private var pendingRecovery: Runnable? = null

    /** Attempts within the current rough patch; gates [MAX_RECOVERY_ATTEMPTS], re-armed after a stable stretch. */
    private var recoveryGateCount: Int = 0

    /** Most recent healthy playing position — the seek target a recovery resumes from. */
    private var lastGoodPositionMs: Long = 0L

    /** elapsedRealtime() when the current uninterrupted STATE_READY stretch began; 0 otherwise. */
    private var stableReadySinceMs: Long = 0L

    /** Latest pre-flight probe round-trip (ms); <= 0 until [recordProbeLatency]. */
    private var probeLatencyMs: Long = 0L

    /** Last commanded volume (0..1); survives player rebuilds and null-player reads. */
    private var lastVolume: Float = 1f

    // Facts about the audio the decoder is actually being fed, for the honest
    // codec chips. Written only from the analytics listener and cleared for each
    // new session, so a chip can never describe the previous film.
    private var audioMimeType: String? = null
    private var audioChannelCount: Int = Format.NO_VALUE
    private var audioCodecs: String? = null

    private data class StartupCallbacks(
        val mediaId: String,
        val onFirstFrame: () -> Unit,
        val onError: (PlaybackException) -> Unit,
    )

    /** Non-null only from player adoption until the exact first video frame. */
    private var startupCallbacks: StartupCallbacks? = null
    private val firstFrameGate = FirstFrameGate()
    private var playbackFailureListener: ((PlaybackException) -> Unit)? = null
    private var externalSubtitleDroppedListener: ((String, ExternalSubtitle) -> Unit)? = null

    // --- Listeners -----------------------------------------------------------

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    // Buffering interrupts the stable stretch that re-arms recovery.
                    stableReadySinceMs = 0L
                    // Only count buffering that happens AFTER playback has started;
                    // the initial fill is not a rebuffer/stall, and buffering caused
                    // by a user seek is tracked as a seek fill, not a rebuffer.
                    if (instrumentation.playbackStarted &&
                        instrumentation.currentRebufferStartMs == 0L &&
                        instrumentation.seekFillStartMs == 0L
                    ) {
                        instrumentation.rebufferCount++
                        instrumentation.currentRebufferStartMs = SystemClock.elapsedRealtime()
                    }
                }
                Player.STATE_READY -> {
                    instrumentation.playbackStarted = true
                    if (stableReadySinceMs == 0L) stableReadySinceMs = SystemClock.elapsedRealtime()
                    closeSeekFillWindow()
                    closeRebufferWindow()
                }
                Player.STATE_ENDED, Player.STATE_IDLE -> {
                    stableReadySinceMs = 0L
                    closeRebufferWindow()
                }
            }
            instrumentation.playbackState = playbackState
            val livePlayer = player
            val attemptToken = livePlayer?.currentMediaItem?.subtitleReloadAttemptToken()
            if (livePlayer != null && attemptToken != null &&
                attemptToken == pendingSubtitleReloadAttemptToken &&
                livePlayer.playbackState == playbackState
            ) {
                if (subtitleReloadWatchdog.onPlaybackState(
                        attemptToken,
                        currentMediaId,
                        ready = playbackState == Player.STATE_READY,
                    )
                ) {
                    cancelSubtitleReloadDeadline(clearState = false)
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            instrumentation.isPlaying = isPlaying
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK && instrumentation.playbackStarted) {
                instrumentation.seekCount++
                instrumentation.seekFillStartMs = SystemClock.elapsedRealtime()
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                instrumentation.videoWidth = videoSize.width
                instrumentation.videoHeight = videoSize.height
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            reportUnplayableVideoTrack(tracks)
            var selected = false
            var selectedMimeType: String? = null
            for (group in tracks.groups) {
                if (group.type != C.TRACK_TYPE_TEXT) continue
                for (index in 0 until group.length) {
                    if (!group.isTrackSelected(index)) continue
                    selected = true
                    selectedMimeType = group.getTrackFormat(index).sampleMimeType
                    break
                }
                if (selected) break
            }
            val selectionChanged = selected != instrumentation.subtitleTrackSelected ||
                selectedMimeType != instrumentation.subtitleTrackMimeType
            instrumentation.subtitleTrackSelected = selected
            instrumentation.subtitleTrackMimeType = selectedMimeType
            if (selectionChanged) instrumentation.subtitleCueKind = SubtitleCueKind.NONE
            // MIME + selection only: never log a cue's text or bitmap payload.
            if (selectionChanged) {
                FlickLog.i(
                    "subtitle",
                    "selected=$selected mime=${selectedMimeType ?: if (selected) "unknown" else "none"}",
                )
            }
        }

        override fun onCues(cueGroup: CueGroup) {
            instrumentation.subtitleCueKind = subtitleCueKind(
                hasText = cueGroup.cues.any { it.text != null },
                hasBitmap = cueGroup.cues.any { it.bitmap != null },
            )
        }

        override fun onPlayerError(error: PlaybackException) {
            // A text file must never cost the user the film. Checked before every
            // other branch so a failed sideloaded subtitle cannot reach the
            // startup callback, the recovery budget, or the diagnosis UI.
            if (dropFailedExternalSubtitle()) return
            // Startup is a separate, short transaction. It must not inherit the
            // long steady-state recovery policy or hide format/decoder failures.
            startupCallbacks?.let { callbacks ->
                startupCallbacks = null
                recordPlaybackError(error)
                callbacks.onError(error)
                return
            }
            if (isTransientError(error) && recoveryGateCount < MAX_RECOVERY_ATTEMPTS) {
                // Bounded, SILENT auto-recovery: don't surface the error UI — after a
                // backoff, seek to the last good position and re-prepare the SAME
                // player. Byte-range makes the resume seamless, and the buffer hides it.
                recoveryGateCount++
                instrumentation.autoRecoveryCount++
                scheduleRecovery(recoveryGateCount)
                return
            }
            // Non-transient (4xx, decoder init) or the recovery budget is spent →
            // hand off to the diagnosis UI.
            recordPlaybackError(error)
            playbackFailureListener?.invoke(error)
        }
    }

    /**
     * Once playback is running Media3 already disables a text renderer whose
     * stream errored and keeps the video going. It does not do that while the
     * video period is still preparing: there the merged subtitle source's fatal
     * load error propagates as an ordinary prepare failure and would end the
     * cast. This is the one-shot net for that window — re-prepare the identical
     * media, same media id, same position, minus the subtitle.
     */
    private fun dropFailedExternalSubtitle(): Boolean {
        if (!subtitleFailureState.shouldRollbackAfterPlayerError(currentSubtitle != null)) return false
        return dropExternalSubtitle("external load failed")
    }

    /** Re-prepare the same live player at the same position, without the text source. */
    private fun dropExternalSubtitle(reason: String): Boolean {
        val droppedSubtitle = currentSubtitle ?: return false
        if (!subtitleFailureState.canRollback(hasSubtitle = true)) return false
        val exo = player ?: return false
        val url = currentUrl ?: return false
        cancelSubtitleReloadDeadline()
        // A recovery queued for the item that still contained the failed subtitle
        // must not re-prepare over this one-shot rollback.
        cancelPendingRecovery()
        subtitleFailureState.recordRollback()
        currentSubtitle = null
        val resumePositionMs = exo.currentPosition.coerceAtLeast(0L).takeIf { it > 0L }
            ?: savedPositionMs.coerceAtLeast(0L)
        val resumePlayWhenReady = exo.playWhenReady
        savedPositionMs = resumePositionMs
        pendingPlayWhenReady = resumePlayWhenReady
        lastGoodPositionMs = resumePositionMs
        FlickLog.w("subtitle", "$reason; continuing without external subtitle")
        exo.setMediaItem(
            mediaItemFor(url, currentMediaId, subtitle = null),
            /* resetPosition = */ false,
        )
        if (resumePositionMs > 0L) exo.seekTo(resumePositionMs)
        exo.prepare()
        exo.playWhenReady = resumePlayWhenReady
        currentMediaId?.let { externalSubtitleDroppedListener?.invoke(it, droppedSubtitle) }
        return true
    }

    /**
     * An external subtitle is optional, so a silent Media3 re-prepare stall gets
     * a much shorter budget than the phone-control lease. A playing reload must
     * become READY and put a new frame on the existing surface. This also applies
     * while paused: Media3 renders the first post-stream-change frame without
     * changing playWhenReady, and READY alone can still leave a blank surface.
     */
    private fun armSubtitleReloadDeadline(
        exo: ExoPlayer,
        attemptToken: Long,
        mediaId: String,
    ) {
        cancelSubtitleReloadDeadline()
        subtitleReloadWatchdog.arm(
            token = attemptToken,
            mediaId = mediaId,
            // setMediaItem is deliberately called before this method. Sampling
            // the resulting non-READY state prevents the old item's READY value
            // from satisfying the new generation.
            alreadyReloading = exo.playbackState != Player.STATE_READY,
        )
        pendingSubtitleReloadAttemptToken = attemptToken
        lateinit var deadline: Runnable
        deadline = Runnable {
            if (pendingSubtitleReloadDeadline !== deadline) return@Runnable
            pendingSubtitleReloadDeadline = null
            pendingSubtitleReloadAttemptToken = null
            if (player !== exo) {
                subtitleReloadWatchdog.cancel()
                return@Runnable
            }
            if (!subtitleReloadWatchdog.consumeDeadline(attemptToken, currentMediaId)) return@Runnable
            dropExternalSubtitle("external reload did not resume within ${SUBTITLE_RELOAD_DEADLINE_MS}ms")
        }
        pendingSubtitleReloadDeadline = deadline
        recoveryHandler.postDelayed(deadline, SUBTITLE_RELOAD_DEADLINE_MS)
    }

    private fun cancelSubtitleReloadDeadline(clearState: Boolean = true) {
        pendingSubtitleReloadDeadline?.let { recoveryHandler.removeCallbacks(it) }
        pendingSubtitleReloadDeadline = null
        pendingSubtitleReloadAttemptToken = null
        if (clearState) subtitleReloadWatchdog.cancel()
    }

    private fun recordPlaybackError(error: PlaybackException) {
        instrumentation.errorMessage = error.message ?: "Playback error"
        instrumentation.errorCode = error.errorCode
        instrumentation.errorCodeName = error.errorCodeName
        // Codes and the classified wire result only — never the raw message,
        // which can carry a tokenized URL.
        FlickLog.e("player", "playbackError code=${error.errorCodeName} classified=${PlaybackFailureClassifier.classify(error).wire}")
    }

    private val analyticsListener = object : AnalyticsListener {
        override fun onRenderedFirstFrame(
            eventTime: AnalyticsListener.EventTime,
            output: Any,
            renderTimeMs: Long,
        ) {
            val mediaPeriodId = eventTime.currentMediaPeriodId
            val renderedMediaItem = if (mediaPeriodId == null) null else runCatching {
                val period = Timeline.Period()
                eventTime.currentTimeline.getPeriodByUid(mediaPeriodId.periodUid, period)
                val window = Timeline.Window()
                eventTime.currentTimeline.getWindow(period.windowIndex, window)
                window.mediaItem
            }.getOrNull()
            val mediaId = renderedMediaItem?.mediaId
            val attemptToken = renderedMediaItem?.subtitleReloadAttemptToken()
            if (attemptToken == pendingSubtitleReloadAttemptToken &&
                subtitleReloadWatchdog.onPresented(attemptToken, mediaId)
            ) {
                cancelSubtitleReloadDeadline(clearState = false)
            }
            val callbacks = startupCallbacks ?: return
            // Fail closed if Media3 cannot identify the period. The startup
            // deadline will issue a terminal result rather than readying B from
            // a late A renderer event.
            if (!firstFrameGate.consumeIfMatches(mediaId)) return
            if (callbacks.mediaId != mediaId) return
            startupCallbacks = null
            FlickLog.i(
                "player",
                "firstFrame decoder=${instrumentation.decoderName ?: "unknown"} " +
                    "res=${instrumentation.videoWidth}x${instrumentation.videoHeight} " +
                    "mime=${instrumentation.videoMimeType ?: "unknown"} transfer=${instrumentation.colorTransfer} renderTimeMs=$renderTimeMs",
            )
            callbacks.onFirstFrame()
        }

        override fun onDroppedVideoFrames(
            eventTime: AnalyticsListener.EventTime,
            droppedFrames: Int,
            elapsedMs: Long,
        ) {
            instrumentation.droppedFrames += droppedFrames
        }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            if (format.frameRate > 0f) {
                instrumentation.frameRate = format.frameRate
            }
            if (format.width > 0 && format.height > 0) {
                instrumentation.videoWidth = format.width
                instrumentation.videoHeight = format.height
            }
            // Capture the real decoded format so the on-screen quality badge is
            // honest (DV vs HDR10 vs SDR) instead of a hardcoded "DOLBY VISION".
            format.sampleMimeType?.let { instrumentation.videoMimeType = it }
            format.colorInfo?.colorTransfer?.takeIf { it != Format.NO_VALUE }?.let {
                instrumentation.colorTransfer = it
            }
            logPresentationShortfalls()
        }

        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            // Whatever the audio renderer actually received — the only honest
            // source for the "E-AC-3 · 5.1" chip. Unknown fields stay unset so
            // the chip is dropped rather than guessed.
            format.sampleMimeType?.let { audioMimeType = it }
            if (format.channelCount > 0) audioChannelCount = format.channelCount
            format.codecs?.takeIf { it.isNotBlank() }?.let { audioCodecs = it }
        }

        /**
         * The only place Media3 names the URI behind a failed load, which is how
         * a subtitle failure is told apart from a media failure. Recording the
         * fact is all that happens here: Media3 recovers from most of these on
         * its own, and re-preparing eagerly would stall a film that is fine.
         */
        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            error: IOException,
            wasCanceled: Boolean,
        ) {
            if (wasCanceled) return
            val attemptToken = subtitleReloadAttemptToken(eventTime)
            if (isCurrentExternalSubtitleLoad(loadEventInfo, attemptToken)) {
                subtitleFailureState.recordLoadFailure()
            }
        }

        override fun onLoadCompleted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
        ) {
            // The one short text retry may succeed; don't let its earlier error
            // make a later, unrelated media error drop a now-healthy subtitle.
            val attemptToken = subtitleReloadAttemptToken(eventTime)
            if (isCurrentExternalSubtitleLoad(loadEventInfo, attemptToken)) {
                subtitleFailureState.recordLoadSuccess()
                if (subtitleReloadWatchdog.onSubtitleLoaded(attemptToken, currentMediaId)) {
                    cancelSubtitleReloadDeadline(clearState = false)
                }
            }
        }

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            instrumentation.decoderName = decoderName
        }
    }

    private fun isCurrentExternalSubtitleLoad(
        loadEventInfo: LoadEventInfo,
        attemptToken: Long?,
    ): Boolean {
        val subtitleUrl = currentSubtitle?.url ?: return false
        val uriMatches = loadEventInfo.dataSpec.uri.toString() == subtitleUrl ||
            loadEventInfo.uri.toString() == subtitleUrl
        if (!uriMatches) return false
        return attemptToken == pendingSubtitleReloadAttemptToken
    }

    private fun subtitleReloadAttemptToken(eventTime: AnalyticsListener.EventTime): Long? {
        val mediaPeriodId = eventTime.mediaPeriodId ?: return null
        return runCatching {
            val period = Timeline.Period()
            eventTime.timeline.getPeriodByUid(mediaPeriodId.periodUid, period)
            val window = Timeline.Window()
            eventTime.timeline.getWindow(period.windowIndex, window)
            window.mediaItem.subtitleReloadAttemptToken()
        }.getOrNull()
    }

    private fun resetAudioFormat() {
        audioMimeType = null
        audioChannelCount = Format.NO_VALUE
        audioCodecs = null
    }

    private fun closeSeekFillWindow() {
        if (instrumentation.seekFillStartMs != 0L) {
            instrumentation.lastSeekFillMs =
                SystemClock.elapsedRealtime() - instrumentation.seekFillStartMs
            instrumentation.seekFillStartMs = 0L
        }
    }

    private fun closeRebufferWindow() {
        if (instrumentation.currentRebufferStartMs != 0L) {
            instrumentation.cumulativeRebufferMs +=
                SystemClock.elapsedRealtime() - instrumentation.currentRebufferStartMs
            instrumentation.currentRebufferStartMs = 0L
        }
    }

    // --- Unplayable video -----------------------------------------------------

    /**
     * One report per media item. `onTracksChanged` fires again for every text-track
     * change and for an in-place subtitle reload, and a capability verdict must not be
     * re-raised over a diagnosis screen the viewer is already reading.
     */
    private var videoShortfallReported = false

    /**
     * Turn "the video track was not selected" into the terminal failure Media3 never
     * raises for it — see [videoTrackShortfall].
     *
     * Routed exactly like [Player.Listener.onPlayerError] except that the transient
     * recovery path is skipped outright: no amount of re-preparing gives this TV a
     * decoder it does not have, and the 2/4/8/15 s recovery budget would only spend
     * 29 s before showing the same answer.
     */
    private fun reportUnplayableVideoTrack(tracks: Tracks) {
        if (videoShortfallReported) return
        val supports = mutableListOf<Int>()
        var anySelected = false
        var mimeType: String? = null
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_VIDEO) continue
            for (index in 0 until group.length) {
                supports += group.getTrackSupport(index)
                if (group.isTrackSelected(index)) anySelected = true
                if (mimeType == null) mimeType = group.getTrackFormat(index).sampleMimeType
            }
        }
        val shortfall = videoTrackShortfall(supports, anySelected, mimeType) ?: return
        videoShortfallReported = true
        FlickLog.w(
            "player",
            "videoUnplayable shortfall=$shortfall mime=${mimeType ?: "unknown"} " +
                "support=${supports.joinToString(",")} decoderPolicy=hardwareOnly",
        )
        val error = PlaybackException(
            "video track unplayable",
            UnplayableVideoTrackException(shortfall),
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        )
        recordPlaybackError(error)
        startupCallbacks?.let { callbacks ->
            startupCallbacks = null
            callbacks.onError(error)
            return
        }
        playbackFailureListener?.invoke(error)
    }

    // --- Panel capability ----------------------------------------------------

    /**
     * The panel's advertised HDR types and its physical mode, read once. Neither
     * changes for the life of the process on a TV, and `DisplayManager` is not free
     * to query from a per-format callback.
     *
     * The **physical** mode is deliberate: a display whose logical size has been
     * overridden (`wm size`) is a developer action, not a hardware limit, and must not
     * report a shortfall. `HdrCapabilities.getSupportedHdrTypes()` is deprecated at
     * API 34 in favour of the per-mode list, but it is the only reading available
     * across minSdk 26, and an empty array is treated as "unknown" downstream anyway.
     */
    private val panel: PanelCapability by lazy {
        runCatching {
            val manager = appContext.getSystemService(DisplayManager::class.java)
            val display = manager?.getDisplay(Display.DEFAULT_DISPLAY) ?: return@runCatching null
            @Suppress("DEPRECATION")
            val hdrTypes = display.hdrCapabilities?.supportedHdrTypes ?: IntArray(0)
            val mode = display.mode
            PanelCapability(hdrTypes, mode?.physicalWidth ?: 0, mode?.physicalHeight ?: 0)
        }.getOrNull() ?: PanelCapability(IntArray(0), 0, 0)
    }

    private class PanelCapability(val hdrTypes: IntArray, val width: Int, val height: Int)

    /**
     * Records what this panel cannot present about the format now decoding.
     *
     * Diagnosis only, and that is the whole design: there is no transcode and no
     * downscale here, so the alternative to presenting it anyway is refusing the film,
     * and `Display.HdrCapabilities` is under-reported often enough on Android TV that
     * refusing on it would break films that play correctly today.
     */
    private fun logPresentationShortfalls() {
        val shortfalls = presentationShortfalls(
            videoWidth = instrumentation.videoWidth,
            videoHeight = instrumentation.videoHeight,
            videoMimeType = instrumentation.videoMimeType,
            colorTransfer = instrumentation.colorTransfer,
            supportedHdrTypes = panel.hdrTypes,
            displayWidth = panel.width,
            displayHeight = panel.height,
        )
        if (shortfalls.isEmpty()) return
        FlickLog.i(
            "player",
            "panelShortfall ${shortfalls.joinToString(" ")} " +
                "panelHdr=${panel.hdrTypes.joinToString(",")} panel=${panel.width}x${panel.height}",
        )
    }

    // --- Bounded auto-recovery ----------------------------------------------

    /**
     * Plausibly-transient = worth silently riding out with a re-prepare:
     * network / IO / timeout, plus Media3's [StuckPlayerException] (a wedged
     * pipeline). Everything else (4xx, decoder init) is fatal → diagnosis UI.
     */
    private fun isTransientError(error: PlaybackException): Boolean {
        // Redirect/status responses must never enter the 2/4/8/15s recovery path.
        if (!PlaybackFailureClassifier.isSteadyStateRecoveryAllowed(error)) return false
        // StuckPlayerException arrives wrapped, so walk the whole cause chain.
        var cause: Throwable? = error.cause
        while (cause != null) {
            if (cause is StuckPlayerException) return true
            cause = cause.cause
        }
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_TIMEOUT -> true
            else -> false
        }
    }

    /** Post a delayed re-prepare of the current player at [lastGoodPositionMs]. */
    private fun scheduleRecovery(attempt: Int) {
        cancelPendingRecovery()
        val delayMs = RECOVERY_BACKOFF_MS[(attempt - 1).coerceIn(0, RECOVERY_BACKOFF_MS.lastIndex)]
        val runnable = Runnable {
            pendingRecovery = null
            val exo = player ?: return@Runnable
            exo.seekTo(lastGoodPositionMs)
            exo.prepare()
            exo.playWhenReady = true
        }
        pendingRecovery = runnable
        recoveryHandler.postDelayed(runnable, delayMs)
    }

    private fun cancelPendingRecovery() {
        pendingRecovery?.let { recoveryHandler.removeCallbacks(it) }
        pendingRecovery = null
    }

    // --- Player construction -------------------------------------------------

    private fun createPlayer(): ExoPlayer {
        // The allocator holds its segments on the Java heap, so every number below
        // is a fraction of THIS device's grant rather than of the one the tuning was
        // measured on — see [bufferBudgetFor]. Read at construction because the
        // grant is a property of the process, and logged because the buffer is the
        // whole anti-buffering thesis and a silently shrunken one must be visible.
        val budget = bufferBudgetFor(Runtime.getRuntime().maxMemory())
        FlickLog.i(
            "player",
            "loadControl targetMiB=${budget.targetBufferBytes / (1024 * 1024)} " +
                "minMs=${budget.minBufferMs} backMs=${budget.backBufferMs} maxMs=${budget.maxBufferMs} " +
                // Ride-out in seconds at two real 4K rates, because the byte target is
                // what bounds 4K and maxMs is not reachable there.
                "rideOut60MbpsSec=${budget.protectionSecondsAt(60_000_000L).toInt()} " +
                "rideOut100MbpsSec=${budget.protectionSecondsAt(PLANNED_PEAK_BITRATE_BPS).toInt()}",
        )
        val allocator = DefaultAllocator(/* trimOnReset = */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
        val loadControl = DefaultLoadControl.Builder()
            .setAllocator(allocator)
            .setBufferDurationsMs(
                budget.minBufferMs,
                budget.maxBufferMs,
                budget.bufferForPlaybackMs,
                budget.bufferForPlaybackAfterRebufferMs,
            )
            .setPrioritizeTimeOverSizeThresholds(budget.prioritizeTimeOverSizeThresholds)
            .setTargetBufferBytes(budget.targetBufferBytes)
            .setBackBuffer(budget.backBufferMs, /* retainBackBufferFromKeyframe = */ true)
            .build()

        // Byte-range aware HTTP source. Cross-protocol redirects OFF (we only
        // ever talk plain http to the LAN sender). Sane connect/read timeouts so
        // a dead sender surfaces as an error instead of hanging forever.
        val httpDataSourceFactory = NoRedirectHttpDataSourceFactory(
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
            userAgent = USER_AGENT,
        )

        // Generous LAN direct-play retry policy replacing Media3's default (3
        // tries). ~100s of quiet capped-backoff retrying rides out router blips,
        // phone roams and brief peer-block episodes, and every video byte-range
        // retry is a perfect resume. A sideloaded text file is optional and gets
        // one short retry instead; making it share this ~100s budget can hold the
        // merged source in prepare long after the video itself is healthy. 4xx
        // (except 416 Range-Not-Satisfiable) still fail fast.
        val loadErrorHandlingPolicy = object : DefaultLoadErrorHandlingPolicy(MAX_LOAD_RETRY_COUNT) {
            override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                // Preserve the default policy's fail-fast classification for non-HTTP
                // fatal errors (ParserException, FileNotFoundException,
                // CleartextNotPermittedException, position-out-of-range
                // DataSourceExceptions): it returns TIME_UNSET for those. Retrying
                // unplayable content ~20x behind the buffer would only delay the
                // diagnosis by ~100s (e.g. the URL points at an HTML page → sniffing
                // throws ParserException). InvalidResponseCodeException stays retriable
                // in the default, so the 4xx check below still governs HTTP codes.
                if (super.getRetryDelayMsFor(loadErrorInfo) == C.TIME_UNSET) return C.TIME_UNSET
                val exception = loadErrorInfo.exception
                // The strict source has already observed an unsafe HTTP response.
                // Never turn a redirect/4xx/5xx startup failure into the long
                // in-playback retry policy.
                //
                // SubtitleTooLargeException belongs in the same set and is not an HTTP
                // verdict: it is a body that has already exceeded the cap, so every retry
                // re-fetches the same oversized file. This policy does reach the
                // subtitle SingleSampleMediaSource, so without it a single over-cap
                // sidecar cost ~20 fetches — on the order of 100 MB and 100 s of TV-side
                // downloading — to reach a conclusion already known on the first attempt.
                if (exception is RedirectRejectedException ||
                    exception is PlaybackHttpStatusException ||
                    exception is SubtitleTooLargeException
                ) {
                    return C.TIME_UNSET
                }
                if (exception is HttpDataSource.InvalidResponseCodeException) {
                    val code = exception.responseCode
                    if (code in 400..499 && code != 416) return C.TIME_UNSET
                }
                return lanLoadRetryDelayMs(
                    trackType = loadErrorInfo.mediaLoadData.trackType,
                    errorCount = loadErrorInfo.errorCount,
                )
            }
        }

        val mediaSourceFactory = DefaultMediaSourceFactory(appContext)
            .setDataSourceFactory(httpDataSourceFactory)
            .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)

        // Hardware decoders only (no software/extension renderers) — the whole
        // point is to prove the TV decodes the original bytes in hardware.
        //
        // Decoder fallback is ON, and that does not weaken the claim: the selector has
        // already removed every software decoder from the candidate list, and fallback
        // can only walk to the NEXT entry of that list. So there is no software decoder
        // left to fall back TO, and what the flag actually buys is the retry to a second
        // *hardware* decoder on a TV that ships more than one — turning "the first
        // decoder would not configure" from a dead cast into a working one.
        val renderersFactory = DefaultRenderersFactory(appContext)
            .setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                val candidates = MediaCodecSelector.DEFAULT.getDecoderInfos(
                    mimeType,
                    requiresSecureDecoder,
                    requiresTunnelingDecoder,
                )
                if (!MimeTypes.isVideo(mimeType)) candidates else candidates.filter { info ->
                    // Media3 populates hardwareAccelerated on every API level, not
                    // only 29+: below 29 it derives it from the codec namespace. The
                    // old `>= 29` gate discarded that and left the policy with a
                    // MediaTek-only fallback, which refused to play on every other
                    // vendor's silicon on API 26-28.
                    HardwareDecoderPolicy.isHardwareVideoCodec(
                        name = info.name,
                        hardwareAccelerated = info.hardwareAccelerated,
                    )
                }
            }
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

        return ExoPlayer.Builder(appContext, renderersFactory)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setBandwidthMeter(bandwidthMeter)
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(10_000L)
            .build()
            .also { exo ->
                exo.addListener(playerListener)
                exo.addAnalyticsListener(analyticsListener)
                // Restore the last commanded volume so it survives player rebuilds
                // (background/foreground) and the control channel stays authoritative.
                exo.volume = lastVolume
            }
    }

    /**
     * The one place a media item is built. An external subtitle is attached as a
     * real sideloaded text track, which `DefaultMediaSourceFactory` merges with
     * the video source; nothing on the video path changes. A null [mediaId]
     * leaves Media3's default, which the first-frame gate can never match — the
     * restore path deliberately keeps that property.
     */
    private fun mediaItemFor(
        url: String,
        mediaId: String?,
        subtitle: ExternalSubtitle?,
        tag: Any? = null,
    ): MediaItem {
        val builder = MediaItem.Builder().setUri(url)
        if (mediaId != null) builder.setMediaId(mediaId)
        if (tag != null) builder.setTag(tag)
        if (subtitle != null) {
            builder.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitle.url))
                        .setMimeType(sideloadedSubtitleMimeType(subtitle.label))
                        .setLanguage(subtitle.language)
                        .setLabel(subtitle.label)
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build(),
                ),
            )
        }
        return builder.build()
    }

    private fun resetSubtitleState(subtitle: ExternalSubtitle?, mediaId: String?) {
        currentSubtitle = subtitle
        currentMediaId = mediaId
        subtitleFailureState.reset()
        // A capability verdict belongs to the file it was reached about: the next cast
        // gets to be judged on its own tracks. This is every load and reload path.
        videoShortfallReported = false
    }

    // --- Lifecycle -----------------------------------------------------------

    /**
     * Media3 owns platform media-button dispatch. When ExoPlayer is rebuilt,
     * switch the session first so no controller can target a released player.
     * All players use the main application looper, as required by setPlayer.
     */
    private fun bindMediaSession(exo: ExoPlayer) {
        val existing = mediaSession
        if (existing == null) {
            mediaSession = MediaSession.Builder(appContext, exo)
                .setCallback(mediaSessionCallback)
                .build()
        } else if (existing.player !== exo) {
            existing.setPlayer(exo)
        }
    }

    /** MediaSession must be released before its underlying player. */
    private fun releaseMediaSession() {
        mediaSession?.release()
        mediaSession = null
    }

    /** Create the player if needed and restore any previous media (called on ON_START). */
    fun onStart() {
        player?.let { existing ->
            if (currentUrl != null && mediaSession == null) bindMediaSession(existing)
            return
        }
        val exo = createPlayer()
        player = exo
        val url = currentUrl
        if (url != null) {
            bindMediaSession(exo)
            exo.setMediaItem(mediaItemFor(url, mediaId = null, subtitle = currentSubtitle))
            exo.prepare()
            if (savedPositionMs > 0L) exo.seekTo(savedPositionMs)
            exo.playWhenReady = pendingPlayWhenReady
        }
    }

    /** Save position + intent, then release the decoder (called on ON_STOP). */
    fun onStop() {
        cancelSubtitleReloadDeadline()
        val exo = player ?: run {
            releaseMediaSession()
            return
        }
        // Drop any queued recovery: it targets the player we're about to release;
        // onStart() re-prepares from savedPositionMs instead.
        cancelPendingRecovery()
        // Close any in-progress rebuffer window BEFORE removing listeners so the
        // decoder release doesn't leave currentRebufferStartMs non-zero and
        // corrupt cumulativeRebufferMs with backgrounded wall-clock time.
        closeRebufferWindow()
        savedPositionMs = exo.currentPosition.coerceAtLeast(0L)
        pendingPlayWhenReady = exo.playWhenReady
        releaseMediaSession()
        exo.removeListener(playerListener)
        exo.removeAnalyticsListener(analyticsListener)
        exo.release()
        player = null
    }

    /** Terminal teardown (onDestroy / Compose onDispose). */
    fun release() {
        cancelSubtitleReloadDeadline()
        cancelPendingRecovery()
        releaseMediaSession()
        val exo = player ?: return
        // Close any in-progress rebuffer window before tearing down.
        closeRebufferWindow()
        exo.removeListener(playerListener)
        exo.removeAnalyticsListener(analyticsListener)
        exo.release()
        player = null
    }

    // --- Playback control ----------------------------------------------------

    /** Start a fresh direct-play session against [url], resetting all metrics. */
    fun play(url: String) {
        startupCallbacks = null
        firstFrameGate.clear()
        currentUrl = url
        savedPositionMs = 0L
        pendingPlayWhenReady = true
        cancelSubtitleReloadDeadline()
        cancelPendingRecovery()
        recoveryGateCount = 0
        lastGoodPositionMs = 0L
        stableReadySinceMs = 0L
        probeLatencyMs = 0L
        instrumentation.reset()
        resetAudioFormat()
        resetSubtitleState(subtitle = null, mediaId = null)
        val exo = player ?: createPlayer().also { player = it }
        // stop() releases the terminal session but intentionally keeps this
        // foreground player instance reusable. Rebind before any new playback.
        bindMediaSession(exo)
        exo.setMediaItem(mediaItemFor(url, mediaId = null, subtitle = null))
        exo.prepare()
        exo.playWhenReady = true
    }

    /** Installs both startup callbacks before setting media or calling prepare. */
    override fun playStartup(
        url: String,
        startMs: Long,
        mediaId: String,
        subtitle: ExternalSubtitle?,
        onFirstFrame: () -> Unit,
        onError: (PlaybackException) -> Unit,
    ) {
        firstFrameGate.arm(mediaId)
        startupCallbacks = StartupCallbacks(mediaId, onFirstFrame, onError)
        currentUrl = url
        savedPositionMs = 0L
        pendingPlayWhenReady = true
        cancelSubtitleReloadDeadline()
        cancelPendingRecovery()
        recoveryGateCount = 0
        instrumentation.reset()
        resetAudioFormat()
        resetSubtitleState(subtitle, mediaId)
        // A startup adoption always gets a fresh listener/renderer instance.
        // Switch the platform session to B before releasing A, then publish B
        // to Compose. This prevents a physical media key targeting released A.
        val previous = player
        val exo = createPlayer()
        bindMediaSession(exo)
        player = exo
        previous?.let {
            it.removeListener(playerListener)
            it.removeAnalyticsListener(analyticsListener)
            it.release()
        }
        exo.setMediaItem(mediaItemFor(url, mediaId, subtitle))
        if (startMs > 0) exo.seekTo(startMs)
        exo.prepare()
        exo.playWhenReady = true
    }

    /**
     * A subtitle change on a cast that is already playing. Everything that makes
     * the picture is deliberately left alone — the ExoPlayer instance, the output
     * surface it is already presenting to, the MediaSession that owns platform
     * media buttons, and the user's track selection all survive; only the media
     * item is rebuilt and re-prepared where the film already is.
     *
     * No startup callback is installed and no first-frame gate is armed. A reload
     * has no first frame to wait for, so an error here must reach the ordinary
     * steady-state path rather than a startup transaction that already completed.
     */
    override fun reloadInPlace(
        url: String,
        positionMs: Long,
        mediaId: String,
        subtitle: ExternalSubtitle?,
    ): Boolean {
        cancelSubtitleReloadDeadline()
        val exo = player ?: return false
        startupCallbacks = null
        firstFrameGate.clear()
        // A recovery queued against the old media would seek and re-prepare on
        // top of this one.
        cancelPendingRecovery()
        // The live player's own clock, not the frame's: the phone's startMs is the
        // TV's confirmed position sampled at 10 Hz and already a tick stale.
        val resumeMs = exo.currentPosition.coerceAtLeast(0L).takeIf { it > 0L }
            ?: positionMs.coerceAtLeast(0L)
        val resumePlayWhenReady = exo.playWhenReady
        currentUrl = url
        savedPositionMs = resumeMs
        pendingPlayWhenReady = resumePlayWhenReady
        lastGoodPositionMs = resumeMs
        stableReadySinceMs = 0L
        resetSubtitleState(subtitle, mediaId)
        // A sideloaded track arrives under SELECTION_FLAG_DEFAULT, which both the
        // panel's Off row and any earlier per-group override outrank. A fresh
        // player used to lose those along with everything else, so reusing one
        // must not turn the subtitle just attached into a track that draws nothing.
        if (subtitle != null) {
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .build()
        }
        val attemptToken = if (subtitle != null) ++nextSubtitleReloadAttemptToken else null
        val reloadedMediaItem = mediaItemFor(
            url = url,
            mediaId = mediaId,
            subtitle = subtitle,
            tag = attemptToken?.let(::SubtitleReloadAttemptTag),
        )
        exo.setMediaItem(reloadedMediaItem, /* resetPosition = */ false)
        if (subtitle != null) {
            armSubtitleReloadDeadline(
                exo = exo,
                attemptToken = checkNotNull(attemptToken),
                mediaId = mediaId,
            )
        }
        if (resumeMs > 0L) exo.seekTo(resumeMs)
        exo.prepare()
        exo.playWhenReady = resumePlayWhenReady
        return true
    }

    override fun clearStartupListener() { startupCallbacks = null; firstFrameGate.clear() }

    /** Delivers the exact Media3 failure to the session while it is still actionable. */
    override fun setPlaybackFailureListener(listener: ((PlaybackException) -> Unit)?) {
        playbackFailureListener = listener
    }

    override fun setExternalSubtitleDroppedListener(
        listener: ((String, ExternalSubtitle) -> Unit)?,
    ) {
        externalSubtitleDroppedListener = listener
    }

    override fun stop() {
        clearStartupListener()
        pendingPlayWhenReady = false
        cancelSubtitleReloadDeadline()
        cancelPendingRecovery()
        stableReadySinceMs = 0L
        // Terminal stop must withdraw the platform session as well as clear the
        // media. Otherwise Android keeps advertising an idle empty player and
        // can continue routing physical media buttons to a dead cast.
        releaseMediaSession()
        // Stop must be terminal for the media. Without clearing these, a later
        // ON_STOP/ON_START cycle (background then foreground) would re-capture a
        // still-true playWhenReady, see currentUrl != null in onStart(), and
        // silently re-prepare — re-allocating the buffer + decoder and resuming
        // playback under an Idle UI. Backgrounding DURING playback still resumes,
        // because that path never calls stop() (only onStop keeps currentUrl set).
        currentUrl = null
        savedPositionMs = 0L
        resetSubtitleState(subtitle = null, mediaId = null)
        player?.let { exo ->
            exo.playWhenReady = false
            exo.stop()
            exo.clearMediaItems()
        }
    }

    /** Record the pre-flight probe round-trip so the overlay can surface it. */
    override fun recordProbeLatency(latencyMs: Long) {
        probeLatencyMs = latencyMs
    }

    /** Seek relative to the current position, clamped to [0, duration). Main-thread only. */
    override fun seekBy(deltaMs: Long) {
        val exo = player ?: return
        if (currentUrl == null) return
        val duration = exo.duration
        val target = (exo.currentPosition + deltaMs)
            .coerceAtLeast(0L)
            .let { if (duration != C.TIME_UNSET) it.coerceAtMost((duration - 1_000L).coerceAtLeast(0L)) else it }
        exo.seekTo(target)
    }

    fun togglePlayPause() {
        player?.let { it.playWhenReady = !it.playWhenReady }
    }

    // --- Control-channel command surface -------------------------------------
    // These map the WS verbs (control-channel.md §4) onto the player. They are
    // additive and must be called on the main thread (the control server
    // marshals to it); none of them touch the terminal-stop / hardware-decode /
    // pre-flight guarantees.

    /** WS `play`: resume only if a session is loaded (never re-prepares from Idle). */
    override fun resume() {
        if (currentUrl == null) return
        player?.let { it.playWhenReady = true }
    }

    /** WS `pause`. */
    override fun pause() {
        player?.let { it.playWhenReady = false }
    }

    /** WS `seek`: absolute (optimistic target), clamped to [0, duration). */
    override fun seekTo(posMs: Long) {
        val exo = player ?: return
        if (currentUrl == null) return
        val duration = exo.duration
        val target = posMs.coerceAtLeast(0L).let {
            if (duration != C.TIME_UNSET) it.coerceAtMost((duration - 1_000L).coerceAtLeast(0L)) else it
        }
        exo.seekTo(target)
    }

    /** WS `setVolume`: 0..1. Persisted so it survives player rebuilds. */
    override fun setVolume(level: Float) {
        lastVolume = level.coerceIn(0f, 1f)
        player?.volume = lastVolume
    }

    // --- Subtitle track surface ----------------------------------------------
    // Read/write of Media3's text-track selection for the subtitles panel. Both
    // are main-thread only and touch nothing but trackSelectionParameters — the
    // media item, load control, decoder policy and recovery state are untouched.

    /**
     * The text tracks Media3 currently reports, in container order. Empty while
     * no player exists or the media declares no subtitles — the panel then shows
     * only its Off row rather than inventing tracks.
     */
    fun subtitleTracks(): List<SubtitleTrackInfo> {
        val exo = player ?: return emptyList()
        return subtitleTracksFrom(exo.currentTracks)
    }

    /**
     * Select the text track carrying [id] (from [subtitleTracks]); null turns
     * subtitles off. An id that no longer resolves against the live listing is
     * ignored, so a stale panel choice can never override an unrelated track.
     */
    fun selectSubtitleTrack(id: String?) {
        val exo = player ?: return
        val builder = exo.trackSelectionParameters.buildUpon()
        if (id == null) {
            exo.trackSelectionParameters = builder
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            return
        }
        val selection: TrackSelectionOverride = subtitleTrackOverride(exo.currentTracks, id) ?: return
        exo.trackSelectionParameters = builder
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(selection)
            .build()
    }

    /** Whether a media session is currently loaded (drives idle-vs-playing UI). */
    fun hasSession(): Boolean = currentUrl != null

    /**
     * The confirmed playback frame streamed to the phone at ~10 Hz. Cheap,
     * main-thread only (reads live ExoPlayer getters).
     */
    override fun readPlaybackState(): PlaybackFrame {
        val exo = player
        val pos = (exo?.currentPosition ?: 0L).coerceAtLeast(0L)
        val duration = exo?.duration?.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
        val buffered = exo?.bufferedPosition?.takeIf { it != C.TIME_UNSET } ?: 0L
        val playing = exo?.isPlaying ?: false
        val volume = exo?.volume ?: lastVolume
        val phase = when {
            instrumentation.errorMessage != null -> PlaybackPhase.Error
            exo == null || currentUrl == null -> PlaybackPhase.Idle
            exo.playbackState == Player.STATE_BUFFERING -> PlaybackPhase.Buffering
            exo.playbackState == Player.STATE_ENDED -> PlaybackPhase.Ended
            exo.playbackState == Player.STATE_READY ->
                if (exo.playWhenReady) PlaybackPhase.Playing else PlaybackPhase.Paused
            else -> PlaybackPhase.Idle
        }
        return PlaybackFrame(
            posMs = pos,
            durationMs = duration,
            playing = playing,
            bufferedMs = buffered,
            phase = phase,
            volume = volume,
        )
    }

    // --- Telemetry snapshot --------------------------------------------------

    /** Build an immutable snapshot of all live metrics. Main-thread only. */
    fun snapshot(): DiagnosticsSnapshot {
        val exo = player
        val position = exo?.currentPosition ?: 0L
        val bufferedPosition = exo?.bufferedPosition ?: 0L
        val bufferedAhead =
            if (exo != null && bufferedPosition != C.TIME_UNSET && position != C.TIME_UNSET) {
                (bufferedPosition - position).coerceAtLeast(0L)
            } else 0L
        val duration = exo?.duration?.takeIf { it != C.TIME_UNSET } ?: 0L

        // Include the in-progress rebuffer window so the timer ticks live.
        val liveRebufferMs = if (instrumentation.currentRebufferStartMs != 0L) {
            SystemClock.elapsedRealtime() - instrumentation.currentRebufferStartMs
        } else 0L

        // Per-tick bookkeeping (main thread): remember the last healthy playing
        // position as the recovery seek target, and re-arm the recovery budget
        // once playback has been stably READY for a stretch.
        if (exo != null && instrumentation.playbackState == Player.STATE_READY && position > 0L) {
            lastGoodPositionMs = position
        }
        if (recoveryGateCount > 0 && stableReadySinceMs != 0L &&
            SystemClock.elapsedRealtime() - stableReadySinceMs >= RECOVERY_RESET_STABLE_MS
        ) {
            recoveryGateCount = 0
        }
        // A seek that landed inside the buffer never leaves STATE_READY, so no
        // state change closes its fill window — close it here once settled.
        if (instrumentation.seekFillStartMs != 0L &&
            instrumentation.playbackState == Player.STATE_READY &&
            SystemClock.elapsedRealtime() - instrumentation.seekFillStartMs >= SEEK_SETTLE_MS
        ) {
            closeSeekFillWindow()
        }

        val wifi = WifiTelemetry.read(appContext)

        return DiagnosticsSnapshot(
            playbackState = instrumentation.playbackState,
            isPlaying = exo?.isPlaying ?: false,
            playbackStarted = instrumentation.playbackStarted,
            width = instrumentation.videoWidth,
            height = instrumentation.videoHeight,
            frameRate = instrumentation.frameRate,
            rebufferCount = instrumentation.rebufferCount,
            cumulativeRebufferMs = instrumentation.cumulativeRebufferMs + liveRebufferMs,
            currentlyRebuffering = instrumentation.currentRebufferStartMs != 0L,
            bufferedAheadMs = bufferedAhead,
            droppedFrames = instrumentation.droppedFrames,
            bitrateEstimateBps = bandwidthMeter.bitrateEstimate,
            decoderName = instrumentation.decoderName,
            videoMimeType = instrumentation.videoMimeType,
            colorTransfer = instrumentation.colorTransfer,
            positionMs = position.coerceAtLeast(0L),
            durationMs = duration,
            errorMessage = instrumentation.errorMessage,
            errorCode = instrumentation.errorCode,
            errorCodeName = instrumentation.errorCodeName,
            autoRecoveryCount = instrumentation.autoRecoveryCount,
            seekCount = instrumentation.seekCount,
            lastSeekFillMs = instrumentation.lastSeekFillMs,
            probeLatencyMs = probeLatencyMs,
            wifiBand = wifi?.band,
            wifiLinkSpeedMbps = wifi?.linkSpeedMbps ?: -1,
            wifiRssiDbm = wifi?.rssiDbm ?: 0,
            subtitleTrackSelected = instrumentation.subtitleTrackSelected,
            subtitleTrackMimeType = instrumentation.subtitleTrackMimeType,
            subtitleCueKind = instrumentation.subtitleCueKind,
            audioMimeType = audioMimeType,
            audioChannelCount = audioChannelCount,
            audioCodecs = audioCodecs,
        )
    }

    companion object {
        // The LoadControl numbers are per-device and live in [bufferBudgetFor];
        // nothing here may reintroduce a fixed one. What the buffer buys, stated in
        // the unit it is actually bounded in: on the verified hardware 256 MB is
        // ~21 s of 100 Mbps 4K, ~54 s at 40 Mbps, and the full 180 s time cap only
        // below ~12 Mbps. The observed real-world event this tuning answers was a
        // ~70 s wireless outage that drained a previous 60 s cap and cost a 12 s
        // stall — so 4K is protected by BYTES, and the seconds follow the bitrate.

        // A seek that lands inside the buffer never leaves STATE_READY, so the
        // seek-fill window is closed from the snapshot tick once this settle time
        // has passed (long enough that a genuine post-seek BUFFERING transition —
        // same main-loop burst as the discontinuity — always arrives first).
        const val SEEK_SETTLE_MS = 700L

        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val USER_AGENT = "FlickReceiver/0.1 (Media3 direct-play)"

        // Load-error retry policy: ~20 tries with capped backoff (min(1000*(n+1), 5000)ms)
        // ≈ 100s of quiet retrying. Whether that is hidden depends on the device's
        // own budget: it is covered outright at 40 Mbps and below on the verified
        // hardware, and only partly on a TV whose heap forced the budget down.
        const val MAX_LOAD_RETRY_COUNT = 20
        // Bounded fatal-error auto-recovery: backoff per attempt (1-4), then give up.
        private val RECOVERY_BACKOFF_MS = longArrayOf(2_000L, 4_000L, 8_000L, 15_000L)
        val MAX_RECOVERY_ATTEMPTS = RECOVERY_BACKOFF_MS.size

        // Re-arm the recovery budget after this long uninterrupted in STATE_READY.
        const val RECOVERY_RESET_STABLE_MS = 30_000L

        // Well below the phone's control-lease loss floor, but long enough for a
        // fresh 4K decoder prepare on the verified Google TV Streamer.
        const val SUBTITLE_RELOAD_DEADLINE_MS = 12_000L
    }
}

private fun MediaItem.subtitleReloadAttemptToken(): Long? =
    (localConfiguration?.tag as? SubtitleReloadAttemptTag)?.token

/**
 * The subtitle route is `/s/{token}`, so the URL carries no extension and the
 * sender's label is the only evidence of the format. Guessing wrong is
 * survivable — the parser fails, Media3 drops the text track and the film keeps
 * playing — so SubRip is the default for anything the extension does not name.
 * The sender only ever offers the four extensions mapped here.
 */
internal fun sideloadedSubtitleMimeType(label: String?): String {
    val name = label?.trimEnd().orEmpty()
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || dot == name.length - 1) return MimeTypes.APPLICATION_SUBRIP
    return when (name.substring(dot + 1).lowercase()) {
        "vtt", "webvtt" -> MimeTypes.TEXT_VTT
        // SubStation Alpha needs its own parser; handing an ASS payload to SubRip
        // yields a track that draws nothing at all.
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        else -> MimeTypes.APPLICATION_SUBRIP
    }
}
