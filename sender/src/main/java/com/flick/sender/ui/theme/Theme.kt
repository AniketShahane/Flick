package com.flick.sender.ui.theme

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Literal Material 3 Expressive entry point for the phone. Dynamic color is retained
 * only as a low-emphasis source for the system-derived base; warm ivory/plum,
 * action/target, LAN/sync, health, and error jobs are deliberately anchored.
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

    val scheme = base.copy(
        primary = flick.spark,
        onPrimary = Color.White,
        primaryContainer = flick.sparkLight,
        onPrimaryContainer = if (flick.isLight) flick.onSurface else Color(0xFF3D1612),
        secondary = flick.link,
        onSecondary = if (flick.isLight) Color.White else Color(0xFF06262A),
        secondaryContainer = flick.link.copy(alpha = if (flick.isLight) 0.16f else 0.20f),
        onSecondaryContainer = flick.link,
        tertiary = flick.live,
        onTertiary = Color.White,
        tertiaryContainer = flick.live.copy(alpha = if (flick.isLight) 0.14f else 0.20f),
        onTertiaryContainer = flick.live,
        background = flick.surface,
        onBackground = flick.onSurface,
        surface = flick.surface,
        onSurface = flick.onSurface,
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
        outline = flick.outline,
        outlineVariant = flick.outlineHairline,
        error = flick.trouble,
        onError = Color.White,
        errorContainer = flick.trouble.copy(alpha = if (flick.isLight) 0.12f else 0.20f),
        onErrorContainer = flick.trouble,
    )

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

val FlickColorsAccessor: FlickColors
    @Composable get() = LocalFlickColors.current

fun Modifier.flickGlass(colors: FlickColors, shape: Shape): Modifier =
    this
        .background(color = colors.glass, shape = shape)
        .border(width = 1.dp, color = colors.glassBorder, shape = shape)

fun Modifier.flickRaised(colors: FlickColors, shape: Shape): Modifier =
    this
        .background(color = colors.surfaceRaised, shape = shape)
        .border(width = 1.dp, color = colors.outlineHairline, shape = shape)
