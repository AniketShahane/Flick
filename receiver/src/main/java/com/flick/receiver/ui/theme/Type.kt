@file:OptIn(ExperimentalTextApi::class)

package com.flick.receiver.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Typography
import com.flick.receiver.R

/**
 * Typography (design-tokens.md §2), TV scale — "no text smaller than 24dp
 * anywhere on TV". Faces load via downloadable Google Fonts with graceful
 * fallback:
 *  - Display / titles / wordmark: Space Grotesk (geometric, kinetic).
 *  - UI / body: platform default (zero font-loading cost).
 *  - Timecode / telemetry: Roboto Mono, TABULAR figures mandatory so digits
 *    never shimmy while the clock runs.
 *
 * If the provider is unavailable the families resolve to the platform default —
 * this never hard-fails (design §2). Sizes here are the 10-ft scale; the one
 * weight step heavier / +1% tracking rule is baked into the styles below.
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

    /** Space Grotesk — display / titles / wordmark. Falls back to default. */
    val Display: FontFamily = family(
        "Space Grotesk",
        FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold,
    )

    /** Roboto Mono — timecode / telemetry. Falls back to platform monospace. */
    val Mono: FontFamily = family(
        "Roboto Mono",
        FontWeight.Normal, FontWeight.Medium, FontWeight.Bold,
    )

    /**
     * Tabular monospace style for any running number (timecodes, throughput).
     * `tnum` keeps digit advance constant so the clock never shimmies.
     */
    fun monoTabular(
        sizeSp: Int,
        weight: FontWeight = FontWeight.Bold,
        family: FontFamily = Mono,
    ): TextStyle = TextStyle(
        fontFamily = family,
        fontWeight = weight,
        fontSize = sizeSp.sp,
        fontFeatureSettings = "tnum",
    )
}

/**
 * TV typography for the theme. Roles map to the §2 ten-foot scale; screen owners
 * must not override these defaults below 24sp for visible TV copy.
 */
val FlickTvTypography: Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = FlickType.Display,
        fontWeight = FontWeight.Bold,
        fontSize = 48.sp,
        lineHeight = 54.sp,
        letterSpacing = (-0.01).em,
    ),
    headlineLarge = TextStyle(
        fontFamily = FlickType.Display,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.01.em,
    ),
    titleLarge = TextStyle(
        fontFamily = FlickType.Display,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.01.em,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.01.em,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 24.sp,
        lineHeight = 28.sp,
    ),
)
