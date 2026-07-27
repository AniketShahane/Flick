package com.flick.sender.ui.theme

import android.animation.ValueAnimator
import android.os.SystemClock
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.DurationBasedAnimationSpec
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Indication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp

/**
 * Motion and feedback for the surfaces Flick draws itself. Flick's controls are
 * hand-drawn boxes rather than Material components, so nothing here is supplied by
 * Material automatically — the press path reaches
 * [androidx.compose.material3.MotionScheme] only because [pressScale] and
 * [pressMorph] ask for it. Springs are used wherever a finger is involved, because
 * a spring retargets from its current velocity while a tween restarts on a fresh
 * clock. The tweens below survive only where there is no gesture to carry velocity
 * from: looping or media-clocked motion. Compose applies the system animator
 * duration scale to a finite spec, so a zero scale snaps rather than leaving a long
 * animation running — but looping animations never reach an end state and must
 * still be gated on [rememberReduceMotion].
 */
object Motion {

    // --- Easing curves (cubic-bezier) ---
    /**
     * The toast rising into place. Sheets no longer use it — their rise is a gesture's
     * consequence and takes the scheme's spatial spring; a toast arrives on its own and
     * has nothing to retarget from.
     */
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
    const val SheetRiseMs = 400
    const val TravelMs = 1050
    const val PulseMs = 1600
    const val DetentMs = 420
    const val ShimmerMs = 900
    const val SpinMs = 800
    const val GlowMs = 5000
    const val ToastMs = 2200

    // --- Press scales ---
    // Only surfaces Flick still draws itself. The transport keys sit in a ButtonGroup
    // whose press response is a width squeeze, and scaling them as well would answer
    // one touch twice.
    const val PressRow = 0.96f

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

    /**
     * Consecutive slider-step ticks closer together than this are dropped. Below
     * roughly this interval the actuator cannot separate two pulses and the run
     * reads as one long buzz.
     */
    const val TickMinIntervalMs = 40L

    // --- Ready-made specs ---
    // No press spec here on purpose: a press is a gesture, so it takes a
    // motionScheme spring via pressScale/pressMorph. A tween cannot retarget from
    // the velocity an interrupted press already carries.
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
    val spec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val scale = animateFloatAsState(
        targetValue = if (pressed) target else 1f,
        animationSpec = Motion.orSnap(reduceMotion, spec),
        label = "press scale",
    )
    return this.graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

/**
 * Press corner morph. This is a clip, so it replaces the surface's own
 * `Modifier.clip(RoundedCornerShape(restRadius))` rather than being added next to
 * it — at [restRadius] the two are identical, and a clip laid over a background
 * that already carries its own rounder shape would morph nothing. The radius is
 * read inside the layer block, so the morph repaints without recomposing the
 * caller.
 */
@Composable
internal fun Modifier.pressMorph(
    interactionSource: InteractionSource,
    restRadius: Dp,
    pressedRadius: Dp,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val reduceMotion = rememberReduceMotion()
    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<Dp>()
    val radius = animateDpAsState(
        targetValue = if (pressed) pressedRadius else restRadius,
        animationSpec = Motion.orSnap(reduceMotion, spec),
        label = "press corner radius",
    )
    return this.graphicsLayer {
        clip = true
        shape = RoundedCornerShape(radius.value)
    }
}

/**
 * Material's ripple, remembered so a press does not allocate a fresh factory on
 * every recomposition. Callers pass the theme role that reads on their own
 * background; Material applies its own alpha to it.
 */
@Composable
internal fun flickRipple(
    color: Color,
    bounded: Boolean = true,
    radius: Dp = Dp.Unspecified,
): Indication = remember(color, bounded, radius) {
    ripple(bounded = bounded, radius = radius, color = color)
}

/**
 * Haptics as named interactions rather than raw constants, so no call site can pick
 * a pulse that does not match what the user did. Every entry point must be invoked
 * from a gesture callback — never from a draw scope, a layer block or a
 * recomposition side effect, and never for state restored from process death or
 * changed programmatically. Reduce-motion does not gate haptics: the animator scale
 * and the system haptic preference are unrelated settings, and the platform already
 * honours the latter.
 *
 * This is the *touch* half only. `net.FlickHaptics` drives the platform vibrator
 * from PlaybackSession's own cue flow, and it already covers play/pause, seek and
 * the whole scrub gesture; anything it owns must not be cued from here as well, or
 * one gesture reaches the actuator twice.
 */
@Stable
internal class FlickTouchHaptics(private val haptics: HapticFeedback) {

    private var lastTickUptimeMs = 0L

    /** Chip and filter selection. Play/pause belongs to the session's own cue. */
    fun toggle(on: Boolean) =
        perform(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)

    /**
     * One volume step. Callers must already be firing on a step transition; the
     * interval floor here is a backstop, not a substitute.
     */
    fun sliderStep() {
        if (!tickAllowed()) return
        perform(HapticFeedbackType.SegmentTick)
    }

    /** A cast or a pairing succeeded. */
    fun confirm() = perform(HapticFeedbackType.Confirm)

    /** A cast or a pairing failed, or the input was rejected. */
    fun reject() = perform(HapticFeedbackType.Reject)

    /** Bottom nav moved to a different tab. Silent on a re-tap of the current one. */
    fun tabChange() = perform(HapticFeedbackType.ContextClick)

    private fun tickAllowed(): Boolean {
        // uptimeMillis is monotonic and unaffected by the wall clock; elapsed
        // realtime would keep counting through the doze the drag cannot survive.
        val now = SystemClock.uptimeMillis()
        if (now - lastTickUptimeMs < Motion.TickMinIntervalMs) return false
        lastTickUptimeMs = now
        return true
    }

    private fun perform(type: HapticFeedbackType) = haptics.performHapticFeedback(type)
}

/**
 * The holder carries the tick interval state, so it must survive recomposition; a
 * fresh instance per frame would let every tick through.
 */
@Composable
internal fun rememberFlickTouchHaptics(): FlickTouchHaptics {
    val haptics = LocalHapticFeedback.current
    return remember(haptics) { FlickTouchHaptics(haptics) }
}
