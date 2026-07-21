package com.flick.receiver.player

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import androidx.media3.common.VideoSize
import androidx.media3.common.util.StuckPlayerException
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.flick.receiver.util.FlickLog
import com.flick.receiver.util.WifiTelemetry

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
class PlayerController(context: Context) {

    private val appContext = context.applicationContext

    /** Shared across player rebuilds so bandwidth history and metrics survive backgrounding. */
    val bandwidthMeter: DefaultBandwidthMeter = DefaultBandwidthMeter.Builder(appContext).build()

    private val instrumentation = InstrumentationState()

    /** Compose-observable current player instance (null while released/stopped). */
    var player by mutableStateOf<ExoPlayer?>(null)
        private set

    private var currentUrl: String? = null
    private var pendingPlayWhenReady: Boolean = false
    private var savedPositionMs: Long = 0L

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
    private data class StartupCallbacks(
        val mediaId: String,
        val onFirstFrame: () -> Unit,
        val onError: (PlaybackException) -> Unit,
    )

    /** Non-null only from player adoption until the exact first video frame. */
    private var startupCallbacks: StartupCallbacks? = null
    private val firstFrameGate = FirstFrameGate()
    private var playbackFailureListener: ((PlaybackException) -> Unit)? = null

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

        override fun onPlayerError(error: PlaybackException) {
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
            val callbacks = startupCallbacks ?: return
            val mediaPeriodId = eventTime.currentMediaPeriodId
            val mediaId = if (mediaPeriodId == null) null else runCatching {
                val period = Timeline.Period()
                eventTime.currentTimeline.getPeriodByUid(mediaPeriodId.periodUid, period)
                val window = Timeline.Window()
                eventTime.currentTimeline.getWindow(period.windowIndex, window)
                window.mediaItem.mediaId
            }.getOrNull()
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
        // Generous 4K-sized allocator + load control. Time thresholds are
        // prioritized over the byte target so that, on a fast LAN, buffering is
        // driven by the min/max buffer *durations* rather than starving early.
        val allocator = DefaultAllocator(/* trimOnReset = */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
        val loadControl = DefaultLoadControl.Builder()
            .setAllocator(allocator)
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setTargetBufferBytes(TARGET_BUFFER_BYTES)
            .setBackBuffer(BACK_BUFFER_MS, /* retainBackBufferFromKeyframe = */ true)
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
        // tries). With the 15-180s buffer, ~100s of quiet capped-backoff retrying
        // rides out router blips, phone roams and brief peer-block episodes with
        // zero visible stall — every byte-range retry is a perfect resume. 4xx
        // (except 416 Range-Not-Satisfiable) fail fast so the diagnosis UI takes
        // over instead of hammering a dead/blocking endpoint.
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
                if (exception is RedirectRejectedException || exception is PlaybackHttpStatusException) {
                    return C.TIME_UNSET
                }
                if (exception is HttpDataSource.InvalidResponseCodeException) {
                    val code = exception.responseCode
                    if (code in 400..499 && code != 416) return C.TIME_UNSET
                }
                return minOf(1000L * (loadErrorInfo.errorCount + 1), MAX_LOAD_RETRY_DELAY_MS)
            }
        }

        val mediaSourceFactory = DefaultMediaSourceFactory(appContext)
            .setDataSourceFactory(httpDataSourceFactory)
            .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)

        // Hardware decoders only (no software/extension renderers) — the whole
        // point is to prove the TV decodes the original bytes in hardware.
        // Decoder fallback is OFF: if the primary (hardware) decoder fails to
        // init, we want a visible PlaybackException in the overlay, NOT a silent
        // drop to a software decoder that would mask the very failure this spike
        // exists to expose.
        val renderersFactory = DefaultRenderersFactory(appContext)
            .setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                val candidates = MediaCodecSelector.DEFAULT.getDecoderInfos(
                    mimeType,
                    requiresSecureDecoder,
                    requiresTunnelingDecoder,
                )
                if (!MimeTypes.isVideo(mimeType)) candidates else candidates.filter { info ->
                    HardwareDecoderPolicy.isHardwareVideoCodec(
                        name = info.name,
                        apiLevel = Build.VERSION.SDK_INT,
                        hardwareAccelerated = if (Build.VERSION.SDK_INT >= 29) info.hardwareAccelerated else null,
                    )
                }
            }
            .setEnableDecoderFallback(false)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

        return ExoPlayer.Builder(appContext, renderersFactory)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setBandwidthMeter(bandwidthMeter)
            .build()
            .also { exo ->
                exo.addListener(playerListener)
                exo.addAnalyticsListener(analyticsListener)
                // Restore the last commanded volume so it survives player rebuilds
                // (background/foreground) and the control channel stays authoritative.
                exo.volume = lastVolume
            }
    }

    // --- Lifecycle -----------------------------------------------------------

    /** Create the player if needed and restore any previous media (called on ON_START). */
    fun onStart() {
        if (player != null) return
        val exo = createPlayer()
        player = exo
        val url = currentUrl
        if (url != null) {
            exo.setMediaItem(MediaItem.fromUri(url))
            exo.prepare()
            if (savedPositionMs > 0L) exo.seekTo(savedPositionMs)
            exo.playWhenReady = pendingPlayWhenReady
        }
    }

    /** Save position + intent, then release the decoder (called on ON_STOP). */
    fun onStop() {
        val exo = player ?: return
        // Drop any queued recovery: it targets the player we're about to release;
        // onStart() re-prepares from savedPositionMs instead.
        cancelPendingRecovery()
        // Close any in-progress rebuffer window BEFORE removing listeners so the
        // decoder release doesn't leave currentRebufferStartMs non-zero and
        // corrupt cumulativeRebufferMs with backgrounded wall-clock time.
        closeRebufferWindow()
        savedPositionMs = exo.currentPosition.coerceAtLeast(0L)
        pendingPlayWhenReady = exo.playWhenReady
        exo.removeListener(playerListener)
        exo.removeAnalyticsListener(analyticsListener)
        exo.release()
        player = null
    }

    /** Terminal teardown (onDestroy / Compose onDispose). */
    fun release() {
        cancelPendingRecovery()
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
        cancelPendingRecovery()
        recoveryGateCount = 0
        lastGoodPositionMs = 0L
        stableReadySinceMs = 0L
        probeLatencyMs = 0L
        instrumentation.reset()
        val exo = player ?: createPlayer().also { player = it }
        exo.setMediaItem(MediaItem.fromUri(url))
        exo.prepare()
        exo.playWhenReady = true
    }

    /** Installs both startup callbacks before setting media or calling prepare. */
    fun playStartup(
        url: String,
        startMs: Long,
        mediaId: String,
        onFirstFrame: () -> Unit,
        onError: (PlaybackException) -> Unit,
    ) {
        firstFrameGate.arm(mediaId)
        startupCallbacks = StartupCallbacks(mediaId, onFirstFrame, onError)
        currentUrl = url
        savedPositionMs = 0L
        pendingPlayWhenReady = true
        cancelPendingRecovery()
        recoveryGateCount = 0
        instrumentation.reset()
        // A startup adoption always gets a fresh listener/renderer instance.
        // Releasing A before B is armed is a second fail-closed barrier against
        // a late A renderer callback being observed as B.
        player?.let { previous ->
            previous.removeListener(playerListener)
            previous.removeAnalyticsListener(analyticsListener)
            previous.release()
            player = null
        }
        val exo = createPlayer().also { player = it }
        exo.setMediaItem(MediaItem.Builder().setUri(url).setMediaId(mediaId).build())
        if (startMs > 0) exo.seekTo(startMs)
        exo.prepare()
        exo.playWhenReady = true
    }

    fun clearStartupListener() { startupCallbacks = null; firstFrameGate.clear() }

    /** Delivers the exact Media3 failure to the session while it is still actionable. */
    fun setPlaybackFailureListener(listener: ((PlaybackException) -> Unit)?) {
        playbackFailureListener = listener
    }

    fun stop() {
        clearStartupListener()
        pendingPlayWhenReady = false
        cancelPendingRecovery()
        stableReadySinceMs = 0L
        // Stop must be terminal for the media. Without clearing these, a later
        // ON_STOP/ON_START cycle (background then foreground) would re-capture a
        // still-true playWhenReady, see currentUrl != null in onStart(), and
        // silently re-prepare — re-allocating the buffer + decoder and resuming
        // playback under an Idle UI. Backgrounding DURING playback still resumes,
        // because that path never calls stop() (only onStop keeps currentUrl set).
        currentUrl = null
        savedPositionMs = 0L
        player?.let { exo ->
            exo.playWhenReady = false
            exo.stop()
            exo.clearMediaItems()
        }
    }

    /** Record the pre-flight probe round-trip so the overlay can surface it. */
    fun recordProbeLatency(latencyMs: Long) {
        probeLatencyMs = latencyMs
    }

    /** Seek relative to the current position, clamped to [0, duration). Main-thread only. */
    fun seekBy(deltaMs: Long) {
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
    fun resume() {
        if (currentUrl == null) return
        player?.let { it.playWhenReady = true }
    }

    /** WS `pause`. */
    fun pause() {
        player?.let { it.playWhenReady = false }
    }

    /** WS `seek`: absolute (optimistic target), clamped to [0, duration). */
    fun seekTo(posMs: Long) {
        val exo = player ?: return
        if (currentUrl == null) return
        val duration = exo.duration
        val target = posMs.coerceAtLeast(0L).let {
            if (duration != C.TIME_UNSET) it.coerceAtMost((duration - 1_000L).coerceAtLeast(0L)) else it
        }
        exo.seekTo(target)
    }

    /** WS `setVolume`: 0..1. Persisted so it survives player rebuilds. */
    fun setVolume(level: Float) {
        lastVolume = level.coerceIn(0f, 1f)
        player?.volume = lastVolume
    }

    /** Whether a media session is currently loaded (drives idle-vs-playing UI). */
    fun hasSession(): Boolean = currentUrl != null

    /**
     * The confirmed playback frame streamed to the phone at ~10 Hz. Cheap,
     * main-thread only (reads live ExoPlayer getters).
     */
    fun readPlaybackState(): PlaybackFrame {
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
        )
    }

    companion object {
        // LoadControl tuning for LAN direct-play of large 4K files. 180s of
        // forward buffer rides out ~3min serving outages invisibly (an observed
        // real-world ~70s wireless event drained the previous 60s cap and caused
        // a 12s stall); at typical 4K bitrates this is ~50-150 MB, still under
        // the byte target below.
        const val MIN_BUFFER_MS = 15_000
        const val MAX_BUFFER_MS = 180_000
        const val BUFFER_FOR_PLAYBACK_MS = 2_500
        const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 5_000

        // Retain the last 30s behind the playhead so short backward seeks replay
        // from memory instead of refetching over the network.
        const val BACK_BUFFER_MS = 30_000

        // A seek that lands inside the buffer never leaves STATE_READY, so the
        // seek-fill window is closed from the snapshot tick once this settle time
        // has passed (long enough that a genuine post-seek BUFFERING transition —
        // same main-loop burst as the discontinuity — always arrives first).
        const val SEEK_SETTLE_MS = 700L

        // Generous byte target for 4K (~256 MB). With largeHeap enabled and
        // prioritizeTimeOverSizeThresholds(true), typical 4K bitrates buffer
        // toward the 60s max while very-high-bitrate content stays memory-bounded.
        const val TARGET_BUFFER_BYTES = 256 * 1024 * 1024

        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val USER_AGENT = "FlickReceiver/0.1 (Media3 direct-play)"

        // Load-error retry policy: ~20 tries with capped backoff (min(1000*(n+1), 5000)ms)
        // ≈ 100s of quiet retrying, hidden by the 15-60s buffer.
        const val MAX_LOAD_RETRY_COUNT = 20
        const val MAX_LOAD_RETRY_DELAY_MS = 5_000L

        // Bounded fatal-error auto-recovery: backoff per attempt (1-4), then give up.
        private val RECOVERY_BACKOFF_MS = longArrayOf(2_000L, 4_000L, 8_000L, 15_000L)
        val MAX_RECOVERY_ATTEMPTS = RECOVERY_BACKOFF_MS.size

        // Re-arm the recovery budget after this long uninterrupted in STATE_READY.
        const val RECOVERY_RESET_STABLE_MS = 30_000L
    }
}
