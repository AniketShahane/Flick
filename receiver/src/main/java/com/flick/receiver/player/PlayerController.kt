package com.flick.receiver.player

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter

/**
 * Owns the ExoPlayer lifecycle and all playback instrumentation for the
 * Phase 0 direct-play spike.
 *
 * The player is built for LAN direct-play of large 4K files: hardware decoding
 * only (no transcode, no software fallback rendering), a generous time- and
 * byte-based buffer, and an HTTP data source with byte-range support (ExoPlayer
 * issues Range requests against the sender's GET /video endpoint automatically).
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

    // --- Listeners -----------------------------------------------------------

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    // Only count buffering that happens AFTER playback has started;
                    // the initial fill is not a rebuffer/stall.
                    if (instrumentation.playbackStarted && instrumentation.currentRebufferStartMs == 0L) {
                        instrumentation.rebufferCount++
                        instrumentation.currentRebufferStartMs = SystemClock.elapsedRealtime()
                    }
                }
                Player.STATE_READY -> {
                    instrumentation.playbackStarted = true
                    closeRebufferWindow()
                }
                Player.STATE_ENDED, Player.STATE_IDLE -> closeRebufferWindow()
            }
            instrumentation.playbackState = playbackState
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            instrumentation.isPlaying = isPlaying
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                instrumentation.videoWidth = videoSize.width
                instrumentation.videoHeight = videoSize.height
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            instrumentation.errorMessage = error.message ?: "Playback error"
            instrumentation.errorCode = error.errorCode
            instrumentation.errorCodeName = error.errorCodeName
        }
    }

    private val analyticsListener = object : AnalyticsListener {
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

    private fun closeRebufferWindow() {
        if (instrumentation.currentRebufferStartMs != 0L) {
            instrumentation.cumulativeRebufferMs +=
                SystemClock.elapsedRealtime() - instrumentation.currentRebufferStartMs
            instrumentation.currentRebufferStartMs = 0L
        }
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
            .build()

        // Byte-range aware HTTP source. Cross-protocol redirects OFF (we only
        // ever talk plain http to the LAN sender). Sane connect/read timeouts so
        // a dead sender surfaces as an error instead of hanging forever.
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(false)
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)
            .setUserAgent(USER_AGENT)

        val mediaSourceFactory = DefaultMediaSourceFactory(appContext)
            .setDataSourceFactory(httpDataSourceFactory)

        // Hardware decoders only (no software/extension renderers) — the whole
        // point is to prove the TV decodes the original bytes in hardware.
        // Decoder fallback is OFF: if the primary (hardware) decoder fails to
        // init, we want a visible PlaybackException in the overlay, NOT a silent
        // drop to a software decoder that would mask the very failure this spike
        // exists to expose.
        val renderersFactory = DefaultRenderersFactory(appContext)
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
        currentUrl = url
        savedPositionMs = 0L
        pendingPlayWhenReady = true
        instrumentation.reset()
        val exo = player ?: createPlayer().also { player = it }
        exo.setMediaItem(MediaItem.fromUri(url))
        exo.prepare()
        exo.playWhenReady = true
    }

    fun stop() {
        pendingPlayWhenReady = false
        player?.let { exo ->
            exo.stop()
            exo.clearMediaItems()
        }
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
            positionMs = position.coerceAtLeast(0L),
            durationMs = duration,
            errorMessage = instrumentation.errorMessage,
            errorCode = instrumentation.errorCode,
            errorCodeName = instrumentation.errorCodeName,
        )
    }

    companion object {
        // LoadControl tuning for LAN direct-play of large 4K files.
        const val MIN_BUFFER_MS = 15_000
        const val MAX_BUFFER_MS = 60_000
        const val BUFFER_FOR_PLAYBACK_MS = 2_500
        const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 5_000

        // Generous byte target for 4K (~256 MB). With largeHeap enabled and
        // prioritizeTimeOverSizeThresholds(true), typical 4K bitrates buffer
        // toward the 60s max while very-high-bitrate content stays memory-bounded.
        const val TARGET_BUFFER_BYTES = 256 * 1024 * 1024

        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val USER_AGENT = "FlickReceiver/0.1 (Media3 direct-play)"
    }
}
