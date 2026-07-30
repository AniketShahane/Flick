package com.flick.receiver.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes

// Whether the PANEL can present what the decoder is about to hand it.
//
// This deliberately does not check the decoder. Media3's track selector already
// refuses a format that exceeds a codec's CodecCapabilities and reports
// ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES, which PlaybackFailureClassifier
// already maps to an honest UNSUPPORTED_VIDEO_FORMAT before any decode is attempted —
// a second capability check on that path would only duplicate it, less well. What
// nothing in the module looks at is the display: its advertised HDR types and its
// native mode. A TV whose decoder does Dolby Vision behind a panel that does not is
// the case this answers.
//
// Neither shortfall may become a refusal to play. There is no transcode and no
// downscale in this system, so the only two options are *present it anyway* and
// *refuse the film* — and Display.HdrCapabilities is under-reported often enough on
// Android TV hardware that refusing on it would break films that play correctly
// today. Both are recorded as diagnosis rather than enforced.

/** The HDR pipeline a decoded format needs from the panel. */
enum class HdrPresentation { Sdr, Hdr10, Hlg, DolbyVision }

/**
 * The four `android.view.Display.HdrCapabilities.HDR_TYPE_*` values, mirrored so this
 * policy stays a pure function. `getSupportedHdrTypes()` returns exactly these ints.
 */
const val HDR_TYPE_DOLBY_VISION = 1
const val HDR_TYPE_HDR10 = 2
const val HDR_TYPE_HLG = 3
const val HDR_TYPE_HDR10_PLUS = 4

/**
 * What the decoded format asks the panel for.
 *
 * Dolby Vision is read off the MIME type rather than the transfer function: profile
 * 8.1 carries an HDR10-compatible ST.2084 base layer, so the transfer alone cannot
 * tell a DV stream from a plain HDR10 one, and the panel needs a DV pipeline for it
 * either way. HDR10+ is not distinguished — its dynamic metadata rides in an HEVC SEI
 * that the format's `colorTransfer` does not expose, so an HDR10+ stream reports as
 * [Hdr10], which is exactly what a panel without HDR10+ will present it as.
 */
fun hdrPresentationOf(videoMimeType: String?, colorTransfer: Int): HdrPresentation = when {
    videoMimeType != null && videoMimeType.equals(MimeTypes.VIDEO_DOLBY_VISION, ignoreCase = true) ->
        HdrPresentation.DolbyVision
    colorTransfer == C.COLOR_TRANSFER_ST2084 -> HdrPresentation.Hdr10
    colorTransfer == C.COLOR_TRANSFER_HLG -> HdrPresentation.Hlg
    else -> HdrPresentation.Sdr
}

/**
 * Whether [presentation] is in the panel's advertised list.
 *
 * SDR is always presentable. An **empty** list is treated as presentable too, not as
 * a shortfall: a display that advertises no HDR types at all is far more often one
 * that does not report them than one that has none, and this policy's output must not
 * turn a reporting gap into a claim about the hardware.
 */
fun isHdrPresentable(presentation: HdrPresentation, supportedHdrTypes: IntArray): Boolean {
    if (presentation == HdrPresentation.Sdr) return true
    if (supportedHdrTypes.isEmpty()) return true
    val required = when (presentation) {
        HdrPresentation.DolbyVision -> HDR_TYPE_DOLBY_VISION
        HdrPresentation.Hdr10 -> HDR_TYPE_HDR10
        HdrPresentation.Hlg -> HDR_TYPE_HLG
        HdrPresentation.Sdr -> return true
    }
    return supportedHdrTypes.contains(required)
}

/**
 * Whether the panel can show every line the stream carries.
 *
 * False means the platform will scale the film down to fit, which is a quality note
 * and never a failure — a 4K file on a 1080p panel is a perfectly good watch. Unknown
 * dimensions (0 or negative, before the format is parsed) count as native, because an
 * absent reading is not evidence of a shortfall.
 */
fun isResolutionNative(
    videoWidth: Int,
    videoHeight: Int,
    displayWidth: Int,
    displayHeight: Int,
): Boolean {
    if (videoWidth <= 0 || videoHeight <= 0) return true
    if (displayWidth <= 0 || displayHeight <= 0) return true
    // Compared against the larger and smaller display edge rather than width against
    // width: a TV may report its native mode in either orientation.
    val displayLong = maxOf(displayWidth, displayHeight)
    val displayShort = minOf(displayWidth, displayHeight)
    val videoLong = maxOf(videoWidth, videoHeight)
    val videoShort = minOf(videoWidth, videoHeight)
    return videoLong <= displayLong && videoShort <= displayShort
}

/** What this TV cannot present about a format, for the diagnosis line. Empty = all of it. */
fun presentationShortfalls(
    videoWidth: Int,
    videoHeight: Int,
    videoMimeType: String?,
    colorTransfer: Int,
    supportedHdrTypes: IntArray,
    displayWidth: Int,
    displayHeight: Int,
): List<String> {
    val shortfalls = mutableListOf<String>()
    val presentation = hdrPresentationOf(videoMimeType, colorTransfer)
    if (!isHdrPresentable(presentation, supportedHdrTypes)) {
        shortfalls += "hdr:${presentation.name}"
    }
    if (!isResolutionNative(videoWidth, videoHeight, displayWidth, displayHeight)) {
        shortfalls += "downscaled:${videoWidth}x$videoHeight"
    }
    return shortfalls
}
