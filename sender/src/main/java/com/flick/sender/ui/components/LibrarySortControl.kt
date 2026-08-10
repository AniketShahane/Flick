package com.flick.sender.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.flick.sender.R
import com.flick.sender.media.LibrarySort
import com.flick.sender.ui.theme.FlickCorners
import com.flick.sender.ui.theme.FlickIcons
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.PillShape
import com.flick.sender.ui.theme.flickRipple
import com.flick.sender.ui.theme.pressScale
import com.flick.sender.ui.theme.rememberFlickTouchHaptics

/**
 * The width the library row has to keep clear for the sort control. Fixed rather than
 * content-sized: the row places the folder chip, this and the search target by arithmetic,
 * and a control that measured itself would have to be measured before any of them could be
 * placed.
 */
val LibrarySortControlWidth = 64.dp

/**
 * The library's order control — two glyphs, no words, sitting immediately left of the
 * search target.
 *
 * It says WHAT it is with [FlickIcons.Sort] and WHICH order is in force with a smaller
 * glyph in front of it, and it says both without a word, because the row it lives in
 * already spends most of its width on a folder name and has to keep a 48 dp search target
 * at the far end. What the glyphs cannot carry, the menu and the spoken label do: every
 * order is named in words the moment the control is opened, and TalkBack is told the
 * current one before it is offered the change.
 *
 * No chevron. The folder chip beside this carries one because a name alone does not say it
 * opens anything; the sort mark is itself the affordance, and a third glyph in 64 dp would
 * be the thing that made this control look like furniture rather than a button.
 *
 * [enabled] goes false while the search field owns the row. The control is still composed
 * and still drawn — the caller fades it — but it must not be tappable or reachable under a
 * finger or a screen reader once it is on its way out, and any menu it had open goes with it.
 */
@Composable
fun LibrarySortChip(
    order: LibrarySort,
    enabled: Boolean,
    onChoose: (LibrarySort) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFlickColors.current
    val haptics = rememberFlickTouchHaptics()
    val interaction = remember { MutableInteractionSource() }
    var open by remember { mutableStateOf(false) }
    val description = stringResource(R.string.library_sort_a11y, stringResource(librarySortLabel(order)))

    // The menu is a focusable popup, so the grid behind it cannot scroll this row away
    // while it is up. Search opening is the one thing that can still pull the row out from
    // under it, and a menu left anchored to a control that has faded out is a card floating
    // over the results with nothing under it.
    //
    // Read in composition AND latched by the effect, which is two mechanisms for one job on
    // purpose. The effect alone runs a frame late, and a popup is its own window that the
    // chip's fade cannot reach — so for that one frame a fully opaque menu paints over the
    // search field arriving underneath it. Reading it here closes the window in the same
    // composition; the effect then clears the latch, so a control that becomes tappable
    // again does not spring its menu back open unasked.
    val menuOpen = open && enabled
    LaunchedEffect(enabled) { if (!enabled) open = false }

    Box(modifier) {
        Row(
            Modifier
                .pressScale(interaction)
                .fillMaxSize()
                .clip(PillShape)
                .background(colors.primaryContainer)
                .clickable(
                    enabled = enabled,
                    interactionSource = interaction,
                    indication = flickRipple(colors.onPrimaryContainer),
                    role = Role.Button,
                    onClick = {
                        haptics.toggle(true)
                        open = true
                    },
                )
                .then(
                    if (enabled) {
                        Modifier.semantics(mergeDescendants = true) {
                            role = Role.Button
                            contentDescription = description
                        }
                    } else {
                        Modifier.clearAndSetSemantics { }
                    },
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SortGlyphGap, Alignment.CenterHorizontally),
        ) {
            Icon(
                imageVector = librarySortGlyph(order),
                contentDescription = null,
                tint = colors.onPrimaryContainer,
                modifier = Modifier.size(SortKindGlyphSize),
            )
            Icon(
                imageVector = FlickIcons.Sort,
                contentDescription = null,
                tint = colors.onPrimaryContainer,
                modifier = Modifier.size(SortGlyphSize),
            )
        }

        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(FlickCorners.statCard),
            containerColor = colors.surfaceRaised,
            border = BorderStroke(1.dp, colors.outlineHairline),
        ) {
            // The button carries no words at all, so the menu has to say what it is a menu
            // OF before it lists the answers.
            Text(
                text = stringResource(R.string.library_sort_title),
                style = FlickText.monoEyebrow.copy(color = colors.onSurfaceFaint),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
            )
            LibrarySort.entries.forEach { candidate ->
                val chosen = candidate == order
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(librarySortLabel(candidate)),
                            style = FlickText.bodyMedium.copy(color = colors.onSurface),
                        )
                    },
                    onClick = {
                        haptics.toggle(true)
                        onChoose(candidate)
                        open = false
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = librarySortGlyph(candidate),
                            contentDescription = null,
                            tint = if (chosen) colors.onSurface else colors.onSurfaceDim,
                            modifier = Modifier.size(SortMenuGlyphSize),
                        )
                    },
                    trailingIcon = {
                        // The tick the folder chooser uses, in the same accent: one
                        // exclusive list, one mark for the member that is live.
                        if (chosen) {
                            Icon(
                                imageVector = FlickIcons.CheckCircle,
                                contentDescription = null,
                                tint = colors.spark,
                                modifier = Modifier.size(SortMenuTickSize),
                            )
                        }
                    },
                    colors = MenuDefaults.itemColors(
                        textColor = colors.onSurface,
                        leadingIconColor = colors.onSurfaceDim,
                        trailingIconColor = colors.spark,
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    // Marked selected rather than merely ticked: this is one exclusive
                    // axis, and TalkBack has to be able to say which member it is on
                    // without relying on an icon that carries no label.
                    modifier = Modifier.semantics { selected = chosen },
                )
            }
        }
    }
}

/** The words for an order. Used by the menu, and by the label spoken for the button. */
internal fun librarySortLabel(order: LibrarySort): Int = when (order) {
    LibrarySort.RECENT -> R.string.library_sort_recent
    LibrarySort.NAME -> R.string.library_sort_name
    LibrarySort.LONGEST -> R.string.library_sort_longest
    LibrarySort.LARGEST -> R.string.library_sort_largest
}

/**
 * The mark for an order. Drawn twice at two sizes — small in front of [FlickIcons.Sort] on
 * the button, and at reading size in the menu — so the glyph the user chose from is the
 * same one that then stands on the control.
 */
internal fun librarySortGlyph(order: LibrarySort): ImageVector = when (order) {
    LibrarySort.RECENT -> FlickIcons.Clock
    LibrarySort.NAME -> FlickIcons.Alphabetical
    LibrarySort.LONGEST -> FlickIcons.Hourglass
    LibrarySort.LARGEST -> FlickIcons.FileSize
}

// The pair on the button: the order's mark kept deliberately smaller than the sort mark, so
// the two read as one adjective and one noun rather than as two buttons crammed together.
private val SortKindGlyphSize = 15.dp
private val SortGlyphSize = 20.dp
private val SortGlyphGap = 5.dp

private val SortMenuGlyphSize = 20.dp
private val SortMenuTickSize = 20.dp
