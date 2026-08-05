package com.flick.receiver.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks

/**
 * How far Flick turns the picture, **on top of** the rotation the container
 * already declares.
 *
 * Additive rather than absolute because that is the only reading under which
 * every cell is reachable: a file tagged 90 and a file tagged 0 need different
 * absolute answers to reach the same presentation, and the viewer looking at a
 * sideways film knows only what they can see. [AsFiled] is therefore the row
 * that means "honour the file exactly", and it is what an over-eager [Auto] is
 * corrected with.
 */
enum class VideoRotation(val extraDegrees: Int?) {
    /** Flick decides — see [autoRotation]. */
    Auto(null),
    AsFiled(0),
    Quarter(90),
    Half(180),
    ThreeQuarter(270),
    ;

    companion object {
        /**
         * The one immutable listing. `values()` clones its array on every call and
         * the selector below sits in a composable that re-runs whenever the 2 Hz
         * track re-read produces a different list.
         */
        val ALL: List<VideoRotation> = VideoRotation.values().asList()

        /** The explicit choice carrying [degrees], or null for a value off the grid. */
        fun forExtraDegrees(degrees: Int): VideoRotation? =
            ALL.firstOrNull { it.extraDegrees != null && it.extraDegrees == degrees }
    }
}

/** The video track's own shape, exactly as the container declares it. */
data class VideoTrackShape(
    /** CODED width — the frame as stored, before any rotation is applied. */
    val widthPx: Int,
    /** CODED height. */
    val heightPx: Int,
    val rotationDegrees: Int,
    val pixelWidthHeightRatio: Float,
    /**
     * Carried for [pictureColourOf], not for [autoRotation] — which turn a film
     * needs is a question about its geometry, and what that turn COSTS is a
     * question about its colour. Defaulted so the geometry tests can go on
     * describing a frame in the four numbers that decide their answer.
     */
    val sampleMimeType: String? = null,
    val colorTransfer: Int = Format.NO_VALUE,
)

/**
 * Everything about a cast [autoRotation] is allowed to reason from. Deliberately
 * plain data: the question is decided the same way whether the answer comes from
 * a live `Tracks` or from a test.
 */
data class MediaShape(
    /** The video track that was actually selected; null when none was. */
    val video: VideoTrackShape?,
    val audioTrackCount: Int,
    val maxAudioChannelCount: Int,
    val audioSampleMimeTypes: List<String>,
    /** Text tracks the CONTAINER carries — a sideloaded file is not evidence about it. */
    val embeddedTextTrackCount: Int,
    /** null while the timeline has not resolved one. */
    val durationMs: Long?,
)

/** Why [autoRotation] answered as it did. Logged, so it must name a reason. */
enum class AutoRotationVerdict {
    /** Nothing selected to judge, or a frame with no declared size. */
    NoVideoTrack,

    /** The container declares 0 or 180 — a film cannot be stood on end by either. */
    ContainerNotSideways,

    /** The coded frame is portrait, so the container's turn is what makes it landscape. */
    RotationMakesItLandscape,

    /** Sideways landscape, but nothing distinguishes it from a phone recording. */
    LooksLikeACameraClip,

    /** Release evidence, but shorter than anything that ships as a title. */
    ShorterThanAFeature,

    /** A landscape film filed sideways — the one case Flick overrules the container. */
    LandscapeFilmFiledSideways,
}

data class AutoRotation(val extraDegrees: Int, val verdict: AutoRotationVerdict)

/** No auto correction, and the verdict that explains it. */
private fun honourContainer(verdict: AutoRotationVerdict) = AutoRotation(0, verdict)

/**
 * Shorter than any feature, episode or short that ships as a title, and longer
 * than the overwhelming majority of camera clips. A floor under the release
 * evidence below rather than a signal in its own right — a long recording is not
 * a film, and the evidence is what decides.
 */
const val MIN_FEATURE_MS = 600_000L

/** Six channels is the first count a phone microphone array does not produce. */
const val SURROUND_CHANNEL_COUNT = 6

/**
 * The broadcast and cinema audio codecs. Every one of them reaches a file by
 * being authored into it; none is a capture format, so a container carrying one
 * was mastered rather than recorded.
 */
private val SURROUND_AUDIO_MIME_TYPES = setOf(
    MimeTypes.AUDIO_AC3,
    MimeTypes.AUDIO_E_AC3,
    MimeTypes.AUDIO_E_AC3_JOC,
    MimeTypes.AUDIO_AC4,
    MimeTypes.AUDIO_TRUEHD,
    MimeTypes.AUDIO_DTS,
    MimeTypes.AUDIO_DTS_HD,
    MimeTypes.AUDIO_DTS_EXPRESS,
    MimeTypes.AUDIO_DTS_X,
)

/**
 * Whether Flick should overrule the container and stand a film back up.
 *
 * The whole difficulty is that a genuine portrait phone recording is stored
 * **exactly** like a mis-tagged landscape film: a 1920×1080 coded frame with a
 * 90° display matrix, because the sensor reads out landscape and the container
 * records which way the phone was held. Nothing in the video track separates the
 * two, so a rule written on the video track alone would turn every portrait clip
 * in the user's gallery on its side — trading a rare annoyance for a common one.
 *
 * What separates them is everything AROUND the picture. A released title is
 * assembled: it carries a surround/broadcast codec no phone records, or subtitle
 * tracks the container itself declares. A camera clip carries one stereo AAC
 * track and nothing else. So the correction fires only on positive evidence of
 * assembly, and the default — for every file that offers none — is byte-identical
 * to honouring the container.
 *
 * Deliberately NOT used as evidence: coded aspect ratio wider than 16:9, which
 * would be conclusive if phones only recorded 16:9, and some record 21:9; and
 * frame rate, because 24 fps is both the cinema rate and an option on the phones
 * whose clips this must not touch.
 *
 * A film whose coded frame is portrait with no rotation at all is not detectable
 * as an error and is left alone; the manual override exists for it.
 */
fun autoRotation(shape: MediaShape): AutoRotation {
    val video = shape.video ?: return honourContainer(AutoRotationVerdict.NoVideoTrack)
    if (video.widthPx <= 0 || video.heightPx <= 0) {
        return honourContainer(AutoRotationVerdict.NoVideoTrack)
    }
    val rotation = quarterTurn(video.rotationDegrees)
        ?: return honourContainer(AutoRotationVerdict.ContainerNotSideways)
    if (rotation != 90 && rotation != 270) {
        return honourContainer(AutoRotationVerdict.ContainerNotSideways)
    }
    if (!codedFrameIsLandscape(video)) {
        return honourContainer(AutoRotationVerdict.RotationMakesItLandscape)
    }
    if (!looksLikeAReleasedTitle(shape)) {
        return honourContainer(AutoRotationVerdict.LooksLikeACameraClip)
    }
    val durationMs = shape.durationMs
    if (durationMs != null && durationMs > 0L && durationMs < MIN_FEATURE_MS) {
        return honourContainer(AutoRotationVerdict.ShorterThanAFeature)
    }
    // Exactly enough to bring the presented picture back to 0.
    return AutoRotation((360 - rotation) % 360, AutoRotationVerdict.LandscapeFilmFiledSideways)
}

/**
 * Whether a delivery of `Tracks` has to resolve which mechanism turns this
 * film's picture.
 *
 * The trap this rule exists for is that [autoRotation]'s answer and the film's
 * TOTAL turn are different questions, and only the second one chooses a
 * mechanism. A phone-shot portrait clip is a container declaring 90 which
 * [autoRotation] correctly leaves alone at 0 — the value a new film already
 * starts at — so a resolution triggered by Auto's answer MOVING never fires for
 * it, [pictureTurnFor] is never asked, and the picture is left to a decoder
 * transform the verified TV's display pipeline drops. Every clip shot on the
 * user's phone then plays sideways from the first frame with no key pressed.
 *
 * So the first delivery always resolves: it is where the container's own turn
 * becomes knowable at all, and it is the larger half of the total. After that the
 * film is settled and only Auto changing its mind — while Auto is still the
 * choice — is worth asking again, because `onTracksChanged` fires for every
 * text-track change and for the panel's 2 Hz re-read, and a resolution can cost a
 * player rebuild.
 */
fun resolvesPictureTurn(
    alreadyResolved: Boolean,
    autoChanged: Boolean,
    choiceIsAuto: Boolean,
): Boolean = !alreadyResolved || (autoChanged && choiceIsAuto)

/**
 * The rotation the decoder is configured with — the container's own turn plus
 * Flick's. A container value off the quarter-turn grid is returned untouched:
 * `MediaFormat.KEY_ROTATION` accepts nothing else, and inventing one would be a
 * worse answer than the file's.
 */
fun effectiveRotationDegrees(containerDegrees: Int, extraDegrees: Int): Int {
    val container = quarterTurn(containerDegrees) ?: return containerDegrees
    val extra = quarterTurn(extraDegrees) ?: return containerDegrees
    return (container + extra) % 360
}

/** Which way a picture lies. [Neither] is a square frame, or one that cannot be read. */
enum class PictureShape { Landscape, Portrait, Neither }

/**
 * The shape of the picture the TV is actually SHOWING, once [extraDegrees] has
 * been added to the container's own turn.
 *
 * Derived from the container rather than read back off a decoded frame, and that
 * is the point: the only reason to ask is to say something about a film at the
 * moment it starts, and media3 publishes the presented size no earlier than the
 * first output format. The two agree — `MediaCodecVideoRenderer` configures the
 * decoder with the rotation and swaps width against height for a quarter turn
 * while building its `VideoSize` — so this is the same reading, one step sooner.
 *
 * A turn off the quarter-turn grid is [Neither]: `MediaFormat.KEY_ROTATION`
 * accepts nothing else, so what the panel would do with it is not knowable here.
 */
fun presentedShape(video: VideoTrackShape?, extraDegrees: Int): PictureShape {
    if (video == null || video.widthPx <= 0 || video.heightPx <= 0) return PictureShape.Neither
    val turn = quarterTurn(effectiveRotationDegrees(video.rotationDegrees, extraDegrees))
        ?: return PictureShape.Neither
    val coded = codedShape(video)
    if (turn != 90 && turn != 270) return coded
    return when (coded) {
        PictureShape.Landscape -> PictureShape.Portrait
        PictureShape.Portrait -> PictureShape.Landscape
        PictureShape.Neither -> PictureShape.Neither
    }
}

/** [degrees] normalized into {0, 90, 180, 270}, or null if it is not a quarter turn. */
internal fun quarterTurn(degrees: Int): Int? {
    val wrapped = ((degrees % 360) + 360) % 360
    return wrapped.takeIf { it % 90 == 0 }
}

/**
 * Sample aspect applies to the width, so a 1440×1080 frame at 1.333 is a
 * 16:9 picture and not a 4:3 one. Square is neither landscape nor portrait and
 * is left to the container.
 */
private fun codedShape(video: VideoTrackShape): PictureShape {
    val ratio = if (video.pixelWidthHeightRatio > 0f) video.pixelWidthHeightRatio else 1f
    val displayWidth = video.widthPx * ratio
    return when {
        displayWidth > video.heightPx -> PictureShape.Landscape
        displayWidth < video.heightPx -> PictureShape.Portrait
        else -> PictureShape.Neither
    }
}

private fun codedFrameIsLandscape(video: VideoTrackShape): Boolean =
    codedShape(video) == PictureShape.Landscape

/**
 * A second audio track is deliberately NOT evidence on its own. It is the only
 * assembly signal a camera clip can acquire after the fact: an editor that lays a
 * music or commentary track over a portrait recording and remuxes it — which
 * copies the rotation matrix and the landscape coded frame through untouched —
 * would otherwise be stood on its side by the correction. Nothing re-authors a
 * home video into AC-3 or gives it container-declared subtitle tracks, so the
 * evidence is narrowed to the signals that only authoring produces. A multi-track
 * film carrying neither is left to the manual row, which is the cheaper error.
 */
private fun looksLikeAReleasedTitle(shape: MediaShape): Boolean =
    shape.embeddedTextTrackCount >= 1 ||
        shape.maxAudioChannelCount >= SURROUND_CHANNEL_COUNT ||
        shape.audioSampleMimeTypes.any(::isSurroundAudioMimeType)

private fun isSurroundAudioMimeType(mimeType: String): Boolean =
    SURROUND_AUDIO_MIME_TYPES.any { it.equals(mimeType, ignoreCase = true) }

/**
 * Read a live [Tracks] into the shape [autoRotation] judges.
 *
 * The video track is the SELECTED one: a track the renderer refused is not the
 * picture on screen, and a capability failure has its own diagnosis. Audio and
 * text are counted whether or not this TV can decode them — a second audio track
 * is evidence that the file was assembled, and remains so when the TV has no
 * decoder for it.
 *
 * [sideloadedTextTracks] is subtracted because a `.srt` the viewer picked on
 * their phone says nothing about how the film was authored, and counting it
 * would let a subtitle turn a portrait clip on its side.
 */
fun mediaShapeFrom(
    tracks: Tracks,
    durationMs: Long?,
    sideloadedTextTracks: Int,
): MediaShape {
    var video: VideoTrackShape? = null
    var audioTrackCount = 0
    var maxAudioChannelCount = 0
    val audioSampleMimeTypes = mutableListOf<String>()
    var textTrackCount = 0
    for (group in tracks.groups) {
        for (index in 0 until group.length) {
            val format = group.getTrackFormat(index)
            when (group.type) {
                C.TRACK_TYPE_VIDEO -> if (video == null && group.isTrackSelected(index)) {
                    video = VideoTrackShape(
                        widthPx = format.width,
                        heightPx = format.height,
                        rotationDegrees = format.rotationDegrees,
                        pixelWidthHeightRatio = format.pixelWidthHeightRatio,
                        sampleMimeType = format.sampleMimeType,
                        colorTransfer = format.colorInfo?.colorTransfer ?: Format.NO_VALUE,
                    )
                }
                C.TRACK_TYPE_AUDIO -> {
                    audioTrackCount++
                    if (format.channelCount > maxAudioChannelCount) {
                        maxAudioChannelCount = format.channelCount
                    }
                    format.sampleMimeType?.let(audioSampleMimeTypes::add)
                }
                C.TRACK_TYPE_TEXT -> textTrackCount++
                else -> Unit
            }
        }
    }
    return MediaShape(
        video = video,
        audioTrackCount = audioTrackCount,
        maxAudioChannelCount = maxAudioChannelCount,
        audioSampleMimeTypes = audioSampleMimeTypes,
        embeddedTextTrackCount = (textTrackCount - sideloadedTextTracks).coerceAtLeast(0),
        durationMs = durationMs,
    )
}
