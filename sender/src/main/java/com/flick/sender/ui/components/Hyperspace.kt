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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos
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
 */
@Composable
internal fun HyperspaceField(
    loop: Boolean,
    intensity: () -> Float,
    modifier: Modifier = Modifier,
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
            val centre = Offset(size.width / 2f, size.height * Hyperspace.VanishingY)
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
            onDrawBehind {
                drawRect(wash)
                val t = clock?.value ?: return@onDrawBehind
                val strength = intensity().coerceIn(0f, 1f)
                if (strength <= 0f) return@onDrawBehind
                for (index in 0 until Hyperspace.RingCount) {
                    drawRing(
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
                    val tail = (head - streakLength * phase).coerceAtLeast(streakStart)
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
    drawRoundRect(
        color = if (amber) Hyperspace.Amber else Hyperspace.RingBlue,
        topLeft = Offset(centre.x - width / 2f, centre.y - height / 2f),
        size = Size(width, height),
        cornerRadius = CornerRadius(corner, corner),
        style = Stroke(width = stroke + strokeGrowth * phase),
        alpha = alpha,
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
 * Every number in docs/flick-hyperspace-phone.md; change either and change both.
 */
internal object Hyperspace {
    /** One loop of the field: every ring and streak returns to its starting pose. */
    const val LoopMs = 1600

    /** The poster holds at its seat this long before the jump, so it reads as the film. */
    const val WarpHoldMs = 280L

    /** The poster shooting into the vanishing point. */
    const val WarpMs = 640
    const val WarpEndScale = 0.02f

    /** The last part of the warp in which the poster fades, so it never parks as a speck. */
    const val WarpFadeFraction = 0.15f

    /** Accelerating all the way in: a cubic ease-in. */
    val WarpEasing: Easing = CubicBezierEasing(0.32f, 0f, 0.67f, 0f)

    /** Height of the vanishing point, as a fraction of the window. */
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
