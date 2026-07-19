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
    /** TV playback bed — near-black, violet-warmed. Never pure #000. */
    val Canvas = Color(0xFF08070C)
    val Surface = Color(0xFF0F0D14)
    val SurfaceRaised = Color(0xFF181521)
    val SurfaceRaisedAlt = Color(0xFF14121A)

    /** Glass fill rgba(32,27,44,0.62) — controls floating over video. */
    val Glass = Color(0x9E201B2C)
    /** 1px hairline on glass rgba(255,255,255,0.14). */
    val GlassBorder = Color(0x24FFFFFF)

    val OnSurface = Color(0xFFEDEAF2)
    val OnSurfaceDim = Color(0xFF9B94A8)
    val OnSurfaceFaint = Color(0xFF6A6478)

    val Outline = Color(0xFF2C2838)
    /** Card borders rgba(255,255,255,0.07). */
    val OutlineHairline = Color(0x12FFFFFF)

    // 1.3 Brand accents — the split
    /** Content & action: playhead fill, play button, CTAs, wordmark dot. */
    val Spark = Color(0xFFFF4B24)
    val SparkLight = Color(0xFFFF6A47)
    val SparkSoft = Color(0xFFFF8B6E)
    /** Connection & focus ONLY: D-pad focus glow, pairing, sync shimmer, connected pills. */
    val Link = Color(0xFF3FD9FF)

    // 1.4 Semantic
    val Live = Color(0xFF34D389)
    val Caution = Color(0xFFFFB454)
    /** Crimson — unreachable/failed. Distinct from Spark; do not confuse. */
    val Trouble = Color(0xFFFF3B5C)
    val Info = Link

    // 1.5 Premium sheen — quality badges only (never UI chrome)
    val SheenGoldA = Color(0xFFE9C87C)
    val SheenGoldB = Color(0xFFF7ECD2)
    val SheenGoldC = Color(0xFFD9B45F)
    val OnSheen = Color(0xFF3A2E14)

    /** Spark gradient linear(120°) #FF6A47 → #FF4B24 — the playhead fill / play circle. */
    val SparkGradient = Brush.horizontalGradient(listOf(SparkLight, Spark))

    /** Premium sheen gradient for the DV/HDR badge flourish. */
    val SheenGradient = Brush.horizontalGradient(listOf(SheenGoldA, SheenGoldB, SheenGoldC))

    // Playhead-track tints (§7 TV scrub bar)
    val TrackBase = Color(0x2EFFFFFF)      // rgba(255,255,255,0.18)
    val TrackBuffered = Color(0x47FFFFFF)  // rgba(255,255,255,0.28)

    // Focus glow layers (§1.7)
    val FocusRingSoft = Color(0x383FD9FF)  // rgba(63,217,255,0.22)
    val FocusGlow = Color(0x593FD9FF)      // rgba(63,217,255,0.35)

    /** Scrim end — a soft top-transparent → rgba(8,7,12,0.78) gradient, never a hard bar. */
    val ScrimEnd = Color(0xC708070C)
}
