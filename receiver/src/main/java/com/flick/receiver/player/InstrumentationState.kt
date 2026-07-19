package com.flick.receiver.player

import androidx.media3.common.Format
import androidx.media3.common.Player

/**
 * Mutable bag of raw playback telemetry, written by the [PlayerController]'s
 * [Player.Listener] / AnalyticsListener callbacks and read (snapshotted) by the
 * UI poll loop.
 *
 * Threading: every ExoPlayer listener callback and every read happens on the
 * application (main) thread — the player is built on main, so its application
 * looper is main, and [PlayerController.snapshot] is invoked from a main-thread
 * coroutine. Plain vars are therefore safe; no synchronization is required.
 */
class InstrumentationState {

    /** Becomes true the first time the player reaches STATE_READY. */
    var playbackStarted: Boolean = false

    var isPlaying: Boolean = false
    var playbackState: Int = Player.STATE_IDLE

    var videoWidth: Int = 0
    var videoHeight: Int = 0
    var frameRate: Float = 0f

    /** Rebuffers = STATE_BUFFERING transitions that happen AFTER playback began. */
    var rebufferCount: Int = 0

    /** Cumulative time spent in post-start rebuffering (completed windows only). */
    var cumulativeRebufferMs: Long = 0L

    /** elapsedRealtime() when the current rebuffer window opened; 0 if not rebuffering. */
    var currentRebufferStartMs: Long = 0L

    var droppedFrames: Long = 0L

    /** Name of the video decoder (e.g. "c2.qti.hevc.decoder") — proves hardware decode. */
    var decoderName: String? = null

    /** Sample MIME of the decoded video track (e.g. "video/dolby-vision") — drives the honest HDR badge. */
    var videoMimeType: String? = null

    /** Color transfer from the decoded Format (C.COLOR_TRANSFER_*), or [Format.NO_VALUE] when unknown. */
    var colorTransfer: Int = Format.NO_VALUE

    var errorMessage: String? = null
    var errorCode: Int = 0
    var errorCodeName: String? = null

    /** Cumulative silent auto-recoveries performed this session (for the overlay). */
    var autoRecoveryCount: Int = 0

    /** User seeks performed after playback started. */
    var seekCount: Int = 0

    /** elapsedRealtime() when the latest seek began filling; 0 when no seek is settling. */
    var seekFillStartMs: Long = 0L

    /** How long the most recent seek took to reach STATE_READY (ms); 0 until first seek. */
    var lastSeekFillMs: Long = 0L

    /** Clears everything for a fresh playback session (called from [PlayerController.play]). */
    fun reset() {
        playbackStarted = false
        isPlaying = false
        playbackState = Player.STATE_IDLE
        videoWidth = 0
        videoHeight = 0
        frameRate = 0f
        rebufferCount = 0
        cumulativeRebufferMs = 0L
        currentRebufferStartMs = 0L
        droppedFrames = 0L
        decoderName = null
        videoMimeType = null
        colorTransfer = Format.NO_VALUE
        errorMessage = null
        errorCode = 0
        errorCodeName = null
        autoRecoveryCount = 0
        seekCount = 0
        seekFillStartMs = 0L
        lastSeekFillMs = 0L
    }
}
