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
    /**
     * The MediaStore bucket this row belongs to, or null when nothing said — below
     * API 29 the column is not public API for video at all, and a provider may
     * withhold it on any release. Null therefore means "no folder Flick can name",
     * never "loose in the gallery": the file is still listed, just not under a folder.
     */
    val bucketId: Long?,
) {
    val resolutionLabel: String get() = resolutionLabelFor(width, height)

    /**
     * Whether MediaStore reported pixels at all. A 4 GB 2160p remux it has no row for
     * reads here exactly like a 320×240 clip does, so every surface that would state a
     * resolution has to ask this first: the difference between "SD" and "we never got
     * told" is the difference between a claim and a lie.
     */
    val knowsResolution: Boolean get() = width > 0 || height > 0

    /** Zero is MediaStore's silence, never a zero-length film. */
    val knowsDuration: Boolean get() = durationMs > 0L

    /**
     * The identity the process-lifetime unplayable memory is keyed by. The MediaStore
     * row id is reassigned when a file is removed and re-indexed; the content URI is
     * what both the server and the receiver were actually handed.
     */
    val uriKey: String get() = uri.toString()
}

/**
 * MediaStore pixel dimensions → the resolution bucket the tile badges, the detail
 * chips and the library's two quality filters all match on. Extracted from [MediaItem]
 * so the filters' exact-string dependency on [FourKLabel] and [FullHdLabel] is
 * testable without a `Uri`.
 *
 * No dimensions at all returns [UnknownResolutionLabel] rather than falling through to
 * the smallest bucket: the bottom of a ladder is a verdict, and nothing here measured
 * anything. It matches neither quality filter for the same reason.
 */
fun resolutionLabelFor(width: Int, height: Int): String = when {
    width <= 0 && height <= 0 -> UnknownResolutionLabel
    height >= 2160 || width >= 3840 -> FourKLabel
    height >= 1080 || width >= 1920 -> FullHdLabel
    height >= 720 -> "HD"
    else -> "SD"
}

const val FourKLabel = "4K"
const val FullHdLabel = "1080p"

/**
 * What a resolution reads as when there is none to read. The same em dash `Format`
 * already prints for an unknown size, so surfaces that only have room for the bare
 * label stay honest without a string lookup; the library tile and the detail sheet
 * withhold more deliberately than this.
 */
const val UnknownResolutionLabel = "—"

/** Best-effort HDR classification of a video track (design badges). */
enum class HdrType { NONE, HDR10, DOLBY_VISION }

/** A TV found on the LAN via NSD (design S1 device list). */
data class DiscoveredTv(
    val name: String,
    val host: String,
    val port: Int,
    val tvId: String? = null,
    val protocolVersion: Int? = null,
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

/** Stable terminal taxonomy retained alongside the friendly error face. */
data class CastFailure(
    val code: String,
    val retryable: Boolean,
    val httpStatus: Int? = null,
)
