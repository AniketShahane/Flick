package com.flick.receiver.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import kotlin.math.max

/**
 * Fixed cinematic-dark theme for the TV. The TV NEVER re-tints from artwork — it
 * holds this palette under every film. Wraps tv-material3's [MaterialTheme] with
 * the Flick colour/type tokens.
 *
 * The Material roles follow the §2c split: `primary` is amber (action, focus,
 * transport), `secondary` is brand blue (connection, the ambient field).
 */
@Composable
fun FlickTvTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = FlickColor.Spark,
        onPrimary = FlickColor.OnSpark,
        primaryContainer = FlickColor.SurfaceRaised,
        onPrimaryContainer = FlickColor.OnSurface,
        secondary = FlickColor.PrimaryOnDark,
        onSecondary = FlickColor.OnSurface,
        background = FlickColor.Canvas,
        onBackground = FlickColor.OnSurface,
        surface = FlickColor.SurfaceRaisedAlt,
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

/** The 5% overscan-safe inset (spec §1b): all chrome/text lives inside this. */
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

// ── Glass surfaces (spec §2a) ───────────────────────────────────────────────

/**
 * Warm-neutral glass: `#09112A` @ 13 % + a white hairline. The **top-chrome
 * pills over the film** — the source pill, the net-health pill, the clock, the
 * paused chip. (A hardware blur is optional and API-gated; the translucent fill
 * carries the read at 10 ft.)
 */
fun Modifier.glass(shape: Shape = FlickShape.Md): Modifier =
    this
        .background(FlickColor.Glass, shape)
        .border(1.dp, FlickColor.GlassBorder, shape)

/**
 * Cool chrome glass: `#163A8C` @ 13 % + the cool hairline. The **bottom transport
 * panel** and any side chrome that sits directly on the film.
 */
fun Modifier.glassChrome(shape: Shape = FlickShape.Hero): Modifier =
    this
        .background(FlickColor.GlassChrome, shape)
        .border(1.dp, FlickColor.GlassBorderCool, shape)

/**
 * Dense panel glass: `#163A8C` @ 50 % + a hairline. The **subtitles and
 * stream-metrics panels** and the handshake card — they carry dense text, so
 * they need more body than [glassChrome].
 *
 * The hairline defaults to the cool [FlickColor.GlassBorderCool] the design draws
 * on the two side panels. Spec §5.2 gives the handshake card the white
 * [FlickColor.GlassBorder] instead, so that caller passes `borderColor`.
 */
fun Modifier.glassPanel(
    shape: Shape = FlickShape.Xl,
    borderColor: Color = FlickColor.GlassBorderCool,
): Modifier =
    this
        .background(FlickColor.GlassPanel, shape)
        .border(1.dp, borderColor, shape)

/**
 * The amber drop shadow under the play button. Coloured shadows are an API 28+
 * feature; below that the elevation still reads and the tint is ignored.
 */
fun Modifier.sparkShadow(shape: Shape = FlickShape.Play, elevation: Dp = 18.dp): Modifier =
    this.shadow(
        elevation = elevation,
        shape = shape,
        clip = false,
        ambientColor = FlickColor.Spark,
        spotColor = FlickColor.Spark,
    )

// ── Gradients & fills (spec §2d) ────────────────────────────────────────────

/** The played portion of the scrub bar — `#FFB61E → #FFD87A`, left to right. */
fun playheadBrush(): Brush = FlickColor.SparkGradient

/**
 * Bottom playback scrim — transparent → `#02040A` @ 92 %. Apply to a box that
 * covers the bottom 56 % of the frame. A gradient, never a hard bar.
 */
fun bottomScrimBrush(): Brush =
    Brush.verticalGradient(listOf(Color.Transparent, FlickColor.ScrimEnd))

/**
 * Top playback scrim — `#02040A` @ 78 % → transparent, over the top 26 % of the
 * frame, so the chrome pills stay legible against a bright plate.
 */
fun topScrimBrush(): Brush =
    Brush.verticalGradient(listOf(FlickColor.ScrimTop, Color.Transparent))

/**
 * The transport panel's 1 dp inner top hairline: a light that fades in from 12 %
 * and out by 88 %, so the panel reads as a lit pane rather than an outlined box.
 */
fun panelTopHighlightBrush(): Brush = Brush.horizontalGradient(
    0f to Color.Transparent,
    0.12f to Color.Transparent,
    0.5f to FlickColor.PanelHighlight,
    0.88f to Color.Transparent,
    1f to Color.Transparent,
)

/**
 * Ambient glow behind the transport (≤30 % opacity so HDR video stays the
 * brightest thing on screen). Amber by default — the transport's own accent.
 */
fun ambientGlowBrush(tint: Color = FlickColor.Spark): Brush =
    Brush.verticalGradient(
        0f to Color.Transparent,
        1f to tint.copy(alpha = 0.18f),
    )

/**
 * The pairing bed (spec §2d): `CanvasPair`, a brand-blue radial from the upper
 * left at 50 %, and an amber radial from the lower right at 22 %. Both washes
 * are static — there is nothing to skip under reduced motion.
 */
fun Modifier.pairAmbientBackground(): Modifier = this
    .background(FlickColor.CanvasPair)
    .drawBehind {
        val reach = max(size.width, size.height)
        drawRect(
            Brush.radialGradient(
                colors = listOf(FlickColor.Primary.copy(alpha = 0.50f), Color.Transparent),
                center = Offset(size.width * 0.10f, -size.height * 0.06f),
                radius = reach * 0.62f,
            )
        )
        drawRect(
            Brush.radialGradient(
                colors = listOf(FlickColor.Spark.copy(alpha = 0.22f), Color.Transparent),
                center = Offset(size.width * 0.96f, size.height * 1.04f),
                radius = reach * 0.55f,
            )
        )
    }

/**
 * The idle bed: `Canvas` plus one soft brand-blue radial hanging off the top
 * edge. Quieter than [pairAmbientBackground] — idle is a resting state.
 */
fun Modifier.idleAmbientBackground(): Modifier = this
    .background(FlickColor.Canvas)
    .drawBehind {
        drawRect(
            Brush.radialGradient(
                colors = listOf(FlickColor.Primary.copy(alpha = 0.22f), Color.Transparent),
                center = Offset(size.width * 0.5f, -size.height * 0.10f),
                radius = max(size.width, size.height) * 0.65f,
            )
        )
    }

/**
 * The ±10 s seek-burst wash (spec §5.3): an amber radial filling a 38 %-wide
 * column on the side that was seeked. Pass `fromRight = true` for forward.
 */
fun Modifier.seekBurstWash(fromRight: Boolean): Modifier = this.drawBehind {
    drawRect(
        Brush.radialGradient(
            colors = listOf(FlickColor.FocusGlow, Color.Transparent),
            center = Offset(size.width * if (fromRight) 0.7f else 0.3f, size.height * 0.5f),
            radius = max(size.width * 0.6f, size.height * 0.5f),
        )
    )
}
