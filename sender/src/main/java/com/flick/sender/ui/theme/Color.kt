package com.flick.sender.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * The Flick palette — the visual contract from docs/design/design-tokens.md.
 * Two surfaces share one language: warm Spark for content & action, cool Link
 * strictly for connection & focus. They never swap jobs.
 *
 * Held as an [Immutable] bundle behind [LocalFlickColors] so components read the
 * brand tokens directly (which is what keeps the Spark playhead / play button
 * anchored even when Material You re-tints the Material [androidx.compose.material3.ColorScheme]).
 */
@Immutable
data class FlickColors(
    val isLight: Boolean,
    // Surfaces
    val canvas: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceRaisedAlt: Color,
    val surfaceTonal: Color,
    val glass: Color,
    val glassBorder: Color,
    // Text / icon inks
    val onSurface: Color,
    val onSurfaceDim: Color,
    val onSurfaceFaint: Color,
    val outline: Color,
    val outlineHairline: Color,
    // Brand split
    val spark: Color,
    val sparkLight: Color,
    val sparkSoft: Color,
    val link: Color,
    // Semantic
    val live: Color,
    val caution: Color,
    val trouble: Color,
    val info: Color,
)

// --- Raw brand constants (identical hue on both themes) ---------------------
val Spark = Color(0xFFFF4B24)
val SparkLight = Color(0xFFFF6A47)
val SparkSoft = Color(0xFFFF8B6E)
val SparkOnLight = Color(0xFFE63E1A)
val Link = Color(0xFF3FD9FF)
val LinkOnLight = Color(0xFF0E7E9C)

val Live = Color(0xFF34D389)
val LiveOnLight = Color(0xFF1E9E66)
val Caution = Color(0xFFFFB454)
val Trouble = Color(0xFFFF3B5C)

// Premium quality-badge sheen (DV / HDR only — never UI chrome).
val PremiumInk = Color(0xFF3A2E14)
val PremiumGoldA = Color(0xFFE9C87C)
val PremiumGoldB = Color(0xFFF7ECD2)
val PremiumGoldC = Color(0xFFD9B45F)

val DarkFlickColors = FlickColors(
    isLight = false,
    canvas = Color(0xFF08070C),
    surface = Color(0xFF0F0D14),
    surfaceRaised = Color(0xFF181521),
    surfaceRaisedAlt = Color(0xFF14121A),
    surfaceTonal = Color(0xFF181521),
    glass = Color(0x9E201B2C), // rgba(32,27,44,0.62)
    glassBorder = Color(0x24FFFFFF), // rgba(255,255,255,0.14)
    onSurface = Color(0xFFEDEAF2),
    onSurfaceDim = Color(0xFF9B94A8),
    onSurfaceFaint = Color(0xFF6A6478),
    outline = Color(0xFF2C2838),
    outlineHairline = Color(0x12FFFFFF), // rgba(255,255,255,0.07)
    spark = Spark,
    sparkLight = SparkLight,
    sparkSoft = SparkSoft,
    link = Link,
    live = Live,
    caution = Caution,
    trouble = Trouble,
    info = Link,
)

val LightFlickColors = FlickColors(
    isLight = true,
    canvas = Color(0xFF08070C),
    surface = Color(0xFFFAF8F5),
    surfaceRaised = Color(0xFFFFFFFF),
    surfaceRaisedAlt = Color(0xFFFFFFFF),
    surfaceTonal = Color(0xFFF0EBE4),
    glass = Color(0xF2FFFFFF),
    glassBorder = Color(0x14000000),
    onSurface = Color(0xFF1D1826),
    onSurfaceDim = Color(0xFF6E6678),
    onSurfaceFaint = Color(0xFF98909F),
    outline = Color(0xFFE7E1D9),
    outlineHairline = Color(0x14000000),
    spark = SparkOnLight,
    sparkLight = SparkLight,
    sparkSoft = SparkSoft,
    link = LinkOnLight,
    live = LiveOnLight,
    caution = Color(0xFFB97A1E),
    trouble = Trouble,
    info = LinkOnLight,
)

/** Reusable brand gradients. The Spark fill leans warm; premium sheen is gold. */
object FlickGradients {
    /** linear(120°, #FF6A47 → #FF4B24) — playhead fill, play button, CTA. */
    fun spark(dark: Boolean = true): Brush = Brush.linearGradient(
        colors = listOf(SparkLight, if (dark) Spark else SparkOnLight),
    )

    /** linear(115°, #E9C87C → #F7ECD2 45% → #D9B45F) — DV / HDR badges only. */
    val premiumSheen: Brush = Brush.linearGradient(
        0f to PremiumGoldA,
        0.45f to PremiumGoldB,
        1f to PremiumGoldC,
    )
}

val LocalFlickColors = staticCompositionLocalOf { DarkFlickColors }
