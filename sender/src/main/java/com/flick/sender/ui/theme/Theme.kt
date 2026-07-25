package com.flick.sender.ui.theme

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Literal Material 3 Expressive entry point for the phone. The brand is committed:
 * electric blue for action, amber for the media itself, cyan for the LAN. Dynamic
 * color survives only as a wallpaper tint on the quiet tonal containers — it is
 * never allowed to reach an anchored role.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FlickTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val flick = if (darkTheme) DarkFlickColors else LightFlickColors
    val context = LocalContext.current
    val useDynamicTonalRoles = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val base = when {
        useDynamicTonalRoles ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }

    FlickMaterial(flick, flickColorScheme(flick, base, useDynamicTonalRoles), content)
}

/**
 * Forces the cinematic dark set regardless of the system theme. Now Playing, the
 * connecting overlay and the quality sheet are dark by design, not by preference.
 */
@Composable
fun FlickCinematicTheme(content: @Composable () -> Unit) {
    val scheme = remember {
        flickColorScheme(CinematicFlickColors, darkColorScheme(), useDynamicTonalRoles = false)
    }
    FlickMaterial(CinematicFlickColors, scheme, content)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FlickMaterial(
    flick: FlickColors,
    scheme: ColorScheme,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalFlickColors provides flick) {
        MaterialExpressiveTheme(
            colorScheme = scheme,
            motionScheme = MotionScheme.expressive(),
            shapes = FlickShapes,
            typography = FlickTypography,
            content = content,
        )
    }
}

private fun flickColorScheme(
    flick: FlickColors,
    base: ColorScheme,
    useDynamicTonalRoles: Boolean,
): ColorScheme = base.copy(
    primary = flick.primary,
    onPrimary = flick.onPrimary,
    primaryContainer = flick.primaryContainer,
    onPrimaryContainer = flick.onPrimaryContainer,
    inversePrimary = flick.primaryFixed,
    secondary = flick.link,
    onSecondary = if (flick.isLight) flick.onPrimary else flick.onInverseSurface,
    secondaryContainer = flick.link.copy(alpha = if (flick.isLight) 0.16f else 0.20f),
    onSecondaryContainer = flick.link,
    tertiary = flick.spark,
    onTertiary = flick.onSpark,
    tertiaryContainer = flick.sparkPale,
    onTertiaryContainer = flick.onSpark,
    background = flick.surface,
    onBackground = flick.onSurface,
    surface = flick.surface,
    onSurface = flick.onSurface,
    surfaceTint = flick.primary,
    // Wallpaper tint is intentionally confined to quiet tonal containment.
    surfaceVariant = if (useDynamicTonalRoles) base.surfaceVariant else flick.surfaceTonal,
    onSurfaceVariant = flick.onSurfaceDim,
    surfaceBright = flick.surfaceRaised,
    surfaceDim = flick.surfaceRaisedAlt,
    surfaceContainer = if (useDynamicTonalRoles) base.surfaceContainer else flick.surfaceTonal,
    surfaceContainerHigh = if (useDynamicTonalRoles) base.surfaceContainerHigh else flick.surfaceRaised,
    surfaceContainerHighest = if (useDynamicTonalRoles) base.surfaceContainerHighest else flick.surfaceRaised,
    surfaceContainerLow = if (useDynamicTonalRoles) base.surfaceContainerLow else flick.surfaceRaisedAlt,
    surfaceContainerLowest = flick.surface,
    inverseSurface = flick.inverseSurface,
    inverseOnSurface = flick.onInverseSurface,
    outline = flick.outline,
    outlineVariant = flick.outlineHairline,
    scrim = flick.scrim,
    error = flick.trouble,
    onError = Color.White,
    errorContainer = flick.trouble.copy(alpha = if (flick.isLight) 0.12f else 0.20f),
    onErrorContainer = flick.trouble,
)

/**
 * Frosted treatment for the floating bottom nav. Compose cannot sample the backdrop
 * without a new dependency, and `Modifier.blur` blurs the content rather than what
 * is behind it — so the glass is approximated with an opaque-enough tint, a raking
 * sheen and a bright hairline.
 */
fun Modifier.flickGlass(
    colors: FlickColors,
    shape: Shape = RoundedCornerShape(FlickCorners.nav),
): Modifier = this
    .shadow(
        elevation = 20.dp,
        shape = shape,
        clip = false,
        ambientColor = NavShadow,
        spotColor = NavShadow,
    )
    .background(color = colors.glass, shape = shape)
    .background(brush = FlickGradients.navSheen, shape = shape)
    .border(width = 1.dp, color = colors.glassBorder, shape = shape)

fun Modifier.flickRaised(
    colors: FlickColors,
    shape: Shape = RoundedCornerShape(FlickCorners.tile),
): Modifier = this
    .background(color = colors.surfaceRaised, shape = shape)
    .border(width = 1.dp, color = colors.outlineHairline, shape = shape)
