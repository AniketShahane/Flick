package com.flick.sender.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flick.sender.R
import com.flick.sender.media.LibraryFolder
import com.flick.sender.media.LibraryFolderId
import com.flick.sender.media.LibraryScope
import com.flick.sender.ui.screens.BottomSheet
import com.flick.sender.ui.screens.LocalSheetDismiss
import com.flick.sender.ui.screens.SheetGrabber
import com.flick.sender.ui.theme.FlickCorners
import com.flick.sender.ui.theme.FlickIcons
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.PillShape
import com.flick.sender.ui.theme.flickRipple
import com.flick.sender.ui.theme.pressScale
import com.flick.sender.ui.theme.rememberFlickTouchHaptics

/** A long folder name shortens rather than pushing the quality chips off their row. */
private val FolderChipMaxWidth = 168.dp

// One step per level of nesting, capped so a deep name keeps a readable width inside a
// sheet that is already 20 dp in from both phone edges: four steps take 64 dp, which
// leaves the title around 210 dp — still a couple of dozen characters at bodyMedium.
private val FolderIndentStep = 16.dp
private const val FolderIndentCap = 4

// The rail sits midway through the step the row was pushed by, and stops short of the
// row's own corners so it reads as a guide rather than as a border the card is missing.
private val FolderRailWidth = 1.dp
private val FolderRailInset = 8.dp

/**
 * The library's scope control, seated beside the quality chips. It carries a chevron
 * and answers to [Role.Button], not [Role.Tab]: the chips are one exclusive axis and
 * this is a menu that opens over them, so it must not be announced as a fourth seat on
 * that axis.
 *
 * Unscoped it advertises the action rather than restating "All videos", which the chip
 * beside it already implies; scoped it names the folder, because that name is the one
 * fact about the library the rest of the screen no longer states.
 */
@Composable
fun LibraryFolderChip(scope: LibraryScope, onClick: () -> Unit) {
    val colors = LocalFlickColors.current
    val interaction = remember { MutableInteractionSource() }
    val scopeName = when (scope) {
        LibraryScope.All -> stringResource(R.string.library_folder_all)
        is LibraryScope.Folder -> scope.name
        is LibraryScope.Missing -> scope.name
    }
    val label = if (scope == LibraryScope.All) stringResource(R.string.library_folder_action) else scopeName
    // A missing folder is named on the chip but is not what the grid is showing, because
    // it is showing nothing: the spoken "Showing…" would be the one thing on the screen
    // contradicting the card underneath it.
    val description = if (scope is LibraryScope.Missing) {
        stringResource(R.string.library_folder_a11y_missing, scopeName)
    } else {
        stringResource(R.string.library_folder_a11y, scopeName)
    }
    Row(
        modifier = Modifier
            .pressScale(interaction)
            .widthIn(max = FolderChipMaxWidth)
            .heightIn(min = 48.dp)
            .clip(PillShape)
            .background(colors.primaryContainer)
            .clickable(
                interactionSource = interaction,
                indication = flickRipple(colors.onPrimaryContainer),
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = description
            }
            .padding(start = 18.dp, end = 13.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            style = FlickText.labelMedium.copy(color = colors.onPrimaryContainer),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(
            FlickIcons.ChevronDown,
            contentDescription = null,
            tint = colors.onPrimaryContainer,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * The chooser. Only folders that already hold video are listed, so a pick can never
 * produce an empty library — which is the whole reason the scope is derived from what
 * MediaStore reports and not from a folder picked out of the system document picker.
 *
 * [folders] arrives in render order — depth-first, siblings by name — so the rows are
 * laid out in the order they are given and indented by the depth each one carries; the
 * list is a tree flattened, never a tree this rebuilds. Picking a folder takes everything
 * nested under it, which is what its count already states.
 *
 * [allCount] is the whole library's size, not the scoped one: "All videos" has to state
 * what leaving the folder would restore.
 *
 * A row does not remove this sheet. [onChoose] applies the scope — the grid re-deals
 * behind the card, which is the tap being answered — and the card then leaves the way it
 * would have left under a finger dragging it down. Removing it belongs to [onDismiss],
 * which the sheet calls once it is actually off the window.
 */
@Composable
fun LibraryFolderSheet(
    folders: List<LibraryFolder>,
    scope: LibraryScope,
    allCount: Int,
    onChoose: (LibraryFolder?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalFlickColors.current
    val haptics = rememberFlickTouchHaptics()
    val chosen = (scope as? LibraryScope.Folder)?.id
    BottomSheet(
        onDismiss = onDismiss,
        contentPadding = PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 26.dp),
    ) {
        // Read inside the sheet that provides it: a choice is a dismissal like any other
        // here, and it must not be the one that cuts the card and its scrim away in a
        // single frame while the grid it just changed re-deals underneath.
        val dismiss = LocalSheetDismiss.current
        SheetGrabber()
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.library_folder_sheet_title),
            style = FlickText.headlineMedium.copy(color = colors.onSurface),
        )
        Spacer(Modifier.height(5.dp))
        Text(
            stringResource(R.string.library_folder_sheet_body),
            style = FlickText.bodyMedium.copy(color = colors.onSurfaceDim),
        )
        Spacer(Modifier.height(16.dp))
        FolderRow(
            title = stringResource(R.string.library_folder_all),
            detail = stringResource(R.string.library_folder_all_summary),
            count = allCount,
            selected = scope == LibraryScope.All,
            onClick = {
                haptics.toggle(false)
                onChoose(null)
                dismiss()
            },
        )
        folders.forEach { folder ->
            Spacer(Modifier.height(8.dp))
            FolderBranch(depth = folder.depth) {
                FolderRow(
                    title = folder.name,
                    detail = null,
                    count = folder.videoCount,
                    selected = chosen == LibraryFolderId.Path(folder.id),
                    onClick = {
                        haptics.toggle(true)
                        onChoose(folder)
                        dismiss()
                    },
                )
            }
        }
    }
}

/**
 * The row's place in the tree, drawn rather than spelled: an indent for every level, and
 * a hairline standing in the gutter the indent opened so the eye can follow a nested name
 * back to what holds it.
 *
 * The indent stops at [FolderIndentCap] levels. A folder name is the whole reason this
 * sheet exists, and the sheet is 20 dp inside a phone: past four levels another step
 * would go on eating the name rather than the empty space beside it, and the rows would
 * disagree about their depth by an amount too small to read anyway. Deeper folders
 * therefore share the deepest indent — the depth-first order still puts each one directly
 * under the folder that holds it, which is where the relationship is actually legible.
 */
@Composable
private fun FolderBranch(depth: Int, content: @Composable () -> Unit) {
    val rail = LocalFlickColors.current.outlineHairline
    val levels = depth.coerceAtMost(FolderIndentCap)
    if (levels == 0) {
        content()
        return
    }
    val indent = FolderIndentStep * levels
    val gutter = FolderIndentStep / 2
    Box(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                val x = (indent - gutter).toPx()
                val inset = FolderRailInset.toPx()
                drawLine(
                    color = rail,
                    start = Offset(x, inset),
                    end = Offset(x, size.height - inset),
                    strokeWidth = FolderRailWidth.toPx(),
                )
            }
            .padding(start = indent),
    ) {
        content()
    }
}

/**
 * One choice. The count is the number of videos Flick can currently list from that
 * folder AND everything under it — the same number the "All" chip would show once it is
 * picked — so the two can never disagree.
 */
@Composable
private fun FolderRow(
    title: String,
    detail: String?,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalFlickColors.current
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .pressScale(interaction)
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(FlickCorners.statCard))
            .background(colors.fillCard)
            .clickable(
                interactionSource = interaction,
                indication = flickRipple(colors.onSurface),
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp)
            // Merged and marked selected rather than described as a button: this is one
            // exclusive list, and TalkBack has to say which member is live.
            .semantics(mergeDescendants = true) {
                role = Role.Button
                this.selected = selected
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = FlickText.bodyMedium.copy(color = colors.onSurface),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            detail?.let {
                Spacer(Modifier.height(3.dp))
                Text(
                    it,
                    style = FlickText.bodySmall.copy(color = colors.onSurfaceDim),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(R.string.library_folder_count, count),
            style = FlickText.monoSmall.copy(color = colors.onSurfaceFaint),
        )
        if (selected) {
            Spacer(Modifier.width(10.dp))
            Icon(
                FlickIcons.CheckCircle,
                contentDescription = null,
                tint = colors.spark,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
