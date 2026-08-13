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

    /** Selected subtitle metadata only; cue payloads are never stored. */
    var subtitleTrackSelected: Boolean = false
    var subtitleTrackMimeType: String? = null
    var subtitleCueKind: SubtitleCueKind = SubtitleCueKind.NONE

    var errorMessage: String? = null
    var errorCode: Int = 0
    var errorCodeName: String? = null

    /** Cumulative silent auto-recoveries performed this session (for the overlay). */
    var autoRecoveryCount: Int = 0

    /**
     * Times the audio sink was rebuilt to decode a bitstream the output refused —
     * see [AudioOutputPolicy]. At most one per cast, and normally zero; a 1 here is
     * the visible trace of an audio route that cannot carry passthrough.
     */
    var audioSinkRebuildCount: Int = 0

    /**
     * Media load errors Media3 retried through, excluding the sideloaded subtitle's.
     *
     * Recorded rather than acted on: the retry policy already rides these out, and this
     * is how far into that ~100 s budget a stall got before it either recovered or
     * became a diagnosis.
     */
    var mediaLoadErrorCount: Int = 0

    /** User seeks performed after playback started. */
    var seekCount: Int = 0

    /** elapsedRealtime() when the latest seek began filling; 0 when no seek is settling. */
    var seekFillStartMs: Long = 0L

    /** How long the most recent seek took to reach STATE_READY (ms); 0 until first seek. */
    var lastSeekFillMs: Long = 0L

    /**
     * elapsedRealtime() while an in-place reload is refilling; 0 otherwise.
     *
     * Its own field rather than a second use of [seekFillStartMs]: a reload is not a
     * seek, and borrowing that window would write a subtitle swap's refill into
     * [lastSeekFillMs], trading one dishonest number for another. What it shares with
     * a seek is only that the buffering it causes is the viewer's own doing and is
     * therefore not a stall — see the [rebufferCount] guard.
     */
    var reloadFillStartMs: Long = 0L

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
        subtitleTrackSelected = false
        subtitleTrackMimeType = null
        subtitleCueKind = SubtitleCueKind.NONE
        errorMessage = null
        errorCode = 0
        errorCodeName = null
        autoRecoveryCount = 0
        audioSinkRebuildCount = 0
        mediaLoadErrorCount = 0
        seekCount = 0
        seekFillStartMs = 0L
        lastSeekFillMs = 0L
        reloadFillStartMs = 0L
    }
}
