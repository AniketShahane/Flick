package com.flick.receiver.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * The canonical Flick color tokens (docs/design/design-tokens.md §1). The TV is
 * ALWAYS "the cinema" — fixed cinematic dark; it never re-tints from artwork, so
 * only the dark set lives here. Values match the design system hex exactly.
 *
 * The split is load-bearing: warm [Spark] carries content & action; cool [Link]
 * carries connection & focus. They never swap jobs.
 */
object FlickColor {
    // 1.1 Dark — "the cinema"
    /** TV playback bed — violet-black. Never pure #000. */
    val Canvas = Color(0xFF0B0912)
    val Surface = Color(0xFF15111D)
    val SurfaceRaised = Color(0xFF211B2B)
    val SurfaceRaisedAlt = Color(0xFF191521)

    /** Glass fill rgba(27,21,38,0.72) — controls floating over video only. */
    val Glass = Color(0xB81B1526)
    /** 1px hairline on glass rgba(255,255,255,0.14). */
    val GlassBorder = Color(0x24FFFFFF)

    val OnSurface = Color(0xFFF6F0F4)
    val OnSurfaceDim = Color(0xFFC8BFCA)
    val OnSurfaceFaint = Color(0xFF988E9B)

    val Outline = Color(0xFF403747)
    /** Card borders rgba(255,255,255,0.07). */
    val OutlineHairline = Color(0x12FFFFFF)

    // 1.3 Brand accents — the split
    /** Content & action: playhead fill, play button, CTAs, wordmark dot. */
    val Spark = Color(0xFFFF6B57)
    val SparkLight = Color(0xFFFF8D7D)
    val SparkSoft = Color(0xFFFFD0C8)
    /** Connection & focus ONLY: D-pad focus glow, pairing, sync shimmer, connected pills. */
    val Link = Color(0xFF41E5F2)

    // 1.4 Semantic
    val Live = Color(0xFF3A9B62)
    val Caution = Color(0xFFB87824)
    /** Crimson — unreachable/failed. Distinct from Spark; do not confuse. */
    val Trouble = Color(0xFFC9314D)
    val Info = Link

    // 1.5 Premium sheen — quality badges only (never UI chrome)
    val SheenGoldA = Color(0xFFD6A34E)
    val SheenGoldB = Color(0xFFFAE7B8)
    val SheenGoldC = Color(0xFFB9822C)
    val OnSheen = Color(0xFF3D2B13)

    /** Coral target gradient — the playhead fill / play circle. */
    val SparkGradient = Brush.horizontalGradient(listOf(SparkLight, Spark))

    /** Premium sheen gradient for the DV/HDR badge flourish. */
    val SheenGradient = Brush.horizontalGradient(listOf(SheenGoldA, SheenGoldB, SheenGoldC))

    // Playhead-track tints (§7 TV scrub bar)
    val TrackBase = Color(0x2EFFFFFF)      // rgba(255,255,255,0.18)
    val TrackBuffered = Color(0x47FFFFFF)  // rgba(255,255,255,0.28)

    // Focus glow layers (§1.7)
    val FocusRingSoft = Color(0x3D41E5F2)  // rgba(65,229,242,0.24)
    val FocusGlow = Color(0x6141E5F2)      // rgba(65,229,242,0.38)

    /** Scrim end — soft transparent → rgba(11,9,18,0.82), never a hard bar. */
    val ScrimEnd = Color(0xD10B0912)
}
