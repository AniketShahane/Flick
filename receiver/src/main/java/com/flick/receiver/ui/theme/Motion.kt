package com.flick.receiver.ui.theme

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.KeyframesSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** One animation-scale observation per receiver composition, provided by the theme. */
val LocalReducedMotion = staticCompositionLocalOf { false }

/**
 * Motion — "flick & settle" (design-tokens.md §6, receiver-expressive-spec.md §6).
 *
 * Two halves. **Springs** carry everything a viewer can interrupt — focus, panels,
 * the seek reconcile — and carry the Expressive motion scheme's stiffnesses so the
 * TV speaks the same vocabulary as the phone. **Tweens** survive only
 * where nothing can interrupt them: looping ambience, media-clock motion, and
 * pure alpha. Each surviving tween below says why it is still a tween.
 *
 * The two apps diverge in exactly one place, and deliberately: TV spatial springs
 * are clamped to [TV_SPATIAL_DAMPING] / [TV_FOCUS_DAMPING], because the bounce
 * that reads as energy in the hand reads as instability at 55 inches.
 *
 * Note several surviving curves overshoot: the control points may exceed 1 on the
 * y axis (b/d), which [CubicBezierEasing] permits; only the x controls (a/c) must
 * stay in [0,1].
 *
 * The design file names four animations; they map onto these tokens as:
 * `tvRise` → [panelSpatial] over a [TvRise] offset, `tvBurst` → [tvBurstScale] +
 * [tvBurstAlpha], `tvSpin` → [tvSpin], `tvPulse` → [tvPulse].
 */
object FlickMotion {

    // --- Easing tokens ------------------------------------------------------

    /** Launch, toss-to-cast, seek confirm, screen transitions, play/pause morph. ~6% overshoot. */
    val FlickSettle: Easing = CubicBezierEasing(0.22f, 1.2f, 0.36f, 1f)

    /** The bar tracking the running clock. */
    val PlayheadGlide: Easing = LinearEasing

    /** Poster ↔ playback dissolve. */
    val CrossDissolve: Easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

    /** TV controls fade — CSS "ease". */
    val ChromeFade: Easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

    /** Ambient breathing (design `tvPulse`) — CSS "ease-in-out". */
    val Breathe: Easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

    // --- Durations (ms) -----------------------------------------------------

    const val FLICK_SETTLE_MS = 320
    const val CROSS_DISSOLVE_MS = 400
    const val CHROME_FADE_IN_MS = 200
    const val CHROME_FADE_OUT_MS = 500
    const val PRESS_CONFIRM_MS = 90

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

    /** A short inward acknowledgement for D-pad center / Enter. */
    const val PRESS_SCALE = 0.98f

    // --- Spring vocabulary (Expressive) -------------------------------------

    /**
     * The Expressive motion scheme's spring stiffnesses, transcribed. The receiver's
     * material3 comes from the Compose BOM, where `MotionScheme` and
     * `MaterialTheme.motionScheme` are `internal` — only the sender's pinned alpha
     * exposes them — so the TV cannot read the scheme object the phone reads. These
     * are its expressive values verbatim (`ExpressiveMotionTokens`), transcribed in
     * this one place so both apps still animate off a single vocabulary. Stiffness
     * is never adjusted for the TV; only damping is, below.
     */
    private const val DEFAULT_SPATIAL_STIFFNESS = 380f
    private const val FAST_SPATIAL_STIFFNESS = 800f
    private const val DEFAULT_EFFECTS_STIFFNESS = 1600f
    private const val FAST_EFFECTS_STIFFNESS = 3800f
    private const val EFFECTS_DAMPING = 1f

    /**
     * The TV's damping floor for geometry. The scheme's expressive spatial springs
     * damp at 0.6–0.8 because a phone is held in the hand that launched the motion;
     * a ten-foot screen is a destination, and an overshoot big enough to see across
     * a room reads as the panel wobbling. Stiffness is never touched — that is what
     * carries the Expressive character.
     */
    const val TV_SPATIAL_DAMPING = 0.8f

    /**
     * Focus geometry clamps harder still. `FlickDimens.FocusRingReserve` is derived
     * from the [FOCUS_SCALE] lift with no overshoot budget in it, so the ring must
     * not fly past the element it surrounds: at 0.85 the peak excursion is ~0.6 %,
     * which the reserve absorbs.
     */
    const val TV_FOCUS_DAMPING = 0.85f

    /** Focus lift, press acknowledgement and beacon travel. Small and frequent. */
    @Composable
    fun <T> focusSpatial(): FiniteAnimationSpec<T> =
        spring(dampingRatio = TV_FOCUS_DAMPING, stiffness = FAST_SPATIAL_STIFFNESS)

    /** The spring successor to [flickSettle] — glyph morphs, seek swells, chips. */
    @Composable
    fun <T> flickSettleSpatial(): FiniteAnimationSpec<T> =
        spring(dampingRatio = TV_SPATIAL_DAMPING, stiffness = FAST_SPATIAL_STIFFNESS)

    /** Panel and chrome geometry — a whole surface arriving, not a control. */
    @Composable
    fun <T> panelSpatial(): FiniteAnimationSpec<T> =
        spring(dampingRatio = TV_SPATIAL_DAMPING, stiffness = DEFAULT_SPATIAL_STIFFNESS)

    /**
     * Every colour, alpha and selection fill. Effects specs are critically damped
     * by design and are never clamped: an opacity that overshoots past its target
     * is a rendering glitch, not expression.
     */
    @Composable
    fun <T> stateEffects(): FiniteAnimationSpec<T> =
        spring(dampingRatio = EFFECTS_DAMPING, stiffness = DEFAULT_EFFECTS_STIFFNESS)

    /** [stateEffects] for a surface on its way out — exits lead with the fade. */
    @Composable
    fun <T> fastStateEffects(): FiniteAnimationSpec<T> =
        spring(dampingRatio = EFFECTS_DAMPING, stiffness = FAST_EFFECTS_STIFFNESS)

    /**
     * Seek-landing reconciliation. A spring rather than a curve because a held
     * D-pad seek re-aims it mid-flight from wherever the bar has reached; a tween
     * re-aimed the same way restarts on a fresh clock and visibly jerks once per
     * key repeat. The threshold is in track fractions — 0.0005 of an 800 dp bar is
     * 0.4 dp, under half a pixel at density 2.
     */
    fun syncSpring(): SpringSpec<Float> = spring(
        dampingRatio = 0.72f,
        stiffness = Spring.StiffnessMediumLow,
        visibilityThreshold = 0.0005f,
    )

    // --- Ready-made specs ---------------------------------------------------

    fun <T> flickSettle(): TweenSpec<T> = tween(FLICK_SETTLE_MS, easing = FlickSettle)
    fun <T> crossDissolve(): TweenSpec<T> = tween(CROSS_DISSOLVE_MS, easing = CrossDissolve)

    /** 90 ms is below the threshold where an interrupted tween can be seen to restart. */
    fun <T> pressConfirm(): TweenSpec<T> = tween(PRESS_CONFIRM_MS, easing = ChromeFade)

    /** Pure alpha, nothing to interrupt: a fade has no position to retarget from. */
    fun <T> chromeFadeIn(): TweenSpec<T> = tween(CHROME_FADE_IN_MS, easing = ChromeFade)
    fun <T> chromeFadeOut(): TweenSpec<T> = tween(CHROME_FADE_OUT_MS, easing = ChromeFade)

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
     * call site through [LocalReducedMotion]; the static ring still reads.
     */
    fun tvSpin(): InfiniteRepeatableSpec<Float> = infiniteRepeatable(
        animation = tween(TV_SPIN_MS, easing = LinearEasing),
        repeatMode = RepeatMode.Restart,
    )

    /**
     * The live-dot breath — a reversing half-cycle, so one full there-and-back
     * takes [TV_PULSE_MS]. Guard with [LocalReducedMotion].
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
    var animatorScale by remember(resolver) { mutableStateOf(readAnimatorScale(resolver)) }

    // This is a live setting on Android TV. Observing it keeps a viewer from
    // having to relaunch Flick after enabling Remove animations in Settings.
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                animatorScale = readAnimatorScale(resolver)
            }
        }
        val registered = runCatching {
            resolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
                false,
                observer,
            )
        }.isSuccess
        onDispose {
            if (registered) runCatching { resolver.unregisterContentObserver(observer) }
        }
    }
    return animatorScale <= 0f
}

private fun readAnimatorScale(resolver: android.content.ContentResolver): Float = runCatching {
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
}.getOrDefault(1f)
