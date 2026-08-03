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
    /** MediaStore's seconds-since-epoch source revision for thumbnail invalidation. */
    val dateModifiedSeconds: Long,
    /** Row generation paired with [mediaStoreVersion], or null below API 30. */
    val generationModified: Long?,
    /** Opaque database generation namespace, or null below API 30/provider failure. */
    val mediaStoreVersion: String?,
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
    /**
     * Where the file sits under the shared-storage root — `Movies/Marvel/Phase 4/` —
     * exactly as MediaStore reported it, separators and all. It is the only column that
     * says which folder CONTAINS which, so it is what the library's folder tree is built
     * from; [bucketId] names the one leaf the file is in and can say nothing about its
     * parents. Behind the same API 29 gate as [bucketId], and null on the same terms: no
     * folder Flick can place this row under, never a claim about where it is.
     */
    val relativePath: String?,
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
 * MediaStore pixel dimensions → the resolution label shown on tile and detail badges.
 * Extracted from [MediaItem] so the boundary behavior is testable without a `Uri`.
 *
 * No dimensions at all returns [UnknownResolutionLabel] rather than falling through to
 * the smallest bucket: the bottom of a ladder is a verdict, and nothing here measured
 * anything.
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

/**
 * Where the phone is in the discover → pair → drive lifecycle.
 *
 * [CONFIRM_ON_TV] is a first-time pairing that has sent a correct-shaped code and is
 * now waiting on a person at the television, which can take tens of seconds. It is a
 * separate state from [PAIRING] because the two owe the user different things: one is
 * "working on it", the other is "your move, and it is over there".
 */
enum class ConnectionStatus { DISCONNECTED, CONNECTING, PAIRING, CONFIRM_ON_TV, CONNECTED, FAILED }

/** The receiver's playback lifecycle, mirrored from TV `state` frames. */
enum class PlaybackPhase { IDLE, BUFFERING, PLAYING, PAUSED, ENDED, ERROR }

/**
 * How far the TV turns the picture, **on top of** the rotation the container
 * already declares. The same five choices the TV's own panel offers, with the
 * same meaning — one feature, one model.
 *
 * Additive rather than absolute because that is the only reading under which
 * every presentation is reachable: a file tagged 90 and a file tagged 0 need
 * different absolute answers to look the same, and the viewer knows only what
 * they can see. [AsFiled] is therefore the cell that means "honour the file
 * exactly", and it is what an over-eager [Auto] is corrected with.
 *
 * [Auto] carries no degrees on purpose. It is the receiver reading the file for
 * itself, which is a verdict the phone has no way to compute and no wire value
 * to name — so the verb that sends it says `auto`, not a number.
 */
enum class VideoRotation(val extraDegrees: Int?) {
    Auto(null),
    AsFiled(0),
    Quarter(90),
    Half(180),
    ThreeQuarter(270),
    ;

    companion object {
        /** The cells, in the order the TV lists them. */
        val ALL: List<VideoRotation> = entries
    }
}

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
    /**
     * A run of ±10s taps the user may still be adding to. The head is theirs for the
     * whole run — nothing has gone to the TV yet — so a `state` frame must not move it and
     * the transport has to stay on screen for the next tap. Distinct from [syncing]: no
     * sync is in flight, which is why the shimmer stays down.
     */
    val skipping: Boolean = false,
    /**
     * The picture orientation this phone last asked the TV for.
     *
     * Optimistic, exactly as [playing] is under a local toggle, and for a harder
     * reason: the `state` frame is validated against an EXACT field set on both
     * sides, so a rotation field added to it would be rejected by every phone or
     * TV that had not been updated in lockstep — the whole playback UI for one
     * readout. The default is the reset the receiver performs for every new cast,
     * so the two genuinely agree at the start of every film; using the TV's own
     * panel mid-cast is the one thing that can make this disagree, until the next
     * cast reseeds both.
     */
    val rotation: VideoRotation = VideoRotation.Auto,
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
