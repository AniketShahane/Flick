package com.flick.sender.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.flick.sender.R

/**
 * Typography from design-tokens.md §2. Display / titles use Archivo, UI and body
 * copy use Manrope, and every numeric or telemetry style uses IBM Plex Mono with
 * **tabular figures mandatory** so digits never shimmy while the clock runs. All
 * three faces load via downloadable Google Fonts and **fall back gracefully** to
 * the platform default if the provider is unavailable (see [googleFamilyOrDefault]);
 * weight, tracking and `tnum` still apply to the fallback face.
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

val Archivo: FontFamily = googleFamilyOrDefault(
    name = "Archivo",
    weights = listOf(FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold),
    fallback = FontFamily.SansSerif,
)

val Manrope: FontFamily = googleFamilyOrDefault(
    name = "Manrope",
    weights = listOf(FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold),
    fallback = FontFamily.SansSerif,
)

val PlexMono: FontFamily = googleFamilyOrDefault(
    name = "IBM Plex Mono",
    weights = listOf(FontWeight.Medium, FontWeight.SemiBold),
    fallback = FontFamily.Monospace,
)

/** Tabular-figures feature — required everywhere a live number is drawn. */
private const val TNUM = "tnum"

/** Standalone Flick text styles used directly by components. */
object FlickText {

    // --- Archivo: display & titles ---

    val displayLarge = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 44.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.045).em,
    )
    val headlineLarge = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 30.sp,
        lineHeight = 31.sp,
        letterSpacing = (-0.04).em,
    )
    val headlineMedium = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 26.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.04).em,
    )
    val headlineSmall = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 24.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.035).em,
    )
    val titleLarge = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 23.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.035).em,
    )
    val titleMedium = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.Bold,
        fontSize = 19.sp,
        lineHeight = 23.sp,
        letterSpacing = (-0.03).em,
    )
    val titleSmall = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        lineHeight = 20.sp,
        letterSpacing = (-0.025).em,
    )
    val labelLarge = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.Bold,
        fontSize = 14.5.sp,
        lineHeight = 17.sp,
        letterSpacing = (-0.025).em,
    )

    // --- Manrope: body & labels ---

    val bodyLarge = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Bold,
        fontSize = 14.5.sp,
        lineHeight = 19.5.sp,
        letterSpacing = (-0.015).em,
    )
    val bodyMedium = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.5.sp,
        lineHeight = 17.sp,
    )
    val bodySmall = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    )
    val labelMedium = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Bold,
        fontSize = 12.5.sp,
        lineHeight = 15.sp,
        letterSpacing = (-0.01).em,
    )
    val labelSmall = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Bold,
        fontSize = 11.5.sp,
        lineHeight = 14.sp,
        letterSpacing = (-0.01).em,
    )

    // --- IBM Plex Mono: every live number, always tabular ---

    /** Section eyebrow — caller supplies UPPERCASE copy. */
    val monoEyebrow = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 9.5.sp,
        letterSpacing = 0.13.em,
        fontFeatureSettings = TNUM,
    )

    /** Wider eyebrow for the discovery count line. */
    val monoEyebrowWide = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        letterSpacing = 0.16.em,
        fontFeatureSettings = TNUM,
    )

    /** Badge chip riding on a poster or tile. */
    val monoBadge = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 9.5.sp,
        letterSpacing = 0.08.em,
        fontFeatureSettings = TNUM,
    )

    /** Badge chip in the detail sheet's fact row. */
    val monoChip = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        letterSpacing = 0.08.em,
        fontFeatureSettings = TNUM,
    )

    /** Compact telemetry: durations, sizes, decoder names. */
    val monoSmall = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        fontFeatureSettings = TNUM,
    )

    /** Throughput and the scrub time row. */
    val monoValue = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.5.sp,
        fontFeatureSettings = TNUM,
    )

    /** The pairing code. */
    val monoDisplay = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        letterSpacing = 0.14.em,
        fontFeatureSettings = TNUM,
    )

    /** Quality-sheet gauge readout. */
    val monoGauge = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 25.sp,
        fontFeatureSettings = TNUM,
    )

    // --- Legacy aliases: kept so call sites outside ui/theme keep compiling. ---
    val title = displayLarge
    val heading = headlineMedium
    val body = bodyLarge
    val caption = bodyMedium
    val timecode = monoValue
    val mono = monoSmall
    val monoLabel = monoEyebrow
}

val FlickTypography = Typography(
    displayLarge = FlickText.displayLarge,
    displayMedium = FlickText.displayLarge.copy(fontSize = 34.sp, lineHeight = 34.sp),
    displaySmall = FlickText.headlineLarge,
    headlineLarge = FlickText.headlineLarge,
    headlineMedium = FlickText.headlineMedium,
    headlineSmall = FlickText.headlineSmall,
    titleLarge = FlickText.titleLarge,
    titleMedium = FlickText.titleMedium,
    titleSmall = FlickText.titleSmall,
    bodyLarge = FlickText.bodyLarge,
    bodyMedium = FlickText.bodyMedium,
    bodySmall = FlickText.bodySmall,
    labelLarge = FlickText.labelLarge,
    labelMedium = FlickText.labelMedium,
    labelSmall = FlickText.labelSmall,
)
