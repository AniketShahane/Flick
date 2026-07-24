@file:OptIn(ExperimentalTextApi::class)

package com.flick.receiver.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Typography
import com.flick.receiver.R

/**
 * Typography (receiver-expressive-spec.md §4), TV scale. Faces load via
 * downloadable Google Fonts with graceful fallback:
 *  - **Display** — Archivo (500/600/700/800): headlines, the now-playing title,
 *    the wordmark. Tight tracking, −0.04 em to −0.05 em.
 *  - **Body / UI** — Manrope (500/600/700/800): reading copy, row labels,
 *    button text.
 *  - **Mono** — IBM Plex Mono (500/600): timecodes, telemetry, uppercase
 *    eyebrows. TABULAR figures are mandatory so digits never shimmy while the
 *    clock runs; eyebrows track +0.14 em to +0.2 em.
 *
 * If the Google Fonts provider is unavailable (a device with no Play Services)
 * the families resolve to the platform default and the platform monospace — this
 * never hard-fails.
 *
 * Sizes follow the §1a floors: reading copy never below 24 sp, mono micro-labels
 * flat at 16 sp, mono running numbers at least 20 sp. Nothing renders below 16 sp.
 */
object FlickType {

    private val provider = GoogleFont.Provider(
        providerAuthority = "com.google.android.gms.fonts",
        providerPackage = "com.google.android.gms",
        certificates = R.array.com_google_android_gms_fonts_certs,
    )

    private fun family(name: String, vararg weights: FontWeight): FontFamily {
        val gFont = GoogleFont(name)
        // Listing each weight lets the resolver request the matching face and,
        // on failure, transparently fall back to the platform default.
        return FontFamily(weights.map { Font(googleFont = gFont, fontProvider = provider, weight = it) })
    }

    /** Archivo — display / headlines / wordmark. Falls back to default. */
    val Display: FontFamily = family(
        "Archivo",
        FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold,
    )

    /** Manrope — body / UI. Falls back to default. */
    val Body: FontFamily = family(
        "Manrope",
        FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold,
    )

    /** IBM Plex Mono — timecode / telemetry / eyebrows. Falls back to monospace. */
    val Mono: FontFamily = family(
        "IBM Plex Mono",
        FontWeight.Medium, FontWeight.SemiBold,
    )

    /**
     * Tabular monospace style for any running number (timecodes, throughput,
     * the clock pill, the pairing code). `tnum` keeps digit advance constant so
     * the number never shimmies — it is mandatory, never optional.
     */
    fun monoTabular(
        sizeSp: Int,
        weight: FontWeight = FontWeight.SemiBold,
        family: FontFamily = Mono,
        letterSpacing: TextUnit = TextUnit.Unspecified,
    ): TextStyle = TextStyle(
        fontFamily = family,
        fontWeight = weight,
        fontSize = sizeSp.sp,
        letterSpacing = letterSpacing,
        fontFeatureSettings = "tnum",
    )

    /**
     * The uppercase mono micro-label — eyebrows, stat labels, spec chips, health
     * pills. Flat 16 sp per the §1a floor; callers uppercase the text themselves
     * so the string resource stays the source of truth. `tnum` is carried too:
     * these labels often interleave numbers (`5 GHz · −44 dBm`).
     */
    fun monoEyebrow(
        sizeSp: Int = 16,
        trackingEm: Float = 0.2f,
        weight: FontWeight = FontWeight.SemiBold,
    ): TextStyle = TextStyle(
        fontFamily = Mono,
        fontWeight = weight,
        fontSize = sizeSp.sp,
        letterSpacing = trackingEm.em,
        fontFeatureSettings = "tnum",
    )

    /** Archivo display style — the §1a "display" class, tight and heavy. */
    fun display(
        sizeSp: Int,
        weight: FontWeight = FontWeight.ExtraBold,
        trackingEm: Float = -0.045f,
        lineHeightRatio: Float = 1f,
    ): TextStyle = TextStyle(
        fontFamily = Display,
        fontWeight = weight,
        fontSize = sizeSp.sp,
        lineHeight = (sizeSp * lineHeightRatio).sp,
        letterSpacing = trackingEm.em,
    )

    /** Manrope reading copy — never below the 24 sp floor. */
    fun body(
        sizeSp: Int = 24,
        weight: FontWeight = FontWeight.SemiBold,
        trackingEm: Float = -0.015f,
        lineHeightRatio: Float = 1.4f,
    ): TextStyle = TextStyle(
        fontFamily = Body,
        fontWeight = weight,
        fontSize = sizeSp.coerceAtLeast(24).sp,
        lineHeight = (sizeSp.coerceAtLeast(24) * lineHeightRatio).sp,
        letterSpacing = trackingEm.em,
    )
}

/**
 * TV typography for the theme. Roles map to the §1a ten-foot scale:
 *  - display* / headline* / titleLarge — Archivo, the expressive voice.
 *  - title(Medium|Small) / body* / labelLarge — Manrope reading copy, ≥ 24 sp.
 *  - label(Medium|Small) — the uppercase IBM Plex Mono micro-label, 16 sp flat.
 *
 * Screen owners must not override these below their role's floor.
 */
val FlickTvTypography: Typography = Typography(
    // Pairing headline — design 104 px ÷ 2, line-height 0.88.
    displayLarge = TextStyle(
        fontFamily = FlickType.Display,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 52.sp,
        lineHeight = 46.sp,
        letterSpacing = (-0.05).em,
    ),
    displayMedium = TextStyle(
        fontFamily = FlickType.Display,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 40.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.048).em,
    ),
    displaySmall = TextStyle(
        fontFamily = FlickType.Display,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 34.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.045).em,
    ),
    // Now-playing title — design 68 px ÷ 2, line-height 0.98.
    headlineLarge = TextStyle(
        fontFamily = FlickType.Display,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 34.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.045).em,
    ),
    // Handshake title / panel headers — design 54 & 34 px ÷ 2.
    headlineMedium = TextStyle(
        fontFamily = FlickType.Display,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 27.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.04).em,
    ),
    headlineSmall = TextStyle(
        fontFamily = FlickType.Display,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.035).em,
    ),
    titleLarge = TextStyle(
        fontFamily = FlickType.Display,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 27.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.04).em,
    ),
    titleMedium = TextStyle(
        fontFamily = FlickType.Body,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.02).em,
    ),
    titleSmall = TextStyle(
        fontFamily = FlickType.Body,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.015).em,
    ),
    bodyLarge = TextStyle(
        fontFamily = FlickType.Body,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.015).em,
    ),
    bodyMedium = TextStyle(
        fontFamily = FlickType.Body,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.015).em,
    ),
    bodySmall = TextStyle(
        fontFamily = FlickType.Body,
        fontWeight = FontWeight.Medium,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.01).em,
    ),
    labelLarge = TextStyle(
        fontFamily = FlickType.Body,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.015).em,
    ),
    // The uppercase mono micro-label — 16 sp flat, wide tracking.
    labelMedium = TextStyle(
        fontFamily = FlickType.Mono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.14.em,
        fontFeatureSettings = "tnum",
    ),
    labelSmall = TextStyle(
        fontFamily = FlickType.Mono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.em,
        fontFeatureSettings = "tnum",
    ),
)
