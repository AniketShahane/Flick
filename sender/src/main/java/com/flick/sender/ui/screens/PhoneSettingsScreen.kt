package com.flick.sender.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flick.sender.R
import com.flick.sender.net.FlickController
import com.flick.sender.ui.components.LocalNavMetrics
import com.flick.sender.ui.components.navBottomClearance
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.FlickCorners
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.ThemePreference
import com.flick.sender.ui.theme.rememberFlickTouchHaptics

/**
 * S11 as a nav peer of the library and the devices list, not as a modal. Everything on
 * it is optional: the advisories name a condition and its exact fix, and casting is
 * never blocked on one of them being answered.
 */
@Composable
fun PhoneSettingsScreen(
    controller: FlickController,
    supportAvailable: Boolean,
    onOpenSupport: () -> Unit,
    batteryExempt: Boolean,
    themePreference: ThemePreference,
    onSelectTheme: (ThemePreference) -> Unit,
    onOpenWifiSettings: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
) {
    val colors = LocalFlickColors.current
    val castingItem by controller.castingItem.collectAsState()

    // The dock floats over this surface too, above the nav, so the foot of the scroll has
    // to clear both of them while a cast is live — otherwise the diagnostics row sits
    // under a bar that answers taps meant for it. The nav's height is measured rather than
    // assumed, because the label's line box grows with the font scale.
    val bottomClearance = navBottomClearance(
        barHeight = LocalNavMetrics.current.height,
        dockLive = castingItem != null,
    )

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = bottomClearance),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = stringResource(R.string.settings_heading),
                style = FlickText.displayLarge.copy(color = colors.onSurface),
            )
            Text(
                text = stringResource(R.string.advisories_sub),
                style = FlickText.bodyMedium.copy(color = colors.onSurfaceDim),
            )
        }
        // One node, not a run of siblings: the advisories carry their own spacing between
        // the cards, and this screen's 22 dp rhythm must not be inserted between them.
        Advisories(
            batteryExempt = batteryExempt,
            onOpenWifiSettings = onOpenWifiSettings,
            onRequestBatteryExemption = onRequestBatteryExemption,
            onOpenDiagnostics = { controller.toggleDiagnostics(true) },
        )
        AppearanceSection(preference = themePreference, onSelect = onSelectTheme)
        if (supportAvailable) {
            SupportFlickSection(onOpen = onOpenSupport)
        }
    }
}

/** An always-available, quiet path after the earned library invitation is gone. */
@Composable
private fun SupportFlickSection(onOpen: () -> Unit) {
    val colors = LocalFlickColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FlickCorners.qualityCard))
            .background(colors.fillCard)
            .clickable(role = Role.Button, onClick = onOpen)
            .heightIn(min = 48.dp)
            .padding(horizontal = 17.dp, vertical = 15.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_support_title),
            style = FlickText.titleMedium.copy(color = colors.onSurface),
        )
        Text(
            text = stringResource(R.string.settings_support_summary),
            style = FlickText.bodyMedium.copy(color = colors.onSurfaceDim),
        )
    }
}

/**
 * Below the advisories, not above them: the line under the heading is a claim about the
 * cards, and each of those names a condition that is costing the user quality right now.
 * Appearance answers nothing and blocks nothing, so it takes the seat after them.
 *
 * No container of its own. The advisory cards already carry this screen's one containment
 * gesture, and a second tinted box competing with them would read as a third advisory.
 */
@Composable
private fun AppearanceSection(preference: ThemePreference, onSelect: (ThemePreference) -> Unit) {
    val colors = LocalFlickColors.current
    // The title carries the live value for TalkBack. Each segment announces its own
    // selected state, but a reader arriving at the section wants the answer once, up
    // front, without walking all three.
    val spoken = stringResource(R.string.settings_theme_a11y, stringResource(labelOf(preference)))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.settings_appearance_title),
                style = FlickText.titleMedium.copy(color = colors.onSurface),
                modifier = Modifier.semantics { contentDescription = spoken },
            )
            Text(
                text = stringResource(R.string.settings_appearance_summary),
                style = FlickText.bodyMedium.copy(color = colors.onSurfaceDim),
            )
        }
        ThemeChoice(selected = preference, onSelect = onSelect)
        // onSurfaceDim rather than the fainter caption ink: this sentence is the only
        // warning a Light user gets that the remote will still be dark, so it has to
        // clear the 4.5:1 floor for body copy — InkFaint reaches 3.9:1 on the pale
        // canvas, onSurfaceDim reaches 6.3:1.
        Text(
            text = stringResource(R.string.settings_theme_note),
            style = FlickText.bodySmall.copy(color = colors.onSurfaceDim),
        )
    }
}

/**
 * The three options as a connected button group — Material Expressive's own answer for a
 * handful of mutually exclusive choices, and the same `ButtonGroup` the remote's transport
 * is built from.
 *
 * [ToggleButton] is a toggle rather than a radio, so a tap on the segment that is already
 * lit arrives as `false`. Dropping it is what keeps the group from landing on no choice at
 * all, and it also means the haptic only fires when something actually moved.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ThemeChoice(selected: ThemePreference, onSelect: (ThemePreference) -> Unit) {
    val haptics = rememberFlickTouchHaptics()
    // One source per segment, held for the life of the control: the group reads the
    // press off it to widen that segment and squeeze its neighbours, and a fresh
    // instance per recomposition would leave the squeeze with nothing to follow.
    val sources = remember { List(ThemePreference.entries.size) { MutableInteractionSource() } }
    ButtonGroup(
        // Three fixed options that are the whole preference. There is nothing to overflow
        // into a menu, and an appearance the user cannot see offered is not a choice.
        overflowIndicator = {},
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        ThemePreference.entries.forEachIndexed { index, option ->
            val source = sources[index]
            customItem(
                buttonGroupContent = {
                    ToggleButton(
                        checked = option == selected,
                        onCheckedChange = { checked ->
                            if (checked) {
                                haptics.toggle(true)
                                onSelect(option)
                            }
                        },
                        shapes = when (index) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            ThemePreference.entries.lastIndex ->
                                ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                        // Tighter than the Material default because a third of a 360 dp
                        // screen has to hold "Match system": the segment spends its width
                        // on the label rather than on air beside it.
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 14.dp),
                        interactionSource = source,
                        modifier = Modifier
                            .weight(1f)
                            .animateWidth(source)
                            .heightIn(min = 48.dp),
                    ) {
                        Text(
                            text = stringResource(labelOf(option)),
                            style = FlickText.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                menuContent = {},
            )
        }
    }
}

@StringRes
private fun labelOf(preference: ThemePreference): Int = when (preference) {
    ThemePreference.SYSTEM -> R.string.settings_theme_system
    ThemePreference.LIGHT -> R.string.settings_theme_light
    ThemePreference.DARK -> R.string.settings_theme_dark
}
