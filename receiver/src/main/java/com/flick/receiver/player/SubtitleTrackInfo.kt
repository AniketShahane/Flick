package com.flick.receiver.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import java.util.Locale

/**
 * One selectable text track, as the subtitles panel needs it.
 *
 * Everything here is read off the container — nothing is inferred or invented.
 * [label] is null when the container names neither a title nor a language; the
 * caller renders its own numbered fallback from [trackNumber] rather than having
 * a user-facing string baked in here.
 */
class SubtitleTrackFocusIdentity(
    val mediaTrackGroup: TrackGroup,
    val trackIndexWithinGroup: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is SubtitleTrackFocusIdentity &&
            mediaTrackGroup === other.mediaTrackGroup &&
            trackIndexWithinGroup == other.trackIndexWithinGroup

    override fun hashCode(): Int =
        31 * System.identityHashCode(mediaTrackGroup) + trackIndexWithinGroup
}

data class SubtitleTrackInfo(
    /** Opaque handle to pass back to `PlayerController.selectSubtitleTrack`. */
    val id: String,
    /** Stable Media3 identity for retaining UI focus while positional [id] changes. */
    val focusIdentity: SubtitleTrackFocusIdentity,
    /** The track's own name, in its own language ("Français"); null when unnamed. */
    val label: String?,
    /** Original sample MIME, with Media3's cue-transcoding wrapper unwrapped; null when unknown. */
    val mimeType: String?,
    val isSelected: Boolean,
    /** 1-based position among the listed text tracks — feeds the numbered label fallback. */
    val trackNumber: Int = 0,
    /** Container marks this as a forced-narrative track. */
    val isForced: Boolean = false,
    /** Container marks this as SDH / hearing-impaired. */
    val isHearingImpaired: Boolean = false,
) {
    /** Bitmap subtitle format (PGS / VobSub / DVB) — the panel's IMAGE vs TEXT source chip. */
    val isImageBased: Boolean get() = subtitleIsImageBased(mimeType)

    /** SRT / PGS / VTT / …; empty when the format is unknown, so the caller omits the chip. */
    val formatLabel: String get() = subtitleFormatLabel(mimeType)
}

/**
 * Map Media3's live [Tracks] to the text tracks worth offering, in container
 * order. Tracks the renderer cannot handle are dropped — offering a row that
 * would draw nothing is worse than not offering it.
 *
 * Pure: no player state, no side effects.
 */
fun subtitleTracksFrom(tracks: Tracks): List<SubtitleTrackInfo> {
    val result = mutableListOf<SubtitleTrackInfo>()
    tracks.groups.forEachIndexed { groupIndex, group ->
        if (group.type != C.TRACK_TYPE_TEXT) return@forEachIndexed
        for (trackIndex in 0 until group.length) {
            if (!group.isTrackSupported(trackIndex)) continue
            val format = group.getTrackFormat(trackIndex)
            result += SubtitleTrackInfo(
                id = subtitleTrackId(SubtitleTrackRef(groupIndex, trackIndex)),
                focusIdentity = SubtitleTrackFocusIdentity(group.mediaTrackGroup, trackIndex),
                label = subtitleTrackLabel(format.label, format.language),
                mimeType = subtitleSampleMimeType(format.sampleMimeType, format.codecs),
                isSelected = group.isTrackSelected(trackIndex),
                trackNumber = result.size + 1,
                isForced = (format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0,
                isHearingImpaired = (format.roleFlags and HEARING_IMPAIRED_ROLE_FLAGS) != 0,
            )
        }
    }
    return result
}

/**
 * The short technical format token for the panel's meta chip. Returns an empty
 * string for anything unmapped so the caller drops that half of the chip instead
 * of printing a guess.
 */
fun subtitleFormatLabel(mimeType: String?): String = when (mimeType?.lowercase()) {
    MimeTypes.APPLICATION_SUBRIP -> "SRT"
    MimeTypes.TEXT_VTT, MimeTypes.APPLICATION_MP4VTT -> "VTT"
    MimeTypes.TEXT_SSA -> "SSA"
    MimeTypes.APPLICATION_TTML -> "TTML"
    MimeTypes.APPLICATION_TX3G -> "TX3G"
    MimeTypes.APPLICATION_PGS -> "PGS"
    MimeTypes.APPLICATION_VOBSUB -> "VOBSUB"
    MimeTypes.APPLICATION_DVBSUBS -> "DVB"
    MimeTypes.APPLICATION_CEA608, MimeTypes.APPLICATION_MP4CEA608 -> "CEA-608"
    MimeTypes.APPLICATION_CEA708 -> "CEA-708"
    MimeTypes.APPLICATION_RAWCC -> "RAWCC"
    else -> ""
}

/** True for the bitmap subtitle formats, which cannot be restyled or resized. */
fun subtitleIsImageBased(mimeType: String?): Boolean = when (mimeType?.lowercase()) {
    MimeTypes.APPLICATION_PGS, MimeTypes.APPLICATION_VOBSUB, MimeTypes.APPLICATION_DVBSUBS -> true
    else -> false
}

/**
 * Media3 parses subtitles during extraction and republishes the track as
 * `application/x-media3-cues`, moving the real sample MIME into `codecs`. Undo
 * that so the panel names the format the file actually carries.
 */
fun subtitleSampleMimeType(sampleMimeType: String?, codecs: String?): String? =
    if (sampleMimeType != null && sampleMimeType.equals(MimeTypes.APPLICATION_MEDIA3_CUES, ignoreCase = true)) {
        codecs?.takeIf { it.isNotBlank() } ?: sampleMimeType
    } else {
        sampleMimeType
    }

/** Position of a text track inside a [Tracks] listing. */
internal data class SubtitleTrackRef(val groupIndex: Int, val trackIndex: Int)

internal fun subtitleTrackId(ref: SubtitleTrackRef): String = "${ref.groupIndex}:${ref.trackIndex}"

internal fun parseSubtitleTrackId(id: String): SubtitleTrackRef? {
    val separator = id.indexOf(':')
    if (separator <= 0 || separator == id.length - 1) return null
    val groupIndex = id.substring(0, separator).toIntOrNull() ?: return null
    val trackIndex = id.substring(separator + 1).toIntOrNull() ?: return null
    if (groupIndex < 0 || trackIndex < 0) return null
    return SubtitleTrackRef(groupIndex, trackIndex)
}

/**
 * Resolve an id produced by [subtitleTracksFrom] against a live [Tracks], or
 * null when the listing has moved on. Re-validating instead of trusting the id
 * keeps a stale panel selection from overriding an unrelated track.
 */
internal fun subtitleTrackOverride(tracks: Tracks, id: String): TrackSelectionOverride? {
    val ref = parseSubtitleTrackId(id) ?: return null
    val groups = tracks.groups
    if (ref.groupIndex !in groups.indices) return null
    val group = groups[ref.groupIndex]
    if (group.type != C.TRACK_TYPE_TEXT) return null
    if (ref.trackIndex < 0 || ref.trackIndex >= group.length) return null
    return TrackSelectionOverride(group.mediaTrackGroup, ref.trackIndex)
}

/**
 * A container-declared track title wins; otherwise the language is rendered as
 * its own endonym ("Español", "Français"), which is how every player names a
 * subtitle track. Null means the container declared nothing usable.
 */
internal fun subtitleTrackLabel(containerLabel: String?, language: String?): String? {
    val declared = containerLabel?.trim().orEmpty()
    if (declared.isNotEmpty()) return declared

    val tag = language?.trim().orEmpty()
    if (tag.isEmpty() || tag.equals(C.LANGUAGE_UNDETERMINED, ignoreCase = true)) return null

    val locale = Locale.forLanguageTag(tag)
    if (locale.language.isEmpty()) return null
    val display = locale.getDisplayName(locale).trim()
    // Without CLDR data the platform echoes the subtag back; that is not a name.
    if (display.isEmpty() ||
        display.equals(tag, ignoreCase = true) ||
        display.equals(locale.language, ignoreCase = true)
    ) {
        return null
    }
    return display.replaceFirstChar { it.titlecase(locale) }
}

/** The role flags containers use to mark SDH / hearing-impaired subtitle tracks. */
private val HEARING_IMPAIRED_ROLE_FLAGS =
    C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND or C.ROLE_FLAG_TRANSCRIBES_DIALOG
