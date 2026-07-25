package com.flick.sender.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.flick.sender.R
import com.flick.sender.ui.screens.NavTab
import com.flick.sender.ui.theme.FlickIcons
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.Motion
import com.flick.sender.ui.theme.PillShape
import com.flick.sender.ui.theme.flickGlass
import com.flick.sender.ui.theme.rememberReduceMotion

/**
 * The floating glass navigation pill (design §5.4). It is drawn over the route rather
 * than inset into it, so each screen it appears on reserves its own bottom padding.
 * The caller owns the 16 dp margins and the window insets.
 */
@Composable
internal fun FlickBottomNav(
    selected: NavTab,
    onSelect: (NavTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFlickColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .flickGlass(colors)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavItem(
            icon = FlickIcons.GridView,
            label = stringResource(R.string.nav_library),
            active = selected == NavTab.LIBRARY,
            onClick = { onSelect(NavTab.LIBRARY) },
        )
        NavItem(
            icon = FlickIcons.PlayCircle,
            label = stringResource(R.string.nav_remote),
            active = selected == NavTab.REMOTE,
            onClick = { onSelect(NavTab.REMOTE) },
        )
        NavItem(
            icon = FlickIcons.Cast,
            label = stringResource(R.string.nav_devices),
            active = selected == NavTab.DEVICES,
            onClick = { onSelect(NavTab.DEVICES) },
        )
    }
}

@Composable
private fun NavItem(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalFlickColors.current
    val reduceMotion = rememberReduceMotion()
    val settle = Motion.orSnap(reduceMotion, Motion.flickSettle<Color>())
    val pill by animateColorAsState(
        targetValue = if (active) colors.primary else Color.Transparent,
        animationSpec = settle,
        label = "navPill",
    )
    val iconTint by animateColorAsState(
        targetValue = if (active) colors.onPrimary else colors.onSurfaceDim,
        animationSpec = settle,
        label = "navIcon",
    )
    val labelTint by animateColorAsState(
        targetValue = if (active) colors.onSurface else colors.onSurfaceDim,
        animationSpec = settle,
        label = "navLabel",
    )

    Column(
        modifier = Modifier
            .width(76.dp)
            .heightIn(min = 48.dp)
            .clip(PillShape)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics(mergeDescendants = true) { selected = active },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .background(pill, PillShape)
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
