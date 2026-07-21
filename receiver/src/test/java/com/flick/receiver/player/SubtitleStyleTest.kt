package com.flick.receiver.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleStyleTest {

    @Test fun subtitleUsesOneTranslucentWindowInsteadOfStackedGlyphBackgrounds() {
        assertEquals(0, SUBTITLE_GLYPH_BACKGROUND_ALPHA)
        assertEquals((255f * 0.55f).toInt(), SUBTITLE_WINDOW_ALPHA)
    }

    @Test fun cueDiagnosticsClassifyShapeWithoutReadingPayloads() {
        assertEquals(SubtitleCueKind.NONE, subtitleCueKind(hasText = false, hasBitmap = false))
        assertEquals(SubtitleCueKind.TEXT, subtitleCueKind(hasText = true, hasBitmap = false))
        assertEquals(SubtitleCueKind.BITMAP, subtitleCueKind(hasText = false, hasBitmap = true))
        assertEquals(SubtitleCueKind.MIXED, subtitleCueKind(hasText = true, hasBitmap = true))
    }

    @Test fun media3BaselineIsReducedByExactlyTwoSp() {
        val currentSizeSp = 1080f * 0.0533f / 2f
        val reduced = reducedSubtitleTextSizeSp(
            viewHeightPx = 1080,
            scaledDensity = 2f,
            captionFontScale = 1f,
            defaultTextSizeFraction = 0.0533f,
        )

        assertEquals(SUBTITLE_SIZE_REDUCTION_SP, currentSizeSp - reduced, 0.0001f)
    }

    @Test fun captionScaleRecomputesWithoutNeedingALayoutChange() {
        val normal = reducedSubtitleTextSizeSp(1080, 2f, 1f, 0.0533f)
        val enlarged = reducedSubtitleTextSizeSp(1080, 2f, 1.25f, 0.0533f)

        assertEquals(26.782f, normal, 0.0001f)
        assertEquals(33.9775f, enlarged, 0.0001f)
    }

    @Test fun viewportAndDensityChangesBothRecomputeTheBaseline() {
        val initial = reducedSubtitleTextSizeSp(1080, 2f, 1f, 0.0533f)
        val resized = reducedSubtitleTextSizeSp(720, 2f, 1f, 0.0533f)
        val densityChanged = reducedSubtitleTextSizeSp(1080, 2.5f, 1f, 0.0533f)

        assertEquals(26.782f, initial, 0.0001f)
        assertEquals(17.188f, resized, 0.0001f)
        assertEquals(21.0256f, densityChanged, 0.0001f)
    }
}
