package com.flick.sender.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flick.sender.R
import com.flick.sender.net.FlickController
import com.flick.sender.ui.components.LocalNavMetrics
import com.flick.sender.ui.components.navBottomClearance
import com.flick.sender.ui.theme.FlickIcons
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.FlickCorners
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.PillMorphShape
import com.flick.sender.ui.theme.PressedPillShape
import com.flick.sender.ui.theme.ThemePreference
import com.flick.sender.ui.theme.flickRipple
import com.flick.sender.ui.theme.pressScale
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
    val simplifiedVideoNames by controller.simplifiedVideoNames.collectAsState()

    // The dock floats over this surface too, above the nav, so the foot of the scroll has
    // to clear both of them while a cast is live — otherwise the screen's final controls sit
    // under a bar that answers taps meant for them. The nav's height is measured rather than
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_heading),
                    style = FlickText.displayLarge.copy(color = colors.onSurface),
                )
                Text(
                    text = stringResource(R.string.advisories_sub),
                    style = FlickText.bodyMedium.copy(color = colors.onSurfaceDim),
                )
            }
            FilledTonalButton(
                onClick = { controller.toggleDiagnostics(true) },
                shapes = ButtonDefaults.shapes(shape = PillMorphShape, pressedShape = PressedPillShape),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = colors.primaryContainer,
                    contentColor = colors.onPrimaryContainer,
                ),
                contentPadding = PaddingValues(horizontal = 15.dp, vertical = 15.dp),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(text = stringResource(R.string.diagnostics_title), style = FlickText.labelMedium)
            }
        }
        // One node, not a run of siblings: the advisories carry their own spacing between
        // the cards, and this screen's 22 dp rhythm must not be inserted between them.
        Advisories(
            batteryExempt = batteryExempt,
            onOpenWifiSettings = onOpenWifiSettings,
            onRequestBatteryExemption = onRequestBatteryExemption,
        )
        VideoNamesSection(
            simplified = simplifiedVideoNames,
            onSelect = controller::selectSimplifiedVideoNames,
        )
        AppearanceSection(preference = themePreference, onSelect = onSelectTheme)
        if (supportAvailable) {
            SupportFlickSection(onOpen = onOpenSupport)
        }
    }
}

/**
 * Sets the card's height, since it is a little taller than the two lines of type beside it.
 * Big enough to be the first thing on the screen the eye lands on, and stopping there: a
 * disc that cleared the type by much would make this a tile with a caption rather than a row.
 */
private val SupportBadgeSize = 46.dp

/** An icon that happens to be a glyph — see [SupportBadge] for why this is a `Dp`. */
private val SupportEmojiSize = 24.dp

/**
 * The support entrance that never goes away, and therefore the only one most people will
 * ever meet: the library's invitation is earned once and can be waved off for good, after
 * which this row is the entire feature.
 *
 * So it is drawn to be found rather than to be discreet. It takes the same spark fill and
 * ink the invitation card uses — one idea wearing one face — which on this screen makes it
 * the single warm block in a column of neutral rows. Sharing the advisories' corner radius
 * does not make it read as a third advisory: those are the saturated caution fill behind a
 * stroke icon with pill buttons inside them, and they are on screen only while something is
 * actually costing the user quality.
 */
@Composable
private fun SupportFlickSection(onOpen: () -> Unit) {
    val colors = LocalFlickColors.current
    val press = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Ahead of the fill, unlike the sheet's tier rows: everything after this rides
            // inside the layer, so the card shrinks as one object under a thumb instead of
            // leaving its own background standing still behind the type.
            .pressScale(press)
            .clip(RoundedCornerShape(FlickCorners.warning))
            .background(colors.sparkPale)
            .clickable(
                interactionSource = press,
                indication = flickRipple(colors.onSpark),
                role = Role.Button,
                onClick = onOpen,
            )
            .heightIn(min = 48.dp)
            .padding(horizontal = 17.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SupportBadge()
        Spacer(Modifier.width(14.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_support_title),
                // A weight step above the screen's other section titles rather than a size
                // step above them. The fill and the badge are what make this card loud;
                // 23 sp here would buy very little of that and would wrap "Support Flick"
                // onto two nearly-solid lines on a 360 dp frame at any raised font scale.
                style = FlickText.titleMediumEmphasized.copy(color = colors.onSpark),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.settings_support_summary),
                style = FlickText.bodyMedium.copy(color = colors.onSpark.copy(alpha = 0.82f)),
            )
        }
        Spacer(Modifier.width(10.dp))
        Icon(
            imageVector = FlickIcons.ChevronRight,
            contentDescription = null,
            tint = colors.onSpark.copy(alpha = 0.72f),
            modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * The emoji on a disc rather than loose on the fill.
 *
 * A colour emoji arrives with its own palette and cannot be tinted, so the only way to know
 * what it will stand against is to give it a ground this scheme owns. `onSpark` is that
 * ground in every set — deep brown under Light and Dark, near-black navy under Cinematic —
 * and it is what keeps a warm glyph from floating on the cinematic card, where the spark
 * family turns pale blue.
 */
@Composable
private fun SupportBadge() {
    val colors = LocalFlickColors.current
    // Pinned to density and not to the font scale, because this is an icon in the shape of
    // a glyph: sized in sp it would outgrow the disc at a 2x scale and be clipped into a
    // crescent by it, which is not what a larger type setting was asking for.
    val glyph = with(LocalDensity.current) { SupportEmojiSize.toSp() }
    Box(
        modifier = Modifier
            .size(SupportBadgeSize)
            .clip(CircleShape)
            .background(colors.onSpark),
        contentAlignment = Alignment.Center,
    ) {
        // Cleared rather than described: the title beside it already names the card, and
        // "yellow heart" ahead of it is a word a reader has to spend before reaching one
        // that means something.
        Text(
            text = stringResource(R.string.settings_support_emoji),
            style = TextStyle(fontSize = glyph),
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

@Composable
internal fun VideoNamesSection(simplified: Boolean, onSelect: (Boolean) -> Unit) {
    val colors = LocalFlickColors.current
    val title = stringResource(R.string.settings_video_names_title)
    val summary = stringResource(R.string.settings_video_names_summary)
    val spoken = stringResource(R.string.settings_video_names_a11y, title, summary)
    val state = stringResource(
        if (simplified) R.string.settings_video_names_on else R.string.settings_video_names_off,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = spoken
                stateDescription = state
            }
            .toggleable(
                value = simplified,
                role = Role.Switch,
                onValueChange = onSelect,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = FlickText.titleMedium.copy(color = colors.onSurface))
            Text(
                text = summary,
                style = FlickText.bodyMedium.copy(color = colors.onSurfaceDim),
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = simplified,
            onCheckedChange = null,
            modifier = Modifier.clearAndSetSemantics {},
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
