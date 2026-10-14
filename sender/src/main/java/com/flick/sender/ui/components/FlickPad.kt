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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.flick.sender.R
import com.flick.sender.ui.FilmPosterBackdrop
import com.flick.sender.ui.motion.ambientFrameRate
import com.flick.sender.ui.motion.animatingFrameRate
import com.flick.sender.ui.theme.CinemaInkDim
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.Motion
import com.flick.sender.ui.theme.posterCardShape
import com.flick.sender.ui.theme.rememberFlickTouchHaptics
import com.flick.sender.ui.theme.rememberIsResumed
import com.flick.sender.ui.theme.rememberReduceMotion
import kotlin.math.floor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The film's poster as the thing you throw: swipe it up toward the TV and it sends. It is the
 * detail page's only send, so [onFlick] is the whole action, including the bounce to pairing
 * when no TV is connected.
 *
 * A plain tap never sends; it lifts the poster once to show which way it goes. TalkBack and
 * switch access get the send as the node's click action, labelled [actionLabel] and spoken
 * as [actionDescription].
 *
 * [enabled] false means the send is not available yet (resume progress still loading) or is
 * already under way: no drag is accepted and the idle nudge stops.
 *
 * [pageScroll] is the scroll of the page the pad sits in. While that page can still
 * scroll, a swipe starting on the poster is the page's, never a send.
 *
 * [caption] is the line under the poster; [note] is a quieter second line (resume point,
 * no TV yet, sending), and its seat is kept when it is null so the pad never changes height.
 *
 * [seat], when given, is the flip that carries the library tile into the poster's seat; the
 * pad's chrome then arrives on the page's tracks — [glowIn] under the card, [chevronsIn] above
 * it, [captionIn] below — and [throwReady] holds the throw until the card has landed.
 *
 * Drawn for the dark detail page only: its ink is white whatever the palette.
 */
@Composable
internal fun FlickPad(
    posterPath: String?,
    frameRequest: ImageRequest,
    imageLoader: ImageLoader,
    enabled: Boolean,
    pageScroll: ScrollState,
    caption: String,
    note: String?,
    actionDescription: String,
    actionLabel: String,
    onFlick: () -> Unit,
    modifier: Modifier = Modifier,
    posterWidth: Dp = FlickPadMotion.PosterWidthDp.dp,
    chevronGap: Dp = FlickPadMotion.ChevronGapDp.dp,
    captionGap: Dp = FlickPadMotion.TitleGapDp.dp,
    seat: CardFlipSeat? = null,
    throwReady: Boolean = true,
    glowIn: () -> Float = { 1f },
    chevronsIn: () -> Float = { 1f },
    captionIn: () -> Float = { 1f },
) {
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
    val hint = remember { Animatable(0f) }
    val presence = remember { Animatable(1f) }
    // The light-speed streak after a send, 0 to 1 on a linear clock; its curves are applied
    // in the layer.
    val streak = remember { Animatable(0f) }
    // Relative to the window, so the streak clears the top on a tall one too.
    val streakDistance = LocalConfiguration.current.screenHeightDp * FlickPadMotion.StreakReach
    var touched by remember { mutableStateOf(false) }
    var armed by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    // From the finger taking the poster until it is back at rest in its seat.
    var dragging by remember { mutableStateOf(false) }

    LaunchedEffect(touched, reduceMotion, resumed, enabled) {
        if (touched || reduceMotion || !resumed || !enabled) {
            // A nudge cut off mid-rise would otherwise leave the poster parked above its seat.
            if (reduceMotion) nudge.snapTo(0f) else nudge.animateTo(0f, tween(FlickPadMotion.NudgeFallMs))
            return@LaunchedEffect
        }
        // The page and the backdrop are still arriving when this route opens.
        delay(FlickPadMotion.NudgeFirstDelayMs)
        while (true) {
            nudge.animateTo(1f, tween(FlickPadMotion.NudgeRiseMs, easing = FlickPadMotion.NudgeRise))
            nudge.animateTo(0f, tween(FlickPadMotion.NudgeFallMs, easing = FlickPadMotion.NudgeFall))
            delay(FlickPadMotion.NudgeRestMs)
        }
    }

    // Keyed on the page's scroll state too: a new state object means the predicate below
    // would otherwise keep asking the old one.
    val gesture = Modifier.pointerInput(enabled && !sending && throwReady, pageScroll) {
        if (!enabled || sending || !throwReady) return@pointerInput

        fun settle() {
            armed = false
            scope.launch {
                val lower = launch { held.animateTo(0f, motionScheme.fastSpatialSpec<Float>()) }
                val across = launch { offsetX.animateTo(0f, motionScheme.defaultSpatialSpec<Float>()) }
                offsetY.animateTo(0f, motionScheme.defaultSpatialSpec<Float>())
                lower.join()
                across.join()
                // Not reached when a new drag takes the poster mid-settle: that cancels these.
                dragging = false
            }
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
            // Unconsumed, so the page's own scroll still sees every gesture this declines.
            val down = awaitFirstDown(requireUnconsumed = false)
            val tracker = VelocityTracker()
            tracker.addPointerInputChange(down)
            // A change left unconsumed here goes on to the page's scroll, which takes it and
            // cancels this wait: the poster only claims a drag the page has no use for.
            val start = awaitTouchSlopOrCancellation(down.id) { change, _ ->
                val travelYDp = (change.position.y - down.position.y) / density
                if (FlickPadPolicy.claims(pageScroll.canScrollForward, pageScroll.canScrollBackward, travelYDp)) {
                    change.consume()
                }
            }
            if (start == null) {
                // Released inside slop and nobody took it: a tap. It shows the throw instead
                // of making it, because a tap on a poster is too easy to make by accident.
                val release = currentEvent.changes.firstOrNull { it.id == down.id }
                if (release != null && release.changedToUp()) {
                    touched = true
                    if (!currentReduceMotion) {
                        scope.launch {
                            hint.animateTo(1f, tween(FlickPadMotion.HintRiseMs, easing = FlickPadMotion.NudgeRise))
                            hint.animateTo(0f, motionScheme.defaultSpatialSpec<Float>())
                        }
                    }
                }
                return@awaitEachGesture
            }

            // Also reached when the key changes mid-drag, which cancels this coroutine
            // without any release: the poster must not be left displaced or armed.
            try {
                touched = true
                dragging = true
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
                            streak.animateTo(1f, Motion.orSnap(snap, tween(FlickPadMotion.StreakMs, easing = LinearEasing)))
                        }
                        // Cut over the streak's last frames: by then it is a sliver of light
                        // leaving the top of the window.
                        presence.animateTo(
                            0f,
                            Motion.orSnap(
                                snap,
                                tween(
                                    FlickPadMotion.StreakFadeMs,
                                    delayMillis = FlickPadMotion.StreakMs - FlickPadMotion.StreakFadeMs,
                                    easing = LinearEasing,
                                ),
                            ),
                        )
                        flight.join()
                        // Still composed after this long means nothing navigated — a CTA
                        // that resolves to pairing, say — so the poster comes back to its
                        // seat instead of leaving an empty one.
                        delay(FlickPadMotion.ReturnDelayMs)
                        streak.snapTo(0f)
                        offsetX.snapTo(0f)
                        offsetY.snapTo(0f)
                        presence.animateTo(1f, Motion.orSnap(snap, tween(FlickPadMotion.ReturnFadeMs)))
                        sending = false
                        dragging = false
                    }
                }
            } finally {
                if (!sending) settle()
            }
        }
    }

    Column(
        modifier.fillMaxWidth().clearAndSetSemantics {
            contentDescription = actionDescription
            role = Role.Button
            if (enabled) {
                onClick(label = actionLabel) {
                    currentOnFlick()
                    true
                }
            } else {
                disabled()
            }
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FlickChevrons(
            armed = { armed },
            live = enabled && !reduceMotion && resumed,
            dimmed = !enabled,
            color = Color.White,
            modifier = Modifier
                .size(
                    width = FlickPadMotion.ChevronWidthDp.dp,
                    height = FlickPadMotion.ChevronRowDp.dp,
                )
                .fadeUp(chevronsIn, CardFlipMotion.RiseDistance),
        )
        Spacer(Modifier.height(chevronGap))
        FlickPosterCard(
            posterPath = posterPath,
            frameRequest = frameRequest,
            imageLoader = imageLoader,
            width = posterWidth,
            seat = seat,
            glowIn = glowIn,
            glare = { FlickPadMotion.StreakGlare * FlickPadMotion.StreakEasing.transform(streak.value) },
            // Outside the layer, so the hit area stays at the seat while the poster moves;
            // inside it the finger's own deltas would shrink as the poster followed them.
            modifier = gesture
                .animatingFrameRate(dragging || sending)
                .graphicsLayer {
                    val liftPx = FlickPadMotion.NudgeDp.dp.toPx() * nudge.value +
                        FlickPadMotion.HintDp.dp.toPx() * hint.value
                    // The streak straightens the throw as it goes: the sideways offset and the
                    // tilt are let go while the card stretches into a line and leaves upward.
                    val s = streak.value
                    val e = FlickPadMotion.StreakEasing.transform(s)
                    translationX = offsetX.value * (1f - s)
                    translationY = offsetY.value - liftPx - streakDistance.dp.toPx() * e
                    rotationZ = FlickPadPolicy.tiltDegrees(offsetX.value / density) * (1f - s)
                    val scale = lerp(1f, FlickPadMotion.HeldScale, held.value)
                    scaleX = scale * lerp(1f, FlickPadMotion.StreakEndScaleX, e)
                    scaleY = scale * lerp(1f, FlickPadMotion.StreakEndScaleY, e)
                    alpha = presence.value
                    // An offscreen layer would clip the glow and the shadow, which paint outside
                    // the card, for as long as the poster is fading.
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                },
        )
        Spacer(Modifier.height(captionGap))
        Column(
            Modifier.fadeUp(captionIn, CardFlipMotion.CaptionRiseDistance, flat = true),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (armed) stringResource(R.string.flick_pad_armed) else caption,
                style = FlickText.titleSmall.copy(color = Color.White),
                textAlign = TextAlign.Center,
                // One line in both states, so arming never moves anything below.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = note.orEmpty(),
                style = FlickText.bodySmall.copy(color = CinemaInkDim),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * The film's portrait card at the pad's size: the TMDB poster, else the video's own frame,
 * cropped into an exact 2:3 so nothing is letterboxed and a corner proportional to the width
 * takes nothing that matters. [modifier] goes on outside the card's size and its layers, so a
 * caller's gesture and layer move the glow and the shadow with the card.
 *
 * The glow and the shadow are a layer of their own under the card rather than part of it: the
 * card is what turns over when it flies in from the grid ([seat]), and light cast on the page
 * stays flat on the page. They grow in with [glowIn] once the card is nearly seated. [glare]
 * washes the art toward white, for the send's streak; read in the draw phase.
 */
@Composable
internal fun FlickPosterCard(
    posterPath: String?,
    frameRequest: ImageRequest,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier,
    width: Dp = FlickPadMotion.PosterWidthDp.dp,
    seat: CardFlipSeat? = null,
    glowIn: () -> Float = { 1f },
    glare: () -> Float = { 0f },
) {
    val colors = LocalFlickColors.current
    val shape = posterCardShape(width)
    val glow = colors.primary
    val ground = colors.surfaceRaisedAlt
    Box(modifier.size(width = width, height = width * FlickPadMotion.PosterAspect)) {
        Box(
            Modifier
                .matchParentSize()
                // The elevation rather than the layer's opacity: an opacity under 1 draws the
                // layer offscreen and would cut the glow and the shadow at the card's edge.
                .graphicsLayer {
                    val g = glowIn().coerceIn(0f, 1f)
                    val scale = lerp(CardFlipMotion.GlowFromScale, 1f, g)
                    scaleX = scale
                    scaleY = scale
                    shadowElevation = FlickPadMotion.PosterShadowDp.dp.toPx() * g
                    this.shape = shape
                    clip = false
                    ambientShadowColor = glow
                    spotShadowColor = glow
                }
                // Painted rather than left to the shadow: a tinted shadow is a no-op before
                // Android 9 and barely reads on a near-black ground after it.
                .drawWithCache {
                    val reach = size.width * FlickPadMotion.GlowReach
                    val brush = Brush.radialGradient(
                        colors = listOf(glow.copy(alpha = FlickPadMotion.GlowAlpha), Color.Transparent),
                        center = size.center,
                        radius = reach,
                    )
                    val stretch = size.height / size.width
                    onDrawBehind {
                        scale(1f, stretch, pivot = center) {
                            drawCircle(brush = brush, radius = reach, center = center, alpha = glowIn().coerceIn(0f, 1f))
                        }
                    }
                },
        )
        FlipCard(seat = seat, seatCorner = width * PosterCornerShare, modifier = Modifier.matchParentSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(ground)
                    // Over the art, inside the card's clip: the poster burning white as it
                    // becomes the streak.
                    .drawWithContent {
                        drawContent()
                        val g = glare()
                        if (g > 0f) drawRect(FlickPadMotion.StreakLight, alpha = g)
                    },
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
        }
    }
}

/** [posterCardShape]'s corner as a share of the width, for a card that has to animate it. */
private const val PosterCornerShare = 0.08f

/**
 * Three up-chevrons with a brightness wave travelling up through them. One canvas, and the
 * clock is read in the draw phase, so the ripple never recomposes the page.
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
    // The ripple is a slow loop and reads the same at a quarter of the panel's peak rate.
    Canvas(modifier.ambientFrameRate()) {
        val stroke = FlickPadMotion.ChevronStrokeDp.dp.toPx()
        val rise = FlickPadMotion.ChevronRiseDp.dp.toPx()
        val pitch = FlickPadMotion.ChevronPitchDp.dp.toPx()
        val haloStroke = stroke + FlickPadMotion.ChevronHaloDp.dp.toPx()
        val halo = Offset(0f, FlickPadMotion.ChevronHaloDropDp.dp.toPx())
        val inset = haloStroke / 2f
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
            val left = Offset(inset, baseY)
            val right = Offset(size.width - inset, baseY)
            // A soft dark bed under each stroke, so the white holds over a bright backdrop.
            val haloAlpha = alpha * FlickPadMotion.ChevronHaloAlpha
            drawLine(Color.Black, left + halo, apex + halo, haloStroke, StrokeCap.Round, alpha = haloAlpha)
            drawLine(Color.Black, apex + halo, right + halo, haloStroke, StrokeCap.Round, alpha = haloAlpha)
            drawLine(color, left, apex, stroke, StrokeCap.Round, alpha = alpha)
            drawLine(color, apex, right, stroke, StrokeCap.Round, alpha = alpha)
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
    0 -> 0.6f
    1 -> 0.8f
    else -> 1f
}

/** Every number in docs/flick-hyperspace-phone.md; change either and change both. */
internal object FlickPadMotion {
    /**
     * Exactly 2:3, so a poster crops by nothing and a frame crops evenly. The detail page
     * sizes the width to its window; this is the size when nothing says otherwise.
     */
    const val PosterWidthDp = 150f
    const val PosterAspect = 1.5f
    const val PosterShadowDp = 24f
    /** The painted glow's centre alpha and its horizontal reach, as a share of the width. */
    const val GlowAlpha = 0.42f
    const val GlowReach = 0.8f
    const val HeldScale = 1.03f

    const val NudgeDp = 12f
    const val NudgeFirstDelayMs = 1200L
    const val NudgeRiseMs = 280
    const val NudgeFallMs = 420
    /** Rise + fall + rest is the 2.8 s period. */
    const val NudgeRestMs = 2100L
    /** A tap's answer: one higher lift, settled on the default spatial spring. */
    const val HintDp = 22f
    const val HintRiseMs = 200
    val NudgeRise: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val NudgeFall: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    /**
     * The send as a jump to light speed: the card stretches to a line [StreakEndScaleX] wide
     * and [StreakEndScaleY] tall, burns toward white and leaves [StreakReach] window heights
     * up, all on one accelerating curve, and is cut over the last [StreakFadeMs], by when it
     * is mostly past the top of the window.
     */
    const val StreakMs = 200
    const val StreakFadeMs = 30
    const val StreakReach = 1.4f
    const val StreakEndScaleX = 0.12f
    const val StreakEndScaleY = 3.2f
    const val StreakGlare = 0.9f
    val StreakLight = Color(0xFFEAF1FF)
    val StreakEasing: Easing = CubicBezierEasing(0.32f, 0f, 0.67f, 0f)
    const val ReturnDelayMs = 700L
    const val ReturnFadeMs = 220

    const val ChevronCount = 3
    const val ChevronCycleMs = 1200
    const val ChevronPeakAt = 0.25f
    const val ChevronBaseAlpha = 0.55f
    const val ChevronDimmedAlpha = 0.3f
    const val ChevronStrokeDp = 3.2f
    const val ChevronRiseDp = 6f
    const val ChevronPitchDp = 8f
    const val ChevronWidthDp = 30f
    const val ChevronRowDp = 32f
    const val ChevronHaloDp = 3f
    const val ChevronHaloDropDp = 1f
    const val ChevronHaloAlpha = 0.35f

    /** Roomy gaps; the detail page passes tighter ones on a short window. */
    const val ChevronGapDp = 10f
    const val TitleGapDp = 16f
}
