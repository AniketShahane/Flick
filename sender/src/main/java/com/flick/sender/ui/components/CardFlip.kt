package com.flick.sender.ui.components

import android.util.Log
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.flick.sender.BuildConfig
import com.flick.sender.ui.theme.FlickCorners
import com.flick.sender.ui.theme.PosterHairline
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.delay

/**
 * The library tile ⇄ poster seat transition: the tile lifts, turns over as it travels, and
 * lands in the detail page's poster seat with the poster on its back. Back is the same path
 * on no-bounce springs, and the card is handed back to the tile the moment it lands.
 *
 * One composable draws both faces, on the SEAT's side ([FlipCard]); the tile's own copy is
 * hidden for the flight ([hideWhileFlipping]). The bounds come from one `sharedBounds` per
 * end, remeasured rather than scaled, so each face is cropped to the card's current shape on
 * every frame and nothing stretches. Everything else is a transform or an opacity.
 */
internal fun cardFlipKey(itemId: Long): String = "card-flip-$itemId"

/**
 * One track: a spring, held for [delayMs] when its leg starts from rest. The spring is a plain
 * [SpringSpec] so a transition that is turned round mid-flight keeps it — anything else is
 * swapped for the transition's default spring on interruption, and the tracks drift apart.
 */
internal class FlipTrack(val delayMs: Int, val dampingRatio: Float, val stiffness: Float) {
    fun springSpec(): SpringSpec<Float> = spring(dampingRatio, stiffness, TrackThreshold)
}

/** Every number in docs/flick-hyperspace-phone.md's card-flip table; change both together. */
internal object CardFlipMotion {
    val LiftOpen = FlipTrack(0, 0.75f, 900f)
    val BadgeOpen = FlipTrack(0, 1f, 2500f)
    // No delay: the bounds carry this spring and a bounds transform cannot hold one.
    val MoveOpen = FlipTrack(0, 0.86f, 260f)
    val FlipOpen = FlipTrack(60, 0.6f, 190f)
    val PageOpen = FlipTrack(60, 1f, 360f)
    val DiscOpen = FlipTrack(260, 1f, 500f)
    val LogoOpen = FlipTrack(290, 1f, 500f)
    val MetaOpen = FlipTrack(320, 1f, 500f)
    val ChipsOpen = FlipTrack(350, 1f, 500f)
    val GlowOpen = FlipTrack(380, 1f, 300f)
    val ChevronsOpen = FlipTrack(460, 1f, 500f)
    val CaptionOpen = FlipTrack(480, 1f, 500f)

    // Back has no delays: every tail here is time the card spends over a tile it has reached.
    val LiftBack = FlipTrack(0, 1f, 1000f)
    /** Only where no seat is flying; with one, the slot follows [BadgeReturnTravel]. */
    val BadgeBack = FlipTrack(0, 1f, 2500f)
    val MoveBack = FlipTrack(0, 1f, 520f)
    // As stiff as the travel, so the turn is home when the card is and the hand-back waits on neither.
    val FlipBack = FlipTrack(0, 1f, 520f)
    val PageBack = FlipTrack(0, 1f, 620f)
    val ChromeBack = FlipTrack(0, 1f, 1400f)
    val GlowBack = FlipTrack(0, 1f, 1100f)
    /**
     * Read by nothing. The stack transition ends when its slowest track does, and ending it stops
     * the shared bounds wherever they are and places both ends at their targets; the bounds start
     * a frame or two after the travel, so with the travel the slowest they were still a pixel or
     * two short and stepped home. Softer than [MoveBack], this keeps the transition running until
     * they arrive on their own spring. Open, it ends well inside the page's own tail.
     */
    val SettleBack = FlipTrack(0, 1f, 360f)

    val OpenTracks = listOf(
        LiftOpen, MoveOpen, FlipOpen, PageOpen, DiscOpen, LogoOpen, MetaOpen, ChipsOpen, GlowOpen,
        ChevronsOpen, CaptionOpen,
    )
    val BackTracks = listOf(LiftBack, MoveBack, FlipBack, PageBack, ChromeBack, GlowBack, SettleBack)

    /** How much bigger the card is while it is lifted and has not yet travelled. */
    const val LiftScale = 0.08f
    /** The roll at the edge-on moment, against the turn. */
    const val TiltDegrees = -6f
    const val CameraDistance = 12f
    /** Where the glow under the seated card starts growing from. */
    const val GlowFromScale = 0.7f
    val RiseDistance = 14.dp
    val CaptionRiseDistance = 18.dp

    /** Travel still left when the slot's ground and shadow start returning; whole at contact. */
    const val BadgeReturnTravel = 0.06f
    /**
     * Share of the travel over which the tile's marks, drawn on the card's front, fade off it on
     * the way out and back onto it on the way home: whole at the slot, gone from 30% of the way.
     */
    const val MarksTravel = 0.3f
    /** Share of the travel home by which the light under the seat is gone; it cannot follow the card. */
    const val GlowGoneTravel = 0.15f
    /** Travel left over which a card coming home has its turn squared off, so it is flat at contact. */
    const val TurnSettleTravel = 0.15f
    /** Travel and turn left at which the card counts as down: a pixel or two, and half a degree. */
    const val LandedWithin = 0.003f
    /** Travel left when the shell lets its bottom stack rise, so the rise overlaps the settle. */
    const val ChromeRiseTravel = 0.12f
}

/** Fine enough that the half-turn lands without a visible last-frame snap (0.18°). */
private const val TrackThreshold = 0.001f

/** A pose this close to 0 or 1 on every track is a leg that has finished. */
private const val RestWithin = 0.005f

/**
 * The card's scale. Open, the lift is all there before the card travels and goes as it does.
 * Back, the same lift is also scaled by the travel still left, so it is exactly zero at
 * contact whatever the lift's own spring is doing: the card meets the tile at the tile's size.
 */
internal fun flipLiftScale(lift: Float, move: Float, backLeg: Boolean): Float {
    val m = move.coerceIn(0f, 1f)
    val reach = if (backLeg) (1f - m) * m else 1f - m
    return 1f + CardFlipMotion.LiftScale * lift * reach
}

/**
 * How opaque the tile's marks are on the card's front at travel [move]. One function of the
 * travel for both legs, so a flight turned round mid-way never jumps.
 */
internal fun flipMarksAlpha(move: Float): Float = 1f - smoothstep(0f, CardFlipMotion.MarksTravel, move)

/**
 * The glow and shadow under the seat. They stay on the page while the card leaves it, so they
 * are also bound to the travel and are gone before the card is clear of the seat. Both legs:
 * open, the glow's delay outlasts the travel and the gate is already open; a flight turned
 * round either way keeps one continuous value.
 */
internal fun flipGlowPresence(glow: Float, move: Float): Float =
    glow.coerceIn(0f, 1f) * smoothstep(1f - CardFlipMotion.GlowGoneTravel, 1f, move)

/**
 * The turn as drawn. Back, it is also scaled down over the last of the travel, so a reversed
 * flight whose turn lags its travel still meets the tile flat; on a fresh leg the two springs
 * match and this only eases the last few degrees.
 */
internal fun flipTurnPose(turn: Float, move: Float, backLeg: Boolean): Float =
    if (backLeg) turn * smoothstep(0f, CardFlipMotion.TurnSettleTravel, move) else turn

/** How much of the grid slot is still gone while the card comes back with [move] left to go. */
internal fun flipBadgeHidden(move: Float): Float =
    smoothstep(0f, CardFlipMotion.BadgeReturnTravel, move)

/** Whether a card coming back has touched down. */
internal fun flipLanded(move: Float, flip: Float): Boolean =
    abs(move) <= CardFlipMotion.LandedWithin && abs(flip) <= CardFlipMotion.LandedWithin

/**
 * Whether the tile's face, placed by its end of the bounds at [offset] in its slot with [size],
 * is where it rests: within half a pixel, at the slot's own size. The travel track and the
 * bounds are separate animations, and the bounds start a frame or two later; the card is handed
 * back only once the copy that takes over would draw exactly where the card is.
 */
internal fun flipFaceHome(offset: Offset, size: IntSize, slot: IntSize): Boolean =
    abs(offset.x) <= FaceHomeWithinPx && abs(offset.y) <= FaceHomeWithinPx && size == slot

/**
 * Whether the card the seat draws is the tile face's size. In the overlay the card is drawn at the
 * shared bounds' current position (the tile's end drives it coming home), but at the size the
 * seat's OWN bounds animation measured it to; the two animations run apart, so the tile can be
 * home while the card is still a pixel or two short of it at its bottom and right edges.
 */
internal fun flipSeatFitsFace(seat: IntSize?, face: IntSize?): Boolean = seat != null && seat == face

/**
 * Which tile has taken the card over, after [key]'s tile composes with the card [landed] or not.
 * Composed landed is the only proof its face has left the overlay: that switch is a composition
 * parameter and takes a frame longer than the landing, which the draw phase sees at once.
 */
internal fun flipHandedOff(current: String?, key: String, landed: Boolean): String? = when {
    landed -> key
    current == key -> null
    else -> current
}

private const val FaceHomeWithinPx = 0.5f

/** Whether every track of a pose is at rest at one end — a leg starting here starts fresh. */
internal fun flipPoseAtRest(values: List<Float>): Boolean =
    values.all { abs(it) <= RestWithin } || values.all { abs(it - 1f) <= RestWithin }

/** The distinct, positive delays of [tracks], in the order a fresh leg releases them. */
internal fun flipStaggerSteps(tracks: List<FlipTrack>): List<Int> =
    tracks.map { it.delayMs }.filter { it > 0 }.distinct().sorted()

/**
 * The travel's spring for the seat's end of the bounds when its transition heads for [target].
 * Each end measures and places itself from its OWN bounds animation, on its own end's spring, so
 * both ends must pick the same spring for the same leg, or the card that is drawn (the seat's)
 * lands somewhere the copy that takes it back (the tile's) is not.
 */
internal fun seatBoundsTrack(target: EnterExitState): FlipTrack =
    if (target == EnterExitState.Visible) CardFlipMotion.MoveOpen else CardFlipMotion.MoveBack

/** The same choice from the tile's side, whose page is coming back when it heads for Visible. */
internal fun tileBoundsTrack(target: EnterExitState): FlipTrack =
    if (target == EnterExitState.Visible) CardFlipMotion.MoveBack else CardFlipMotion.MoveOpen

// Read once per shared-transition activation, not per leg: the bounds keep the first spec they
// were given until no shared transition is running. So a Back pressed before the open is idle
// keeps both ends on the open spring; what matters is that both ends made the same choice.
// Data classes, because the shared bounds hold the transform as state and a new instance on
// every recomposition would be a write.
@OptIn(ExperimentalSharedTransitionApi::class)
private data class SeatBounds(val transition: Transition<EnterExitState>) : BoundsTransform {
    override fun createAnimationSpec(initialBounds: Rect, targetBounds: Rect): FiniteAnimationSpec<Rect> =
        seatBoundsTrack(transition.targetState).boundsSpring()
}

@OptIn(ExperimentalSharedTransitionApi::class)
private data class TileBounds(val transition: Transition<EnterExitState>) : BoundsTransform {
    override fun createAnimationSpec(initialBounds: Rect, targetBounds: Rect): FiniteAnimationSpec<Rect> =
        tileBoundsTrack(transition.targetState).boundsSpring()
}

private fun FlipTrack.boundsSpring(): SpringSpec<Rect> = spring(dampingRatio, stiffness, Rect.VisibilityThreshold)

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
    renderInOverlay: Boolean = true,
): Modifier = with(sharedScope) {
    this@cardFlipBounds.sharedBounds(
        sharedContentState = state,
        animatedVisibilityScope = animatedScope,
        enter = EnterTransition.None,
        exit = ExitTransition.None,
        boundsTransform = if (seat) SeatBounds(animatedScope.transition) else TileBounds(animatedScope.transition),
        resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
        renderInOverlayDuringTransition = renderInOverlay,
    )
}

/**
 * One end of the flip, switched off while it rests on [restingIn]. Both ends stay composed while
 * the page is up, and a match left standing between legs keeps the bounds it last animated: Back
 * would start from where the seat was when the card landed, not from where the page has since
 * moved it. Off at rest, each leg matches afresh from the ends' current bounds. The seat's switch
 * is the one that clears a match at the settle: an end that is not the target is not watched then.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Stable
internal class FlipEndConfig(
    private val transition: Transition<EnterExitState>,
    private val restingIn: EnterExitState,
    private val placed: () -> Boolean = { true },
) : SharedTransitionScope.SharedContentConfig {
    // [placed] is false for an end kept composed off screen, whose bounds are the last it was
    // placed at: matching them would fly the card to where it no longer is.
    override val SharedTransitionScope.SharedContentState.isEnabled: Boolean
        get() = (transition.currentState != restingIn || transition.targetState != restingIn) && placed()

    override val shouldKeepEnabledForOngoingAnimation: Boolean
        get() = false
}

/**
 * Hides the tile's own copy while the seat is drawing the card in its place, and shows it
 * again the moment the card is handed back — not when the route transition ends, which is as
 * long as the slowest of the page's tracks and would hold a badge-less copy over the tile for it.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
internal fun Modifier.hideWhileFlipping(
    sharedScope: SharedTransitionScope,
    state: SharedTransitionScope.SharedContentState,
    handedBack: () -> Boolean,
): Modifier = graphicsLayer {
    alpha = if (state.isMatchFound && sharedScope.isTransitionActive && !handedBack()) 0f else 1f
}

/**
 * What the two ends of a flip share outside the shared-element machinery: the seat in flight,
 * so the grid can tell when its card is down and the shell when to bring its chrome back, and
 * the size the tapped tile's face was drawn at, so the seat can ask Coil for the very bitmap
 * the tile is showing, and the tile's marks, so the card can carry them.
 */
@Stable
internal class CardFlipHandoff {
    private var flight by mutableStateOf<SeatFlight?>(null)
    private var tileKey: String? = null
    private var tileFace: IntSize = IntSize.Zero
    private var tileMarks by mutableStateOf<TileMarks?>(null)
    private var homeFace by mutableStateOf<String?>(null)
    private var handedOff by mutableStateOf<String?>(null)
    // Plain: written at placement, and only the verdict they make is read anywhere.
    private var faceAtRest = false
    private var faceSize: IntSize? = null
    private var seatSize: IntSize? = null

    /** The seat flying [key]'s card, if one is registered. Snapshot state: read it in a layer. */
    fun flightFor(key: String): SeatFlight? = flight?.takeIf { it.key == key }

    /** Whichever seat is registered. */
    fun current(): SeatFlight? = flight

    fun register(seat: SeatFlight) {
        flight = seat
        forgetPlacements()
    }

    fun unregister(seat: SeatFlight) {
        if (flight === seat) {
            flight = null
            forgetPlacements()
        }
    }

    private fun forgetPlacements() {
        faceAtRest = false
        faceSize = null
        seatSize = null
        homeFace = null
        handedOff = null
    }

    /** Written at the tap, before the route changes, so the page's first composition has it. */
    fun armTile(key: String, facePx: IntSize, marks: TileMarks) {
        tileKey = key
        tileFace = facePx
        tileMarks = marks
    }

    /** Keeps the armed tile's marks current, so the card lands with exactly what the tile shows. */
    fun refreshMarks(key: String, marks: TileMarks) {
        if (tileKey == key) tileMarks = marks
    }

    /** The armed tile's marks, for the card's front. Snapshot state. */
    fun marksFor(key: String): TileMarks? = tileMarks?.takeIf { tileKey == key }

    /**
     * Written from the flying card's tile at every placement of its face: whether it is at rest
     * in its slot ([flipFaceHome]), and at what size.
     */
    fun reportFace(key: String, home: Boolean, size: IntSize) {
        if (flight?.key != key) return
        faceAtRest = home
        faceSize = size
        settle(key)
    }

    /** Written from the seat at every placement of the card it draws, coming home. */
    fun reportSeat(key: String, size: IntSize) {
        if (flight?.key != key) return
        seatSize = size
        settle(key)
    }

    // Whichever end is placed last in a frame decides; an end that has stopped moving is not
    // placed again, and its last report still stands.
    private fun settle(key: String) {
        homeFace = if (faceAtRest && flipSeatFitsFace(seatSize, faceSize)) key else null
    }

    /**
     * Whether [key]'s tile face is at rest in its slot and the seat's card is drawn at its very
     * size, so the copy that takes over shows the pixels the card did. Snapshot state.
     */
    fun faceHome(key: String): Boolean = homeFace == key

    /** Written from [key]'s tile after each composition, with the landing that composition read. */
    fun reportComposed(key: String, landed: Boolean) {
        if (flight?.key != key) return
        handedOff = flipHandedOff(handedOff, key, landed)
    }

    /**
     * Whether [key]'s card is down AND its tile has composed out of the overlay since, so the
     * seat may stop drawing it. The landing alone is a frame early: on it the tile's face is
     * still in the overlay, over its own marks. Snapshot state: read it in a layer.
     */
    fun handedBack(key: String): Boolean =
        handedOff == key && flightFor(key)?.landed == true

    fun tileFaceFor(key: String): IntSize? =
        tileFace.takeIf { tileKey == key && it.width > 0 && it.height > 0 }
}

internal val LocalCardFlipHandoff = staticCompositionLocalOf { CardFlipHandoff() }

/** A seat's flight as the rest of the shell sees it. Every read is a snapshot read. */
@Stable
internal class SeatFlight(
    val key: String,
    private val tracks: CardFlipTracks,
    private val transition: Transition<EnterExitState>,
    private val faceHome: () -> Boolean = { true },
) {
    val backLeg: Boolean get() = transition.targetState != EnterExitState.Visible
    val move: Float get() = tracks.move.value
    val landed: Boolean
        get() = backLeg &&
            flipLanded(tracks.move.value, flipTurnPose(tracks.flip.value, tracks.move.value, true)) &&
            faceHome()
    val nearContact: Boolean get() = backLeg && tracks.move.value <= CardFlipMotion.ChromeRiseTravel
}

/**
 * The page side's tracks. [paired] is the shell's verdict that this transition is the
 * library ⇄ detail pair; on any other route pair every track rests at 1 and the page simply
 * is there, so the route's own transition is the only motion.
 *
 * Filled in by [CardFlipTrackHost], a scope of its own, so the stagger releasing a track
 * recomposes the eleven track declarations and nothing of the page around them.
 */
@Stable
internal class CardFlipTracks {
    lateinit var lift: State<Float>
    lateinit var move: State<Float>
    lateinit var flip: State<Float>
    lateinit var page: State<Float>
    lateinit var disc: State<Float>
    lateinit var logo: State<Float>
    lateinit var meta: State<Float>
    lateinit var chips: State<Float>
    lateinit var glow: State<Float>
    lateinit var chevrons: State<Float>
    lateinit var caption: State<Float>
    internal var all: List<State<Float>> = emptyList()
}

@Composable
internal fun rememberCardFlipTracks(
    transition: Transition<EnterExitState>,
    paired: Boolean,
): CardFlipTracks {
    val tracks = remember(transition) { CardFlipTracks() }
    CardFlipTrackHost(transition, paired, tracks)
    return tracks
}

/**
 * The stagger. A leg that starts from rest releases each track at its delay; until then the
 * track's target is the pose it is already in, so it does not move. A leg that turns the
 * transition round mid-flight releases everything at once, and every track turns from where
 * it is, at the velocity it has, on its own spring.
 */
@Stable
private class FlipGate {
    private var leg by mutableStateOf<EnterExitState?>(null)
    private var releasedMs by mutableIntStateOf(0)

    fun released(target: EnterExitState, delayMs: Int): Boolean =
        delayMs <= 0 || (leg == target && releasedMs >= delayMs)

    suspend fun run(target: EnterExitState, steps: List<Int>) {
        leg = target
        releasedMs = 0
        var elapsed = 0
        for (step in steps) {
            delay((step - elapsed).toLong())
            elapsed = step
            releasedMs = step
        }
    }
}

@Composable
private fun CardFlipTrackHost(
    transition: Transition<EnterExitState>,
    paired: Boolean,
    tracks: CardFlipTracks,
) {
    val target = transition.targetState
    // Read without subscribing: the pose only decides, once per leg, whether it starts fresh.
    val fresh = remember(target) {
        Snapshot.withoutReadObservation { flipPoseAtRest(tracks.all.map { it.value }) }
    }
    val gate = remember { FlipGate() }
    LaunchedEffect(target, fresh) {
        val steps = when {
            !fresh || !paired -> emptyList()
            target == EnterExitState.Visible -> flipStaggerSteps(CardFlipMotion.OpenTracks)
            else -> flipStaggerSteps(CardFlipMotion.BackTracks)
        }
        flipLog { "leg target=$target fresh=$fresh paired=$paired steps=$steps" }
        gate.run(target, steps)
    }
    val released: (FlipTrack) -> Boolean = { track -> !fresh || gate.released(target, track.delayMs) }
    with(CardFlipMotion) {
        tracks.lift = transition.seatTrack(paired, LiftOpen, LiftBack, released, "flip-lift")
        tracks.move = transition.seatTrack(paired, MoveOpen, MoveBack, released, "flip-move")
        tracks.flip = transition.seatTrack(paired, FlipOpen, FlipBack, released, "flip-turn")
        tracks.page = transition.seatTrack(paired, PageOpen, PageBack, released, "flip-page")
        tracks.disc = transition.seatTrack(paired, DiscOpen, ChromeBack, released, "flip-disc")
        tracks.logo = transition.seatTrack(paired, LogoOpen, ChromeBack, released, "flip-logo")
        tracks.meta = transition.seatTrack(paired, MetaOpen, ChromeBack, released, "flip-meta")
        tracks.chips = transition.seatTrack(paired, ChipsOpen, ChromeBack, released, "flip-chips")
        tracks.glow = transition.seatTrack(paired, GlowOpen, GlowBack, released, "flip-glow")
        tracks.chevrons = transition.seatTrack(paired, ChevronsOpen, ChromeBack, released, "flip-chevrons")
        tracks.caption = transition.seatTrack(paired, CaptionOpen, ChromeBack, released, "flip-caption")
        // Not in [CardFlipTracks.all]: a leg that starts while it is still settling starts fresh.
        transition.seatTrack(paired, SettleBack, SettleBack, released, "flip-settle")
    }
    tracks.all = listOf(
        tracks.lift, tracks.move, tracks.flip, tracks.page, tracks.disc, tracks.logo, tracks.meta,
        tracks.chips, tracks.glow, tracks.chevrons, tracks.caption,
    )
}

private fun restValue(state: EnterExitState, paired: Boolean): Float =
    if (state == EnterExitState.Visible || !paired) 1f else 0f

@Composable
private fun Transition<EnterExitState>.seatTrack(
    paired: Boolean,
    open: FlipTrack,
    back: FlipTrack,
    released: (FlipTrack) -> Boolean,
    label: String,
): State<Float> {
    val current = currentState
    val target = targetState
    val leg = if (target == EnterExitState.Visible) open else back
    return animateFloat(
        transitionSpec = { if (targetState == EnterExitState.Visible) open.springSpec() else back.springSpec() },
        label = label,
    ) { state ->
        if (state == target && !released(leg)) restValue(current, paired) else restValue(state, paired)
    }
}

/**
 * The grid side's one track: how far the tile's slot — its badges, its running time, its
 * shadow — has faded out from under the card that lifted off it. Coming back, a tile whose
 * card is in the air follows that card's travel instead ([flipBadgeHidden]).
 */
@Composable
internal fun rememberTileBadgeTrack(
    transition: Transition<EnterExitState>,
    paired: Boolean,
): State<Float> = transition.animateFloat(
    transitionSpec = {
        if (targetState == EnterExitState.PostExit) {
            CardFlipMotion.BadgeOpen.springSpec()
        } else {
            CardFlipMotion.BadgeBack.springSpec()
        }
    },
    label = "flip-badge",
) { state -> if (state != EnterExitState.Visible && paired) 1f else 0f }

/**
 * Rises [distance] into place and fades in with [progress]: the page's chrome arriving
 * around the card. Read in the layer, so the tracks never recompose what they move.
 *
 * [flat] is content that never draws over itself (a line of type, an icon beside it): its fade
 * can modulate each draw, instead of the platform allocating an offscreen layer for every
 * frame the fade is part-way, which on the page's first frames is a fresh allocation per fade.
 */
internal fun Modifier.fadeUp(progress: () -> Float, distance: Dp, flat: Boolean = false): Modifier =
    graphicsLayer {
        val p = progress()
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * distance.toPx()
        if (flat) compositingStrategy = CompositingStrategy.ModulateAlpha
    }

/**
 * What the seat needs to draw the flight: the shared bounds' scopes and the page's tracks.
 * [marks] are the tile's badges, running time and watched line, drawn on the front face so they
 * travel, scale and turn with it while they fade ([flipMarksAlpha]).
 */
@Stable
internal class CardFlipSeat(
    val sharedScope: SharedTransitionScope,
    val animatedScope: AnimatedVisibilityScope,
    val key: String,
    val tracks: CardFlipTracks,
    val marks: @Composable () -> Unit,
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
 * and only fades with the page. Coming back, it stops drawing once it has landed and the tile
 * has composed out of the overlay: from then on the tile, badges and all, is the card.
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
    val seatTransition = seat.animatedScope.transition
    val config = remember(seatTransition) { FlipEndConfig(seatTransition, EnterExitState.Visible) }
    val state = with(seat.sharedScope) { rememberSharedContentState(seat.key, config) }
    val tracks = seat.tracks
    val handoff = LocalCardFlipHandoff.current
    val flight = remember(seat.key, tracks, seat.animatedScope, handoff) {
        val key = seat.key
        SeatFlight(key, tracks, seat.animatedScope.transition) { handoff.faceHome(key) }
    }
    DisposableEffect(handoff, flight) {
        handoff.register(flight)
        onDispose { handoff.unregister(flight) }
    }
    if (CardFlipTrace) {
        LaunchedEffect(flight) {
            snapshotFlow { flight.landed }.collect { flipLog { "key=${flight.key} landed=$it" } }
        }
    }
    val inFlight = { state.isMatchFound && seat.sharedScope.isTransitionActive }
    val turn = { if (inFlight()) flipTurnPose(tracks.flip.value, tracks.move.value, flight.backLeg) else 1f }
    val corner: Density.() -> Float = {
        val move = if (inFlight()) tracks.move.value else 1f
        lerp(FlickCorners.tile.toPx(), seatCorner.toPx(), move.coerceIn(0f, 1f))
    }
    Box(
        modifier
            .cardFlipBounds(seat.sharedScope, seat.animatedScope, state, seat = true)
            .onPlaced { card ->
                if (!flight.backLeg || handoff.current() !== flight) return@onPlaced
                handoff.reportSeat(flight.key, card.size)
            }
            .graphicsLayer {
                val flying = inFlight()
                val move = if (flying) tracks.move.value else 1f
                val lift = if (flying) tracks.lift.value else 0f
                val scale = flipLiftScale(lift, move, flight.backLeg)
                scaleX = scale
                scaleY = scale
                rotationZ = CardFlipMotion.TiltDegrees * sin(PI.toFloat() * turn())
                alpha = when {
                    flying && handoff.handedBack(flight.key) -> 0f
                    flying -> 1f
                    else -> tracks.page.value.coerceIn(0f, 1f)
                }
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
        ) {
            seat.front()
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = if (inFlight()) flipMarksAlpha(tracks.move.value) else 0f },
            ) { seat.marks() }
        }
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

/** Flip to true for a logcat trace of each leg and each landing; debug builds only. */
private const val CardFlipTrace = false

private inline fun flipLog(message: () -> String) {
    if (CardFlipTrace && BuildConfig.DEBUG) Log.d("FlickFlip", message())
}
