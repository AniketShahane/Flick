package com.flick.receiver.player

import androidx.media3.common.Player

/** Overall health of the current direct-play session, for the overlay. */
enum class PlaybackStatus { IDLE, PASS, WARN, ERROR }

/**
 * An immutable, point-in-time view of every metric shown on the debug overlay.
 * Produced ~2x/sec by [PlayerController.snapshot]; Compose diffs it to redraw.
 */
data class DiagnosticsSnapshot(
    val playbackState: Int,
    val isPlaying: Boolean,
    val playbackStarted: Boolean,
    val width: Int,
    val height: Int,
    val frameRate: Float,
    val rebufferCount: Int,
    val cumulativeRebufferMs: Long,
    val currentlyRebuffering: Boolean,
    val bufferedAheadMs: Long,
    val droppedFrames: Long,
    val bitrateEstimateBps: Long,
    val decoderName: String?,
    val positionMs: Long,
    val durationMs: Long,
    val errorMessage: String?,
    val errorCode: Int,
    val errorCodeName: String?,
    /** Count of silent bounded auto-recoveries performed this session. */
    val autoRecoveryCount: Int,
    /** User seeks performed after playback started. */
    val seekCount: Int,
    /** Time the most recent seek took to reach READY (ms); 0 until first seek. */
    val lastSeekFillMs: Long,
    /** Pre-flight probe round-trip in ms; <= 0 when no probe has run. */
    val probeLatencyMs: Long,
    /** TV's own Wi-Fi band ("2.4 GHz"/"5 GHz"/"6 GHz"); null on Ethernet/unknown. */
    val wifiBand: String?,
    /** TV Wi-Fi link speed in Mb/s; -1 when unavailable. */
    val wifiLinkSpeedMbps: Int,
    /** TV Wi-Fi RSSI in dBm; 0 when unavailable. */
    val wifiRssiDbm: Int,
) {
    /** True 4K UHD (>= 3840x2160). This is the flag the spike is proving out. */
    val is4k: Boolean get() = width >= 3840 && height >= 2160

    /**
     * PASS condition for zero-stall direct play: playback started, no error,
     * zero rebuffers, ~zero dropped frames, and a steadily positive
     * buffered-ahead window while actually playing.
     */
    val status: PlaybackStatus
        get() = when {
            errorMessage != null -> PlaybackStatus.ERROR
            !playbackStarted -> PlaybackStatus.IDLE
            rebufferCount == 0 && droppedFrames == 0L && bufferedAheadMs > 0L && isPlaying ->
                PlaybackStatus.PASS
            else -> PlaybackStatus.WARN
        }

    val stateLabel: String
        get() = when (playbackState) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> if (isPlaying) "PLAYING" else "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> "UNKNOWN"
        }

    companion object {
        val EMPTY = DiagnosticsSnapshot(
            playbackState = Player.STATE_IDLE,
            isPlaying = false,
            playbackStarted = false,
            width = 0,
            height = 0,
            frameRate = 0f,
            rebufferCount = 0,
            cumulativeRebufferMs = 0L,
            currentlyRebuffering = false,
            bufferedAheadMs = 0L,
            droppedFrames = 0L,
            bitrateEstimateBps = 0L,
            decoderName = null,
            positionMs = 0L,
            durationMs = 0L,
            errorMessage = null,
            errorCode = 0,
            errorCodeName = null,
            autoRecoveryCount = 0,
            seekCount = 0,
            lastSeekFillMs = 0L,
            probeLatencyMs = 0L,
            wifiBand = null,
            wifiLinkSpeedMbps = -1,
            wifiRssiDbm = 0,
        )
    }
}
