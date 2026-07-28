package com.flick.sender.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import com.flick.sender.media.LibraryScope
import com.flick.sender.ui.screens.BottomSheet
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
 * produce an empty library — which is the whole reason the scope is a MediaStore bucket
 * and not a folder picked out of the system document picker.
 *
 * [allCount] is the whole library's size, not the scoped one: "All videos" has to state
 * what leaving the folder would restore.
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
    BottomSheet(
        onDismiss = onDismiss,
        contentPadding = PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 26.dp),
    ) {
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
            },
        )
        folders.forEach { folder ->
            Spacer(Modifier.height(8.dp))
            FolderRow(
                title = folder.name,
                detail = null,
                count = folder.videoCount,
                selected = (scope as? LibraryScope.Folder)?.id == folder.id,
                onClick = {
                    haptics.toggle(true)
                    onChoose(folder)
                },
            )
        }
    }
}

/**
 * One choice. The count is the number of videos Flick can currently list from that
 * folder — the same number the "All" chip would show once it is picked — so the two can
 * never disagree.
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
