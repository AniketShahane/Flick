package com.flick.sender.ui.theme

import android.animation.ValueAnimator
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.DurationBasedAnimationSpec
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable

/**
 * Product-specific motion intents. Generic Material components obtain their motion
 * from [androidx.compose.material3.MotionScheme.expressive] in [FlickTheme]; these
 * remain only for behavior that Material cannot own (the playhead, scrub reconcile,
 * and play/pause path morph). Compose applies the system animator duration scale to
 * these specs, so a zero scale snaps rather than leaving a long-running animation.
 */
object Motion {

    // --- Easing curves (cubic-bezier) ---
    /** Decisive spring, ~6% overshoot — seek confirm and play/pause morph. */
    val FlickSettle: Easing = CubicBezierEasing(0.22f, 1.2f, 0.36f, 1f)

    /** The bar tracking the running clock — perfectly linear. */
    val PlayheadGlide: Easing = LinearEasing

    /** Ghost → target snap on release, slight overshoot. */
    val SyncSpring: Easing = CubicBezierEasing(0.3f, 1.4f, 0.4f, 1f)

    /** Poster ↔ playback dissolve. */
    val CrossDissolve: Easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

    /** Reserved for the receiver's chrome implementation. */
    val ChromeFade: Easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

    /** TV D-pad focus pop. */
    val FocusPop: Easing = CubicBezierEasing(0.2f, 0.9f, 0.25f, 1.1f)

    // --- Durations (ms) ---
    const val FlickSettleMs = 320
    const val SyncSpringMs = 180
    const val CrossDissolveMs = 400
    const val ChromeFadeInMs = 200
    const val ChromeFadeOutMs = 500
    const val FocusPopMs = 160

    // --- Ready-made specs ---
    fun <T> flickSettle(): DurationBasedAnimationSpec<T> =
        tween(durationMillis = FlickSettleMs, easing = FlickSettle)

    fun <T> syncSpring(): DurationBasedAnimationSpec<T> =
        tween(durationMillis = SyncSpringMs, easing = SyncSpring)

    fun <T> crossDissolve(): DurationBasedAnimationSpec<T> =
        tween(durationMillis = CrossDissolveMs, easing = CrossDissolve)

    fun <T> chromeFadeIn(): FiniteAnimationSpec<T> =
        tween(durationMillis = ChromeFadeInMs, easing = ChromeFade)
}

/**
 * Product motion that is not managed by Material must still respect the platform's
 * animator setting. API 26 is Flick's minimum, so this is safe without a fallback.
 */
@Composable
fun rememberReduceMotion(): Boolean = !ValueAnimator.areAnimatorsEnabled()
