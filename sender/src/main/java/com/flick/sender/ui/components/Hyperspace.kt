package com.flick.sender.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/**
 * The deep-space field behind the cast handshake: a radial wash, phone-shaped rings
 * expanding out of the vanishing point and star streaks shooting past them.
 *
 * [loop] false is the still scene — reduce motion, or a window that is not resumed — and
 * no clock is created at all. [intensity] is read in the draw phase only, so the warp that
 * ramps the field in never recomposes this; neither does the loop clock.
 *
 * [vanishingFraction] is the vanishing point's height as a fraction of the field, so the
 * field converges where the screen put its poster.
 *
 * [surge] is the light-speed jump's progress, 0 at the jump and 1 once the field is back at
 * its cruising loop: it pushes the field [Hyperspace.SurgeLoops] extra loops ahead and
 * stretches the streaks while it runs. Read in the draw phase, like [intensity].
 */
@Composable
internal fun HyperspaceField(
    loop: Boolean,
    intensity: () -> Float,
    modifier: Modifier = Modifier,
    vanishingFraction: Float = Hyperspace.VanishingY,
    surge: () -> Float = { 1f },
) {
    val clock: State<Float>? = if (loop) {
        rememberInfiniteTransition(label = "hyperspace").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(Hyperspace.LoopMs, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "hyperspace-clock",
        )
    } else {
        null
    }
    Box(
        modifier.drawWithCache {
            val centre = Offset(size.width / 2f, size.height * vanishingFraction)
            val wash = Brush.radialGradient(
                0f to Hyperspace.Core,
                Hyperspace.MidStop to Hyperspace.Mid,
                1f to Hyperspace.Edge,
                center = centre,
                radius = max(size.width, size.height) * Hyperspace.WashRadius,
            )
            val ringStroke = Hyperspace.RingStrokeDp.dp.toPx()
            val ringStrokeGrowth = Hyperspace.RingStrokeGrowthDp.dp.toPx()
            val streakWidth = Hyperspace.StreakWidthDp.dp.toPx()
            val streakStart = Hyperspace.StreakStartDp.dp.toPx()
            val streakLength = Hyperspace.StreakLengthDp.dp.toPx()
            // Far enough that the largest ring has left every edge before it fades out.
            val maxRingWidth = max(size.width, size.height / Hyperspace.RingAspect) * Hyperspace.RingReach
            val reach = max(size.width, size.height)
            // One paint for every ring: each ring's stroke width moves with its phase, and a
            // Stroke per ring per frame is an allocation the loop does not need.
            val ringPaint = Paint().apply { style = PaintingStyle.Stroke }
            onDrawBehind {
                drawRect(wash)
                val run = surge().coerceIn(0f, 1f)
                // An offset rather than a faster clock: the phases below take the fraction,
                // so the field is continuous through the surge and through every wrap.
                val t = (clock?.value ?: return@onDrawBehind) + run * Hyperspace.SurgeLoops
                val strength = intensity().coerceIn(0f, 1f)
                if (strength <= 0f) return@onDrawBehind
                val length = streakLength * (1f + Hyperspace.SurgeStretch * (1f - run))
                for (index in 0 until Hyperspace.RingCount) {
                    drawRing(
                        paint = ringPaint,
                        phase = ringPhase(t, index),
                        amber = index == Hyperspace.AmberRing,
                        centre = centre,
                        maxWidth = maxRingWidth,
                        stroke = ringStroke,
                        strokeGrowth = ringStrokeGrowth,
                        strength = strength,
                    )
                }
                for (index in 0 until Hyperspace.StreakCount) {
                    val phase = streakPhase(t, index)
                    val alpha = hyperspaceFade(phase) * strength
                    if (alpha <= 0f) continue
                    val angle = streakAngleRadians(index)
                    val dx = cos(angle)
                    val dy = sin(angle)
                    val head = streakStart + (reach - streakStart) * phase * phase
                    val tail = (head - length * phase).coerceAtLeast(streakStart)
                    drawLine(
                        color = streakColor(index),
                        start = Offset(centre.x + dx * tail, centre.y + dy * tail),
                        end = Offset(centre.x + dx * head, centre.y + dy * head),
                        strokeWidth = streakWidth,
                        cap = StrokeCap.Round,
                        alpha = alpha,
                    )
                }
            }
        },
    )
}

private fun DrawScope.drawRing(
    paint: Paint,
    phase: Float,
    amber: Boolean,
    centre: Offset,
    maxWidth: Float,
    stroke: Float,
    strokeGrowth: Float,
    strength: Float,
) {
    val alpha = hyperspaceFade(phase) * strength * (if (amber) Hyperspace.AmberRingAlpha else 1f)
    if (alpha <= 0f) return
    val width = maxWidth * ringScale(phase)
    if (width < 1f) return
    val height = width * Hyperspace.RingAspect
    val corner = width * Hyperspace.RingCorner
    val colour = if (amber) Hyperspace.Amber else Hyperspace.RingBlue
    paint.color = colour.copy(alpha = colour.alpha * alpha)
    paint.strokeWidth = stroke + strokeGrowth * phase
    drawContext.canvas.drawRoundRect(
        left = centre.x - width / 2f,
        top = centre.y - height / 2f,
        right = centre.x + width / 2f,
        bottom = centre.y + height / 2f,
        radiusX = corner,
        radiusY = corner,
        paint = paint,
    )
}

/** Where ring [index] is in its own life, 0 born at the vanishing point, 1 gone. */
internal fun ringPhase(clock: Float, index: Int): Float =
    fract(clock + index.toFloat() / Hyperspace.RingCount)

/** Squared so a ring crawls out of the point and races past the edge, like depth. */
internal fun ringScale(phase: Float): Float = phase * phase

/**
 * Whole-number speeds only: the clock wraps from 1 to 0, and a streak that crosses the
 * cycle a fractional number of times would jump on every wrap.
 */
internal fun streakPhase(clock: Float, index: Int): Float =
    fract(clock * streakSpeed(index) + streakOffset(index))

internal fun streakSpeed(index: Int): Int = 1 + index % 2

internal fun streakOffset(index: Int): Float =
    ((index * 7) % Hyperspace.StreakCount).toFloat() / Hyperspace.StreakCount

/**
 * Zero at both ends of a life, so neither a birth at the vanishing point nor a wrap of
 * the clock is ever a visible pop.
 */
internal fun hyperspaceFade(phase: Float): Float {
    val p = phase.coerceIn(0f, 1f)
    return (p / Hyperspace.FadeInFraction).coerceAtMost(1f) * (1f - p)
}

private fun streakAngleRadians(index: Int): Float {
    val spacing = 360f / Hyperspace.StreakCount
    // A fixed jitter, not a random one: the field must be the same field every frame.
    val jitter = (((index * 37) % 11) - 5) * 2f
    return Math.toRadians((index * spacing + jitter).toDouble()).toFloat()
}

private fun streakColor(index: Int): Color = when {
    index % 6 == 5 -> Hyperspace.Amber
    index % 2 == 0 -> Color.White
    else -> Hyperspace.StreakBlue
}

private fun fract(value: Float): Float = value - floor(value)

/**
 * The light-speed jump, measured in ms from the connecting screen's first frame — the
 * moment the poster was let go on the detail page, which is streaking off the top of the
 * window above this one as it fades.
 */
internal object LightSpeed {
    /** The field's strength: dark while the streak is still on screen, then in fast. */
    fun field(ms: Float): Float =
        ((ms - Hyperspace.JumpFieldDelayMs) / Hyperspace.JumpFieldInMs).coerceIn(0f, 1f)

    /**
     * The surge's progress: the integral of a speed that starts at its peak at the flash and
     * decays exponentially, less its own end value, so the extra speed is exactly 0 when the
     * surge hands back to the cruising loop and the field never hitches there. Normalised to
     * end at exactly 1.
     */
    fun surge(ms: Float): Float {
        val x = ((ms - Hyperspace.JumpFlashAtMs) / Hyperspace.SurgeMs).coerceIn(0f, 1f)
        val k = Hyperspace.SurgeDecay
        val tail = k * exp(-k)
        val g = 1f - exp(-k * x) - tail * x
        return (g / (1f - exp(-k) - tail)).coerceIn(0f, 1f)
    }

    /** The flash's opacity: a linear rise to its peak, then out on the landing's fade curve. */
    fun flash(ms: Float): Float {
        val since = ms - Hyperspace.JumpFlashAtMs
        return when {
            since <= 0f -> 0f
            since < Hyperspace.FlashRiseMs -> Hyperspace.FlashPeak * since / Hyperspace.FlashRiseMs
            else -> {
                val fall = ((since - Hyperspace.FlashRiseMs) / Hyperspace.FlashFallMs).coerceIn(0f, 1f)
                Hyperspace.FlashPeak * (1f - Hyperspace.FlashFallEasing.transform(fall))
            }
        }
    }

    /** The copy's arrival, 0 to 1, once the flash has mostly cleared. */
    fun copy(ms: Float): Float {
        val p = ((ms - Hyperspace.JumpCopyDelayMs) / Hyperspace.JumpCopyInMs).coerceIn(0f, 1f)
        return Hyperspace.CopyEasing.transform(p)
    }
}

/**
 * Every number in docs/flick-hyperspace-phone.md; change either and change both.
 */
internal object Hyperspace {
    /** One loop of the field: every ring and streak returns to its starting pose. */
    const val LoopMs = 1600

    /** The whole jump; past this every [LightSpeed] track is at rest. */
    const val JumpMs = 1600

    /** The field comes in as the streak leaves the window. */
    const val JumpFieldDelayMs = 120f
    const val JumpFieldInMs = 100f

    /** The flash: up to [FlashPeak] in [FlashRiseMs], out over [FlashFallMs]. */
    const val JumpFlashAtMs = 180f
    const val FlashRiseMs = 60f
    const val FlashFallMs = 350f
    const val FlashPeak = 0.75f
    val FlashFallEasing: Easing = CubicBezierEasing(0.4f, 0f, 1f, 1f)
    /** The flash's radius as a share of the window's height, centred on its top edge. */
    const val FlashReach = 1f
    val FlashCore = Color(0xFFEBF2FF)
    val FlashEdge = Color(0x666FA0FF)

    /**
     * The surge: [SurgeLoops] extra loops over [SurgeMs], on a speed decaying at [SurgeDecay]
     * — about 5.2× the cruising speed at the flash, easing to exactly 1× at the end.
     */
    const val SurgeMs = 1400f
    const val SurgeLoops = 1f
    const val SurgeDecay = 3.1f
    /** Extra streak length at the surge's peak, as a share of [StreakLengthDp]. */
    const val SurgeStretch = 1.6f

    /** "On its way" fades up once the flash has mostly gone. */
    const val JumpCopyDelayMs = 450f
    const val JumpCopyInMs = 350f
    const val CopyRiseDp = 12f
    val CopyEasing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Height of the vanishing point, as a fraction of the window, where the window has room. */
    const val VanishingY = 0.38f

    const val RingCount = 4
    const val AmberRing = 2

    /** Height over width: a phone held upright. */
    const val RingAspect = 2.05f
    const val RingCorner = 0.16f
    const val RingReach = 1.3f
    const val RingStrokeDp = 1.5f
    const val RingStrokeGrowthDp = 1.5f
    const val AmberRingAlpha = 0.8f

    const val StreakCount = 12
    const val StreakWidthDp = 2f
    const val StreakStartDp = 18f
    const val StreakLengthDp = 72f

    const val FadeInFraction = 0.12f

    const val MidStop = 0.45f
    const val WashRadius = 0.75f

    val Core = Color(0xFF1F3AA8)
    val Mid = Color(0xFF0B1640)
    val Edge = Color(0xFF04070F)
    val RingBlue = Color(0x8C6FA0FF)
    val Amber = Color(0xFFFFB61E)
    val StreakBlue = Color(0xFF9FC0FF)
}
