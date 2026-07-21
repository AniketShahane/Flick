package com.flick.receiver.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/**
 * Fixed cinematic-dark theme for the TV. Per design-tokens.md §1.6 the TV NEVER
 * re-tints — it holds this palette under every film. Wraps tv-material3's
 * [MaterialTheme] with the Flick color/type tokens.
 */
@Composable
fun FlickTvTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = FlickColor.Spark,
        onPrimary = FlickColor.OnSurface,
        primaryContainer = FlickColor.SurfaceRaised,
        onPrimaryContainer = FlickColor.OnSurface,
        secondary = FlickColor.Link,
        onSecondary = FlickColor.Canvas,
        background = FlickColor.Canvas,
        onBackground = FlickColor.OnSurface,
        surface = FlickColor.Surface,
        onSurface = FlickColor.OnSurface,
        surfaceVariant = FlickColor.SurfaceRaised,
        onSurfaceVariant = FlickColor.OnSurfaceDim,
        border = FlickColor.Outline,
        error = FlickColor.Trouble,
        onError = FlickColor.OnSurface,
    )
    MaterialTheme(
        colorScheme = colors,
        typography = FlickTvTypography,
        content = content,
    )
}

/** The 5% overscan-safe inset (design §3): all chrome/text lives inside this. */
val OverscanSafe = PaddingValues(horizontal = 48.dp, vertical = 27.dp)

/**
 * Viewport-relative safe area for every TV chrome surface. `OverscanSafe` remains
 * as a source-compatible fallback while screen owners migrate to this composable;
 * the new primitive keeps the 5% contract at both 1080p and 4K instead of tying
 * it to one density.
 */
@Composable
fun rememberTvSafeAreaPadding(): PaddingValues {
    val configuration = LocalConfiguration.current
    val horizontal = (configuration.screenWidthDp.coerceAtLeast(0) * 0.05f).dp
    val vertical = (configuration.screenHeightDp.coerceAtLeast(0) * 0.05f).dp
    return if (horizontal == 0.dp || vertical == 0.dp) OverscanSafe else PaddingValues(horizontal, vertical)
}

/**
 * e2 glass (§1.6): translucent violet fill + a 1px white hairline. Used by the
 * quality/metrics cards that float over the film. (A hardware blur is optional
 * and API-gated; the translucent fill carries the read at 10 ft.)
 */
fun Modifier.glass(shape: Shape = FlickShape.Md): Modifier =
    this
        .background(FlickColor.Glass, shape)
        .border(1.dp, FlickColor.GlassBorder, shape)

/** Soft bottom scrim (§1.6) — a gradient, never a hard bar. */
fun bottomScrimBrush(): Brush =
    Brush.verticalGradient(listOf(Color.Transparent, FlickColor.ScrimEnd))

/**
 * Poster-derived ambient glow behind the transport (§1.6, ≤30% opacity so HDR
 * video stays the brightest thing). Warm amber radial by default.
 */
fun ambientGlowBrush(tint: Color = FlickColor.Spark): Brush =
    Brush.verticalGradient(
        0f to Color.Transparent,
        1f to tint.copy(alpha = 0.18f),
    )
