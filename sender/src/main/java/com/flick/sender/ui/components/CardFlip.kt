package com.flick.sender.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.AnimationVector3D
import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.VectorizedFiniteAnimationSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.flick.sender.ui.theme.FlickCorners
import com.flick.sender.ui.theme.PosterHairline
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The library tile ⇄ poster seat transition: the tile lifts, turns over as it travels, and
 * lands in the detail page's poster seat with the poster on its back. Back is the same path
 * on no-bounce springs, and the lift lets go last so the card drops into its slot.
 *
 * One composable draws both faces, on the SEAT's side ([FlipCard]); the tile's own copy is
 * hidden for the flight ([hideWhileFlipping]). The bounds come from one `sharedBounds` per
 * end, remeasured rather than scaled, so each face is cropped to the card's current shape on
 * every frame and nothing stretches. Everything else is a transform or an opacity.
 */
internal fun cardFlipKey(itemId: Long): String = "card-flip-$itemId"

/** One track: a spring, held for [delayMs] when it starts from rest. */
internal class FlipTrack(val delayMs: Int, val dampingRatio: Float, val stiffness: Float)

/** Every number in docs/flick-hyperspace-phone.md's card-flip table; change both together. */
internal object CardFlipMotion {
    val LiftOpen = FlipTrack(0, 0.75f, 900f)
    val BadgeOpen = FlipTrack(0, 1f, 2500f)
    val MoveOpen = FlipTrack(60, 0.86f, 260f)
    val FlipOpen = FlipTrack(60, 0.6f, 190f)
    val PageOpen = FlipTrack(60, 1f, 360f)
    val DiscOpen = FlipTrack(260, 1f, 500f)
    val LogoOpen = FlipTrack(290, 1f, 500f)
    val MetaOpen = FlipTrack(320, 1f, 500f)
    val ChipsOpen = FlipTrack(350, 1f, 500f)
    val GlowOpen = FlipTrack(380, 1f, 300f)
    val ChevronsOpen = FlipTrack(460, 1f, 500f)
    val CaptionOpen = FlipTrack(480, 1f, 500f)

    val LiftBack = FlipTrack(300, 0.85f, 1000f)
    val BadgeBack = FlipTrack(360, 1f, 2500f)
    val MoveBack = FlipTrack(0, 1f, 520f)
    val FlipBack = FlipTrack(0, 1f, 420f)
    val PageBack = FlipTrack(80, 1f, 620f)
    val ChromeBack = FlipTrack(0, 1f, 1400f)
    val GlowBack = FlipTrack(0, 1f, 1100f)

    /** How much bigger the card is while it is lifted and has not yet travelled. */
    const val LiftScale = 0.08f
    /** The roll at the edge-on moment, against the turn. */
    const val TiltDegrees = -6f
    const val CameraDistance = 12f
    /** Where the glow under the seated card starts growing from. */
    const val GlowFromScale = 0.7f
    val RiseDistance = 14.dp
    val CaptionRiseDistance = 18.dp
}

/**
 * [spec] with a start delay that only applies when the animation begins at rest. A transition
 * reversed mid-flight hands its spring the velocity it had, and a delay there would freeze the
 * card in the air before it turned round; one started fresh has none.
 */
internal fun <T> restDelayed(delayMs: Int, spec: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> =
    if (delayMs <= 0) spec else RestDelayedSpec(delayMs * 1_000_000L, spec)

private class RestDelayedSpec<T>(
    private val delayNanos: Long,
    private val spec: FiniteAnimationSpec<T>,
) : FiniteAnimationSpec<T> {
    override fun <V : AnimationVector> vectorize(
        converter: TwoWayConverter<T, V>,
    ): VectorizedFiniteAnimationSpec<V> = RestDelayedVectorizedSpec(delayNanos, spec.vectorize(converter))

    override fun equals(other: Any?): Boolean =
        other is RestDelayedSpec<*> && other.delayNanos == delayNanos && other.spec == spec

    override fun hashCode(): Int = 31 * spec.hashCode() + delayNanos.hashCode()
}

private class RestDelayedVectorizedSpec<V : AnimationVector>(
    private val delayNanos: Long,
    private val spec: VectorizedFiniteAnimationSpec<V>,
) : VectorizedFiniteAnimationSpec<V> {
    private fun delayFor(initialVelocity: V): Long = if (initialVelocity.atRest()) delayNanos else 0L

    override fun getValueFromNanos(playTimeNanos: Long, initialValue: V, targetValue: V, initialVelocity: V): V {
        val delay = delayFor(initialVelocity)
        return if (playTimeNanos < delay) {
            initialValue
        } else {
            spec.getValueFromNanos(playTimeNanos - delay, initialValue, targetValue, initialVelocity)
        }
    }

    override fun getVelocityFromNanos(playTimeNanos: Long, initialValue: V, targetValue: V, initialVelocity: V): V {
        val delay = delayFor(initialVelocity)
        return if (playTimeNanos < delay) {
            initialVelocity
        } else {
            spec.getVelocityFromNanos(playTimeNanos - delay, initialValue, targetValue, initialVelocity)
        }
    }

    override fun getDurationNanos(initialValue: V, targetValue: V, initialVelocity: V): Long =
        delayFor(initialVelocity) + spec.getDurationNanos(initialValue, targetValue, initialVelocity)
}

private fun AnimationVector.atRest(): Boolean = when (this) {
    is AnimationVector1D -> abs(value) < RestVelocity
    is AnimationVector2D -> abs(v1) < RestVelocity && abs(v2) < RestVelocity
    is AnimationVector3D -> abs(v1) < RestVelocity && abs(v2) < RestVelocity && abs(v3) < RestVelocity
    is AnimationVector4D ->
        abs(v1) < RestVelocity && abs(v2) < RestVelocity && abs(v3) < RestVelocity && abs(v4) < RestVelocity
    else -> true
}

private const val RestVelocity = 1e-3f

private fun FlipTrack.floatSpec(threshold: Float = TrackThreshold): FiniteAnimationSpec<Float> =
    restDelayed(delayMs, spring(dampingRatio, stiffness, threshold))

/** Fine enough that the half-turn lands without a visible last-frame snap (0.18°). */
private const val TrackThreshold = 0.001f

/**
 * The entering end of a shared bounds drives it, so each end names its own direction's spring
 * and never has to read the geometry to guess which way it is going: the seat only ever
 * arrives on the way out of the grid, the tile only ever on the way back. A pixel per edge is
 * where the card is home.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
private val SeatBounds = BoundsTransform { _, _ ->
    restDelayed(
        CardFlipMotion.MoveOpen.delayMs,
        spring(CardFlipMotion.MoveOpen.dampingRatio, CardFlipMotion.MoveOpen.stiffness, Rect.VisibilityThreshold),
    )
}

@OptIn(ExperimentalSharedTransitionApi::class)
private val TileBounds = BoundsTransform { _, _ ->
    restDelayed(
        CardFlipMotion.MoveBack.delayMs,
        spring(CardFlipMotion.MoveBack.dampingRatio, CardFlipMotion.MoveBack.stiffness, Rect.VisibilityThreshold),
    )
}

/**
 * One end of the flip's bounds. No fades: the tile's copy is hidden by the caller and the
 * seat draws both faces, so neither end may dissolve. Remeasured, so a face laid out with a
 * crop is re-cropped at every size instead of being stretched from the shape it started in.
 * In the overlay, so the card can leave the grid's clip and cross the page's.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
internal fun Modifier.cardFlipBounds(
    sharedScope: SharedTransitionScope,
    animatedScope: AnimatedVisibilityScope,
    state: SharedTransitionScope.SharedContentState,
    seat: Boolean,
): Modifier = with(sharedScope) {
    this@cardFlipBounds.sharedBounds(
        sharedContentState = state,
        animatedVisibilityScope = animatedScope,
        enter = EnterTransition.None,
        exit = ExitTransition.None,
        boundsTransform = if (seat) SeatBounds else TileBounds,
        resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
        renderInOverlayDuringTransition = true,
    )
}

/** Hides the tile's own copy while the seat is drawing the card in its place. */
@OptIn(ExperimentalSharedTransitionApi::class)
internal fun Modifier.hideWhileFlipping(
    sharedScope: SharedTransitionScope,
    state: SharedTransitionScope.SharedContentState,
): Modifier = graphicsLayer {
    alpha = if (state.isMatchFound && sharedScope.isTransitionActive) 0f else 1f
}

/**
 * The page side's tracks. [paired] is the shell's verdict that this transition is the
 * library ⇄ detail pair; on any other route pair every track rests at 1 and the page simply
 * is there, so the route's own transition is the only motion.
 */
@Stable
internal class CardFlipTracks(
    val lift: State<Float>,
    val move: State<Float>,
    val flip: State<Float>,
    val page: State<Float>,
    val disc: State<Float>,
    val logo: State<Float>,
    val meta: State<Float>,
    val chips: State<Float>,
    val glow: State<Float>,
    val chevrons: State<Float>,
    val caption: State<Float>,
)

@Composable
internal fun rememberCardFlipTracks(
    transition: Transition<EnterExitState>,
    paired: Boolean,
): CardFlipTracks = with(CardFlipMotion) {
    CardFlipTracks(
        lift = transition.seatTrack(paired, LiftOpen, LiftBack, "flip-lift"),
        move = transition.seatTrack(paired, MoveOpen, MoveBack, "flip-move"),
        flip = transition.seatTrack(paired, FlipOpen, FlipBack, "flip-turn"),
        page = transition.seatTrack(paired, PageOpen, PageBack, "flip-page"),
        disc = transition.seatTrack(paired, DiscOpen, ChromeBack, "flip-disc"),
        logo = transition.seatTrack(paired, LogoOpen, ChromeBack, "flip-logo"),
        meta = transition.seatTrack(paired, MetaOpen, ChromeBack, "flip-meta"),
        chips = transition.seatTrack(paired, ChipsOpen, ChromeBack, "flip-chips"),
        glow = transition.seatTrack(paired, GlowOpen, GlowBack, "flip-glow"),
        chevrons = transition.seatTrack(paired, ChevronsOpen, ChromeBack, "flip-chevrons"),
        caption = transition.seatTrack(paired, CaptionOpen, ChromeBack, "flip-caption"),
    )
}

@Composable
private fun Transition<EnterExitState>.seatTrack(
    paired: Boolean,
    open: FlipTrack,
    back: FlipTrack,
    label: String,
): State<Float> = animateFloat(
    transitionSpec = {
        if (targetState == EnterExitState.Visible) open.floatSpec() else back.floatSpec()
    },
    label = label,
) { state -> if (state == EnterExitState.Visible || !paired) 1f else 0f }

/**
 * The grid side's one track: how far the tile's slot — its badges, its running time, its
 * shadow — has faded out from under the card that lifted off it.
 */
@Composable
internal fun rememberTileBadgeTrack(
    transition: Transition<EnterExitState>,
    paired: Boolean,
): State<Float> = transition.animateFloat(
    transitionSpec = {
        if (targetState == EnterExitState.PostExit) {
            CardFlipMotion.BadgeOpen.floatSpec()
        } else {
            CardFlipMotion.BadgeBack.floatSpec()
        }
    },
    label = "flip-badge",
) { state -> if (state != EnterExitState.Visible && paired) 1f else 0f }

/**
 * Rises [distance] into place and fades in with [progress]: the page's chrome arriving
 * around the card. Read in the layer, so the tracks never recompose what they move.
 */
internal fun Modifier.fadeUp(progress: () -> Float, distance: Dp): Modifier = graphicsLayer {
    val p = progress()
    alpha = p.coerceIn(0f, 1f)
    translationY = (1f - p) * distance.toPx()
}

/** What the seat needs to draw the flight: the shared bounds' scopes and the page's tracks. */
@Stable
internal class CardFlipSeat(
    val sharedScope: SharedTransitionScope,
    val animatedScope: AnimatedVisibilityScope,
    val key: String,
    val tracks: CardFlipTracks,
    val front: @Composable () -> Unit,
)

/**
 * The seated card, and the card in flight. [back] is the poster as it rests in the seat;
 * [seat]'s front is the tile as it rests in the grid, composed only while a flip can happen.
 *
 * Two layers, so the roll stays in the screen's plane while the card turns about its own
 * vertical axis. The faces swap where the card is edge-on, and the back is turned a further
 * half-turn so it reads the right way round. With no match — this page opened from anywhere
 * but the grid, or a grid that no longer holds the tile — the card stays seated, poster up,
 * and only fades with the page.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun FlipCard(
    seat: CardFlipSeat?,
    seatCorner: Dp,
    modifier: Modifier = Modifier,
    back: @Composable () -> Unit,
) {
    if (seat == null) {
        Box(
            modifier.graphicsLayer {
                shape = RoundedCornerShape(seatCorner)
                clip = true
            },
        ) {
            Box(Modifier.fillMaxSize().posterHairline { seatCorner.toPx() }) { back() }
        }
        return
    }
    val state = with(seat.sharedScope) { rememberSharedContentState(seat.key) }
    val tracks = seat.tracks
    val matched = { state.isMatchFound }
    val turn = { if (matched()) tracks.flip.value else 1f }
    val corner: Density.() -> Float = {
        val move = if (matched()) tracks.move.value else 1f
        lerp(FlickCorners.tile.toPx(), seatCorner.toPx(), move.coerceIn(0f, 1f))
    }
    Box(
        modifier
            .cardFlipBounds(seat.sharedScope, seat.animatedScope, state, seat = true)
            .graphicsLayer {
                val inFlight = matched()
                val move = if (inFlight) tracks.move.value else 1f
                val lift = if (inFlight) tracks.lift.value else 0f
                val scale = 1f + CardFlipMotion.LiftScale * lift * (1f - move)
                scaleX = scale
                scaleY = scale
                rotationZ = CardFlipMotion.TiltDegrees * sin(PI.toFloat() * turn())
                alpha = if (inFlight) 1f else tracks.page.value.coerceIn(0f, 1f)
            }
            .graphicsLayer {
                val degrees = 180f * turn()
                rotationY = if (degrees < 90f) degrees else degrees - 180f
                cameraDistance = CardFlipMotion.CameraDistance * density
                shape = RoundedCornerShape(corner())
                clip = true
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = if (180f * turn() < 90f) 1f else 0f },
        ) { seat.front() }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = if (180f * turn() < 90f) 0f else 1f }
                .posterHairline(corner),
        ) { back() }
    }
}

/** The seated poster's 1 dp rim, drawn over its art and following the corner it is given. */
private fun Modifier.posterHairline(corner: Density.() -> Float): Modifier =
    drawWithContent {
        drawContent()
        val stroke = PosterHairlineWidth.toPx()
        val inset = stroke / 2f
        val radius = (corner() - inset).coerceAtLeast(0f)
        drawRoundRect(
            color = PosterHairline,
            topLeft = Offset(inset, inset),
            size = Size(size.width - stroke, size.height - stroke),
            cornerRadius = CornerRadius(radius, radius),
            style = Stroke(stroke),
        )
    }

private val PosterHairlineWidth = 1.dp
