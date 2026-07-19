package com.flick.sender.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.flick.sender.R

/**
 * Typography from design-tokens.md §2. Display / titles use Space Grotesk;
 * timecode & telemetry use Roboto Mono with **tabular figures mandatory** so
 * digits never shimmy while the clock runs. Both faces load via downloadable
 * Google Fonts and **fall back gracefully** to the platform default if the
 * provider is unavailable (see [googleFamilyOrDefault]).
 */
private val provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

private fun googleFamilyOrDefault(
    name: String,
    weights: List<FontWeight>,
    fallback: FontFamily,
): FontFamily = runCatching {
    val font = GoogleFont(name)
    FontFamily(weights.map { Font(googleFont = font, fontProvider = provider, weight = it) })
}.getOrDefault(fallback)

val SpaceGrotesk: FontFamily = googleFamilyOrDefault(
    name = "Space Grotesk",
    weights = listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold),
    fallback = FontFamily.SansSerif,
)

val RobotoMono: FontFamily = googleFamilyOrDefault(
    name = "Roboto Mono",
    weights = listOf(FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold),
    fallback = FontFamily.Monospace,
)

/** Tabular-figures feature — required everywhere a live number is drawn. */
private const val TNUM = "tnum"

/** Standalone Flick text styles used directly by components (mono timecode etc.). */
object FlickText {
    val title = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.02).em,
    )
    val heading = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    )
    val body = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    )
    val caption = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    )

    /** 17sp mono, tabular — the scrub timecode. */
    val timecode = TextStyle(
        fontFamily = RobotoMono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        fontFeatureSettings = TNUM,
    )

    /** Small mono meta (durations, sizes, throughput) — tabular. */
    val mono = TextStyle(
        fontFamily = RobotoMono,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        fontFeatureSettings = TNUM,
    )

    /** Mono label used for the "ON HOMENET — 2 FOUND" style eyebrows. */
    val monoLabel = TextStyle(
        fontFamily = RobotoMono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        letterSpacing = 0.12.em,
        fontFeatureSettings = TNUM,
    )
}

val FlickTypography = Typography(
    displayLarge = FlickText.title,
    headlineMedium = FlickText.title,
    headlineSmall = FlickText.heading,
    titleLarge = FlickText.heading,
    titleMedium = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = FlickText.body,
    bodyMedium = FlickText.body.copy(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = FlickText.caption,
    labelSmall = FlickText.caption.copy(fontSize = 11.sp),
)

/** Convenience: right-aligned mono for timecodes that hug an edge. */
val MonoEnd = FlickText.mono.copy(textAlign = TextAlign.End)
