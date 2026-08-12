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
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionResult
import com.flick.receiver.net.ExternalSubtitle
import com.flick.receiver.session.ReceiverFaultDetail
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
    /**
     * The failure, plus the local detail the wire code cannot carry — see
     * [faultDetail]. The detail travels with the exception because it is derived from
     * player state that only exists here and only at this instant.
     */
    fun setPlaybackFailureListener(listener: ((PlaybackException, ReceiverFaultDetail) -> Unit)?)
    fun setExternalSubtitleDroppedListener(listener: ((String, ExternalSubtitle) -> Unit)?)

    /**
     * The film carries audio that will not be heard, by media id and audio MIME.
     *
     * Reported for the same reason the subtitle drop above is: the player is where
     * the fact exists and the cast transaction is where it can be correlated with
     * a phone. It decides nothing — a listener that does anything but report this
     * onward would be spending a cast on a film that is still perfectly watchable.
     */
    fun setSilentAudioListener(listener: ((String, String) -> Unit)?)
    fun recordProbeLatency(latencyMs: Long)

    /**
     * Cold start: adopts a NEW player instance and reports the exact first frame.
     *
     * [onRotationRePrepare] fires when a picture-orientation correction has
     * re-prepared the player before that frame arrived. It is reported rather
     * than absorbed because the receiver chose that work itself, and the cast
     * transaction is the only place that knows what the startup budget is being
     * spent on.
     */
    fun playStartup(
        url: String,
        startMs: Long,
        mediaId: String,
        subtitle: ExternalSubtitle?,
        onFirstFrame: () -> Unit,
        onError: (PlaybackException, ReceiverFaultDetail) -> Unit,
        onRotationRePrepare: () -> Unit,
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

    /**
     * An explicit rotation on top of the container's own, in quarter turns. A
     * value off that grid is ignored rather than snapped — the control channel
     * validates the domain, and guessing at a malformed one would turn the
     * picture on a frame nobody asked about.
     */
    fun setVideoRotationDegrees(degrees: Int)

    /**
     * Give the reading back to the receiver: the automatic verdict is applied
     * again for the media that is playing, rather than the degrees Auto happened
     * to resolve to when the film loaded.
     */
    fun setAutoVideoRotation()

    /** Positive means audio heard later than the picture; see [AudioDelayPolicy]. */
    fun setAudioDelay(delayMs: Int)
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

    /** The settled-value line a walked audio nudge has not finished walking yet. */
    private var pendingAudioDelayLine: Runnable? = null
    private var pendingSubtitleReloadAttemptToken: Long? = null

    // --- Bounded auto-recovery state (all touched on the main thread only) ------
    private val recoveryHandler = Handler(Looper.getMainLooper())

    /** The delayed re-prepare a rough patch schedules; see [scheduleRecovery]. */
    private val pendingRecovery = PendingWork(
        schedule = { work, delayMs -> recoveryHandler.postDelayed(work, delayMs) },
        unschedule = { work -> recoveryHandler.removeCallbacks(work) },
    )

    /** Attempts within the current rough patch; gates [MAX_RECOVERY_ATTEMPTS], re-armed after a stable stretch. */
    private var recoveryGateCount: Int = 0

    /** Most recent healthy playing position — the seek target a recovery resumes from. */
    private var lastGoodPositionMs: Long = 0L

    /** elapsedRealtime() when the current uninterrupted STATE_READY stretch began; 0 otherwise. */
    private var stableReadySinceMs: Long = 0L

    /** Latest pre-flight probe round-trip (ms); <= 0 until [recordProbeLatency]. */
    private var probeLatencyMs: Long = 0L

    // --- Frozen-picture watch (main thread, sampled from [snapshot]) ------------

    /** Rendered-frame count at the previous sample; -1 before the first one. */
    private var lastRenderedFrames: Long = -1L

    /** Confirmed position at the previous sample, so a masked clock cannot pass for one. */
    private var lastSampledPositionMs: Long = -1L

    /** Consecutive samples the rendered counter has not moved on. */
    private var frozenPictureSamples: Int = 0

    /**
     * Whether this film has EVER put a frame on the surface.
     *
     * A latch and not a reading: the re-prepare below rebuilds the renderers, which
     * reallocates the decoder's counters, so from the next sample on the live count says
     * "never rendered" about a film that has been playing for an hour — and the guard
     * that reads it would suppress the very announcement the re-prepare failed to earn.
     */
    private var pictureEverRendered: Boolean = false

    /** Whether this film has already spent its one silent re-prepare for a dead picture. */
    private var pictureRePrepared: Boolean = false

    /** Last commanded volume (0..1); survives player rebuilds and null-player reads. */
    private var lastVolume: Float = 1f

    // --- Picture orientation (main thread, except the volatile holder) --------

    /**
     * The value the decoder is configured with. Held outside Compose because it
     * is read on the playback thread, and shared across player rebuilds so a
     * corrected film survives backgrounding.
     */
    private val rotationOverride = VideoRotationOverride()

    /** The viewer's choice. Compose state so the panel renders what is applied. */
    var videoRotation by mutableStateOf(VideoRotation.Auto)
        private set

    /** What [VideoRotation.Auto] resolved to for the current media, in degrees. */
    var autoVideoRotationDegrees by mutableStateOf(0)
        private set

    /** What the viewer is owed about this film's orientation — see [orientationHintFor]. */
    var orientationHint by mutableStateOf<OrientationHint?>(null)
        private set

    /**
     * What the turn now in force cost, or could not do at all. Null is the
     * ordinary case and means the picture is exactly what was asked for.
     */
    var turnNote by mutableStateOf<TurnNote?>(null)
        private set

    /**
     * The audio format of a film that will play silent, or null when there is
     * nothing to say. A reading and not a verdict: see [reportSilentAudio], which
     * is the only writer and never stops a cast over it.
     */
    var silentAudioMimeType by mutableStateOf<String?>(null)
        private set

    /**
     * Whether this cast has had its audio sink rebuilt under it — see
     * [AudioOutputPolicy]. Latched rather than pulsed: the band's queue is what decides
     * when the notice is actually seen, and the rebuild itself is over in one
     * main-thread turn.
     */
    var audioRestarted by mutableStateOf(false)
        private set

    /** Whether a sideloaded subtitle was dropped from this cast, by either route. */
    var subtitleDropped by mutableStateOf(false)
        private set

    /**
     * The turn the VIDEO SURFACE is carrying, and the picture it has to fit.
     *
     * Compose state, because a turn is carried by a different view: a `TextureView`
     * is the only surface whose contents a view transform can reach, and
     * `PlayerView` fixes its surface type at construction. [SurfaceTurn.NONE] is
     * therefore not merely "no turn" — it is the whole ordinary path, the
     * `SurfaceView` a cast would have had if this feature did not exist.
     *
     * A property of the FILM rather than of the ExoPlayer instance, so a turned
     * film comes back turned after a background/foreground cycle rebuilds the
     * player under it.
     */
    var surfaceTurn by mutableStateOf(SurfaceTurn.NONE)
        private set

    /** The selected video track's container turn, for [pictureTurnFor]. */
    private var filmContainerRotationDegrees: Int = 0

    /** The selected video track's colour class, for [pictureTurnFor]. */
    private var filmColour: PictureColour = PictureColour.Sdr

    /**
     * Set when the turn failed on THIS film, so it is never engaged for it again.
     * Cleared with the film — but not back to false for a film this session has
     * already condemned; see [filmsWithoutTurn].
     */
    private var turnUnavailableForFilm: Boolean = false

    /**
     * Every film this session has condemned, so a retry of one of them does not
     * re-engage the turn that wedged it. The keys are media URLs and carry the
     * sender's token: held, compared, never logged.
     */
    private val filmsWithoutTurn = FilmsWithoutTurn()

    /**
     * Whether this film's turn has been resolved against [pictureTurnFor] at all.
     * The rule it feeds, and why the resolution cannot be driven by Auto's answer
     * moving, are [resolvesPictureTurn]'s. Cleared with the film.
     */
    private var pictureTurnResolvedForFilm: Boolean = false

    /** The liveness deadline a turn is watched by; see [TurnWatchdog]. */
    private val turnWatchdog = TurnWatchdog()
    private var pendingTurnDeadline: Runnable? = null

    /**
     * The one-shot hand-back of a film whose turn was condemned, in its OWN slot.
     *
     * [PendingWork] carries why it cannot share [pendingRecovery]'s: an error
     * queued behind this used to cancel it and re-prepare the still-turned player
     * instead, leaving a frozen picture nothing was on its way to fix.
     */
    private val pendingTurnFallback = PendingWork(
        schedule = { work, delayMs -> recoveryHandler.postDelayed(work, delayMs) },
        unschedule = { work -> recoveryHandler.removeCallbacks(work) },
    )

    /**
     * Whether this cast's audio sink refuses compressed formats, so the renderer
     * decodes instead of passing through. Latched per cast by [AudioOutputPolicy]
     * and read by [createPlayer]; false for every ordinary cast.
     */
    private var decodeCompressedAudio = false

    /**
     * The one-shot rebuild onto a decoding audio sink, in its OWN slot for the
     * reason [pendingTurnFallback] documents: this releases the player that raised
     * the error, so it must not be collected by a canceller belonging to work
     * queued against that same player.
     */
    private val pendingAudioSinkRebuild = PendingWork(
        schedule = { work, delayMs -> recoveryHandler.postDelayed(work, delayMs) },
        unschedule = { work -> recoveryHandler.removeCallbacks(work) },
    )

    /**
     * The turn's proof of life, and the only per-frame signal media3 offers.
     *
     * `onRenderedFirstFrame` is not usable for this: it fires once and is gated on
     * `VideoFrameReleaseControl`'s first-frame state, which says nothing about a
     * picture that renders one frame and then stops — the failure this watches
     * for. `MediaCodecVideoRenderer` calls this listener immediately before it
     * releases every buffer to the output surface, so it is a per-frame heartbeat
     * on exactly the path the turn uses.
     *
     * Playback thread, and one volatile write with no branch and no main-thread
     * post. Installed only while a turn is in force, so an untouched cast keeps
     * `frameMetadataListener` null and media3 skips the call entirely.
     */
    private val turnFrameListener = VideoFrameMetadataListener { _, _, _, _ ->
        turnWatchdog.onFrameRendered(SystemClock.elapsedRealtime())
    }

    /**
     * The commanded audio delay, in the form the video renderers read. Built once
     * and handed to every renderers factory, so — unlike [lastVolume], which has
     * to be re-applied to each new ExoPlayer — a delay set before a
     * background/foreground cycle is already in force on the rebuilt renderers.
     */
    private val audioDelayShift = AudioDelayShift()

    /**
     * The widest delay this device's buffer can carry, read once. The heap grant
     * is a property of the process and [bufferBudgetFor] is a pure function of it,
     * so this cannot change while the app is running — and it is deliberately not
     * part of the wire rule; see [AudioDelayPolicy.accepts].
     */
    private val audioDelayCapMs: Int by lazy { AudioDelayPolicy.maxDelayMsFor(bufferBudget) }

    /**
     * This device's load-control budget. The heap grant is a property of the process and
     * [bufferBudgetFor] is a pure function of it, so one reading serves every player this
     * class builds — and serves the rebuffer plate, which measures a stall against the
     * ride-out this budget actually bought rather than against a guessed constant.
     */
    private val bufferBudget: BufferBudget by lazy { bufferBudgetFor(Runtime.getRuntime().maxMemory()) }

    // Facts about the audio the decoder is actually being fed, for the honest
    // codec chips. Written only from the analytics listener and cleared for each
    // new session, so a chip can never describe the previous film.
    private var audioMimeType: String? = null
    private var audioChannelCount: Int = Format.NO_VALUE
    private var audioCodecs: String? = null

    private data class StartupCallbacks(
        val mediaId: String,
        val onFirstFrame: () -> Unit,
        val onError: (PlaybackException, ReceiverFaultDetail) -> Unit,
        val onRotationRePrepare: () -> Unit,
    )

    /** Non-null only from player adoption until the exact first video frame. */
    private var startupCallbacks: StartupCallbacks? = null
    private val firstFrameGate = FirstFrameGate()
    private var playbackFailureListener: ((PlaybackException, ReceiverFaultDetail) -> Unit)? = null
    private var externalSubtitleDroppedListener: ((String, ExternalSubtitle) -> Unit)? = null
    private var silentAudioListener: ((String, String) -> Unit)? = null

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
                        // A stall is the one event this whole app exists to prevent, and
                        // until now it was the one event the log could not confirm or
                        // deny: the count and the elapsed total are kept for the
                        // on-screen overlay and never written anywhere a `logcat` can
                        // reach. Reading a clean log therefore proved nothing. At W so
                        // `-s FlickTV:W` is a stall filter, and it costs nothing in the
                        // zero-stall case this is supposed to be.
                        FlickLog.w("player", "rebuffer start n=${instrumentation.rebufferCount}")
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
            // A turned film's geometry is derived from the picture's own shape, so
            // a new shape has to reach the surface transform. Guarded on the turn
            // rather than published always: this is Compose state, and an
            // untouched cast must not recompose the playback surface at all.
            // Guarded on the SURFACE and not on the turn: a film back at zero on the
            // texture it was turned on is still fitted by the matrix, so it still needs
            // the picture's shape. An untouched cast publishes nothing here and does not
            // recompose the playback surface at all.
            if (surfaceTurn.onTexture && videoSize.width > 0 && videoSize.height > 0) {
                surfaceTurn = surfaceTurn.copy(
                    pictureWidthPx = videoSize.width,
                    pictureHeightPx = videoSize.height,
                    pixelWidthHeightRatio = videoSize.pixelWidthHeightRatio,
                )
            }
            // What the RENDERER thinks the picture's shape is, which is not the
            // same claim under the two mechanisms and is the reason this line
            // exists.
            //
            // Under the decoder: media3 swaps these dimensions and inverts the
            // sample aspect from `Format.rotationDegrees` on its own assumption
            // that the codec honoured `KEY_ROTATION` — which the verified TV's
            // display pipeline does not. A box that changed shape over a picture
            // that stayed sideways is exactly that failure, and is what put the
            // view turn here.
            //
            // Under the view: the codec is configured at 0, so these stay the
            // CODED dimensions — landscape for a film turned on end — and that is
            // correct rather than a symptom. It is also load-bearing: they are the
            // shape the frames actually arrive in, and therefore the shape the
            // surface transform has to be computed from.
            FlickLog.i(
                "player",
                "videoSize w=${videoSize.width} h=${videoSize.height} " +
                    "par=${videoSize.pixelWidthHeightRatio} decoderExtraDegrees=" +
                    (rotationOverride.decoderExtraDegrees?.toString() ?: "none") +
                    " via=" + when (rotationOverride.decoderReadViaView) {
                    null -> "none"
                    true -> "view"
                    false -> "decoder"
                },
            )
        }

        override fun onTracksChanged(tracks: Tracks) {
            reportUnplayableVideoTrack(tracks)
            reportSilentAudio(tracks)
            readAutoVideoRotation(tracks)
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
            // A rotation key must never cost the user the film either. Checked
            // before startup for the same reason: the turn can fail during the
            // startup window, and the film without it is the one that was about
            // to play.
            if (recoverFromTurnFailure(error)) return
            // The audio ROUTE must never cost the user the film. Checked before
            // startup for the reason the turn is: this fires during the startup
            // window — the picture had already reached first frame when it was
            // found — and the film with its audio decoded is the one that was
            // about to play.
            if (recoverFromAudioOutputRefusal(error)) return
            // Startup is a separate, short transaction. It must not inherit the
            // long steady-state recovery policy or hide format/decoder failures.
            startupCallbacks?.let { callbacks ->
                startupCallbacks = null
                recordPlaybackError(error)
                callbacks.onError(error, faultDetail(error, decodeCompressedAudio))
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
            // hand off to the diagnosis UI. The detail is read HERE, while
            // [decodeCompressedAudio] still says whether the one sink rebuild this
            // cast is allowed has already been spent — which is the whole difference
            // between a refusal the app answered and one it cannot.
            recordPlaybackError(error)
            playbackFailureListener?.invoke(error, faultDetail(error, decodeCompressedAudio))
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
        pendingRecovery.cancel()
        subtitleFailureState.recordRollback()
        subtitleDropped = true
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
            }.onFailure {
                // Failing closed below is right — an unresolvable period cannot be told
                // from one belonging to another cast — but it costs a film that IS
                // decoding the whole 18 s startup budget under a screen saying it is
                // starting, so it may not also be silent.
                FlickLog.w("player", "firstFrame period unresolved; gate fails closed")
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
            if (isCurrentExternalSubtitleLoad(loadEventInfo)) {
                subtitleFailureState.recordLoadFailure()
                return
            }
            // Every other load error used to be dropped here, which is why nothing could
            // say how much of the ~100 s retry budget a stall had spent. Counting is all
            // that happens — re-preparing eagerly would stall a film that is fine.
            instrumentation.mediaLoadErrorCount++
        }

        override fun onLoadCompleted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
        ) {
            // The one short text retry may succeed; don't let its earlier error
            // make a later, unrelated media error drop a now-healthy subtitle.
            if (isCurrentExternalSubtitleLoad(loadEventInfo)) {
                subtitleFailureState.recordLoadSuccess()
                // The watchdog still asks which ATTEMPT this was, because that is the one
                // question a generation gate exists to answer. Attribution above no longer
                // does, and the two are not the same question.
                val attemptToken = subtitleReloadAttemptToken(eventTime)
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

    /** Media3's two spellings of the same load, put to [isExternalSubtitleLoad]. */
    private fun isCurrentExternalSubtitleLoad(loadEventInfo: LoadEventInfo): Boolean =
        isExternalSubtitleLoad(
            currentSubtitleUrl = currentSubtitle?.url,
            dataSpecUri = loadEventInfo.dataSpec.uri.toString(),
            eventUri = loadEventInfo.uri.toString(),
        )

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
            val heldMs = SystemClock.elapsedRealtime() - instrumentation.currentRebufferStartMs
            instrumentation.cumulativeRebufferMs += heldMs
            instrumentation.currentRebufferStartMs = 0L
            // How long it held is the number that decides whether a stall was a blip or
            // the failure this app is a thesis against, and it is knowable only here.
            FlickLog.w(
                "player",
                "rebuffer end heldMs=$heldMs n=${instrumentation.rebufferCount} " +
                    "cumulativeMs=${instrumentation.cumulativeRebufferMs}",
            )
        }
    }

    /**
     * Frames released to the surface, or 0 before a video renderer exists.
     *
     * `ensureUpdated` is required because the counters are written on the playback thread
     * and read here on the main one; without it this reads a value the writer has not
     * flushed, which for a counter watched for MOVEMENT is the one error that matters.
     */
    private fun renderedFrames(exo: ExoPlayer?): Long {
        val counters = exo?.videoDecoderCounters ?: return 0L
        counters.ensureUpdated()
        return counters.renderedOutputBufferCount.toLong()
    }

    // --- Frozen picture -------------------------------------------------------

    /**
     * Watch the one counter that can tell a picture this app never painted from a picture
     * it painted and something beneath it discarded — see [pictureVerdict] for what the
     * verdict may and may not claim.
     *
     * A TURNED film is deliberately skipped: it already has a per-frame heartbeat and a
     * hand-back of its own in [TurnWatchdog], and two watchdogs racing over one picture
     * would spend the turn's one fallback on a re-prepare it did not ask for.
     */
    private fun watchPictureProgress(exo: ExoPlayer?, rendered: Long, positionMs: Long) {
        if (exo == null || currentUrl == null || surfaceTurn.isTurned) {
            resetPictureProgress()
            return
        }
        if (rendered > 0L) pictureEverRendered = true
        val advancing = lastSampledPositionMs in 0 until positionMs
        val moved = rendered != lastRenderedFrames
        val ready = exo.playbackState == Player.STATE_READY
        val playing = exo.isPlaying
        lastRenderedFrames = rendered
        lastSampledPositionMs = positionMs
        if (moved || !ready || !playing) {
            // Samples spent paused, buffering or ended are not counted against the film
            // that resumes out of them: a two-minute pause would otherwise arrive at the
            // threshold on its first playing sample and spend the film's one recovery on
            // a picture that was never stuck.
            //
            // [pictureRePrepared] is deliberately NOT handed back here. A re-prepare
            // rebuilds the renderers and reallocates their counters, so the attempt
            // itself always reads as the picture moving — and handing the latch back on
            // a picture that recovers for a few frames each time would make a dying one
            // an endless re-prepare instead of one attempt and an answer.
            frozenPictureSamples = 0
            return
        }
        frozenPictureSamples++
        val verdict = pictureVerdict(
            frozenSamples = frozenPictureSamples,
            ready = ready,
            playing = playing,
            positionAdvancing = advancing,
            hasRenderedAFrame = pictureEverRendered,
            framesExpected = framesAreExpected(exo.videoFormat?.frameRate ?: Format.NO_VALUE.toFloat()),
            rePrepared = pictureRePrepared,
        )
        when (verdict) {
            PictureVerdict.HEALTHY -> Unit
            PictureVerdict.REPREPARE -> {
                FlickLog.w(
                    "player",
                    "pictureFrozen samples=$frozenPictureSamples renderedFrames=$rendered " +
                        "clockAdvancing=$advancing; rebuilding the player once",
                )
                pictureRePrepared = true
                frozenPictureSamples = 0
                lastGoodPositionMs = positionMs
                pendingRecovery.cancel()
                // stop() first, and that is the whole recovery: prepare() returns
                // immediately unless the player is IDLE, so without it this is a seek to
                // the position the picture is already stuck at — a codec flush, not the
                // decoder rebuild a wedged renderer needs. The scheduleRecovery path this
                // imitates runs from the post-error IDLE state, where prepare() rebuilds
                // on its own.
                exo.stop()
                exo.seekTo(positionMs)
                exo.prepare()
                exo.playWhenReady = true
            }
            PictureVerdict.ANNOUNCE -> {
                resetPictureProgress()
                FlickLog.e("player", "pictureStopped renderedFrames=$rendered; ending the cast")
                // UNKNOWN is the honest wire answer: this TV genuinely cannot explain it
                // in the shared vocabulary, and the vocabulary is frozen. The detail is
                // what gives the screen its sentence, and it never leaves this device.
                val error = PlaybackException(
                    "picture stopped",
                    null,
                    PlaybackException.ERROR_CODE_UNSPECIFIED,
                )
                recordPlaybackError(error)
                // On the next main-thread turn, for the reason every other recovery here
                // takes one: the listener ends the cast, which stops this very player,
                // and this is running inside the sampling read of it.
                recoveryHandler.post {
                    playbackFailureListener?.invoke(error, ReceiverFaultDetail.PictureStopped)
                }
            }
        }
    }

    /** A new film — or no film — is judged on its own frames. */
    private fun resetPictureProgress() {
        lastRenderedFrames = -1L
        lastSampledPositionMs = -1L
        frozenPictureSamples = 0
        pictureEverRendered = false
        pictureRePrepared = false
    }

    // --- Unplayable video -----------------------------------------------------

    /**
     * One report per media item. `onTracksChanged` fires again for every text-track
     * change and for an in-place subtitle reload, and a capability verdict must not be
     * re-raised over a diagnosis screen the viewer is already reading.
     */
    private var videoShortfallReported = false

    /** One report per media item, for the same reason as [videoShortfallReported]. */
    private var silentAudioReported = false

    /**
     * The film carries audio and none of it was selected, so it will play silent.
     *
     * Said out loud, and nothing more than said. This is NOT routed like
     * [reportUnplayableVideoTrack]: a film with no picture is not worth watching and
     * has to end the cast, while a film with no sound is still the film, and ending
     * it would take away something the viewer can see. So the reading is published
     * for the screen and offered to the phone, and neither consumer may turn it
     * into a playback decision. The observed case is DTS on the verified TV, which
     * has no DTS decoder and, on a Bluetooth route, no passthrough either — nothing
     * this app can do restores it.
     *
     * The screen is given the reading and owns the words; the log keeps the format
     * exactly as the container declared it, because a diagnostician reading it is
     * the one reader who wants the raw value rather than the safe one.
     */
    private fun reportSilentAudio(tracks: Tracks) {
        if (silentAudioReported) return
        var present = false
        var selected = false
        var mimeType: String? = null
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (index in 0 until group.length) {
                present = true
                if (group.isTrackSelected(index)) selected = true
                if (mimeType == null) mimeType = group.getTrackFormat(index).sampleMimeType
            }
        }
        if (!present || selected) return
        silentAudioReported = true
        FlickLog.w(
            "player",
            "audioSilent mime=${mimeType ?: SILENT_AUDIO_MIME_UNKNOWN} reason=noSupportedAudioTrack",
        )
        val reading = silentAudioMimeReading(mimeType)
        silentAudioMimeType = reading
        currentMediaId?.let { silentAudioListener?.invoke(it, reading) }
    }

    /**
     * Turn "the video track was not selected" into the terminal failure Media3 never
     * raises for it — see [videoTrackShortfall].
     *
     * Routed exactly like [Player.Listener.onPlayerError] except that the transient
     * recovery path is skipped outright: no amount of re-preparing gives this TV a
     * decoder it does not have, and the 2/4/8/15 s recovery budget would only spend
     * 29 s before showing the same answer.
     *
     * It is deliberately NOT routed through the turn's fallback, which the effects
     * graph needed: a shortfall is `getTrackSupport`'s answer, decided by the
     * codec's own capability check, and nothing about which view the frames are
     * presented in enters it. Handing a genuinely unplayable film back "as filed"
     * first would spend a re-prepare out of the startup budget and show the
     * viewer a note about orientation before the real diagnosis.
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
        FlickLog.w(
            "player",
            "videoUnplayable shortfall=$shortfall mime=${mimeType ?: "unknown"} " +
                "support=${supports.joinToString(",")} decoderPolicy=hardwareOnly",
        )
        videoShortfallReported = true
        val error = PlaybackException(
            "video track unplayable",
            UnplayableVideoTrackException(shortfall),
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        )
        recordPlaybackError(error)
        startupCallbacks?.let { callbacks ->
            startupCallbacks = null
            callbacks.onError(error, ReceiverFaultDetail.None)
            return
        }
        playbackFailureListener?.invoke(error, ReceiverFaultDetail.None)
    }

    // --- Picture orientation -------------------------------------------------

    /**
     * Judge the container's rotation against the rest of the file, once per
     * distinct answer.
     *
     * `Tracks` is the earliest place the discriminating evidence exists at all —
     * the audio and text tracks are not visible to a video renderer, and they are
     * what tell a released title from a phone recording. It is delivered on the
     * main thread, and in practice it arrives before the first frame: the
     * playback thread publishes the selection in an earlier pass than the one
     * that can produce a decoded frame, and both reach this thread through the
     * same Looper queue. A verdict that lands late costs a re-buffer rather than
     * a wrong answer.
     */
    private fun readAutoVideoRotation(tracks: Tracks) {
        val exo = player ?: return
        val shape = mediaShapeFrom(
            tracks = tracks,
            durationMs = exo.duration.takeIf { it != C.TIME_UNSET && it > 0L },
            // We attach exactly one, and it is not evidence about the file.
            sideloadedTextTracks = if (currentSubtitle != null) 1 else 0,
        )
        // The two facts every turn is decided from, kept off the playback thread
        // and off the format callbacks: which way the container says the frames
        // lie, and what leaving the video layer would cost their colour.
        shape.video?.let { video ->
            filmContainerRotationDegrees = video.rotationDegrees
            filmColour = pictureColourOf(video.sampleMimeType, video.colorTransfer)
        }
        val auto = autoRotation(shape)
        // A re-prepare publishes Tracks.EMPTY before the new selection arrives, and
        // "there is no picture yet" is not a verdict about the film. Reading it as
        // one would clear the correction, re-prepare to clear it, and loop.
        // A new cast is reset explicitly instead.
        if (auto.verdict == AutoRotationVerdict.NoVideoTrack) return
        // Published here rather than beside the degrees below, because the case the
        // hint exists for is the one this function does NOT act on: a portrait
        // picture the evidence rule left alone changes no degrees at all.
        val hint = orientationHintFor(shape, auto, videoRotation)
        if (hint != orientationHint) {
            orientationHint = hint
            FlickLog.i("player", "orientationHint=${hint ?: "none"} verdict=${auto.verdict}")
        }
        val autoChanged = auto.extraDegrees != autoVideoRotationDegrees
        if (autoChanged) {
            autoVideoRotationDegrees = auto.extraDegrees
            FlickLog.i(
                "player",
                "autoRotation extraDegrees=${auto.extraDegrees} verdict=${auto.verdict}",
            )
        }
        // [resolvesPictureTurn] carries the rule and the reason. The first delivery
        // resolves whatever the choice is, because an explicit turn taken before
        // the tracks landed was resolved against a container of 0 — the wrong film;
        // [applyVideoRotation] reads the choice itself and declines when both the
        // surface and the decoder already hold it.
        if (
            !resolvesPictureTurn(
                alreadyResolved = pictureTurnResolvedForFilm,
                autoChanged = autoChanged,
                choiceIsAuto = videoRotation == VideoRotation.Auto,
            )
        ) {
            return
        }
        pictureTurnResolvedForFilm = true
        applyVideoRotation()
    }

    /** The mechanism, the numbers and the cost for a turn of [extraDegrees] on this film. */
    private fun currentPictureTurn(extraDegrees: Int): PictureTurn = pictureTurnFor(
        containerDegrees = filmContainerRotationDegrees,
        extraDegrees = extraDegrees,
        colour = filmColour,
        turnUnavailable = turnUnavailableForFilm,
    )

    /**
     * Take the viewer's choice, or Auto's verdict, to whichever mechanism can
     * actually carry it — see [pictureTurnFor] for which, and why.
     *
     * Two independent things can be owed, and the common case owes only the first:
     *
     *  1. **The video surface.** Publishing [surfaceTurn] is what moves the film
     *     onto a `TextureView` and puts the turn in its matrix. The player is not
     *     rebuilt and the media is not re-fetched: `MediaCodecVideoRenderer.setOutput`
     *     hands a live codec its new surface through `MediaCodec.setOutputSurface`,
     *     and re-initialises the codec only where that call is unavailable — the
     *     worst case is a decoder restart inside one film, never a re-prepare and
     *     never a re-buffer of bytes off the LAN. And the case that matters most:
     *     CHANGING a turn already in force is a new matrix on a view and nothing
     *     else at all, because the surface does not move for it.
     *  2. **The decoder's configuration.** `MediaFormat.KEY_ROTATION` is read once
     *     at codec configuration, so a change is only real after the codec has
     *     been configured again, and re-preparing the same player is what does
     *     that. Media3 agrees from the other side: `MediaCodecInfo.canReuseCodec`
     *     names a rotation change as a discard reason.
     *
     * The second is owed far less often than the command changes, and that is the
     * whole reason the question is "does the DECODER still owe this a
     * re-prepare?" rather than "did the commanded number move?". A turn asserted
     * on an ordinary film hands the surface a 90 and leaves the codec at the 0 it
     * already had. [VideoRotationOverride] is what can tell those apart, and this
     * is the only caller allowed to ask.
     *
     * The command is recorded before the question, because the question is about
     * the command; and it is marked carried either by the re-prepare that goes out
     * or, when none is owed, by the decoder that is already configured for it.
     */
    private fun applyVideoRotation(): Boolean {
        val exo = player ?: return false
        if (currentUrl == null) return false
        val extraDegrees = videoRotation.extraDegrees ?: autoVideoRotationDegrees
        val turn = currentPictureTurn(extraDegrees)
        turnNote = turn.note
        rotationOverride.commandTurn(extraDegrees, viaView = turn.mechanism == TurnMechanism.View)
        val owedRePrepare = rotationOverride.needsDecoderReconfigure()
        // Marked before anything else can decline: nothing is left for a
        // re-prepare to deliver, so the command is carried the moment it is
        // recorded, and a repeat of it must not read as one nobody carried.
        if (!owedRePrepare) rotationOverride.markCarried()
        val surfaceMoves = surfaceTurn.degrees != turn.viewDegrees
        if (!owedRePrepare && !surfaceMoves) return false
        if (surfaceMoves) engageSurfaceTurn(exo, turn.viewDegrees)
        val rePrepared = if (owedRePrepare) rePrepareForRotation() else false
        if (rePrepared) rotationOverride.markCarried()
        FlickLog.i(
            "player",
            "turnMechanism via=${if (turn.viewDegrees != 0) "view" else "decoder"} " +
                "viewDegrees=${turn.viewDegrees} decoderDegrees=${turn.decoderDegrees} " +
                "colour=$filmColour note=${turnNote ?: "none"} rePrepared=$rePrepared",
        )
        return rePrepared || surfaceMoves
    }

    /**
     * Move the picture onto the surface that can carry [viewDegrees], or off it.
     *
     * The frame listener and its deadline are installed with the turn and removed
     * with it, so a film nobody has turned never gets a per-frame callback at all
     * — which is what keeps the ordinary path identical to having no rotation
     * feature. The picture's shape is sampled here rather than remembered because
     * the transform is derived from it; a shape that changes later arrives through
     * `onVideoSizeChanged`.
     */
    private fun engageSurfaceTurn(exo: ExoPlayer, viewDegrees: Int) {
        cancelTurnDeadline()
        if (viewDegrees == 0) {
            if (surfaceTurn.isTurned) exo.clearVideoFrameMetadataListener(turnFrameListener)
            // The turn goes, the surface stays — see [SurfaceTurn.onTexture]. The
            // picture's shape is kept with it, because the matrix still has to fit the
            // texture into a player that is RESIZE_MODE_FILL. A film that never left the
            // video layer has nothing to keep and goes back to NONE.
            surfaceTurn = if (surfaceTurn.onTexture) {
                surfaceTurn.copy(degrees = 0)
            } else {
                SurfaceTurn.NONE
            }
            return
        }
        val size = exo.videoSize
        surfaceTurn = SurfaceTurn(
            degrees = viewDegrees,
            pictureWidthPx = size.width,
            pictureHeightPx = size.height,
            pixelWidthHeightRatio = size.pixelWidthHeightRatio,
            onTexture = true,
        )
        exo.setVideoFrameMetadataListener(turnFrameListener)
        armTurnDeadline(exo)
    }

    /**
     * Watch a turn for as long as it is in force.
     *
     * Deliberately not one-shot: the deadline re-arms after every verdict, because
     * the failure that hid the longest was a picture that rendered its first frame
     * and then froze — which a watchdog that cancels itself on that frame cannot
     * see. [TURN_DEADLINE_MS] is the whole judgement and [TurnWatchdog] is the
     * state machine. A deadline that comes due while the film is paused, finished
     * or refilling its buffer re-arms rather than firing, and one that comes due
     * against a player that has since been replaced is dropped.
     */
    private fun armTurnDeadline(exo: ExoPlayer) {
        cancelTurnDeadline()
        val generation = turnWatchdog.engage(SystemClock.elapsedRealtime())
        lateinit var deadline: Runnable
        deadline = Runnable {
            if (pendingTurnDeadline !== deadline) return@Runnable
            pendingTurnDeadline = null
            if (player !== exo) {
                turnWatchdog.disengage()
                return@Runnable
            }
            val verdict = turnWatchdog.consumeDeadline(
                generation = generation,
                nowMs = SystemClock.elapsedRealtime(),
                renderingExpected = framesExpectedFrom(
                    playWhenReady = exo.playWhenReady,
                    playbackState = exo.playbackState,
                    provenOnce = turnWatchdog.hasRenderedAFrame,
                ),
            )
            when (verdict) {
                TurnWatchdog.Verdict.Stale -> Unit
                TurnWatchdog.Verdict.NotYet, TurnWatchdog.Verdict.Alive -> {
                    pendingTurnDeadline = deadline
                    recoveryHandler.postDelayed(deadline, TURN_DEADLINE_MS)
                }
                TurnWatchdog.Verdict.NoFrames ->
                    failTurn("reason=turnWatchdog deadlineMs=$TURN_DEADLINE_MS")
            }
        }
        pendingTurnDeadline = deadline
        recoveryHandler.postDelayed(deadline, TURN_DEADLINE_MS)
    }

    private fun cancelTurnDeadline() {
        pendingTurnDeadline?.let { recoveryHandler.removeCallbacks(it) }
        pendingTurnDeadline = null
        turnWatchdog.disengage()
    }

    /**
     * Re-prepare the LIVE player where the film already is, which is the only way
     * to change what the codec was configured with. Everything the cast owns
     * survives — the ExoPlayer instance, the MediaSession, the sideloaded subtitle
     * and the track selection — so the change costs a re-buffer and nothing else,
     * exactly as [reloadInPlace] does. It is spent only when the decoder's own
     * rotation has to move; a turn the video surface takes over from a codec that
     * was already at 0 never reaches here.
     *
     * A subtitle attach still inside its 12 s watchdog window forfeits that
     * window: the rebuilt item carries no attempt tag, so the deadline could only
     * fire against a subtitle that is fine and drop it.
     *
     * A cast that has not reached its first frame is told, because that window is
     * budgeted: [StartupCallbacks] is non-null for exactly as long as startup is
     * outstanding, so reading it here IS the condition, not a check of one.
     */
    private fun rePrepareForRotation(): Boolean {
        val exo = player ?: return false
        val url = currentUrl ?: return false
        cancelSubtitleReloadDeadline()
        // A recovery queued against the old item would re-prepare on top of this.
        pendingRecovery.cancel()
        val resumeMs = exo.currentPosition.coerceAtLeast(0L).takeIf { it > 0L }
            ?: savedPositionMs.coerceAtLeast(0L)
        val resumePlayWhenReady = exo.playWhenReady
        savedPositionMs = resumeMs
        pendingPlayWhenReady = resumePlayWhenReady
        lastGoodPositionMs = resumeMs
        stableReadySinceMs = 0L
        exo.setMediaItem(
            mediaItemFor(url, currentMediaId, currentSubtitle),
            /* resetPosition = */ false,
        )
        if (resumeMs > 0L) exo.seekTo(resumeMs)
        exo.prepare()
        exo.playWhenReady = resumePlayWhenReady
        startupCallbacks?.onRotationRePrepare?.invoke()
        return true
    }

    /**
     * The audio output refused the track — hand the film back with its audio
     * decoded instead of passed through.
     *
     * See [AudioOutputPolicy] for what is being refused and why. The lever is a
     * property of the `AudioSink`, fixed when the sink is built, so unlike every
     * other recovery here this one cannot re-prepare the player it is holding: it
     * has to build a new one. That happens on the next main-thread turn, because
     * releasing a player from inside its own listener callback is not a thing to
     * do, and in its own [PendingWork] slot so no other canceller can collect it.
     *
     * Once per cast. A refusal that survives the rebuild is not about passthrough,
     * and falls through to the ordinary diagnosis rather than looping.
     */
    private fun recoverFromAudioOutputRefusal(error: PlaybackException): Boolean {
        if (pendingAudioSinkRebuild.isPending) {
            FlickLog.w("player", "audio rebuild absorbed code=${error.errorCodeName}")
            return true
        }
        if (!AudioOutputPolicy.shouldDecodeInstead(error.errorCode, decodeCompressedAudio)) {
            return false
        }
        FlickLog.w(
            "player",
            "audioOutputRefused code=${error.errorCodeName}; rebuilding sink to decode",
        )
        instrumentation.audioSinkRebuildCount++
        audioRestarted = true
        decodeCompressedAudio = true
        pendingRecovery.cancel()
        pendingAudioSinkRebuild.post { rebuildForDecodedAudio() }
        return true
    }

    /**
     * Swap in a player whose sink will not passthrough, keeping the film, the
     * position, the subtitle and the play/pause state.
     *
     * Mirrors the startup adoption's ordering — bind the session to the new player
     * before releasing the old one, so a physical media key cannot reach a released
     * instance — and mirrors [rePrepareForRotation]'s position bookkeeping.
     * [rotationOverride] is deliberately NOT reset: this is the same film
     * continuing, and the viewer's turn survives the swap.
     */
    private fun rebuildForDecodedAudio() {
        val previous = player ?: return
        val url = currentUrl ?: return
        cancelSubtitleReloadDeadline()
        cancelTurnDeadline()
        val resumeMs = previous.currentPosition.coerceAtLeast(0L).takeIf { it > 0L }
            ?: savedPositionMs.coerceAtLeast(0L)
        val resumePlayWhenReady = previous.playWhenReady
        savedPositionMs = resumeMs
        pendingPlayWhenReady = resumePlayWhenReady
        lastGoodPositionMs = resumeMs
        stableReadySinceMs = 0L

        val fresh = createPlayer()
        bindMediaSession(fresh)
        player = fresh
        previous.removeListener(playerListener)
        previous.removeAnalyticsListener(analyticsListener)
        previous.release()

        fresh.setMediaItem(mediaItemFor(url, currentMediaId, currentSubtitle))
        if (resumeMs > 0L) fresh.seekTo(resumeMs)
        fresh.prepare()
        fresh.playWhenReady = resumePlayWhenReady
        FlickLog.i("player", "audioSinkRebuilt decode=compressed resumeMs=$resumeMs")
    }

    /**
     * The turn failed on this film — give the viewer the film back.
     *
     * A decoder that will not configure against a `SurfaceTexture` raises a
     * decoder failure, and that would otherwise end the cast on a diagnosis screen
     * because the viewer pressed a rotation key. It says nothing about the film's
     * playability untouched, which is how it was playing a moment ago.
     *
     * This is only the errors media3 chose to raise, which is the narrower half of
     * the net; [failTurn] carries the rest.
     */
    private fun recoverFromTurnFailure(error: PlaybackException): Boolean {
        // The hand-back for this film is already queued and is itself a full
        // re-prepare from the last good position. Whatever media3 raises in the
        // one main-thread turn between the latch and that hand-back belongs to the
        // condemned turn, so it must not spend a recovery attempt on the player
        // still carrying it, and must not reach the diagnosis screen ahead of the
        // film being handed back — which is the whole point of the latch.
        if (pendingTurnFallback.isPending) {
            FlickLog.w(
                "player",
                "turn absorbed code=${error.errorCodeName}; fallback already queued",
            )
            return true
        }
        if (!surfaceTurn.isTurned || turnUnavailableForFilm) return false
        if (!isTurnFailure(error)) return false
        return failTurn("reason=playerError code=${error.errorCodeName}")
    }

    /**
     * Latch this film's turn as unusable and hand the film back, whatever said so.
     *
     * Two things can say it: a `PlaybackException` media3 raised, and a turned
     * picture that went a whole [TURN_DEADLINE_MS] without a frame. They differ
     * only in what they name in the log — the film as filed is the one that was
     * playing in both, and the recovery is identical.
     *
     * Latched per film, so this is one attempt and one fallback rather than a
     * loop, and the fallback runs on the next main-thread turn: re-preparing a
     * player from inside its own listener callback is not a thing to do. It goes
     * in its own slot, because a hand-back the generic recovery canceller can
     * collect is no hand-back at all; see [PendingWork].
     */
    private fun failTurn(reason: String): Boolean {
        if (turnUnavailableForFilm) return false
        turnUnavailableForFilm = true
        // Keyed on the URL and not the media id: the session builds that from the
        // cast's generation, so it is a different string on every retry of the
        // same film — which is the loop this memory exists to break.
        filmsWithoutTurn.remember(currentUrl)
        cancelTurnDeadline()
        FlickLog.w("player", "turn failed $reason; showing this film as filed")
        pendingRecovery.cancel()
        pendingTurnFallback.post { applyVideoRotation() }
        return true
    }

    /** A new film is judged on its own container; it never inherits a choice. */
    private fun resetVideoRotation() {
        videoRotation = VideoRotation.Auto
        autoVideoRotationDegrees = 0
        orientationHint = null
        turnNote = null
        filmContainerRotationDegrees = 0
        filmColour = PictureColour.Sdr
        // The listener goes back off the player the new film may well reuse: the
        // ordinary path is defined by the absence of a per-frame callback.
        if (surfaceTurn.isTurned) player?.clearVideoFrameMetadataListener(turnFrameListener)
        surfaceTurn = SurfaceTurn.NONE
        // Not simply false: a film this session already condemned keeps its
        // verdict, so a startup retry of THAT film does not re-engage the turn
        // that wedged it and fail identically for as long as the viewer keeps
        // trying. Every caller sets [currentUrl] to the film being judged — or to
        // null, which is remembered about nothing — before reaching here.
        turnUnavailableForFilm = filmsWithoutTurn.remembers(currentUrl)
        pictureTurnResolvedForFilm = false
        cancelTurnDeadline()
        pendingTurnFallback.cancel()
        rotationOverride.reset()
    }

    /**
     * The panel's choice. True when a re-prepare was issued for it. Main-thread only.
     *
     * A repeat of the choice already recorded is deliberately NOT refused here.
     * The recorded choice is what the panel draws, the decoder's configuration is
     * what the viewer sees, and those two can disagree; [applyVideoRotation] is
     * the only place that can tell, so it is the only place that decides.
     */
    fun setVideoRotation(choice: VideoRotation): Boolean {
        // Checked before the choice is recorded, not after: these are the only two
        // ways the re-prepare below can decline, and a cast that ended under an open
        // panel would otherwise leave the cell drawn as selected over a picture that
        // never turned.
        if (player == null || currentUrl == null) {
            FlickLog.i("player", "rotation choice=${choice.name} outcome=noCast")
            return false
        }
        videoRotation = choice
        val extraDegrees = choice.extraDegrees ?: autoVideoRotationDegrees
        val carried = applyVideoRotation()
        // The receiver's half of the answer: what was asked for, what that resolved
        // to in degrees, which mechanism owns it, what that costs the picture, and
        // whether anything had to be done or it was already there.
        // `rotationToDecoder` is the far half — the two together say whether a turn
        // the viewer chose reached the picture.
        FlickLog.i(
            "player",
            "rotation choice=${choice.name} extraDegrees=$extraDegrees " +
                "via=${if (rotationOverride.commandedViaView) "view" else "decoder"} " +
                "viewDegrees=${surfaceTurn.degrees} note=${turnNote ?: "none"} " +
                "carried=$carried decoderExtraDegrees=" +
                (rotationOverride.decoderExtraDegrees?.toString() ?: "none"),
        )
        return carried
    }

    /** WS `setRotation` with `degrees`: an explicit quarter turn, or nothing at all. */
    override fun setVideoRotationDegrees(degrees: Int) {
        val choice = VideoRotation.forExtraDegrees(degrees)
        if (choice == null) {
            // The wire already refuses anything off the quarter-turn grid, so this
            // is unreachable from a control frame and would otherwise be the one
            // rotation path that declines without saying so.
            FlickLog.i("player", "rotation ignored degrees=$degrees reason=offGrid")
            return
        }
        setVideoRotation(choice)
    }

    /**
     * WS `setRotation` with `auto`. [applyVideoRotation] reads
     * [autoVideoRotationDegrees], which `onTracksChanged` keeps at the verdict for
     * the film that is playing and a new cast resets — so this re-runs the reading
     * rather than restoring a remembered turn.
     */
    override fun setAutoVideoRotation() {
        setVideoRotation(VideoRotation.Auto)
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
    /**
     * The failures a turn can produce that the same film has already proved it
     * does not produce without one.
     *
     * They are all decoder codes, and that is the whole point: a `TextureView`'s
     * surface is backed by a `SurfaceTexture` rather than by the panel's own
     * buffer queue, and a TV decoder that will not configure against one — media3
     * carries a `c2.mtk.*` workaround for exactly that family of problems — fails
     * at initialization with nothing that names the turn as the cause. The
     * per-film latch is what keeps this from swallowing a genuine decoder
     * shortfall: the fallback plays the film untouched, and a second failure takes
     * the ordinary route.
     */
    private fun isTurnFailure(error: PlaybackException): Boolean = when (error.errorCode) {
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> true
        else -> false
    }

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

    /**
     * The ride-out this device bought at the rate it is actually receiving.
     *
     * With no estimate yet the planned peak stands in, which is the SHORTEST honest
     * answer this budget can give — never zero, which would escalate the plate on a
     * stall nothing had measured.
     */
    private fun protectionSeconds(): Int {
        val bitrate = bandwidthMeter.bitrateEstimate.takeIf { it > 0L } ?: PLANNED_PEAK_BITRATE_BPS
        return bufferBudget.protectionSecondsAt(bitrate).toInt()
    }

    /** Post a delayed re-prepare of the current player at [lastGoodPositionMs]. */
    private fun scheduleRecovery(attempt: Int) {
        val delayMs = RECOVERY_BACKOFF_MS[(attempt - 1).coerceIn(0, RECOVERY_BACKOFF_MS.lastIndex)]
        pendingRecovery.post(delayMs) {
            val exo = player ?: return@post
            exo.seekTo(lastGoodPositionMs)
            exo.prepare()
            exo.playWhenReady = true
        }
    }

    // --- Player construction -------------------------------------------------

    /**
     * Every player this class builds is the same player, and the picture turn is
     * no longer a construction concern at all: the turn lives on the view that
     * presents the frames, and media3 accepts a new output surface on a live
     * instance. Nothing here knows whether a film is turned.
     */
    private fun createPlayer(): ExoPlayer {
        // The allocator holds its segments on the Java heap, so every number below
        // is a fraction of THIS device's grant rather than of the one the tuning was
        // measured on — see [bufferBudgetFor]. Read at construction because the
        // grant is a property of the process, and logged because the buffer is the
        // whole anti-buffering thesis and a silently shrunken one must be visible.
        val budget = bufferBudget
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
        //
        // ExoPlayer takes one renderers factory, and two features customize the
        // video renderer: the rotation handed to the decoder and the A/V nudge's
        // shift of the render clock. [FlickRenderersFactory] carries both, at the
        // two different levels they hook. With no rotation asserted and a zero
        // delay it builds what DefaultRenderersFactory builds.
        val renderersFactory = FlickRenderersFactory(
            appContext,
            rotationOverride,
            audioDelayShift,
            decodeCompressedAudio,
        )
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
        // A reload is the viewer asking again, so the drop this is about is no longer
        // the current state of the film — the rollback is one-shot per attempt.
        subtitleDropped = false
        // A capability verdict belongs to the file it was reached about: the next cast
        // gets to be judged on its own tracks. This is every load and reload path.
        videoShortfallReported = false
        silentAudioReported = false
        silentAudioMimeType = null
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
            // The turn survives with the film — [surfaceTurn] is state and the view
            // carrying it is still composed — but the frame listener and the
            // deadline belonged to the instance that was released, so a restored
            // turn is watched from scratch on a decoder and a surface that are both
            // new. The decoder is configured from the command that survived too:
            // [rotationOverride] outlives every player.
            if (surfaceTurn.isTurned) {
                exo.setVideoFrameMetadataListener(turnFrameListener)
                armTurnDeadline(exo)
            }
        }
    }

    /** Save position + intent, then release the decoder (called on ON_STOP). */
    fun onStop() {
        cancelSubtitleReloadDeadline()
        // The decoder this was watching is about to be released with its player,
        // so there is nothing left for the deadline to be evidence about; [onStart]
        // engages a fresh one if the film is still turned. The hand-back goes with
        // it: the latch survives, so a condemned film comes back as filed anyway
        // and there is nothing left for the fallback to take off.
        cancelTurnDeadline()
        pendingTurnFallback.cancel()
        pendingAudioSinkRebuild.cancel()
        val exo = player ?: run {
            releaseMediaSession()
            return
        }
        // Drop any queued recovery: it targets the player we're about to release;
        // onStart() re-prepares from savedPositionMs instead.
        pendingRecovery.cancel()
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
        cancelTurnDeadline()
        cancelPendingAudioDelayLine()
        pendingTurnFallback.cancel()
        pendingAudioSinkRebuild.cancel()
        pendingRecovery.cancel()
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
        cancelTurnDeadline()
        pendingRecovery.cancel()
        recoveryGateCount = 0
        lastGoodPositionMs = 0L
        stableReadySinceMs = 0L
        probeLatencyMs = 0L
        instrumentation.reset()
        resetPictureProgress()
        audioRestarted = false
        // [decodeCompressedAudio] is deliberately NOT reset here. What it records is
        // a property of this TV's current audio route, not of the film that found
        // it: once the output has refused a bitstream, every later film would fail
        // and rebuild the same way, so latching it for the process turns a hiccup on
        // every AC-3 film into one on the first. A receiver restart is the amnesty,
        // for the reason the sender's UnplayableMemory gives about persistence.
        pendingAudioSinkRebuild.cancel()
        resetAudioFormat()
        resetVideoRotation()
        resetSubtitleState(subtitle = null, mediaId = null)
        // The retained instance is reusable whatever the previous film did: a turn
        // was never built into it. [resetVideoRotation] has already taken the frame
        // listener back off it and put the picture on the ordinary surface.
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
        onError: (PlaybackException, ReceiverFaultDetail) -> Unit,
        onRotationRePrepare: () -> Unit,
    ) {
        firstFrameGate.arm(mediaId)
        startupCallbacks = StartupCallbacks(mediaId, onFirstFrame, onError, onRotationRePrepare)
        currentUrl = url
        savedPositionMs = 0L
        pendingPlayWhenReady = true
        cancelSubtitleReloadDeadline()
        cancelTurnDeadline()
        pendingRecovery.cancel()
        recoveryGateCount = 0
        instrumentation.reset()
        resetPictureProgress()
        audioRestarted = false
        // [decodeCompressedAudio] is deliberately NOT reset here. What it records is
        // a property of this TV's current audio route, not of the film that found
        // it: once the output has refused a bitstream, every later film would fail
        // and rebuild the same way, so latching it for the process turns a hiccup on
        // every AC-3 film into one on the first. A receiver restart is the amnesty,
        // for the reason the sender's UnplayableMemory gives about persistence.
        pendingAudioSinkRebuild.cancel()
        resetAudioFormat()
        resetVideoRotation()
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
            // The released player's playback thread can drain a queued format read
            // between the reset above and this call, and record it against the new
            // film's command. `release()` joins that thread, so this is the first
            // point at which no further write is possible — the previous film
            // cannot leave its decoder's turn behind for this one.
            rotationOverride.reset()
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
        pendingRecovery.cancel()
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
        resetPictureProgress()
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
    override fun setPlaybackFailureListener(listener: ((PlaybackException, ReceiverFaultDetail) -> Unit)?) {
        playbackFailureListener = listener
    }

    override fun setExternalSubtitleDroppedListener(
        listener: ((String, ExternalSubtitle) -> Unit)?,
    ) {
        externalSubtitleDroppedListener = listener
    }

    override fun setSilentAudioListener(listener: ((String, String) -> Unit)?) {
        silentAudioListener = listener
    }

    override fun stop() {
        clearStartupListener()
        pendingPlayWhenReady = false
        cancelSubtitleReloadDeadline()
        pendingRecovery.cancel()
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
        resetVideoRotation()
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

    /**
     * WS `setAudioDelay`: positive is audio heard later than the picture.
     *
     * Nothing is pushed into the player. The renderers read [audioDelayShift]
     * every tick, so the next rendered frame already carries the new offset —
     * there is no re-prepare, no seek and no re-buffer, and passthrough audio is
     * never touched.
     *
     * The wire range is the widest any TV may be asked for; how much of it THIS
     * one can carry is a property of the heap it was granted, so the shift is
     * clamped a second time here — see [AudioDelayPolicy.maxDelayMsFor]. Both
     * numbers are logged because a device that is capping is otherwise
     * indistinguishable from a phone that is not sending what its dial reads.
     */
    override fun setAudioDelay(delayMs: Int) {
        val commanded = AudioDelayPolicy.clamp(delayMs)
        val applied = commanded.coerceIn(-audioDelayCapMs, audioDelayCapMs)
        audioDelayShift.videoShiftUs = AudioDelayPolicy.videoShiftUs(applied)
        // The shift above is applied on the frame it arrived on, unchanged. Only the LINE
        // waits: the phone walks a drag to its target 25 times a second for as long as
        // 1.6 s, and the ring holds 200 entries, so a line per frame says one gesture
        // forty times and evicts the cast it was made during. Each frame cancels the one
        // before it, so what survives is where the nudge came to rest — which is the only
        // value a reader can act on anyway.
        cancelPendingAudioDelayLine()
        // The cap is captured with the values it clamped, not read again when the line
        // finally runs: a line whose three numbers came from two different instants
        // would be the one thing this log is read to rule out.
        val cap = audioDelayCapMs
        val line = Runnable {
            pendingAudioDelayLine = null
            FlickLog.i("player", "audioDelay ms=$applied commandedMs=$commanded capMs=$cap")
        }
        pendingAudioDelayLine = line
        recoveryHandler.postDelayed(line, AUDIO_DELAY_LINE_QUIET_MS)
    }

    private fun cancelPendingAudioDelayLine() {
        pendingAudioDelayLine?.let { recoveryHandler.removeCallbacks(it) }
        pendingAudioDelayLine = null
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

        val rendered = renderedFrames(exo)
        watchPictureProgress(exo, rendered, position)

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
            renderedFrames = rendered,
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
            audioSinkRebuildCount = instrumentation.audioSinkRebuildCount,
            mediaLoadErrorCount = instrumentation.mediaLoadErrorCount,
            bufferingPlate = bufferingPlate(
                stallMs = liveRebufferMs,
                protectionSeconds = protectionSeconds(),
                recoveryAttempts = recoveryGateCount,
            ),
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

        // How long the audio-delay line waits for a walked nudge to stop moving. The
        // phone walks one hop every 40 ms, so this must outlast a hop by enough that
        // scheduling jitter cannot look like the end of a walk; it matches the
        // renderer's own settle window so the pair of lines describes one gesture.
        const val AUDIO_DELAY_LINE_QUIET_MS = 250L

        // How long a turned picture may go without a frame before it is treated as
        // dead. Deliberately generous, because the two errors are not symmetric:
        // waiting too long costs a viewer who is already looking at a stalled
        // picture a few more seconds of it, while firing too early costs them the
        // turn for the WHOLE film — the fallback latch is per film and is never
        // retried.
        //
        // Sized on the most expensive honest silence, which is the engagement that
        // also owes the decoder a re-prepare: `bufferForPlaybackAfterRebufferMs` is
        // 4,765 ms of media on the verified tier, and that at the planned 100 Mbps
        // peak is ~60 MB to re-fetch over the LAN; the codec teardown, configure
        // and one byte-range round trip on top of it are what
        // `StartupDeadlinePolicy` budgets its 6 s for. Near eleven seconds of
        // honest worst case, so anything under this would fire on an ordinary slow
        // engagement. A turn that owes the decoder nothing — the common one — is a
        // surface swap and costs a frame interval, so it never comes near this.
        //
        // It is also bounded from above, which is why it is not larger still: an
        // engagement during startup has to be judged AND the film handed back has
        // to reach its own first frame, both inside the cast's 18 s budget plus the
        // one 6 s rotation extension. Off a resolution a second or two in, twelve
        // seconds leaves the hand-back the better part of ten.
        //
        // It is a repeating deadline, not a one-shot: the same number is what a
        // turned picture is allowed to go quiet for at any point in the film, and
        // what it is measured against once it has rendered is a player that says it
        // is READY and playing — see [framesExpectedFrom].
        const val TURN_DEADLINE_MS = 12_000L
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
