package com.flick.receiver.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import android.provider.Settings

/**
 * Motion — "flick & settle" (design-tokens.md §6). The six curves are shared,
 * byte-for-byte, with the phone so that one event fires the same easing on both
 * surfaces. Durations are the canonical values.
 *
 * Note several curves overshoot: the control points may exceed 1 on the y axis
 * (b/d), which [CubicBezierEasing] permits; only the x controls (a/c) must stay
 * in [0,1].
 */
object FlickMotion {

    // --- Easing tokens ------------------------------------------------------

    /** Launch, toss-to-cast, seek confirm, screen transitions, play/pause morph. ~6% overshoot. */
    val FlickSettle: Easing = CubicBezierEasing(0.22f, 1.2f, 0.36f, 1f)

    /** The bar tracking the running clock. */
    val PlayheadGlide: Easing = LinearEasing

    /** Ghost → target snap on release (slight overshoot). */
    val SyncSpring: Easing = CubicBezierEasing(0.3f, 1.4f, 0.4f, 1f)

    /** Poster ↔ playback dissolve. */
    val CrossDissolve: Easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

    /** TV controls fade — CSS "ease". */
    val ChromeFade: Easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

    /** TV D-pad focus. */
    val FocusPop: Easing = CubicBezierEasing(0.2f, 0.9f, 0.25f, 1.1f)

    // --- Durations (ms) -----------------------------------------------------

    const val FLICK_SETTLE_MS = 320
    const val SYNC_SPRING_MS = 180
    const val CROSS_DISSOLVE_MS = 400
    const val CHROME_FADE_IN_MS = 200
    const val CHROME_FADE_OUT_MS = 500
    const val FOCUS_POP_MS = 160

    // --- Ready-made specs ---------------------------------------------------

    fun <T> flickSettle(): TweenSpec<T> = tween(FLICK_SETTLE_MS, easing = FlickSettle)
    fun <T> syncSpring(): TweenSpec<T> = tween(SYNC_SPRING_MS, easing = SyncSpring)
    fun <T> crossDissolve(): TweenSpec<T> = tween(CROSS_DISSOLVE_MS, easing = CrossDissolve)
    fun <T> focusPop(): TweenSpec<T> = tween(FOCUS_POP_MS, easing = FocusPop)
    fun <T> chromeFadeIn(): TweenSpec<T> = tween(CHROME_FADE_IN_MS, easing = ChromeFade)
    fun <T> chromeFadeOut(): TweenSpec<T> = tween(CHROME_FADE_OUT_MS, easing = ChromeFade)
}

/**
 * A zero animator scale is a request for static state, not merely faster motion.
 * Foundation components branch on this before starting ambient/infinite effects;
 * Compose's regular animation clock still scales the finite specs above.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return runCatching {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) <= 0f
    }.getOrDefault(false)
}
