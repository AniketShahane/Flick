package com.flick.receiver.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.tv.material3.Icon
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickDimens
import com.flick.receiver.ui.theme.FlickMotion
import com.flick.receiver.ui.theme.FlickShape
import com.flick.receiver.ui.theme.FlickSpace
import com.flick.receiver.ui.theme.LocalReducedMotion
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The detached ring's stroke (receiver-expressive-spec.md §3). It came down with
 * the rest of the TV re-scale, but not proportionally: focus visibility is how a
 * D-pad user knows where they are, so this is the last dimension to give ground.
 * At density 2.0 this is 4 physical px — about 2.5 mm on a 55" panel, roughly
 * 3 arcmin at 3 m, comfortably above the eye's ~1 arcmin resolving limit.
 */
val FlickFocusRingWidth: Dp = 2.dp

/**
 * How far outside the element bounds the ring sits — it never affects layout.
 *
 * Because the ring is painted rather than laid out, nothing reserves this space:
 * the outermost focusable on a screen must keep [FlickDimens.FocusRingReserve]
 * of clearance inside the overscan safe area or the ring clips. The extent a
 * focused element actually needs beyond its own bounds is
 * `FOCUS_SCALE * (offset + width / 2 + contour) + (FOCUS_SCALE - 1) * side / 2`
 * — 1.06 × (4.5 + 1 + 1) + 0.03 × side, so 10 dp covers any control up to
 * ~103 dp. [FlickFocusRingContourWidth] is in that sum: it widened the ring
 * outward, and a control wide enough to exceed the reserve spends the difference
 * on the overscan margin rather than being cut, since nothing on the path clips.
 */
val FlickFocusRingOffset: Dp = 4.5.dp

/**
 * Where the ring starts its arrival bloom, as a fraction of [FlickFocusRingOffset].
 * It only ever grows *towards* the resting offset, never past it, so the reserve
 * derived above stays the worst case.
 */
private const val RING_BLOOM_FLOOR = 0.6f

/** How much a focused control's own corners round off while it holds focus. */
private val FocusCornerGrowth: Dp = 4.dp

/**
 * How far a beacon will fly between controls. Past this the ring fades out and
 * blooms back in at the destination: a ring crossing most of the panel stops
 * reading as one object moving and starts reading as something thrown.
 */
private val FocusBeaconTravelCap: Dp = 320.dp

/**
 * The dark contour on the ring's outer edge — 1 dp of [FlickColor.FocusRingContour]
 * on each side of the amber stroke.
 *
 * The ring is the only decoration in the system drawn OUTSIDE its control, so on
 * the playback screen part of it lands on the film rather than on chrome, and the
 * film is not ours: amber measures 1.2:1 against the frame under the END SESSION
 * pill. The contour costs one extra stroked outline on the one element that holds
 * focus, and it is invisible on every dark surface — it only appears where it is
 * the whole read.
 */
val FlickFocusRingContourWidth: Dp = 1.dp

/** The hairline a filled affordance carries around its fill. */
val FlickControlBorderWidth: Dp = FlickDimens.Hairline

/**
 * The stroke for an **outline-only** affordance. Double the control hairline:
 * with no fill of its own the border is the whole silhouette.
 *
 * Width was never the compensation for a thinned scrim — the END SESSION pill
 * carried this stroke in `OutlineSoft` (white @ 18 %) over bare film and
 * composited to 0.68 luminance against its own backdrop, invisible at any width.
 * A control that sits where the scrim has thinned takes a plate; see
 * `FlickColor.GlassState`.
 */
val FlickOutlinedChromeBorderWidth: Dp = 2.dp

/**
 * The stroke for a control whose fill is [containerColor]: a transparent fill
 * means the border draws the control on its own and needs
 * [FlickOutlinedChromeBorderWidth]. A caller that gives an outline-only chrome
 * pill a fill must pass that width explicitly to keep it.
 */
fun flickBorderWidth(containerColor: Color?): Dp =
    if (containerColor != null && containerColor.alpha == 0f) {
        FlickOutlinedChromeBorderWidth
    } else {
        FlickControlBorderWidth
    }

/**
 * Grows every corner of a [CornerBasedShape] by [grow], so a detached ring keeps
 * concentric corners with the element it surrounds. Non-corner shapes ring at
 * their own outline.
 */
private data class GrownCornerSize(val base: CornerSize, val grow: Dp) : CornerSize {
    override fun toPx(shapeSize: Size, density: Density): Float =
        base.toPx(shapeSize, density) + with(density) { grow.toPx() }
}

/** Element radius + [offset] (spec §3). Percent corners still resolve as percent. */
fun Shape.grownBy(offset: Dp): Shape =
    if (this is CornerBasedShape) {
        copy(
            topStart = GrownCornerSize(topStart, offset),
            topEnd = GrownCornerSize(topEnd, offset),
            bottomEnd = GrownCornerSize(bottomEnd, offset),
            bottomStart = GrownCornerSize(bottomStart, offset),
        )
    } else {
        this
    }

/**
 * How many frames a surface keeps asking for D-pad focus — see [landTvFocus].
 * Four frames is ~67 ms at 60 Hz: long enough for a placement pass, short enough
 * that a requester which never attaches cannot spin.
 */
private const val FOCUS_ENTRY_FRAMES = 4

/**
 * Puts D-pad focus inside a surface that is composed by the same state change
 * which takes away the surface focus is currently on.
 *
 * A [FocusRequester] can only be honoured once its node is attached AND placed,
 * so a single request issued in the composition that creates the node can arrive
 * a frame early, throw, and leave the remote steering nothing at all. On the
 * playback screen both handoffs are exactly that shape — the side panel arrives
 * as the transport bar leaves, and the bar returns as the panel leaves — so the
 * request is repeated until [held] reports it took.
 *
 * [preferred] is where focus belongs: the primary key, the selected track row.
 * [fallback] must name a control the surface is CERTAIN to have — it is what the
 * last attempt aims at when the preferred one has been refused every frame, and
 * landing somewhere is always better than landing nowhere. Pass the same
 * requester for both when the surface has only one entry point.
 */
suspend fun landTvFocus(
    preferred: FocusRequester,
    fallback: FocusRequester,
    held: () -> Boolean,
) {
    repeat(FOCUS_ENTRY_FRAMES) {
        if (held()) return
        runCatching { preferred.requestFocus() }
        withFrameNanos { }
    }
    if (!held()) runCatching { fallback.requestFocus() }
}

/**
 * The amber focus ring (spec §3) — a **detached** stroke drawn [offset] outside
 * the element bounds. It is painted, never laid out, so focusing an element can
 * never reflow the row it sits in.
 *
 * Place it AFTER any `graphicsLayer` that scales (so the ring scales with the
 * element) and BEFORE the element's own `clip`/`background` (so the element's
 * clip cannot eat it). [ringColor] must be [FlickColor.FocusRingOnSpark] on an
 * amber fill — amber on amber vanishes.
 *
 * [progress] is the arrival bloom: 0 draws nothing, 1 draws the settled ring, and
 * between the two the ring fades up while expanding from [RING_BLOOM_FLOOR] of its
 * offset. It is invoked in the draw phase, so pass a lambda that reads an animated
 * value rather than reading one at the call site.
 */
fun Modifier.flickFocusRing(
    visible: Boolean,
    shape: Shape,
    ringColor: Color = FlickColor.FocusRing,
    offset: Dp = FlickFocusRingOffset,
    width: Dp = FlickFocusRingWidth,
    progress: () -> Float = { 1f },
): Modifier {
    val settledShape = shape.grownBy(offset)
    return this.drawWithContent {
        drawContent()
        if (!visible) return@drawWithContent
        val presence = progress().coerceIn(0f, 1f)
        if (presence <= 0f) return@drawWithContent
        val bloom = RING_BLOOM_FLOOR + (1f - RING_BLOOM_FLOOR) * presence
        val inset = offset.toPx() * bloom
        val ringSize = Size(size.width + inset * 2f, size.height + inset * 2f)
        if (ringSize.width <= 0f || ringSize.height <= 0f) return@drawWithContent
        val ringShape = if (bloom >= 0.999f) settledShape else shape.grownBy(offset * bloom)
        val outline = ringShape.createOutline(ringSize, layoutDirection, this)
        val stroke = width.toPx()
        translate(left = -inset, top = -inset) {
            drawOutline(
                outline = outline,
                color = FlickColor.FocusRingContour,
                alpha = presence,
                style = Stroke(width = stroke + FlickFocusRingContourWidth.toPx() * 2f),
            )
            drawOutline(
                outline = outline,
                color = ringColor,
                alpha = presence,
                style = Stroke(width = stroke),
            )
        }
    }
}

// ── The traveling ring (spec §3, B5) ────────────────────────────────────────

/** What the focused member of a beacon group publishes to its host. */
internal data class FocusBeacon(
    val owner: Any,
    val bounds: Rect,
    val shape: Shape,
    val ringColor: Color,
)

/**
 * The half of a [FocusBeacon] that steers the ring. A member republishes on every
 * corner-radius frame as well as on every move, so the flight is driven off this
 * projection instead: keying it on the whole beacon cancelled the arrival bloom
 * once per frame and left the ring stranded at partial opacity.
 */
private data class FocusBeaconTravel(val owner: Any, val bounds: Rect)

/** One host's shared ring state. Written by beacon nodes, read in the host's draw. */
@Stable
internal class FocusBeaconState {
    var beacon: FocusBeacon? by mutableStateOf(null)
    var origin: Offset by mutableStateOf(Offset.Zero)
}

/**
 * Present only inside a [FocusBeaconHost]. Absent everywhere else, which is what
 * makes the beacon opt-in: a screen that installs no host is untouched.
 */
internal val LocalFocusBeacon = staticCompositionLocalOf<FocusBeaconState?> { null }

/** Whether the calling composable sits inside a [FocusBeaconHost]. */
@Composable
internal fun beaconHosted(): Boolean = LocalFocusBeacon.current != null

/**
 * Installs a single traveling focus ring for the focus group inside [content].
 *
 * One ring exists for the whole group and glides between its members instead of
 * vanishing at the old control and popping in at the new one. Members mark
 * themselves with [Modifier.focusBeacon] and suppress their own
 * [Modifier.flickFocusRing]; a member with no host above it falls straight back
 * to drawing its own.
 *
 * Install one per coherent focus group — the transport row, a side panel, the
 * Settings column — never one for a whole screen: a ring that flies between
 * unrelated regions reads as a bug, not as travel.
 */
@Composable
fun FocusBeaconHost(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = remember { FocusBeaconState() }
    val reducedMotion = LocalReducedMotion.current
    val bounds = remember { Animatable(Rect.Zero, Rect.VectorConverter) }
    val presence = remember { Animatable(0f) }
    // The scheme specs are rebuilt on every recomposition, so they are read
    // through a holder rather than keyed into the effect below, which would
    // restart the ring's flight each time the group recomposed.
    val travelSpec = rememberUpdatedState(FlickMotion.focusSpatial<Rect>())
    val presenceSpec = rememberUpdatedState(FlickMotion.stateEffects<Float>())
    val travelCapPx = with(LocalDensity.current) { FocusBeaconTravelCap.toPx() }
    // Derived so the host recomposes when the ring changes colour — a white ring
    // on the amber play key — and not on every position tick a member reports.
    val tint = remember(state) { derivedStateOf { state.beacon?.ringColor ?: FlickColor.FocusRing } }
    val ringColor = animateColorAsState(
        targetValue = tint.value,
        animationSpec = FlickMotion.stateEffects(),
        label = "focusBeaconTint",
    )

    LaunchedEffect(state, reducedMotion, travelCapPx) {
        var previous: Any? = null
        // Distinguishes an interrupted flight from a settled ring, which is the
        // difference between retargeting the spring and teleporting to the target.
        var traveling = false
        snapshotFlow {
            state.beacon?.let { FocusBeaconTravel(it.owner, it.bounds) }
        }.collectLatest { target ->
            if (target == null) {
                previous = null
                traveling = false
                presence.animateTo(0f, presenceSpec.value)
                return@collectLatest
            }
            val entering = previous == null
            val movedControl = previous !== target.owner
            val tooFar = (bounds.value.center - target.bounds.center).getDistance() > travelCapPx
            previous = target.owner
            // Entering the group, jumping too far to read as one object, or a
            // viewer who asked for no motion: the ring appears where it belongs
            // and blooms, rather than flying in across dead screen. A republish
            // for the SAME control keeps flying only while a flight is still in
            // the air — otherwise it is a scroll moving the row under a settled
            // ring, and tracking it exactly is the point.
            val flies = if (reducedMotion) {
                false
            } else if (movedControl) {
                !entering && !tooFar
            } else {
                traveling
            }
            if (!flies) {
                if (movedControl && presence.value > 0f) presence.animateTo(0f, presenceSpec.value)
                bounds.snapTo(target.bounds)
                if (movedControl) presence.snapTo(0f)
                traveling = false
            }
            coroutineScope {
                if (flies) {
                    traveling = true
                    launch {
                        // Restarted from wherever the ring has actually reached, so
                        // holding the D-pad down a column reads as one continuous
                        // slide instead of one ring per row.
                        bounds.animateTo(target.bounds, travelSpec.value)
                        traveling = false
                    }
                }
                // Re-entered on every republish, so an arrival interrupted by a
                // scroll under the ring resumes instead of stalling part-lit.
                if (presence.value < 1f) presence.animateTo(1f, presenceSpec.value)
            }
        }
    }

    CompositionLocalProvider(LocalFocusBeacon provides state) {
        Box(
            modifier = modifier
                .onGloballyPositioned { state.origin = it.positionInRoot() }
                .drawWithContent {
                    drawContent()
                    val target = state.beacon ?: return@drawWithContent
                    val lit = presence.value.coerceIn(0f, 1f)
                    if (lit <= 0f) return@drawWithContent
                    val local = bounds.value.translate(-state.origin.x, -state.origin.y)
                    if (local.width <= 0f || local.height <= 0f) return@drawWithContent
                    // The member publishes its pre-scale layout rect, so the host
                    // adds back the focus lift the member is drawing itself with.
                    val lift = if (reducedMotion) 1f else FlickMotion.FOCUS_SCALE
                    val bloom = RING_BLOOM_FLOOR + (1f - RING_BLOOM_FLOOR) * lit
                    val ringOffset = FlickFocusRingOffset.toPx()
                    val dx = (ringOffset + (lift - 1f) * 0.5f * local.width) * bloom
                    val dy = (ringOffset + (lift - 1f) * 0.5f * local.height) * bloom
                    val ringSize = Size(local.width + dx * 2f, local.height + dy * 2f)
                    val outline = target.shape
                        .grownBy(((dx + dy) * 0.5f).toDp())
                        .createOutline(ringSize, layoutDirection, this)
                    val stroke = FlickFocusRingWidth.toPx()
                    translate(left = local.left - dx, top = local.top - dy) {
                        drawOutline(
                            outline = outline,
                            color = FlickColor.FocusRingContour,
                            alpha = lit,
                            style = Stroke(width = stroke + FlickFocusRingContourWidth.toPx() * 2f),
                        )
                        drawOutline(
                            outline = outline,
                            color = ringColor.value,
                            alpha = lit,
                            style = Stroke(width = stroke),
                        )
                    }
                },
        ) { content() }
    }
}

/** Marks a focusable as a beacon target. No-op when no host is present above it. */
fun Modifier.focusBeacon(shape: Shape): Modifier = this.focusBeacon(shape, FlickColor.FocusRing)

/**
 * [focusBeacon] for a control whose ring is not the default amber — the play key
 * fills with `Spark`, and amber on amber vanishes.
 */
fun Modifier.focusBeacon(shape: Shape, ringColor: Color): Modifier =
    this.then(FocusBeaconElement(shape, ringColor))

private data class FocusBeaconElement(
    val shape: Shape,
    val ringColor: Color,
) : ModifierNodeElement<FocusBeaconNode>() {
    override fun create(): FocusBeaconNode = FocusBeaconNode(shape, ringColor)
    override fun update(node: FocusBeaconNode) = node.update(shape, ringColor)
}

private class FocusBeaconNode(
    private var shape: Shape,
    private var ringColor: Color,
) : Modifier.Node(),
    CompositionLocalConsumerModifierNode,
    GlobalPositionAwareModifierNode,
    FocusEventModifierNode {

    private var host: FocusBeaconState? = null
    private var bounds: Rect? = null
    private var focused = false

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (!isAttached) return
        host = currentValueOf(LocalFocusBeacon) ?: return
        // The focus lift is a centred `graphicsLayer` scale, so the CENTRE of these
        // coordinates is invariant under it while `size` is always the layout size.
        // Deriving the rect from those two is what makes the published bounds the
        // pre-scale ones regardless of where this sits in the modifier chain.
        val size = coordinates.size.toSize()
        val center = coordinates.localToRoot(Offset(size.width / 2f, size.height / 2f))
        bounds = Rect(
            left = center.x - size.width / 2f,
            top = center.y - size.height / 2f,
            right = center.x + size.width / 2f,
            bottom = center.y + size.height / 2f,
        )
        if (focused) publish()
    }

    override fun onFocusEvent(focusState: FocusState) {
        if (focusState.isFocused == focused) return
        focused = focusState.isFocused
        if (focused) publish() else release()
    }

    override fun onDetach() {
        if (!focused) return
        focused = false
        release()
    }

    fun update(shape: Shape, ringColor: Color) {
        this.shape = shape
        this.ringColor = ringColor
        if (focused) publish()
    }

    private fun publish() {
        val rect = bounds ?: return
        host?.beacon = FocusBeacon(this, rect, shape, ringColor)
    }

    private fun release() {
        val owner = host ?: return
        if (owner.beacon?.owner === this) owner.beacon = null
    }
}

/**
 * The one TV focus primitive (spec §3). There is no hover on TV, so this is the
 * whole vocabulary:
 *  - FOCUSED  = detached amber ring + scale [FlickMotion.FOCUS_SCALE] + corners
 *    eased out by [FocusCornerGrowth]. The fill does not move, so focus and
 *    selection stay separable.
 *  - SELECTED = [FlickColor.SelectedFill] + [FlickColor.SelectedBorder], no ring.
 *  - UNFOCUSED = [FlickColor.ControlFill] + [FlickColor.Outline].
 *  - DISABLED = 38 % opacity.
 *
 * D-pad center fires [onClick] (foundation `clickable` maps DPAD_CENTER/ENTER to
 * click for a focused element). Every screen requests focus on exactly one of
 * these at entry so a focus target is always present.
 *
 * [containerColor] / [borderColor] override the state fills for the inverted
 * amber affordances (the open subtitles / metrics cards, the selected size cell);
 * pass [FlickColor.FocusRingOnSpark] as [ringColor] whenever you do.
 *
 * [borderWidth] defaults per [flickBorderWidth]: the control hairline when there
 * is a fill, the doubled stroke when the border is the whole control.
 */
@Composable
fun FlickTvButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    /** Toggle semantics; deliberately independent from the visual selected fill. */
    checked: Boolean? = null,
    stateDescription: String? = null,
    enabled: Boolean = true,
    contentDescription: String? = null,
    focusRequester: FocusRequester? = null,
    shape: Shape = FlickShape.Pill,
    ringColor: Color = FlickColor.FocusRing,
    containerColor: Color? = null,
    borderColor: Color? = null,
    borderWidth: Dp = flickBorderWidth(containerColor),
    contentPadding: PaddingValues = FlickDimens.ControlPadding,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(FlickSpace.Sm),
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val reducedMotion = LocalReducedMotion.current
    val hosted = beaconHosted()
    val ringVisible = focused && enabled
    // Held as State and read inside the layer / draw lambdas below: a lift or a
    // bloom may repaint this control, but it may not recompose it once a frame
    // while a decoder is running underneath the chrome.
    val scale = animateFloatAsState(
        targetValue = when {
            reducedMotion -> 1f
            pressed && ringVisible -> 1.02f
            pressed -> FlickMotion.PRESS_SCALE
            ringVisible -> FlickMotion.FOCUS_SCALE
            else -> 1f
        },
        animationSpec = if (reducedMotion) snap() else FlickMotion.focusSpatial(),
        label = "buttonFeedbackScale",
    )
    val ringPresence = animateFloatAsState(
        targetValue = if (ringVisible) 1f else 0f,
        animationSpec = if (reducedMotion) snap() else FlickMotion.stateEffects(),
        label = "buttonRingPresence",
    )
    // Corners open up while focused. Kept a CornerBasedShape all the way through
    // so `grownBy` still finds corners to make the ring concentric with.
    val cornerGrowth by animateDpAsState(
        targetValue = if (ringVisible && !reducedMotion) FocusCornerGrowth else 0.dp,
        animationSpec = if (reducedMotion) snap() else FlickMotion.focusSpatial(),
        label = "buttonFocusCorner",
    )
    val focusShape = if (cornerGrowth == 0.dp) shape else shape.grownBy(cornerGrowth)

    val baseFill = containerColor ?: if (selected) FlickColor.SelectedFill else FlickColor.ControlFill
    val baseStroke = borderColor ?: if (selected) FlickColor.SelectedBorder else FlickColor.Outline
    // Selection persists as a fill/border state; press is a brief physical
    // acknowledgement. Neither changes layout, so the detached focus ring stays
    // inside the caller's overscan reserve.
    val fill by animateColorAsState(
        targetValue = if (pressed && !selected && containerColor == null) FlickColor.ControlFillStrong else baseFill,
        animationSpec = if (reducedMotion) snap() else FlickMotion.stateEffects(),
        label = "buttonFill",
    )
    val stroke by animateColorAsState(
        targetValue = baseStroke,
        animationSpec = if (reducedMotion) snap() else FlickMotion.stateEffects(),
        label = "buttonSelectionStroke",
    )

    Row(
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .semantics(mergeDescendants = true) {
                this.role = if (checked != null) Role.Switch else Role.Button
                if (checked != null) {
                    this.toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
                    if (stateDescription != null) this.stateDescription = stateDescription
                } else {
                    this.selected = selected
                }
                if (!enabled) disabled()
                if (contentDescription != null) this.contentDescription = contentDescription
            }
            .focusBeacon(focusShape, ringColor)
            .graphicsLayer {
                val lift = scale.value
                scaleX = lift
                scaleY = lift
                alpha = if (enabled) 1f else 0.38f
            }
            .flickFocusRing(
                visible = ringVisible && !hosted,
                shape = focusShape,
                ringColor = ringColor,
                progress = { ringPresence.value },
            )
            .clip(focusShape)
            .background(fill)
            .border(borderWidth, stroke, focusShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = if (checked != null) Role.Switch else Role.Button,
                onClick = onClick,
            )
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = horizontalArrangement,
        content = content,
    )
}

/**
 * A square glyph-only affordance — the panel close buttons (spec §5.4/§5.5).
 * Small by design (D-pad, not touch), but it carries the full focus ring so it
 * is never lost on screen.
 *
 * [glyphSize] gave up proportionally less than [side] in the TV re-scale: the
 * icon set strokes at 1.8 units on a 24-unit grid, so the glyph's stroke is
 * `glyphSize * 0.075` — at 12 dp that is 0.9 dp, already the thinnest line that
 * still resolves at 3 m. The box shrank around it instead.
 */
@Composable
fun FlickTvIconButton(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    side: Dp = 19.dp,
    glyphSize: Dp = 12.dp,
    shape: Shape = FlickShape.Sm,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    tint: Color = FlickColor.OnChrome,
    containerColor: Color = FlickColor.ChromeButtonFill,
    borderColor: Color = Color.Transparent,
    borderWidth: Dp = flickBorderWidth(containerColor),
    ringColor: Color = FlickColor.FocusRing,
) {
    FlickTvButton(
        onClick = onClick,
        modifier = modifier.size(side),
        enabled = enabled,
        contentDescription = contentDescription,
        focusRequester = focusRequester,
        shape = shape,
        ringColor = ringColor,
        containerColor = containerColor,
        borderColor = borderColor,
        borderWidth = borderWidth,
        contentPadding = PaddingValues(0.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(glyphSize),
        )
    }
}

/** A focusable settings/list row — full-width, left-aligned, same focus rules. */
@Composable
fun FlickTvRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    selected: Boolean = false,
    checked: Boolean? = null,
    stateDescription: String? = null,
    enabled: Boolean = true,
    contentDescription: String? = null,
    shape: Shape = FlickShape.Md,
    ringColor: Color = FlickColor.FocusRing,
    containerColor: Color? = null,
    borderColor: Color? = null,
    borderWidth: Dp = flickBorderWidth(containerColor),
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 13.dp),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(FlickSpace.Md),
    content: @Composable RowScope.() -> Unit,
) {
    FlickTvButton(
        onClick = onClick,
        modifier = modifier,
        selected = selected,
        checked = checked,
        stateDescription = stateDescription,
        enabled = enabled,
        contentDescription = contentDescription,
        focusRequester = focusRequester,
        shape = shape,
        ringColor = ringColor,
        containerColor = containerColor,
        borderColor = borderColor,
        borderWidth = borderWidth,
        contentPadding = contentPadding,
        horizontalArrangement = horizontalArrangement,
        content = content,
    )
}
