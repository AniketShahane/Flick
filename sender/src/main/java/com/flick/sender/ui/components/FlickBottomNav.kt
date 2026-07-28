package com.flick.sender.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
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
import com.flick.sender.ui.theme.rememberReduceMotion
import kotlin.math.roundToInt

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
    val reduceMotion = rememberReduceMotion()
    val travel = MaterialTheme.motionScheme.defaultSpatialSpec<Rect>()
    val indicator = remember { Animatable(Rect.Zero, Rect.VectorConverter) }

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

    LaunchedEffect(selected, seatEpoch, reduceMotion) {
        val destination = seats[selected] ?: return@LaunchedEffect
        if (indicator.value == destination) return@LaunchedEffect
        // Rect.Zero is "nothing measured yet", so the first placement lands instead of
        // flying in from the corner of the bar — which is also the only guard the two
        // seats have on the frame the bar is first measured.
        if (reduceMotion || indicator.value == Rect.Zero) {
            indicator.snapTo(destination)
        } else {
            indicator.animateTo(destination, travel)
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .flickGlass(colors)
            .onGloballyPositioned { host.value = it },
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
                onClick = { onSelect(NavTab.LIBRARY) },
            )
            NavItem(
                icon = FlickIcons.Cast,
                label = stringResource(R.string.nav_devices),
                active = selected == NavTab.DEVICES,
                host = host,
                onSeat = { reportSeat(NavTab.DEVICES, it) },
                onClick = { onSelect(NavTab.DEVICES) },
            )
            NavItem(
                icon = FlickIcons.Tune,
                label = stringResource(R.string.nav_settings),
                active = selected == NavTab.SETTINGS,
                host = host,
                onSeat = { reportSeat(NavTab.SETTINGS, it) },
                onClick = { onSelect(NavTab.SETTINGS) },
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
    // The icon rides the travelling fill, so its ink must not resolve before the fill
    // has arrived under it — a slower effects spec, never a spatial one.
    val iconTint by animateColorAsState(
        targetValue = if (active) colors.onPrimary else colors.onSurfaceDim,
        animationSpec = Motion.orSnap(reduceMotion, motionScheme.slowEffectsSpec<Color>()),
        label = "navIcon",
    )
    val labelTint by animateColorAsState(
        targetValue = if (active) colors.onSurface else colors.onSurfaceDim,
        animationSpec = Motion.orSnap(reduceMotion, motionScheme.defaultEffectsSpec<Color>()),
        label = "navLabel",
    )

    // No haptic here: the shell decides whether a tap moved at all — a re-tap of the
    // seat already carrying the fill is silent. FlickApp fires the pulse from onSelect.
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Material's ripple dilutes whatever ink it is handed, and on this palette
    // onSurface is near-white — which lands on the glass as a grey blob. The seat
    // answers a touch by pressing in under it instead, washed in the same brand tint
    // the selection itself travels in. Never a neutral one, and never on the seat that
    // already carries the fill: there the travelling pill is the wash.
    val wash = animateFloatAsState(
        targetValue = if (pressed && !active) NavPressWashAlpha else 0f,
        animationSpec = Motion.orSnap(reduceMotion, motionScheme.fastEffectsSpec<Float>()),
        label = "navPressWash",
    )
    val washColor = colors.primary

    Column(
        modifier = Modifier
            .width(76.dp)
            .heightIn(min = 48.dp)
            .pressScale(interaction, target = NavPressScale)
            .drawBehind {
                // Read in the draw scope, so a press repaints one seat rather than
                // recomposing the bar it sits in.
                val alpha = wash.value
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
            // The label carries the name; a second announcement would only repeat it.
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = FlickText.labelSmall.copy(color = labelTint),
        )
    }
}

// Press response for one seat. The dip is deeper than the row scale because the seat
// is small and carries no fill of its own to deform; the wash sits just above the
// glass sheen it has to read against.
private const val NavPressScale = 0.92f
private const val NavPressWashAlpha = 0.16f
private val NavPressWashRadius = 20.dp
