package com.flick.receiver.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.KeyframesSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Motion — "flick & settle" (design-tokens.md §6, receiver-expressive-spec.md §6).
 * The six curves are shared, byte-for-byte, with the phone so that one event
 * fires the same easing on both surfaces. Durations are the canonical values.
 *
 * Note several curves overshoot: the control points may exceed 1 on the y axis
 * (b/d), which [CubicBezierEasing] permits; only the x controls (a/c) must stay
 * in [0,1].
 *
 * The design file names four animations; they map onto these tokens as:
 * `tvRise` → [tvRise], `tvBurst` → [tvBurstScale] + [tvBurstAlpha],
 * `tvSpin` → [tvSpin], `tvPulse` → [tvPulse].
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

    /** Ambient breathing (design `tvPulse`) — CSS "ease-in-out". */
    val Breathe: Easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

    // --- Durations (ms) -----------------------------------------------------

    const val FLICK_SETTLE_MS = 320
    const val SYNC_SPRING_MS = 180
    const val CROSS_DISSOLVE_MS = 400
    const val CHROME_FADE_IN_MS = 200
    const val CHROME_FADE_OUT_MS = 500
    const val FOCUS_POP_MS = 160

    /** Design `tvRise` — panel entrance, authored at 0.38–0.5 s. */
    const val TV_RISE_MS = 420

    /** Design `tvBurst` — the ±10 s seek flash. */
    const val TV_BURST_MS = 720

    /** `tvBurst` reaches full opacity and unit scale at 22 % of its run. */
    const val TV_BURST_PEAK_MS = 158

    /** Design `tvSpin` — the handshake ring, one turn per second, linear. */
    const val TV_SPIN_MS = 1000

    /** Design `tvPulse` full cycle; the spec below runs a reversing half-cycle. */
    const val TV_PULSE_MS = 1900

    // --- Entrance offsets ---------------------------------------------------

    /** Rise distance for the bottom transport panel and the side panels (§5.3). */
    val TvRise: Dp = 21.dp

    /** Rise distance for the centred handshake card (§5.2). */
    val TvRiseCard: Dp = 23.dp

    // --- tvPulse envelope ---------------------------------------------------

    const val PULSE_ALPHA_MIN = 0.35f
    const val PULSE_ALPHA_MAX = 1f
    const val PULSE_SCALE_MIN = 0.8f
    const val PULSE_SCALE_MAX = 1.25f

    // --- Focus envelope (§3) ------------------------------------------------

    /** Scale applied to a focused element; skipped under reduced motion. */
    const val FOCUS_SCALE = 1.06f

    // --- Ready-made specs ---------------------------------------------------

    fun <T> flickSettle(): TweenSpec<T> = tween(FLICK_SETTLE_MS, easing = FlickSettle)
    fun <T> syncSpring(): TweenSpec<T> = tween(SYNC_SPRING_MS, easing = SyncSpring)
    fun <T> crossDissolve(): TweenSpec<T> = tween(CROSS_DISSOLVE_MS, easing = CrossDissolve)
    fun <T> focusPop(): TweenSpec<T> = tween(FOCUS_POP_MS, easing = FocusPop)
    fun <T> chromeFadeIn(): TweenSpec<T> = tween(CHROME_FADE_IN_MS, easing = ChromeFade)
    fun <T> chromeFadeOut(): TweenSpec<T> = tween(CHROME_FADE_OUT_MS, easing = ChromeFade)

    /** Panel/card entrance — pair with a [TvRise]/[TvRiseCard] slide-up and a fade. */
    fun <T> tvRise(): TweenSpec<T> = tween(TV_RISE_MS, easing = FlickSettle)

    /** Seek-flash scale: 0.7 → 1 (at 22 %) → 1.14. */
    fun tvBurstScale(): KeyframesSpec<Float> = keyframes {
        durationMillis = TV_BURST_MS
        0.7f at 0 using FlickSettle
        1f at TV_BURST_PEAK_MS using ChromeFade
        1.14f at TV_BURST_MS
    }

    /** Seek-flash opacity: 0 → 1 (at 22 %) → 0. */
    fun tvBurstAlpha(): KeyframesSpec<Float> = keyframes {
        durationMillis = TV_BURST_MS
        0f at 0 using FlickSettle
        1f at TV_BURST_PEAK_MS using ChromeFade
        0f at TV_BURST_MS
    }

    /**
     * Handshake ring rotation — one linear turn per second, forever. Guard every
     * call site with [rememberReducedMotion]; the static ring still reads.
     */
    fun tvSpin(): InfiniteRepeatableSpec<Float> = infiniteRepeatable(
        animation = tween(TV_SPIN_MS, easing = LinearEasing),
        repeatMode = RepeatMode.Restart,
    )

    /**
     * The live-dot breath — a reversing half-cycle, so one full there-and-back
     * takes [TV_PULSE_MS]. Guard with [rememberReducedMotion].
     */
    fun tvPulse(): InfiniteRepeatableSpec<Float> = infiniteRepeatable(
        animation = tween(TV_PULSE_MS / 2, easing = Breathe),
        repeatMode = RepeatMode.Reverse,
    )
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
