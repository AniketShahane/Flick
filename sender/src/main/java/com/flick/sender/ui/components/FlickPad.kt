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
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
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
import androidx.compose.ui.text.style.TextOverflow
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
 *
 * [sheetScroll] is the scroll of the sheet the pad sits in. While that sheet can still
 * scroll, a swipe starting on the poster is the sheet's, never a send.
 */
@Composable
internal fun FlickPad(
    posterPath: String?,
    frameRequest: ImageRequest,
    imageLoader: ImageLoader,
    enabled: Boolean,
    sheetScroll: ScrollState,
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

    // Keyed on the sheet's scroll state too: a new state object means the predicate below
    // would otherwise keep asking the old one.
    val gesture = Modifier.pointerInput(enabled && !sending, sheetScroll) {
        if (!enabled || sending) return@pointerInput
        val flyPx = FlickPadMotion.FlyDistanceDp.dp.toPx()

        fun settle() {
            armed = false
            scope.launch { held.animateTo(0f, motionScheme.fastSpatialSpec<Float>()) }
            scope.launch { offsetX.animateTo(0f, motionScheme.defaultSpatialSpec<Float>()) }
            scope.launch { offsetY.animateTo(0f, motionScheme.defaultSpatialSpec<Float>()) }
        }

        // Measured from the down position, not from where slop was crossed, so the arm and
        // fling distances are the finger's real travel.
        fun follow(travel: Offset) {
            val followX = FlickPadPolicy.followX(travel.x / density) * density
            val followY = FlickPadPolicy.followY(travel.y / density) * density
            scope.launch {
                offsetX.snapTo(followX)
                offsetY.snapTo(followY)
            }
            val nowArmed = FlickPadPolicy.armed(-travel.y / density)
            if (nowArmed != armed) {
                armed = nowArmed
                if (nowArmed) haptics.gestureArmed()
            }
        }

        awaitEachGesture {
            // Unconsumed, so the sheet's own scroll still sees every gesture this declines.
            val down = awaitFirstDown(requireUnconsumed = false)
            val tracker = VelocityTracker()
            tracker.addPointerInputChange(down)
            // A change left unconsumed here goes on to the sheet's scroll, which takes it and
            // cancels this wait: the poster only claims a drag the sheet has no use for.
            val start = awaitTouchSlopOrCancellation(down.id) { change, _ ->
                val travelYDp = (change.position.y - down.position.y) / density
                if (FlickPadPolicy.claims(sheetScroll.canScrollForward, sheetScroll.canScrollBackward, travelYDp)) {
                    change.consume()
                }
            } ?: return@awaitEachGesture

            // Also reached when the key changes mid-drag, which cancels this coroutine
            // without any release: the poster must not be left displaced or armed.
            try {
                touched = true
                scope.launch { nudge.snapTo(0f) }
                scope.launch { held.animateTo(1f, motionScheme.fastSpatialSpec<Float>()) }
                tracker.addPointerInputChange(start)
                follow(start.position - down.position)
                var last = start
                val released = drag(start.id) { change ->
                    change.consume()
                    tracker.addPointerInputChange(change)
                    last = change
                    follow(change.position - down.position)
                }
                val upDp = -(last.position.y - down.position.y) / density
                val upVelocity = -tracker.calculateVelocity().y / density
                if (released && currentEnabled && FlickPadPolicy.sends(upDp, upVelocity)) {
                    sending = true
                    armed = false
                    haptics.gestureEnd()
                    // Before the flight, not after it: the flight is decoration, and a pad
                    // that leaves composition mid-flight must not take the send with it.
                    currentOnFlick()
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
                        // Still composed after this long means nothing navigated — a CTA
                        // that resolves to pairing, say — so the poster comes back to its
                        // seat instead of leaving an empty one.
                        delay(FlickPadMotion.ReturnDelayMs)
                        offsetX.snapTo(0f)
                        offsetY.snapTo(0f)
                        presence.animateTo(1f, Motion.orSnap(snap, tween(FlickPadMotion.ReturnFadeMs)))
                        sending = false
                    }
                }
            } finally {
                if (!sending) settle()
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
            modifier = Modifier.size(
                width = FlickPadMotion.ChevronWidthDp.dp,
                height = FlickPadMotion.ChevronRowDp.dp,
            ),
        )
        Spacer(Modifier.height(FlickPadMotion.ChevronGapDp.dp))
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
                .shadow(FlickPadMotion.PosterShadowDp.dp, shape, clip = false, ambientColor = TileShadow, spotColor = TileShadow)
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
        Spacer(Modifier.height(FlickPadMotion.TitleGapDp.dp))
        Text(
            text = stringResource(if (armed) R.string.flick_pad_armed else R.string.flick_pad_title),
            style = FlickText.titleSmall.copy(color = if (armed) colors.primary else colors.onSurface),
            textAlign = TextAlign.Center,
            // One line in both states, so arming never moves the CTA below.
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
    const val PosterWidthDp = 104f
    const val PosterHeightDp = 156f
    const val PosterCornerDp = 14f
    const val PosterShadowDp = 14f
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
    const val ChevronStrokeDp = 2.5f
    const val ChevronRiseDp = 5f
    const val ChevronPitchDp = 6.5f
    const val ChevronWidthDp = 24f
    const val ChevronRowDp = 24f

    /** Chevrons, gap, poster, gap, one title line: about 210 dp of sheet in all. */
    const val ChevronGapDp = 6f
    const val TitleGapDp = 8f
}
