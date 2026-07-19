package com.flick.sender.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.DurationBasedAnimationSpec
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween

/**
 * "Flick & settle" — the six motion tokens from design-tokens.md §6, expressed as
 * reusable [Easing] curves and [tween] specs. One event → one motion: these same
 * curves fire on both surfaces so a flick on the phone *is* the movement on the TV.
 */
object Motion {

    // --- Easing curves (cubic-bezier) ---
    /** Decisive spring, ~6% overshoot — launch, toss-to-cast, seek confirm, morph. */
    val FlickSettle: Easing = CubicBezierEasing(0.22f, 1.2f, 0.36f, 1f)

    /** The bar tracking the running clock — perfectly linear. */
    val PlayheadGlide: Easing = LinearEasing

    /** Ghost → target snap on release, slight overshoot. */
    val SyncSpring: Easing = CubicBezierEasing(0.3f, 1.4f, 0.4f, 1f)

    /** Poster ↔ playback dissolve. */
    val CrossDissolve: Easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

    /** TV controls in/out — plain ease. */
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
