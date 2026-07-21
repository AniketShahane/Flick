package com.flick.receiver.player

internal const val SUBTITLE_SIZE_REDUCTION_SP = 2f
internal const val SUBTITLE_GLYPH_BACKGROUND_ALPHA = 0
internal const val SUBTITLE_WINDOW_ALPHA = 140 // 55%: one plate, never stacked per glyph.

/** Cue payload shape only; subtitle text and bitmap contents are never retained. */
enum class SubtitleCueKind { NONE, TEXT, BITMAP, MIXED }

internal fun subtitleCueKind(hasText: Boolean, hasBitmap: Boolean): SubtitleCueKind = when {
    hasText && hasBitmap -> SubtitleCueKind.MIXED
    hasBitmap -> SubtitleCueKind.BITMAP
    hasText -> SubtitleCueKind.TEXT
    else -> SubtitleCueKind.NONE
}

/** Keeps Media3's current viewport-relative baseline, reduced by exactly 2sp. */
internal fun reducedSubtitleTextSizeSp(
    viewHeightPx: Int,
    scaledDensity: Float,
    captionFontScale: Float,
    defaultTextSizeFraction: Float,
): Float {
    if (viewHeightPx <= 0 || scaledDensity <= 0f) return 1f
    val currentSizeSp = viewHeightPx * defaultTextSizeFraction * captionFontScale / scaledDensity
    return (currentSizeSp - SUBTITLE_SIZE_REDUCTION_SP).coerceAtLeast(1f)
}
