package com.flick.receiver.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
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
    // The system animation scale is observed once here, not once for every live
    // dot, panel or button in the receiver composition.
    CompositionLocalProvider(LocalReducedMotion provides rememberReducedMotion()) {
        MaterialTheme(
            colorScheme = colors,
            typography = FlickTvTypography,
            content = content,
        )
    }
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
 * Warm-neutral glass: [FlickColor.Glass] + a white hairline. The **top-chrome
 * pills over the film** — the source pill, the net-health pill, the clock.
 *
 * There is no blur under any of these: a hardware blur is API-gated and the
 * performance fence (spec §6.1) rules it out over a live decoder, so the
 * translucent fill IS the read. That is exactly why the tone carries the density
 * it does rather than the design's 13 % — see [FlickColor.Glass].
 */
fun Modifier.glass(shape: Shape = FlickShape.Md): Modifier =
    this
        .background(FlickColor.Glass, shape)
        .border(1.dp, FlickColor.GlassBorder, shape)

/**
 * Cool chrome glass: [FlickColor.GlassChrome] + the cool hairline. The **bottom
 * transport panel** and any side chrome that sits directly on the film.
 */
fun Modifier.glassChrome(shape: Shape = FlickShape.Hero): Modifier =
    this
        .background(FlickColor.GlassChrome, shape)
        .border(1.dp, FlickColor.GlassBorderCool, shape)

/**
 * Dense panel glass: [FlickColor.GlassPanel] + a hairline. The **subtitles and
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
 * State glass: [FlickColor.GlassState] + a white hairline. The plate every
 * **centred playback state overlay** carries — the buffering card, the
 * paused / finished chip.
 *
 * Its job is the band between [BOTTOM_SCRIM_FRACTION] and [TOP_SCRIM_FRACTION]
 * that carries no scrim on purpose. Nothing else in the system may assume a
 * backdrop there.
 */
fun Modifier.glassState(shape: Shape = FlickShape.Xl): Modifier =
    this
        .background(FlickColor.GlassState, shape)
        .border(1.dp, FlickColor.GlassBorder, shape)

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

// ── The playback scrims (spec §2d) ──────────────────────────────────────────

/** Design's top playback scrim coverage, as a fraction of the frame height. */
internal const val TOP_SCRIM_FRACTION = 0.26f

/** Design's bottom playback scrim coverage, as a fraction of the frame height. */
internal const val BOTTOM_SCRIM_FRACTION = 0.56f

/**
 * Where the bottom scrim reaches [FlickColor.ScrimKnee], as a fraction of its
 * own height. 0.26 of 0.56 puts it at 58.6 % of the frame — just past the top
 * edge of a full three-row transport panel, so the panel meets a scrim that has
 * already done its work rather than one still ramping up.
 */
private const val BOTTOM_SCRIM_KNEE = 0.26f

/**
 * The two scrims **do not meet**, and that is the design: between them lies a
 * band of the frame with no scrim over it at all, because the film is the point.
 * The consequence is a contract, not a defect — anything anchored in this band
 * carries its own plate ([Modifier.glassState]) and never leans on the chrome's
 * scrims. [playbackScrimAlphaAt] is how that claim is checked.
 */
internal val UNSCRIMMED_BAND: ClosedFloatingPointRange<Float> =
    TOP_SCRIM_FRACTION..(1f - BOTTOM_SCRIM_FRACTION)

/**
 * The scrim alpha over the film at [fraction] of the frame height, with the
 * chrome fully revealed. Composed source-over from the same stops the two
 * brushes below draw, so the profile and the paint cannot drift apart.
 */
internal fun playbackScrimAlphaAt(fraction: Float): Float {
    val top = if (fraction < TOP_SCRIM_FRACTION) {
        FlickColor.ScrimTop.alpha * (1f - fraction / TOP_SCRIM_FRACTION)
    } else {
        0f
    }
    val bottomStart = 1f - BOTTOM_SCRIM_FRACTION
    val bottom = if (fraction > bottomStart) {
        val t = (fraction - bottomStart) / BOTTOM_SCRIM_FRACTION
        val knee = FlickColor.ScrimKnee.alpha
        if (t <= BOTTOM_SCRIM_KNEE) {
            knee * t / BOTTOM_SCRIM_KNEE
        } else {
            knee + (FlickColor.ScrimEnd.alpha - knee) * (t - BOTTOM_SCRIM_KNEE) / (1f - BOTTOM_SCRIM_KNEE)
        }
    } else {
        0f
    }
    return 1f - (1f - top) * (1f - bottom)
}

/**
 * Bottom playback scrim — transparent → `#02040A` @ 66 % → @ 92 %. Apply to a box
 * that covers the bottom [BOTTOM_SCRIM_FRACTION] of the frame. A gradient, never
 * a hard bar; the knee is what makes it a scrim for the chrome rather than only
 * for the last few rows of pixels — see [FlickColor.ScrimKnee].
 */
fun bottomScrimBrush(): Brush = Brush.verticalGradient(
    0f to Color.Transparent,
    BOTTOM_SCRIM_KNEE to FlickColor.ScrimKnee,
    1f to FlickColor.ScrimEnd,
)

/**
 * Top playback scrim — `#02040A` @ 78 % → transparent, over the top
 * [TOP_SCRIM_FRACTION] of the frame, so the chrome pills stay legible against a
 * bright plate. It thins to nothing by the END SESSION pill, which is why that
 * control carries a plate of its own.
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
 * One ambient radial, plus the rectangle it can actually tint.
 *
 * Both halves exist for the GPU, and neither changes a pixel. **The brush**: a
 * `ShaderBrush` caches its platform shader against the size it was built for, so a
 * `Brush.radialGradient(...)` constructed *inside* a draw lambda throws that cache
 * away and makes the driver regenerate the gradient every single frame. Built once
 * per size, it is uploaded once. **The rectangle**: outside its radius the gradient
 * is already transparent, so filling the whole panel with it shades and blends
 * millions of pixels that cannot come out any colour. Clipping the fill to the
 * gradient's own footprint drops roughly a third of the pairing bed's shaded area
 * at 16:9 and is bit-identical inside it.
 */
private class AmbientWash(val brush: Brush, val topLeft: Offset, val size: Size)

/**
 * [plateau] holds the wash at its full colour out to that fraction of [radius]
 * before it starts feathering. A two-stop radial is at its nominal alpha only at
 * the centre pixel, which is fine for an ambient field and wrong for a bed that
 * ink stands on — see [SEEK_WASH_PLATEAU].
 *
 * [footprintRadius] widens the drawn rectangle without touching the gradient, for the
 * one caller whose wash MOVES: a drifting bed is drawn through a canvas transform so
 * its shader is never rebuilt, and the rectangle it is clipped to has to cover every
 * position the drift reaches rather than only the resting one.
 */
private fun ambientWash(
    color: Color,
    center: Offset,
    radius: Float,
    panel: Size,
    plateau: Float = 0f,
    footprintRadius: Float = radius,
): AmbientWash {
    val left = (center.x - footprintRadius).coerceIn(0f, panel.width)
    val top = (center.y - footprintRadius).coerceIn(0f, panel.height)
    val right = (center.x + footprintRadius).coerceIn(0f, panel.width)
    val bottom = (center.y + footprintRadius).coerceIn(0f, panel.height)
    val brush = if (plateau > 0f) {
        Brush.radialGradient(
            0f to color,
            plateau to color,
            1f to Color.Transparent,
            center = center,
            radius = radius,
        )
    } else {
        Brush.radialGradient(
            colors = listOf(color, Color.Transparent),
            center = center,
            radius = radius,
        )
    }
    return AmbientWash(
        brush = brush,
        topLeft = Offset(left, top),
        size = Size(right - left, bottom - top),
    )
}

private fun DrawScope.drawWash(wash: AmbientWash, alpha: Float = 1f) {
    if (wash.size.width <= 0f || wash.size.height <= 0f || alpha <= 0f) return
    drawRect(brush = wash.brush, topLeft = wash.topLeft, size = wash.size, alpha = alpha)
}

/**
 * The pairing bed (spec §2d): `CanvasPair`, a brand-blue radial from the upper
 * left at 50 %, and an amber radial from the lower right at 22 %. Both washes
 * are static — there is nothing to skip under reduced motion.
 */
fun Modifier.pairAmbientBackground(): Modifier = this
    .background(FlickColor.CanvasPair)
    .drawWithCache {
        val reach = max(size.width, size.height)
        val brand = ambientWash(
            color = FlickColor.Primary.copy(alpha = 0.50f),
            center = Offset(size.width * 0.10f, -size.height * 0.06f),
            radius = reach * 0.62f,
            panel = size,
        )
        val spark = ambientWash(
            color = FlickColor.Spark.copy(alpha = 0.22f),
            center = Offset(size.width * 0.96f, size.height * 1.04f),
            radius = reach * 0.55f,
            panel = size,
        )
        onDrawBehind {
            drawWash(brand)
            drawWash(spark)
        }
    }

/** The idle bed's resting geometry, shared by the static and drifting variants. */
private const val IDLE_WASH_ALPHA = 0.22f
private const val IDLE_WASH_CENTRE_X = 0.5f
private const val IDLE_WASH_CENTRE_Y = -0.10f
private const val IDLE_WASH_RADIUS = 0.65f

/**
 * How far the idle bed's centre and radius wander, as fractions of the viewport and
 * of [IDLE_WASH_RADIUS]'s reach respectively. Idle is the one deliberate ambient loop
 * in the system — see `IdleScreen`.
 */
private const val IDLE_DRIFT_CENTRE = 0.06f
private const val IDLE_DRIFT_RADIUS = 0.08f

/**
 * The idle bed: `Canvas` plus one soft brand-blue radial hanging off the top
 * edge. Quieter than [pairAmbientBackground] — idle is a resting state. This is the
 * still variant, held under reduced motion and by Settings; [idleAmbientDrift] is the
 * same geometry in motion.
 */
fun Modifier.idleAmbientBackground(): Modifier = this
    .background(FlickColor.Canvas)
    .drawWithCache {
        val wash = ambientWash(
            color = FlickColor.Primary.copy(alpha = IDLE_WASH_ALPHA),
            center = Offset(size.width * IDLE_WASH_CENTRE_X, size.height * IDLE_WASH_CENTRE_Y),
            radius = max(size.width, size.height) * IDLE_WASH_RADIUS,
            panel = size,
        )
        onDrawBehind { drawWash(wash) }
    }

/**
 * The drifting idle bed, for a [phase] in −1..1.
 *
 * The drift is a canvas transform over ONE cached gradient, not a gradient rebuilt per
 * frame. A `ShaderBrush` caches its platform shader against the size it was built for,
 * so constructing `Brush.radialGradient(...)` inside the draw lambda — which is what a
 * moving centre and radius invite — makes the driver regenerate the gradient on every
 * frame of a permanent loop, full-screen, for the hours a standby screen is up. A
 * translate plus a scale about the gradient's own centre reaches exactly the same
 * geometry: the pivot fixes the centre so the scale is purely the radius, and the
 * shader is uploaded once per size.
 *
 * The footprint covers the drift's full envelope so the clip cannot crop a crest at
 * the extremes of the loop. At 16:9 that envelope is the whole viewport — this wash
 * hangs off the top edge and its resting footprint already fills the screen — so the
 * clip is not what saves anything here; the shader is.
 */
fun Modifier.idleAmbientDrift(phase: () -> Float): Modifier = this
    .background(FlickColor.Canvas)
    .drawWithCache {
        val reach = max(size.width, size.height)
        val centre = Offset(size.width * IDLE_WASH_CENTRE_X, size.height * IDLE_WASH_CENTRE_Y)
        val radius = reach * IDLE_WASH_RADIUS
        val wash = ambientWash(
            color = FlickColor.Primary.copy(alpha = IDLE_WASH_ALPHA),
            center = centre,
            radius = radius,
            panel = size,
            // Both drift terms at their extreme, against the longer edge for each: one
            // scalar has to cover all four sides, so it covers the worst of them.
            footprintRadius = radius + reach * (IDLE_DRIFT_RADIUS + IDLE_DRIFT_CENTRE),
        )
        val driftX = size.width * IDLE_DRIFT_CENTRE
        val driftY = size.height * IDLE_DRIFT_CENTRE
        // The radius drift expressed as the scale that reaches it, so the pivot can do
        // the work: (0.65 + 0.08p) / 0.65 = 1 + (0.08 / 0.65)p.
        val radiusGain = IDLE_DRIFT_RADIUS / IDLE_WASH_RADIUS
        onDrawBehind {
            val p = phase()
            translate(left = driftX * p, top = driftY * p) {
                scale(scale = 1f + radiusGain * p, pivot = centre) {
                    drawWash(wash)
                }
            }
        }
    }

/**
 * The failure bed: `Canvas` under one wash in the failure's own [accent]. It is
 * full-bleed on purpose — the card inside it stops at the overscan inset.
 */
fun Modifier.errorAmbientBackground(accent: Color): Modifier = this
    .background(FlickColor.Canvas)
    .drawWithCache {
        val wash = ambientWash(
            color = accent.copy(alpha = 0.20f),
            center = Offset(size.width * 0.5f, size.height * 0.14f),
            radius = max(size.width, size.height) * 0.68f,
            panel = size,
        )
        onDrawBehind { drawWash(wash) }
    }

/**
 * How forcefully the burst's amber accent reads, from the speed level the key
 * policy actually reached (1×/2×/3×), so a long hold is visibly more forceful
 * than a tap without inventing a number.
 *
 * It scales the ACCENT and nothing else. As a `graphicsLayer` alpha over the
 * whole wash it also thinned the dark bed the white glyph stands on, and a single
 * tap — the overwhelmingly common gesture — got 62 % of it: 2.4:1 over a daylight
 * frame, on the most-used control on the remote. Force is the amber's job;
 * legibility is not negotiable with it.
 */
internal fun seekAccentIntensity(speedLevel: Int): Float = when (speedLevel.coerceIn(1, 3)) {
    1 -> 0.62f
    2 -> 0.82f
    else -> 1f
}

/**
 * How much of the bed's radius stays at full density before it feathers out.
 *
 * The bed is not an ambient field, it is the backdrop the glyph and its label are
 * read against, and a plain two-stop radial reaches its nominal alpha only at the
 * centre pixel. The plateau covers the whole glyph column; the feather is spent
 * out at the column's edges, where there is no ink.
 */
internal const val SEEK_WASH_PLATEAU = 0.55f

/** The burst bed's radius for a wash box of [width] × [height]. */
internal fun seekWashReach(width: Float, height: Float): Float = max(width * 0.6f, height * 0.5f)

/**
 * The ±10 s seek-burst wash (spec §5.3): a 38 %-wide column on the side that was
 * seeked. Pass `fromRight = true` for forward and the speed level's
 * [seekAccentIntensity] as [accentIntensity].
 *
 * Two radials, not one. The design's amber @ 16 % is the accent and stays anchored
 * toward the seeked edge, but amber over a bright frame *lightens* it — the most
 * used gesture on the remote had feedback that disappeared on daylight footage.
 * The bed goes under it, centred on the glyph column rather than on the edge,
 * because an edge-anchored bed leaves the glyph itself standing on bare film, and
 * it is drawn at full strength at every speed level.
 *
 * Both are built once per size in the cache scope: a `ShaderBrush` rebuilt inside
 * a draw lambda regenerates its platform shader every frame. The intensity is a
 * draw-time alpha for the same reason — it must not force a new gradient.
 */
fun Modifier.seekBurstWash(fromRight: Boolean, accentIntensity: Float): Modifier = this.drawWithCache {
    val reach = seekWashReach(size.width, size.height)
    val bed = ambientWash(
        color = FlickColor.SeekWashBed,
        center = Offset(size.width * 0.5f, size.height * 0.5f),
        radius = reach,
        panel = size,
        plateau = SEEK_WASH_PLATEAU,
    )
    val accent = ambientWash(
        color = FlickColor.FocusGlow,
        center = Offset(size.width * if (fromRight) 0.7f else 0.3f, size.height * 0.5f),
        radius = reach,
        panel = size,
    )
    onDrawBehind {
        drawWash(bed)
        drawWash(accent, alpha = accentIntensity)
    }
}
