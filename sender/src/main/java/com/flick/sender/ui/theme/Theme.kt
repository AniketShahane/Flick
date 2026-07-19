package com.flick.sender.ui.theme

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
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
 * FlickTheme — dark ("the cinema") and light ("the pocket"). On Android 12+ the
 * Material [androidx.compose.material3.ColorScheme] may take a Material-You tint
 * (so tonal chips / secondary containers pick up the wallpaper), but every
 * brand-critical visual reads [FlickColors] via [LocalFlickColors], so the Spark
 * playhead and play button stay **anchored** regardless of dynamic color.
 */
@Composable
fun FlickTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val flick = if (darkTheme) DarkFlickColors else LightFlickColors
    val context = LocalContext.current

    val base = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }

    // Anchor the roles the brand owns; let dynamic color keep the rest.
    val scheme = base.copy(
        primary = flick.spark,
        onPrimary = Color.White,
        secondary = flick.link,
        onSecondary = if (flick.isLight) Color.White else Color(0xFF06222B),
        error = flick.trouble,
        background = flick.surface,
        onBackground = flick.onSurface,
        surface = flick.surface,
        onSurface = flick.onSurface,
        surfaceVariant = flick.surfaceRaised,
        onSurfaceVariant = flick.onSurfaceDim,
        outline = flick.outline,
    )

    CompositionLocalProvider(LocalFlickColors provides flick) {
        MaterialTheme(
            colorScheme = scheme,
            typography = FlickTypography,
            shapes = FlickShapes,
            content = content,
        )
    }
}

/** Shorthand for the active brand palette inside composables. */
val FlickColorsAccessor: FlickColors
    @Composable get() = LocalFlickColors.current

/**
 * e2 "glass" — a translucent raised fill with a hairline border. True backdrop
 * blur needs a RenderEffect that isn't portable below Android 12, so this is the
 * flat-glass approximation used for controls floating over video.
 */
fun Modifier.flickGlass(colors: FlickColors, shape: Shape): Modifier =
    this
        .background(color = colors.glass, shape = shape)
        .border(width = 1.dp, color = colors.glassBorder, shape = shape)

/** e1 raised card fill + hairline. */
fun Modifier.flickRaised(colors: FlickColors, shape: Shape): Modifier =
    this
        .background(color = colors.surfaceRaised, shape = shape)
        .border(width = 1.dp, color = colors.outlineHairline, shape = shape)
