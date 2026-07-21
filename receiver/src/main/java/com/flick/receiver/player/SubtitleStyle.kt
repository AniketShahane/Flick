package com.flick.receiver.player

internal const val SUBTITLE_SIZE_REDUCTION_SP = 2f

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
