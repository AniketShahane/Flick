package com.flick.sender.model

import android.net.Uri

/**
 * A local video from the on-device MediaStore gallery (design S3). Immutable
 * snapshot; the content [uri] is what both Coil (for the still) and the media
 * server (for the bytes) read.
 */
data class MediaItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val bucket: String?,
) {
    val resolutionLabel: String
        get() = when {
            height >= 2160 || width >= 3840 -> "4K"
            height >= 1080 || width >= 1920 -> "1080p"
            height >= 720 -> "HD"
            else -> "SD"
        }
}

/** Best-effort HDR classification of a video track (design badges). */
enum class HdrType { NONE, HDR10, DOLBY_VISION }

/** A TV found on the LAN via NSD (design S1 device list). */
data class DiscoveredTv(
    val name: String,
    val host: String,
    val port: Int,
    val model: String?,
    val state: TvAvailability,
)

enum class TvAvailability { READY, SLEEPING, UNKNOWN }

/** Where the phone is in the discover → pair → drive lifecycle. */
enum class ConnectionStatus { DISCONNECTED, CONNECTING, PAIRING, CONNECTED, FAILED }

/** The receiver's playback lifecycle, mirrored from TV `state` frames. */
enum class PlaybackPhase { IDLE, BUFFERING, PLAYING, PAUSED, ENDED, ERROR }

/**
 * The single session clock, drawn twice (design Part 4). [targetMs] is the
 * optimistic head that leads with the thumb; [confirmedMs] is the last TV-reported
 * position that trails. When they're close, sync is invisible.
 */
data class PlaybackUiState(
    val title: String? = null,
    val durationMs: Long = 0L,
    val targetMs: Long = 0L,
    val confirmedMs: Long = 0L,
    val playing: Boolean = false,
    val bufferedMs: Long = 0L,
    val phase: PlaybackPhase = PlaybackPhase.IDLE,
    val volume: Float = 1f,
    val syncing: Boolean = false,
    val scrubbing: Boolean = false,
) {
    val targetFraction: Float
        get() = if (durationMs > 0L) (targetMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val confirmedFraction: Float
        get() = if (durationMs > 0L) (confirmedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/** Which error face S12 shows. */
enum class CastErrorKind { REACHABLE_NOT_SERVING, UNREACHABLE, NO_LAN, GENERIC }
