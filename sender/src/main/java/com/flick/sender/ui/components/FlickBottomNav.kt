package com.flick.sender.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.flick.sender.R
import com.flick.sender.ui.screens.NavTab
import com.flick.sender.ui.theme.FlickIcons
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.Motion
import com.flick.sender.ui.theme.PillShape
import com.flick.sender.ui.theme.flickGlass
import com.flick.sender.ui.theme.pressScale
import com.flick.sender.ui.theme.rememberPressAmount
import com.flick.sender.ui.theme.rememberReduceMotion
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * The floating glass navigation pill (design §5.4). It is drawn over the route rather
 * than inset into it, so each screen it appears on reserves its own bottom padding.
 * The caller owns the 16 dp margins and the window insets.
 *
 * Selection is ONE fill that travels between the seats rather than one fill per seat
 * cross-fading: the tab left behind and the tab arrived at are the same object moving,
 * which is the claim the route transition makes at the same moment.
 *
 * The travelling fill is seat-count agnostic — it is placed against measured bounds — so
 * a seat is added by adding a [NavItem] and nothing else. Three 76 dp seats plus the
 * row's 12 dp margins are 252 dp, which still leaves free space to distribute inside the
 * 328 dp the bar spans on the narrowest phone the app supports.
 */
@Composable
internal fun FlickBottomNav(
    selected: NavTab,
    onSelect: (NavTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFlickColors.current
    val density = LocalDensity.current
    val metrics = LocalNavMetrics.current
    val reduceMotion = rememberReduceMotion()
    // The FAST spatial spring, not the default one the rest of the shell travels on
    // (0.6/800 against 0.8/380). What a tap reads is the first 100 ms, and the default
    // spring is lazy exactly there: 28 % of the way at 50 ms and 66 % at 100 ms, against
    // 53 % and home. Both are quiet by ~200 ms, so all that is traded is the fast spec's
    // ~9 % ring past the seat at ~140 ms — the Expressive selection idiom, and small
    // enough that it does not break the ordering the icon's tint depends on below: on its
    // slow effects spring the ink is 77 % resolved at 100 ms, so it still lands after the
    // fill has arrived under it.
    val travel = MaterialTheme.motionScheme.fastSpatialSpec<Rect>()
    val indicator = remember { Animatable(Rect.Zero, Rect.VectorConverter) }
    val scope = rememberCoroutineScope()
    // Where the fill was last SENT, which is not where it is: written from the tap and
    // from the effect below, read from neither composition nor layout, so keeping it
    // costs no invalidation of either.
    val commanded = remember { mutableStateOf<Rect?>(null) }
    // The seat the shell actually resolved, readable from a coroutine that outlives the
    // composition that launched it.
    val settled = rememberUpdatedState(selected)

    // Seats are measured, not composed: a plain map plus an epoch keeps a layout pass
    // from writing snapshot state the same layout pass reads, and the epoch only moves
    // when a seat genuinely changes (first placement, rotation, font scale).
    val seats = remember { mutableMapOf<NavTab, Rect>() }
    var seatEpoch by remember { mutableIntStateOf(0) }
    // Read only from a positioning callback, never from composition, so publishing the
    // bar's own coordinates costs nothing.
    val host = remember { mutableStateOf<LayoutCoordinates?>(null) }
    val reportSeat: (NavTab, Rect) -> Unit = { tab, rect ->
        if (seats[tab] != rect) {
            seats[tab] = rect
            seatEpoch++
        }
    }

    // The route is the authority on where the fill belongs; it is no longer the thing that
    // starts it moving. This effect is re-launched from applyChanges, INSIDE the frame
    // callback, and a launch dispatches — so its body ran after that frame's traversal and
    // the first withFrameNanos only latched a start time on the frame after. Two frames of
    // zero movement, and they were the two dearest frames of the interaction: the same
    // route flip composes, measures and draws the arriving screen while the outgoing one is
    // still laid out for its fade.
    LaunchedEffect(selected, seatEpoch, reduceMotion) {
        val destination = seats[selected] ?: return@LaunchedEffect
        // The tap below already sent the fill here, a frame ahead of the route flip that
        // re-launched this effect; re-issuing it would restart the spring mid-flight. The
        // comparison is the Rect and not the tab on purpose — a rotation or a font-scale
        // change moves the seat out from under a fill already sitting on it, and that
        // still has to re-place.
        if (commanded.value == destination || indicator.value == destination) {
            return@LaunchedEffect
        }
        commanded.value = destination
        // Rect.Zero is "nothing measured yet", so the first placement lands instead of
        // flying in from the corner of the bar — which is also the only guard the two
        // seats have on the frame the bar is first measured.
        if (reduceMotion || indicator.value == Rect.Zero) {
            indicator.snapTo(destination)
        } else {
            indicator.animateTo(destination, travel)
        }
    }

    // The tap, which is a whole frame earlier than the route it causes.
    // rememberCoroutineScope dispatches onto the composition's own frame clock, and that
    // clock drains its trampoline BEFORE its frame callbacks in the same Choreographer
    // pass — so a launch from a click handler, which runs in the input phase, reaches
    // animateTo while the tapped frame's own time is still the frame time. The first drawn
    // frame after the tap is already a moved one, and because the spring runs on the wall
    // clock, whatever that expensive frame costs is absorbed by the travel instead of
    // being added in front of it.
    val onTap: (NavTab) -> Unit = { tab ->
        onSelect(tab)
        // The same rule the shell gates on: while the bar is shown, its seat IS the
        // route's tab, so a re-tap moves nothing here either. Under reduce-motion there is
        // no travel to get ahead of and the effect above still snaps.
        val seat = if (reduceMotion || tab == selected) null else seats[tab]
        if (seat != null && indicator.value != Rect.Zero) {
            // Set here and not inside the coroutine: the effect above reads it during the
            // applyChanges of this same frame, and a dispatch is not ordered against
            // composition.
            commanded.value = seat
            scope.launch {
                indicator.animateTo(seat, travel)
                // The shell decides where the app actually went; this only got the fill
                // moving sooner. A navigation it refuses leaves the pill on a seat the app
                // is not on and re-keys nothing, so the resolved seat is read back once the
                // travel is over — by which time the route has long since settled. A tap
                // that supersedes this one cancels this coroutine at the animateTo above,
                // so a stale correction can never overtake a live one.
                val resolved = seats[settled.value]
                if (resolved != null && resolved != commanded.value) {
                    commanded.value = resolved
                    indicator.animateTo(resolved, travel)
                }
            }
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .flickGlass(colors)
            .onGloballyPositioned { host.value = it }
            // The routes this floats over reserve their own room for it, and how much is
            // not a constant — see [LocalNavMetrics].
            .onSizeChanged { metrics.height = with(density) { it.height.toDp() } },
    ) {
        // Measured and placed in the layout phase straight off the Animatable, so a
        // travelling fill never recomposes the tab under the finger.
        Box(
            Modifier
                .layout { measurable, _ ->
                    val bounds = indicator.value
                    val width = bounds.width.roundToInt().coerceAtLeast(0)
                    val height = bounds.height.roundToInt().coerceAtLeast(0)
                    val placeable = measurable.measure(Constraints.fixed(width, height))
                    layout(width, height) {
                        placeable.place(bounds.left.roundToInt(), bounds.top.roundToInt())
                    }
                }
                .background(colors.primary, PillShape),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavItem(
                icon = FlickIcons.GridView,
                label = stringResource(R.string.nav_library),
                active = selected == NavTab.LIBRARY,
                host = host,
                onSeat = { reportSeat(NavTab.LIBRARY, it) },
                onClick = { onTap(NavTab.LIBRARY) },
            )
            NavItem(
                icon = FlickIcons.Cast,
                label = stringResource(R.string.nav_devices),
                active = selected == NavTab.DEVICES,
                host = host,
                onSeat = { reportSeat(NavTab.DEVICES, it) },
                onClick = { onTap(NavTab.DEVICES) },
            )
            NavItem(
                icon = FlickIcons.Tune,
                label = stringResource(R.string.nav_settings),
                active = selected == NavTab.SETTINGS,
                host = host,
                onSeat = { reportSeat(NavTab.SETTINGS, it) },
                onClick = { onTap(NavTab.SETTINGS) },
            )
        }
    }
}

@Composable
private fun NavItem(
    icon: ImageVector,
    label: String,
    active: Boolean,
    host: State<LayoutCoordinates?>,
    onSeat: (Rect) -> Unit,
    onClick: () -> Unit,
) {
    val colors = LocalFlickColors.current
    val reduceMotion = rememberReduceMotion()
    val motionScheme = MaterialTheme.motionScheme
    // Both tints stay State and are spent in the draw phase, exactly as the library's
    // filter chips do it. Read at composition scope they recomposed the seat on every frame
    // of the travel — and the label's went further than that: a tint inside a TextStyle is
    // a new layout input, so each frame re-measured the text as well as repainting it.
    //
    // The icon rides the travelling fill, so its ink must not resolve before the fill has
    // arrived under it — a slower effects spec, never a spatial one.
    val iconTint = animateColorAsState(
        targetValue = if (active) colors.onPrimary else colors.onSurfaceDim,
        animationSpec = Motion.orSnap(reduceMotion, motionScheme.slowEffectsSpec<Color>()),
        label = "navIcon",
    )
    val labelTint = animateColorAsState(
        targetValue = if (active) colors.onSurface else colors.onSurfaceDim,
        animationSpec = Motion.orSnap(reduceMotion, motionScheme.defaultEffectsSpec<Color>()),
        label = "navLabel",
    )
    val labelInk = remember(labelTint) { ColorProducer { labelTint.value } }

    // No haptic here: the shell decides whether a tap moved at all — a re-tap of the
    // seat already carrying the fill is silent. FlickApp fires the pulse from onSelect.
    val interaction = remember { MutableInteractionSource() }
    // Material's ripple dilutes whatever ink it is handed, and on this palette
    // onSurface is near-white — which lands on the glass as a grey blob. The seat
    // answers a touch by pressing in under it instead, washed in the same brand tint
    // the selection itself travels in. Never a neutral one, and never on the seat that
    // already carries the fill: there the travelling pill is the wash.
    //
    // An effects spring, not the spatial one the scale takes: this is opacity and must
    // not ring past either end of its range.
    val wash = rememberPressAmount(interaction, motionScheme.fastEffectsSpec())
    val washColor = colors.primary

    Column(
        modifier = Modifier
            .width(76.dp)
            .heightIn(min = 48.dp)
            .pressScale(interaction, target = NavPressScale)
            .drawBehind {
                // Read in the draw scope, so a press repaints one seat rather than
                // recomposing the bar it sits in.
                val alpha = if (active) 0f else wash.value * NavPressWashAlpha
                if (alpha > 0f) {
                    drawRoundRect(
                        color = washColor,
                        topLeft = Offset.Zero,
                        size = size,
                        cornerRadius = CornerRadius(NavPressWashRadius.toPx()),
                        alpha = alpha,
                    )
                }
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) { selected = active },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                // Reported in the bar's own space: the fill is drawn once, above, and
                // has to be placed against a seat rather than inside it.
                .onGloballyPositioned { coordinates ->
                    host.value?.let { onSeat(it.localBoundingBoxOf(coordinates, clipBounds = false)) }
                }
                .padding(horizontal = 22.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Painted rather than composed as an Icon: the tint is a draw-scope colour
            // filter here, and `Icon` can only take one resolved in composition. The label
            // carries the name, so this glyph announces nothing a reader would hear twice.
            val painter = rememberVectorPainter(icon)
            Box(
                Modifier.size(24.dp).drawBehind {
                    with(painter) { draw(size, colorFilter = ColorFilter.tint(iconTint.value)) }
                },
            )
        }
        Spacer(Modifier.height(6.dp))
        // The ink is handed over as a producer rather than as a style, so the tint animation
        // resolves at draw time and never recomposes the label.
        //
        // One line always: the seat is a fixed 76 dp and the three of them are a lockup, so
        // at an accessibility font scale the label gives up its tail rather than its row.
        BasicText(
            text = label,
            style = FlickText.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = labelInk,
        )
    }
}

// Press response for one seat. The dip is deeper than the row scale because the seat
// is small and carries no fill of its own to deform; the wash sits just above the
// glass sheen it has to read against.
private const val NavPressScale = 0.92f
private const val NavPressWashAlpha = 0.16f
private val NavPressWashRadius = 20.dp

/**
 * How tall the nav actually came out. The bar is drawn OVER the routes rather than inset
 * into them, so each route reserves its own room at the foot of its scroll — and the height
 * it has to reserve is a measurement, not a literal: the label's line box scales with the
 * user's font setting, which at the accessibility end adds enough to hide the last row
 * under the pill.
 *
 * Written from a layout callback and read only where a route computes its bottom padding.
 * A bar whose height never changes therefore costs one write and no recomposition at all.
 */
@Stable
internal class NavMetrics {
    var height: Dp by mutableStateOf(NavNominalHeight)
}

/** Provided by the shell, which composes both the bar and the routes it floats over. */
internal val LocalNavMetrics = staticCompositionLocalOf { NavMetrics() }

/**
 * Room a route has to leave under its content: the bar's own height, the margin the shell
 * holds it off the window edge with, and the gap the design leaves between the last row and
 * the pill. While a cast is live the dock rides above the bar on the same stack, so its
 * clearance is part of the same reservation.
 */
internal fun navBottomClearance(barHeight: Dp, dockLive: Boolean): Dp =
    barHeight + NavShellMargin + NavContentGap + if (dockLive) NowPlayingDockClearance else 0.dp

/** The shell's own margin around the floating bottom stack (design §5.4). */
internal val NavShellMargin = 16.dp

/** Breathing room between the last row of a route and the pill floating over it. */
private val NavContentGap = 22.dp

/**
 * The bar before it has been measured — icon, its 6 dp padding, the 6 dp spacer, one line
 * box of `labelSmall` and the row's 11 dp, at a 1.0 font scale. Only the first frame of the
 * first route uses it; a real measurement replaces it in the same layout pass.
 */
private val NavNominalHeight = 78.dp
