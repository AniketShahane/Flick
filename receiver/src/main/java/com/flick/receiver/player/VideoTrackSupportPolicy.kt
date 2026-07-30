package com.flick.receiver.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import java.io.IOException

/**
 * Why the video this cast carries will not play, decided from track selection rather
 * than from a decode failure.
 *
 * This exists because an unplayable video does not raise a `PlaybackException` at all.
 * When the codec selector hands back an empty list the track selector marks the video
 * `FORMAT_UNSUPPORTED_SUBTYPE` and simply **does not select it** — so the audio track
 * plays on over a black screen, no error is reported, and the only thing that eventually
 * fires is the 18 s startup deadline, which reports `STARTUP_TIMEOUT` with
 * `retryable = true`. The person with the remote is then told the cast "didn't start in
 * time" and offered a Retry that cannot ever succeed, on a file the TV can never play.
 * Reading the selection is what turns that into an immediate, correct, terminal answer.
 */
enum class VideoTrackShortfall {
    /** No decoder on this TV claims the codec at all. */
    NoDecoderForCodec,

    /** No Dolby Vision decoder — the TV has no DV pipeline for this file. */
    NoDolbyVisionDecoder,

    /** A decoder claims the codec but not at this resolution/level/profile. */
    ExceedsDecoderCapabilities,

    /** The track is encrypted and this build carries no DRM session. */
    DrmUnsupported,
}

/**
 * Raised in place of the `PlaybackException` Media3 never throws for an unselected
 * video track, so the failure travels the existing error path.
 *
 * An [IOException] to match the shape of the other synthesized failures on this path
 * ([RedirectRejectedException], [PlaybackHttpStatusException]); the classifier reads the
 * [shortfall] off the cause chain rather than the message.
 */
class UnplayableVideoTrackException(val shortfall: VideoTrackShortfall) :
    IOException("video track unplayable: $shortfall")

/**
 * The shortfall implied by a video group's support levels, or null if there is nothing
 * wrong.
 *
 * [anyVideoSelected] is the whole test for "nothing wrong": a supported video track is
 * always selected, so a video group present and nothing chosen from it is the signal.
 * The **best** support level across the group decides the reason — `C.FORMAT_*` is
 * ordered, `FORMAT_HANDLED` (4) down to `FORMAT_UNSUPPORTED_TYPE` (0) — because a group
 * whose worst track is unsupported is not a failure if a better one exists.
 *
 * A best level of `FORMAT_HANDLED` with nothing selected returns null: the format is
 * playable and something other than capability chose not to select it, and guessing at
 * a capability fault there would refuse a film the TV can actually decode.
 *
 * Dolby Vision is separated out from the generic codec answer because
 * `video/dolby-vision` being an unsupported subtype means precisely that the TV has no
 * DV pipeline, which is an HDR-profile fault and has its own, better wording — not a
 * guess. An `EXCEEDS_CAPABILITIES` verdict is left generic on purpose: a 4K DV file
 * rejected by a 1080p decoder is a resolution fault, and attributing it to HDR because
 * the file happens to be HDR would be inventing a cause.
 */
fun videoTrackShortfall(
    trackSupports: List<Int>,
    anyVideoSelected: Boolean,
    videoMimeType: String?,
): VideoTrackShortfall? {
    if (anyVideoSelected) return null
    val best = trackSupports.maxOrNull() ?: return null
    val dolbyVision = videoMimeType != null &&
        videoMimeType.equals(MimeTypes.VIDEO_DOLBY_VISION, ignoreCase = true)
    return when (best) {
        C.FORMAT_HANDLED -> null
        C.FORMAT_EXCEEDS_CAPABILITIES -> VideoTrackShortfall.ExceedsDecoderCapabilities
        C.FORMAT_UNSUPPORTED_DRM -> VideoTrackShortfall.DrmUnsupported
        // FORMAT_UNSUPPORTED_SUBTYPE / FORMAT_UNSUPPORTED_TYPE
        else -> if (dolbyVision) {
            VideoTrackShortfall.NoDolbyVisionDecoder
        } else {
            VideoTrackShortfall.NoDecoderForCodec
        }
    }
}
