package com.flick.sender.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.Spark
import com.flick.sender.ui.theme.rememberIsResumed
import com.flick.sender.ui.theme.rememberReduceMotion
import kotlin.math.abs
import kotlin.math.min

/**
 * The tip jar on the support sheet: coins of light falling into a vessel that rocks under
 * them and settles.
 *
 * Drawn rather than shipped as an asset, like [FlickMark] and for its reasons — one
 * resolution-independent grid, no drawable to keep in step with the palette, nothing added
 * to the APK, and no animation library for a single mark. The grid is 200 wide by 260 tall
 * rather than square because a coin has to fall from ABOVE the vessel: a square box would
 * either clip the entrance or shrink the jar to make room for empty air.
 *
 * ## Colour
 *
 * Amber is the thing given and the structure is what receives it, which is the grammar the
 * rest of the app already speaks — a travelling light is amber, the chrome it crosses is
 * not. So every coin and the jar's mouth are [coinTint] and the vessel is [vesselTint]: the
 * only warm thing in the mark is the money.
 *
 * The vessel and the backing are scheme tokens, because they are structure and structure
 * follows the theme. The coin is NOT: it is a fixed [Spark], and it is the third mark in
 * this app to step outside its palette deliberately, after the Settings support heart and
 * the Devices badge. `spark` is amber in the light and dark schemes and BLUE in the
 * cinematic one, where `primary` has taken the warm end — so a coin tinted from it was gold
 * on two surfaces out of three and cold on the one the sheet most often opens over. Money
 * is gold; a blue coin is a token for something else.
 *
 * `primary` was never a candidate for the same reason in reverse: it is blue in light and
 * dark and amber in cinematic, so the vessel would collapse to amber-on-amber exactly where
 * it most needs an edge.
 *
 * What the constant costs is measured rather than assumed. Against this mark's own backing
 * the gold reads 8.7:1 on the cinematic sets, where the token it replaced read 5.3:1 — so
 * the change buys contrast rather than spending it. On the light scheme nothing moves at
 * all: `spark` IS [Spark] there, and the 1.5:1 the coins have always had against a near-white
 * backing is carried by their shape and their motion, which is what a decorative mark is
 * allowed to do and what a caption is not.
 */
@Composable
fun TipJarMark(
    modifier: Modifier,
    vesselTint: Color = LocalFlickColors.current.onSurfaceDim,
    coinTint: Color = Spark,
    backingTint: Color = LocalFlickColors.current.fillCard,
) {
    // Both gates, for the two reasons this app already keeps apart: a viewer who has turned
    // animations off is owed the resting state rather than a faster version of this one,
    // and a loop on a surface that can be open while the phone serves 4K over HTTP must
    // stop asking for frames the moment the window is not on screen.
    val still = rememberReduceMotion() || !rememberIsResumed()
    val loop = rememberInfiniteTransition(label = "tip jar")
    val clockMs by loop.animateFloat(
        initialValue = 0f,
        targetValue = TipJarMotion.LOOP_MS.toFloat(),
        animationSpec = infiniteRepeatable(
            // Linear on purpose: every curve in this mark lives in the keyframes below, and
            // an eased clock would ease them a second time.
            animation = tween(TipJarMotion.LOOP_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "tip jar clock",
    )
    // Rewound per frame rather than rebuilt: this is the one allocation in a mark like this
    // worth not making.
    val vessel = remember { Path() }
    Canvas(modifier) {
        val scale = min(size.width / GRID_W, size.height / GRID_H)
        translate(
            left = (size.width - GRID_W * scale) / 2f,
            top = (size.height - GRID_H * scale) / 2f,
        ) {
            scale(scale, scale, pivot = Offset.Zero) {
                drawTipJar(clockMs, still, vessel, vesselTint, coinTint, backingTint)
            }
        }
    }
}

private const val GRID_W = 200f
private const val GRID_H = 260f

/** Where the vessel starts inside the grid; everything above it is the coin's runway. */
private const val JAR_TOP = 60f

private const val COIN_R = 17f

/**
 * Where the three coins come to rest, and how solid each looks once it is there.
 *
 * The alphas are the pile's only depth cue — this mark carries no shadow and no gradient,
 * for the same reason [FlickMark] does not — and they run back to front so the two behind
 * read as *under* rather than beside.
 */
private data class CoinSeat(val x: Float, val y: Float, val alpha: Float)

private val PILE = listOf(
    CoinSeat(83f, JAR_TOP + 162f, alpha = 0.70f),
    CoinSeat(115f, JAR_TOP + 168f, alpha = 0.85f),
    CoinSeat(99f, JAR_TOP + 140f, alpha = 1f),
)

private fun DrawScope.drawTipJar(
    clockMs: Float,
    still: Boolean,
    vessel: Path,
    vesselTint: Color,
    coinTint: Color,
    backingTint: Color,
) {
    // Static, unlike the source design's slowly turning bloom: a shape that revolves for
    // ever is motion the eye keeps going back to with nothing to report, and it would still
    // be turning in the frame a viewer stops on.
    drawCircle(color = backingTint, radius = 94f, center = Offset(100f, 132f))

    val tilt = if (still) 0f else TipJarMotion.jarTiltDegrees(clockMs)
    // About the foot, so the vessel rocks on the surface it stands on instead of swinging
    // about its middle like something hung.
    rotate(degrees = tilt, pivot = Offset(100f, JAR_TOP + 196f)) {
        vessel.rewind()
        vessel.moveTo(53f, JAR_TOP + 22f)
        vessel.lineTo(147f, JAR_TOP + 22f)
        vessel.lineTo(157f, JAR_TOP + 60f)
        vessel.lineTo(157f, JAR_TOP + 168f)
        vessel.cubicTo(157f, JAR_TOP + 184f, 145f, JAR_TOP + 196f, 129f, JAR_TOP + 196f)
        vessel.lineTo(71f, JAR_TOP + 196f)
        vessel.cubicTo(55f, JAR_TOP + 196f, 43f, JAR_TOP + 184f, 43f, JAR_TOP + 168f)
        vessel.lineTo(43f, JAR_TOP + 60f)
        vessel.close()
        // Filled faintly and edged solid: the vessel has to read as something the coins are
        // INSIDE, and a flat silhouette would hide the pile it is holding.
        drawPath(vessel, color = vesselTint.copy(alpha = 0.14f))
        drawPath(vessel, color = vesselTint.copy(alpha = 0.85f), style = Stroke(width = 5f))

        // The mouth, in the coin's own colour: it is the aperture the amber goes through,
        // and the only part of the vessel with anything to do with giving.
        drawRoundRect(
            color = coinTint,
            topLeft = Offset(60f, JAR_TOP + 2f),
            size = Size(80f, 18f),
            cornerRadius = CornerRadius(9f, 9f),
        )

        // Each seat swells as its own coin lands on it, so a falling coin does not stop so
        // much as become the one already there.
        PILE.forEachIndexed { index, seat ->
            val pop = if (still) 1f else TipJarMotion.pilePopScale(clockMs, index)
            val at = Offset(seat.x, seat.y)
            scale(pop, pop, pivot = at) {
                drawCircle(color = coinTint.copy(alpha = seat.alpha), radius = COIN_R, center = at)
            }
        }
    }

    if (still) return
    // Clipped to the grid, and outside the tilt above. Clipped because a coin starts its
    // fall above the mark's own box and `Canvas` does not bound what it draws — unclipped
    // it would cross the sheet's heading on its way in. Outside the tilt because a coin in
    // the air is not attached to the vessel and must not lean with it: it arrives against a
    // rocking target, which is the whole charm of the thing.
    clipRect(0f, 0f, GRID_W, GRID_H) {
        PILE.forEachIndexed { index, seat ->
            val alpha = TipJarMotion.coinAlpha(clockMs, index)
            if (alpha <= 0f) return@forEachIndexed
            val at = Offset(seat.x, seat.y + TipJarMotion.coinRise(clockMs, index))
            rotate(degrees = TipJarMotion.coinTurn(clockMs, index), pivot = at) {
                drawCircle(color = coinTint.copy(alpha = alpha), radius = COIN_R, center = at)
            }
        }
    }
}

/**
 * The mark's choreography, as pure arithmetic on one monotonic clock.
 *
 * Every value is a function of milliseconds-into-the-loop and nothing else — no state, no
 * `Animatable` per element — which is what lets a dozen moving parts share a single frame
 * clock and stay in step, and what lets the timing be checked on the JVM without a device.
 *
 * The keyframes and their easings are carried over from the design this was ported from, so
 * the motion is the one that was approved rather than an approximation of it. What changed
 * is only what the shapes are and what colour they are.
 */
internal object TipJarMotion {

    /** One turn of the whole scene. Every element below is a phase of this. */
    const val LOOP_MS = 6_000

    /** When each coin begins its fall, spread so the three arrive as a run and not a heap. */
    private val ENTRY_MS = floatArrayOf(400f, 2_100f, 4_000f)

    /**
     * How long after a coin's own entry its seat swells.
     *
     * Not a number chosen for feel: it is exactly [COIN_LANDS], the phase at which that
     * coin's fall reaches the pile. The pop IS the landing, so tuning it independently
     * would be tuning the two halves of one event apart.
     */
    private const val PILE_POP_LAG = 0.40f

    /** How far above its seat a coin starts, in grid units. Clears the top of the grid. */
    private const val RUNWAY = 250f

    // Fractions of the loop. The fall lands, rebounds once, and settles; the coin then
    // holds its place until the loop is nearly over and fades so the next turn can reuse it.
    private const val COIN_IN = 0.07f
    private const val COIN_LANDS = 0.40f
    private const val COIN_PEAK = 0.52f
    private const val COIN_SETTLES = 0.62f
    private const val COIN_HOLDS = 0.90f

    /** The rebound's height, in grid units. */
    private const val BOUNCE = 14f

    private fun phase(clockMs: Float, index: Int): Float {
        val shifted = (clockMs - ENTRY_MS[index]) % LOOP_MS
        return (if (shifted < 0f) shifted + LOOP_MS else shifted) / LOOP_MS
    }

    /** How far above its seat coin [index] is: negative is in the air, zero is landed. */
    fun coinRise(clockMs: Float, index: Int): Float = when (val t = phase(clockMs, index)) {
        in 0f..COIN_LANDS -> lerp(-RUNWAY, 0f, ease(FALL, span(t, 0f, COIN_LANDS)))
        in COIN_LANDS..COIN_PEAK -> lerp(0f, -BOUNCE, ease(FALL, span(t, COIN_LANDS, COIN_PEAK)))
        in COIN_PEAK..COIN_SETTLES -> lerp(-BOUNCE, 0f, ease(FALL, span(t, COIN_PEAK, COIN_SETTLES)))
        else -> 0f
    }

    /** The tumble a coin carries in, unwound by the time it has settled. */
    fun coinTurn(clockMs: Float, index: Int): Float = when (val t = phase(clockMs, index)) {
        in 0f..COIN_LANDS -> lerp(-15f, 10f, ease(FALL, span(t, 0f, COIN_LANDS)))
        in COIN_LANDS..COIN_PEAK -> lerp(10f, 3f, ease(FALL, span(t, COIN_LANDS, COIN_PEAK)))
        in COIN_PEAK..COIN_SETTLES -> lerp(3f, 0f, ease(FALL, span(t, COIN_PEAK, COIN_SETTLES)))
        else -> 0f
    }

    /**
     * A falling coin's opacity. Zero for the whole of its own turn once it has faded, which
     * is what lets the caller skip drawing it rather than drawing nothing.
     */
    fun coinAlpha(clockMs: Float, index: Int): Float = when (val t = phase(clockMs, index)) {
        in 0f..COIN_IN -> span(t, 0f, COIN_IN)
        in COIN_IN..COIN_HOLDS -> 1f
        else -> 1f - span(t, COIN_HOLDS, 1f)
    }

    /**
     * How much seat [index] is swollen right now. One over almost the whole loop, so the
     * caller pays a multiply and nothing else for the frames where nothing is landing.
     */
    fun pilePopScale(clockMs: Float, index: Int): Float {
        val t = (phase(clockMs, index) - PILE_POP_LAG + 1f) % 1f
        return when {
            t <= POP_PEAK -> lerp(1f, POP_SCALE, ease(POP, span(t, 0f, POP_PEAK)))
            t <= POP_ENDS -> lerp(POP_SCALE, 1f, ease(POP, span(t, POP_PEAK, POP_ENDS)))
            else -> 1f
        }
    }

    private const val POP_PEAK = 0.05f
    private const val POP_ENDS = 0.12f
    private const val POP_SCALE = 1.18f

    /**
     * The vessel's rock, in degrees, over one loop: a hard lean and four decaying returns.
     *
     * On its own clock rather than on a coin's, exactly as the design has it. The two share
     * the same loop, so a landing and a lean recur in the same relation to each other every
     * turn without either being derived from the other.
     */
    fun jarTiltDegrees(clockMs: Float): Float {
        val t = (clockMs % LOOP_MS) / LOOP_MS
        var previousAt = 0f
        var previousDeg = 0f
        for ((at, degrees) in WOBBLE) {
            if (t <= at) return lerp(previousDeg, degrees, ease(WOBBLE_EASE, span(t, previousAt, at)))
            previousAt = at
            previousDeg = degrees
        }
        return 0f
    }

    private val WOBBLE = listOf(
        0.12f to -6f,
        0.28f to 5f,
        0.44f to -3f,
        0.60f to 2f,
        0.76f to -1f,
        1f to 0f,
    )

    /** Where [value] sits between [from] and [to], clamped. */
    private fun span(value: Float, from: Float, to: Float): Float =
        if (to <= from) 1f else ((value - from) / (to - from)).coerceIn(0f, 1f)

    private fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t

    // The design's own timing functions, kept so the ported motion is the approved one.
    private val FALL = Bezier(0.34f, 1.2f, 0.64f, 1f)
    private val WOBBLE_EASE = Bezier(0.36f, 0.07f, 0.19f, 0.97f)
    private val POP = Bezier(0f, 0f, 0.58f, 1f)

    internal data class Bezier(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

    /**
     * A CSS `cubic-bezier` evaluated the way a browser evaluates one: the curve is
     * parametric, so the parameter that puts x at [t] has to be solved for before y can be
     * read off it.
     *
     * Newton-Raphson from a linear guess, which is what the engines do and what makes four
     * passes enough at this precision — the derivative only vanishes at a control point a
     * legal easing cannot place there. The bisection fallback covers the flat spots those
     * curves do have, where Newton would otherwise step past the interval.
     */
    internal fun ease(curve: Bezier, t: Float): Float {
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f
        var guess = t
        repeat(NEWTON_PASSES) {
            val error = bezier(curve.x1, curve.x2, guess) - t
            if (abs(error) < EPSILON) return bezier(curve.y1, curve.y2, guess)
            val slope = bezierSlope(curve.x1, curve.x2, guess)
            if (abs(slope) < EPSILON) return@repeat
            guess -= error / slope
        }
        var low = 0f
        var high = 1f
        guess = t
        repeat(BISECTION_PASSES) {
            val x = bezier(curve.x1, curve.x2, guess)
            if (abs(x - t) < EPSILON) return bezier(curve.y1, curve.y2, guess)
            if (x < t) low = guess else high = guess
            guess = (low + high) / 2f
        }
        return bezier(curve.y1, curve.y2, guess)
    }

    /** One axis of a unit cubic Bézier whose outer control points are pinned at 0 and 1. */
    private fun bezier(a: Float, b: Float, t: Float): Float {
        val inverse = 1f - t
        return 3f * inverse * inverse * t * a + 3f * inverse * t * t * b + t * t * t
    }

    private fun bezierSlope(a: Float, b: Float, t: Float): Float {
        val inverse = 1f - t
        return 3f * inverse * inverse * a + 6f * inverse * t * (b - a) + 3f * t * t * (1f - b)
    }

    private const val NEWTON_PASSES = 4
    private const val BISECTION_PASSES = 12
    private const val EPSILON = 1e-4f
}
