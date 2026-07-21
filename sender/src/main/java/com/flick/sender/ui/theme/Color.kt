package com.flick.sender.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Sender-only semantic roles that Material color roles cannot represent without
 * losing Flick's product meaning. They deliberately describe jobs, not a visual
 * direction: screens may use media and synchronization roles without knowing a
 * hex value.
 */
@Immutable
data class FlickColors(
    val isLight: Boolean,
    val canvas: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceRaisedAlt: Color,
    val surfaceTonal: Color,
    val glass: Color,
    val glassBorder: Color,
    val onSurface: Color,
    val onSurfaceDim: Color,
    val onSurfaceFaint: Color,
    val outline: Color,
    val outlineHairline: Color,
    val spark: Color,
    val sparkLight: Color,
    val sparkSoft: Color,
    val link: Color,
    val live: Color,
    val caution: Color,
    val trouble: Color,
    val info: Color,
)

// Selected option 2: warm editorial action, cyan only for LAN/synchronization.
val Spark = Color(0xFFFF6B57)
val SparkLight = Color(0xFFFF8D7D)
val SparkSoft = Color(0xFFFFD0C8)
val SparkOnLight = Color(0xFFC94B3D)
val Link = Color(0xFF41E5F2)
val LinkOnLight = Color(0xFF007F91)
val Live = Color(0xFF3A9B62)
val LiveOnLight = Color(0xFF277A4B)
val Caution = Color(0xFFB87824)
val Trouble = Color(0xFFC9314D)

val PremiumInk = Color(0xFF3D2B13)
val PremiumGoldA = Color(0xFFD6A34E)
val PremiumGoldB = Color(0xFFFAE7B8)
val PremiumGoldC = Color(0xFFB9822C)

val DarkFlickColors = FlickColors(
    isLight = false,
    canvas = Color(0xFF0B0912),
    surface = Color(0xFF15111D),
    surfaceRaised = Color(0xFF211B2B),
    surfaceRaisedAlt = Color(0xFF191521),
    surfaceTonal = Color(0xFF211B2B),
    glass = Color(0xB8271526),
    glassBorder = Color(0x24FFFFFF),
    onSurface = Color(0xFFF6F0F4),
    onSurfaceDim = Color(0xFFC8BFCA),
    onSurfaceFaint = Color(0xFF988E9B),
    outline = Color(0xFF403747),
    outlineHairline = Color(0x12FFFFFF),
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
    canvas = Color(0xFFFFF8ED),
    surface = Color(0xFFFFF8ED),
    surfaceRaised = Color(0xFFFFFCF6),
    surfaceRaisedAlt = Color(0xFFFFF4E4),
    surfaceTonal = Color(0xFFF6E8D3),
    glass = Color(0xF2FFFCF6),
    glassBorder = Color(0x1A3F3037),
    onSurface = Color(0xFF3F3037),
    onSurfaceDim = Color(0xFF705B62),
    onSurfaceFaint = Color(0xFF9A8287),
    outline = Color(0xFFE6D4C0),
    outlineHairline = Color(0x1F3F3037),
    spark = SparkOnLight,
    sparkLight = SparkLight,
    sparkSoft = SparkOnLight,
    link = LinkOnLight,
    live = LiveOnLight,
    caution = Caution,
    trouble = Trouble,
    info = LinkOnLight,
)

object FlickGradients {
    fun spark(dark: Boolean = true): Brush = Brush.linearGradient(
        colors = listOf(SparkLight, if (dark) Color(0xFFFF6250) else SparkOnLight),
    )

    val premiumSheen: Brush = Brush.linearGradient(
        0f to PremiumGoldA,
        0.45f to PremiumGoldB,
        1f to PremiumGoldC,
    )
}

val LocalFlickColors = staticCompositionLocalOf { DarkFlickColors }
