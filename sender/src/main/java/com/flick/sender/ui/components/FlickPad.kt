package com.flick.sender.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.flick.sender.R
import com.flick.sender.ui.FilmPosterBackdrop
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.Motion
import com.flick.sender.ui.theme.TileShadow
import com.flick.sender.ui.theme.rememberFlickTouchHaptics
import com.flick.sender.ui.theme.rememberIsResumed
import com.flick.sender.ui.theme.rememberReduceMotion
import kotlin.math.floor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The film's poster as the thing you throw: swipe it up toward the TV and it sends, exactly
 * as the CTA under it does. [onFlick] must be that same action.
 *
 * Decoration to accessibility — the CTA already carries the action and its label, and a
 * second node saying the same thing would only make TalkBack read it twice.
 *
 * [enabled] false is the CTA's own disabled state: no drag is accepted and the idle nudge
 * stops, so the poster never invites a send the button would refuse.
 */
@Composable
internal fun FlickPad(
    posterPath: String?,
    frameRequest: ImageRequest,
    imageLoader: ImageLoader,
    enabled: Boolean,
    onFlick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFlickColors.current
    val motionScheme = MaterialTheme.motionScheme
    val reduceMotion = rememberReduceMotion()
    val resumed = rememberIsResumed()
    val haptics = rememberFlickTouchHaptics()
    val scope = rememberCoroutineScope()
    val currentOnFlick by rememberUpdatedState(onFlick)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentReduceMotion by rememberUpdatedState(reduceMotion)

    // Pixels, as displayed (after resistance), read only inside the poster's layer.
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    val held = remember { Animatable(0f) }
    val nudge = remember { Animatable(0f) }
    val presence = remember { Animatable(1f) }
    var touched by remember { mutableStateOf(false) }
    var armed by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }

    LaunchedEffect(touched, reduceMotion, resumed, enabled) {
        if (touched || reduceMotion || !resumed || !enabled) {
            // A nudge cut off mid-rise would otherwise leave the poster parked above its seat.
            if (reduceMotion) nudge.snapTo(0f) else nudge.animateTo(0f, tween(FlickPadMotion.NudgeFallMs))
            return@LaunchedEffect
        }
        // The sheet and the backdrop are still arriving when this route opens.
        delay(FlickPadMotion.NudgeFirstDelayMs)
        while (true) {
            nudge.animateTo(1f, tween(FlickPadMotion.NudgeRiseMs, easing = FlickPadMotion.NudgeRise))
            nudge.animateTo(0f, tween(FlickPadMotion.NudgeFallMs, easing = FlickPadMotion.NudgeFall))
            delay(FlickPadMotion.NudgeRestMs)
        }
    }

    val gesture = Modifier.pointerInput(enabled && !sending) {
        if (!enabled || sending) return@pointerInput
        val tracker = VelocityTracker()
        var rawX = 0f
        var rawY = 0f
        val flyPx = FlickPadMotion.FlyDistanceDp.dp.toPx()

        fun settle() {
            armed = false
            scope.launch { held.animateTo(0f, motionScheme.fastSpatialSpec<Float>()) }
            scope.launch { offsetX.animateTo(0f, motionScheme.defaultSpatialSpec<Float>()) }
            scope.launch { offsetY.animateTo(0f, motionScheme.defaultSpatialSpec<Float>()) }
        }

        detectDragGestures(
            onDragStart = { _: Offset ->
                touched = true
                rawX = 0f
                rawY = 0f
                tracker.resetTracking()
                scope.launch { nudge.snapTo(0f) }
                scope.launch { held.animateTo(1f, motionScheme.fastSpatialSpec<Float>()) }
            },
            onDragEnd = {
                val upDp = -rawY / density
                val upVelocity = -tracker.calculateVelocity().y / density
                if (currentEnabled && FlickPadPolicy.sends(upDp, upVelocity)) {
                    sending = true
                    armed = false
                    haptics.confirm()
                    scope.launch {
                        val snap = currentReduceMotion
                        held.snapTo(0f)
                        val flight = launch {
                            offsetY.animateTo(
                                offsetY.value - flyPx,
                                Motion.orSnap(snap, tween(FlickPadMotion.FlyMs, easing = FlickPadMotion.FlyEasing)),
                            )
                        }
                        presence.animateTo(
                            0f,
                            Motion.orSnap(snap, tween(FlickPadMotion.FlyMs, easing = FlickPadMotion.FadeEasing)),
                        )
                        flight.join()
                        if (currentEnabled) currentOnFlick()
                        // Still composed after this long means nothing navigated — a CTA
                        // that resolves to pairing, say — so the poster comes back to its
                        // seat instead of leaving an empty one.
                        delay(FlickPadMotion.ReturnDelayMs)
                        offsetX.snapTo(0f)
                        offsetY.snapTo(0f)
                        presence.animateTo(1f, Motion.orSnap(snap, tween(FlickPadMotion.ReturnFadeMs)))
                        sending = false
                    }
                } else {
                    settle()
                }
            },
            onDragCancel = { settle() },
        ) { change, amount ->
            change.consume()
            tracker.addPointerInputChange(change)
            rawX += amount.x
            rawY += amount.y
            val followX = FlickPadPolicy.followX(rawX / density) * density
            val followY = FlickPadPolicy.followY(rawY / density) * density
            scope.launch {
                offsetX.snapTo(followX)
                offsetY.snapTo(followY)
            }
            val nowArmed = FlickPadPolicy.armed(-rawY / density)
            if (nowArmed != armed) {
                armed = nowArmed
                if (nowArmed) haptics.gestureArmed()
            }
        }
    }

    val shape = RoundedCornerShape(FlickPadMotion.PosterCornerDp.dp)
    Column(
        modifier.fillMaxWidth().clearAndSetSemantics {},
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FlickChevrons(
            armed = { armed },
            live = enabled && !reduceMotion && resumed,
            dimmed = !enabled,
            color = colors.primary,
            modifier = Modifier.size(width = 30.dp, height = 34.dp),
        )
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .size(width = FlickPadMotion.PosterWidthDp.dp, height = FlickPadMotion.PosterHeightDp.dp)
                // Outside the layer, so the hit area stays at the seat while the poster
                // moves; inside it the finger's own deltas would shrink as the poster
                // followed them.
                .then(gesture)
                .graphicsLayer {
                    val nudgePx = FlickPadMotion.NudgeDp.dp.toPx() * nudge.value
                    translationX = offsetX.value
                    translationY = offsetY.value - nudgePx
                    rotationZ = FlickPadPolicy.tiltDegrees(offsetX.value / density)
                    val scale = lerp(1f, FlickPadMotion.HeldScale, held.value)
                    scaleX = scale
                    scaleY = scale
                    alpha = presence.value
                }
                .shadow(18.dp, shape, clip = false, ambientColor = TileShadow, spotColor = TileShadow)
                .clip(shape)
                .background(colors.surfaceRaisedAlt),
        ) {
            if (posterPath != null) {
                FilmPosterBackdrop(posterPath = posterPath, imageLoader = imageLoader, modifier = Modifier.fillMaxSize())
            } else {
                AsyncImage(
                    model = frameRequest,
                    contentDescription = null,
                    imageLoader = imageLoader,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(if (armed) R.string.flick_pad_armed else R.string.flick_pad_title),
            style = FlickText.titleSmall.copy(color = if (armed) colors.primary else colors.onSurface),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = stringResource(R.string.flick_pad_hint),
            style = FlickText.bodyMedium.copy(color = colors.onSurfaceDim),
            textAlign = TextAlign.Center,
            // Hidden rather than removed while armed, so the CTA below never moves.
            modifier = Modifier.graphicsLayer { alpha = if (armed) 0f else 1f },
        )
    }
}

/**
 * Three up-chevrons with a brightness wave travelling up through them. One canvas, and the
 * clock is read in the draw phase, so the ripple never recomposes the sheet.
 */
@Composable
private fun FlickChevrons(
    armed: () -> Boolean,
    live: Boolean,
    dimmed: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val clock: State<Float>? = if (live) {
        rememberInfiniteTransition(label = "chevrons").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(FlickPadMotion.ChevronCycleMs, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "chevron-clock",
        )
    } else {
        null
    }
    Canvas(modifier) {
        val stroke = FlickPadMotion.ChevronStrokeDp.dp.toPx()
        val rise = FlickPadMotion.ChevronRiseDp.dp.toPx()
        val pitch = FlickPadMotion.ChevronPitchDp.dp.toPx()
        val inset = stroke
        val isArmed = armed()
        for (index in 0 until FlickPadMotion.ChevronCount) {
            val alpha = when {
                dimmed -> FlickPadMotion.ChevronDimmedAlpha
                isArmed -> 1f
                clock == null -> chevronRestAlpha(index)
                else -> chevronAlpha(clock.value, index)
            }
            // Index 0 is the bottom chevron; the wave starts there and climbs.
            val baseY = size.height - inset - index * pitch
            val apex = Offset(size.width / 2f, baseY - rise)
            drawLine(color, Offset(inset, baseY), apex, stroke, StrokeCap.Round, alpha = alpha)
            drawLine(color, apex, Offset(size.width - inset, baseY), stroke, StrokeCap.Round, alpha = alpha)
        }
    }
}

/** The ripple: each chevron peaks a third of a cycle after the one below it. */
internal fun chevronAlpha(clock: Float, index: Int): Float {
    val shifted = clock - index.toFloat() / FlickPadMotion.ChevronCount
    val phase = shifted - floor(shifted)
    val pulse = if (phase < FlickPadMotion.ChevronPeakAt) {
        phase / FlickPadMotion.ChevronPeakAt
    } else {
        (1f - phase) / (1f - FlickPadMotion.ChevronPeakAt)
    }
    return lerp(FlickPadMotion.ChevronBaseAlpha, 1f, pulse)
}

/** Still, the chevrons brighten toward the top: the direction without the motion. */
internal fun chevronRestAlpha(index: Int): Float = when (index) {
    0 -> 0.35f
    1 -> 0.6f
    else -> 0.85f
}

/** Every number in docs/flick-hyperspace-phone.md; change either and change both. */
internal object FlickPadMotion {
    const val PosterWidthDp = 132f
    const val PosterHeightDp = 198f
    const val PosterCornerDp = 18f
    const val HeldScale = 1.03f

    const val NudgeDp = 12f
    const val NudgeFirstDelayMs = 1200L
    const val NudgeRiseMs = 280
    const val NudgeFallMs = 420
    /** Rise + fall + rest is the 2.8 s period. */
    const val NudgeRestMs = 2100L
    val NudgeRise: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val NudgeFall: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    const val FlyMs = 250
    const val FlyDistanceDp = 640f
    /** The flick's Release curve: breaks hard, glides long. */
    val FlyEasing: Easing = CubicBezierEasing(0.12f, 0.62f, 0.24f, 1f)
    val FadeEasing: Easing = CubicBezierEasing(0.4f, 0f, 1f, 1f)
    const val ReturnDelayMs = 700L
    const val ReturnFadeMs = 220

    const val ChevronCount = 3
    const val ChevronCycleMs = 1200
    const val ChevronPeakAt = 0.25f
    const val ChevronBaseAlpha = 0.25f
    const val ChevronDimmedAlpha = 0.2f
    const val ChevronStrokeDp = 3f
    const val ChevronRiseDp = 7f
    const val ChevronPitchDp = 10f
}
