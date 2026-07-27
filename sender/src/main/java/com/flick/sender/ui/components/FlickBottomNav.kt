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
import com.flick.sender.ui.theme.FlickCorners
import com.flick.sender.ui.theme.FlickIcons
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.Motion
import com.flick.sender.ui.theme.PillShape
import com.flick.sender.ui.theme.flickGlass
import com.flick.sender.ui.theme.flickRipple
import com.flick.sender.ui.theme.pressMorph
import com.flick.sender.ui.theme.rememberReduceMotion
import kotlin.math.roundToInt

/**
 * The floating glass navigation pill (design §5.4). It is drawn over the route rather
 * than inset into it, so each screen it appears on reserves its own bottom padding.
 * The caller owns the 16 dp margins and the window insets.
 *
 * Selection is ONE fill that travels between the three seats rather than three fills
 * that cross-fade: the tab left behind and the tab arrived at are the same object
 * moving, which is the claim the route transition makes at the same moment.
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
        // flying in from the corner of the bar.
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
                icon = FlickIcons.PlayCircle,
                label = stringResource(R.string.nav_remote),
                active = selected == NavTab.REMOTE,
                host = host,
                onSeat = { reportSeat(NavTab.REMOTE, it) },
                onClick = { onSelect(NavTab.REMOTE) },
            )
            NavItem(
                icon = FlickIcons.Cast,
                label = stringResource(R.string.nav_devices),
                active = selected == NavTab.DEVICES,
                host = host,
                onSeat = { reportSeat(NavTab.DEVICES, it) },
                onClick = { onSelect(NavTab.DEVICES) },
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

    // No haptic here: a Remote tap can be refused, and only the shell knows whether
    // the tap moved or raised a toast. FlickApp fires the pulse from onSelect.
    val interaction = remember { MutableInteractionSource() }

    Column(
        modifier = Modifier
            .width(76.dp)
            .heightIn(min = 48.dp)
            // This item paints no fill of its own, so the clip is what bounds the
            // ripple — and RoundedCornerShape scales adjacent radii down to fit, which
            // renders the 34dp rest radius as 28dp on a 56dp-tall item. The pressed
            // radius has to clear that clamp or the corners never actually travel.
            .pressMorph(interaction, restRadius = FlickCorners.nav, pressedRadius = 20.dp)
            .clickable(
                interactionSource = interaction,
                indication = flickRipple(colors.onSurface),
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
