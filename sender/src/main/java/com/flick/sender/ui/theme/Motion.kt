package com.flick.sender.ui.theme

import android.animation.ValueAnimator
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.DurationBasedAnimationSpec
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Product-specific motion intents. Generic Material components obtain their motion
 * from [androidx.compose.material3.MotionScheme.expressive] in [FlickTheme]; these
 * remain only for behavior that Material cannot own (the playhead, scrub reconcile,
 * the sheet rise, and the connecting traveling light). Compose applies the system
 * animator duration scale to these specs, so a zero scale snaps rather than leaving
 * a long-running animation — but looping animations must still be gated on
 * [rememberReduceMotion] because they never reach an end state.
 */
object Motion {

    // --- Easing curves (cubic-bezier) ---
    /** Default settle, ~overshoot — rows, CTA, track/thumb growth, seek confirm. */
    val FlickSettle: Easing = CubicBezierEasing(0.2f, 1.5f, 0.4f, 1f)

    /** Firmer overshoot for the play/pause FAB. */
    val FabSettle: Easing = CubicBezierEasing(0.2f, 1.6f, 0.35f, 1f)

    /** Sheets and the toast rising into place. */
    val SheetRise: Easing = CubicBezierEasing(0.2f, 1.4f, 0.35f, 1f)

    /** The link light crossing the connecting hairline. */
    val Travel: Easing = CubicBezierEasing(0.3f, 0f, 0.2f, 1f)

    /** Pulse dot and the ambient glow breathe symmetrically. */
    val Breathe: Easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

    /** Detent ripple expanding away from the thumb. */
    val RippleOut: Easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)

    /** Shimmer and spinner run at a constant rate. */
    val Steady: Easing = LinearEasing

    // --- Durations (ms) ---
    const val FlickSettleMs = 320
    const val FabSettleMs = 340
    const val SheetRiseMs = 400
    const val TravelMs = 1050
    const val PulseMs = 1600
    const val DetentMs = 420
    const val ShimmerMs = 900
    const val SpinMs = 800
    const val GlowMs = 5000
    const val ToastMs = 2200

    // --- Press scales ---
    const val PressRow = 0.96f
    const val PressFab = 0.92f
    const val PressSeek = 0.90f

    // --- Sheet entry offsets ---
    const val SheetRiseOffsetDp = 30
    const val SheetRiseScale = 0.96f

    // --- Detent ripple geometry ---
    const val RippleFromScale = 0.55f
    const val RippleToScale = 2.5f
    const val RippleFromAlpha = 0.6f

    // --- Pulse-dot envelope ---
    const val PulseMinAlpha = 0.4f
    const val PulseMinScale = 0.82f
    const val PulseMaxScale = 1.18f

    // --- Ambient-glow envelope ---
    const val GlowMinAlpha = 0.45f
    const val GlowMaxAlpha = 0.95f

    // --- Ready-made specs ---
    fun <T> flickSettle(): DurationBasedAnimationSpec<T> =
        tween(durationMillis = FlickSettleMs, easing = FlickSettle)

    fun <T> fabSettle(): DurationBasedAnimationSpec<T> =
        tween(durationMillis = FabSettleMs, easing = FabSettle)

    fun <T> sheetRise(): DurationBasedAnimationSpec<T> =
        tween(durationMillis = SheetRiseMs, easing = SheetRise)

    fun <T> travel(): DurationBasedAnimationSpec<T> =
        tween(durationMillis = TravelMs, easing = Travel)

    fun <T> detent(): DurationBasedAnimationSpec<T> =
        tween(durationMillis = DetentMs, easing = RippleOut)

    /** Snap instead of animating when the platform's animators are off. */
    fun <T> orSnap(reduceMotion: Boolean, spec: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> =
        if (reduceMotion) snap() else spec
}

/**
 * Product motion that is not managed by Material must still respect the platform's
 * animator setting. API 26 is Flick's minimum, so this is safe without a fallback.
 */
@Composable
fun rememberReduceMotion(): Boolean = !ValueAnimator.areAnimatorsEnabled()

/**
 * Press response for rows, cards, buttons and the FAB. The scale is read inside the
 * layer block, so a press repaints without recomposing the caller.
 */
@Composable
internal fun Modifier.pressScale(
    interactionSource: InteractionSource,
    target: Float = Motion.PressRow,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val reduceMotion = rememberReduceMotion()
    val scale = animateFloatAsState(
        targetValue = if (pressed) target else 1f,
        animationSpec = Motion.orSnap(reduceMotion, Motion.flickSettle()),
        label = "press scale",
    )
    return this.graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}
