package com.flick.sender.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.flick.sender.R
import com.flick.sender.media.LibraryFolder
import com.flick.sender.media.LibraryFolderId
import com.flick.sender.media.LibraryFolders
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
import com.flick.sender.ui.theme.rememberReduceMotion

// One step per level of nesting, capped so a deep name keeps a readable width inside a
// sheet that is already 20 dp in from both phone edges: four steps take 64 dp, which
// leaves the title around 210 dp — still a couple of dozen characters at bodyMedium.
private val FolderIndentStep = 16.dp
private const val FolderIndentCap = 4

// The rail sits midway through the step the row was pushed by, and stops short of the
// row's own corners so it reads as a guide rather than as a border the card is missing.
private val FolderRailWidth = 1.dp
private val FolderRailInset = 8.dp

// The disclosure control's column, OUTSIDE the card rather than inside it: opening a
// folder and choosing it are two different answers, and a control that shares the card
// with the choice reads as part of it. Out here the twisty and the rail are the tree, and
// the card is the decision. A leaf reserves the same column so every card at a given depth
// starts on the same line whether or not it has anything to open.
//
// Square and a good deal wider than the glyph it holds: the row is 56 dp tall, so this is
// what decides whether the target is one a thumb can hit beside a card that is 250 dp of
// its own tap area.
private val FolderDisclosureSize = 44.dp
private val FolderDisclosureGlyph = 18.dp

// The mark a CLOSED folder carries when the chosen folder is somewhere inside it. Smaller
// than the tick and in the same accent, because it is the same fact seen from further
// away: this branch holds the answer, open it to see which row.
private val FolderChoiceDotSize = 8.dp

/**
 * The library's scope control. It carries a chevron and answers to [Role.Button], not
 * [Role.Tab], because it opens a chooser rather than selecting a value in an axis.
 *
 * Unscoped it advertises the action rather than restating "All videos"; scoped it names
 * the folder, because that name is the one fact about the library the rest of the screen
 * no longer states.
 */
@Composable
fun LibraryFolderChip(
    scope: LibraryScope,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
        modifier = modifier
            .pressScale(interaction)
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
 * [folders] arrives in render order — depth-first, siblings by name — so the rows are laid
 * out in the order they are given and indented by the depth each one carries; the list is
 * a tree flattened, never a tree this rebuilds. Picking a folder takes everything nested
 * under it, which is what its count already states.
 *
 * The tree COLLAPSES, and that is the difference between an indent and a hierarchy. A
 * phone that keeps video in a handful of places produces a dozen folders once every
 * ancestor is counted as one, and a flat dozen of them under four indent steps is a list
 * the eye has to parse a leading margin to read. Closed, the sheet opens on the few
 * top-level folders that are the actual choice, and every level below one is a deliberate
 * step into it. Which folders start open is [LibraryFolders.initialExpansion]'s to say;
 * what the user does afterwards is held here, for as long as the sheet is on screen.
 *
 * [allCount] is the whole library's size, not the scoped one: "All videos" has to state
 * what leaving the folder would restore.
 *
 * A row does not remove this sheet. [onChoose] applies the scope — the grid re-deals
 * behind the card, which is the tap being answered — and the card then leaves the way it
 * would have left under a finger dragging it down. Removing it belongs to [onDismiss],
 * which the sheet calls once it is actually off the window. Opening a folder is not a
 * dismissal of any kind: it neither changes the scope nor closes the card.
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
    // Unkeyed on purpose. The opening shape is decided once, from the tree and the choice
    // as they stood when the sheet was raised; a library reload arriving behind the card
    // must not fold a branch back up under the finger that just opened it, and a set that
    // still names a folder the reload dropped is harmless — the id simply stops matching.
    var expanded by remember { mutableStateOf(LibraryFolders.initialExpansion(folders, scope)) }
    val rows = remember(folders, expanded, chosen) { LibraryFolders.rows(folders, expanded, chosen) }
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
        FolderLine {
            FolderCard(
                title = stringResource(R.string.library_folder_all),
                detail = stringResource(R.string.library_folder_all_summary),
                count = allCount,
                selected = scope == LibraryScope.All,
                holdsChoice = false,
                onClick = {
                    haptics.toggle(false)
                    onChoose(null)
                    dismiss()
                },
            )
        }
        rows.forEach { row ->
            key(row.folder.id) {
                FolderReveal(visible = row.visible) {
                    Spacer(Modifier.height(8.dp))
                    FolderBranch(depth = row.folder.depth) {
                        FolderLine(
                            disclosure = {
                                if (row.expandable) {
                                    FolderDisclosure(
                                        expanded = row.expanded,
                                        name = row.folder.name,
                                        onToggle = {
                                            haptics.toggle(!row.expanded)
                                            expanded = if (row.expanded) {
                                                expanded - row.folder.id
                                            } else {
                                                expanded + row.folder.id
                                            }
                                        },
                                    )
                                }
                            },
                        ) {
                            FolderCard(
                                title = row.folder.name,
                                detail = null,
                                count = row.folder.videoCount,
                                selected = chosen == LibraryFolderId.Path(row.folder.id),
                                holdsChoice = row.holdsChoice && !row.expanded,
                                onClick = {
                                    haptics.toggle(true)
                                    onChoose(row.folder)
                                    dismiss()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A branch arriving or leaving. Height and opacity only — the card itself is never
 * rebuilt, and a subtree closing runs one of these per row, all on the same clock, so the
 * whole branch folds as a piece rather than as a column of rows racing each other.
 *
 * Reduce motion removes the transitions rather than shortening them: a row that is not
 * there is not there, and a height animation is exactly the kind of movement that setting
 * exists to stop.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FolderReveal(visible: Boolean, content: @Composable () -> Unit) {
    val reduce = rememberReduceMotion()
    val spatial = MaterialTheme.motionScheme.fastSpatialSpec<IntSize>()
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    AnimatedVisibility(
        visible = visible,
        enter = if (reduce) EnterTransition.None else expandVertically(spatial) + fadeIn(effects),
        exit = if (reduce) ExitTransition.None else shrinkVertically(spatial) + fadeOut(effects),
    ) {
        Column { content() }
    }
}

/**
 * One line of the chooser: the disclosure column, then the card.
 *
 * The column is reserved whether or not anything is drawn in it, which is what keeps a
 * leaf's card flush with the cards of the folders it sits among — a tree whose rows start
 * in two different places depending on whether they open is harder to read than the flat
 * list this replaced.
 */
@Composable
private fun FolderLine(
    disclosure: @Composable () -> Unit = {},
    card: @Composable RowScope.() -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(FolderDisclosureSize), contentAlignment = Alignment.Center) { disclosure() }
        card()
    }
}

/**
 * The twisty. A control of its own, with its own tap target, its own label and its own
 * place in the traversal order — NOT a decoration inside the card's merged semantics,
 * which would leave a screen reader with a row it can choose and no way to open.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FolderDisclosure(expanded: Boolean, name: String, onToggle: () -> Unit) {
    val colors = LocalFlickColors.current
    val interaction = remember { MutableInteractionSource() }
    val spec: AnimationSpec<Float> =
        if (rememberReduceMotion()) snap() else MaterialTheme.motionScheme.fastSpatialSpec()
    val turn = animateFloatAsState(if (expanded) 90f else 0f, spec, label = "folderDisclosure")
    val description = stringResource(
        if (expanded) R.string.library_folder_collapse else R.string.library_folder_expand,
        name,
    )
    Box(
        Modifier
            .size(FolderDisclosureSize)
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = flickRipple(colors.onSurface),
                onClick = onToggle,
            )
            .semantics {
                role = Role.Button
                contentDescription = description
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            FlickIcons.ChevronRight,
            contentDescription = null,
            tint = colors.onSurfaceDim,
            // Read in the draw phase: a folder opening must not recompose the glyph, and
            // one chevron turning is not a reason to re-lay-out the row it stands in.
            modifier = Modifier
                .size(FolderDisclosureGlyph)
                .graphicsLayer { rotationZ = turn.value },
        )
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
 *
 * [holdsChoice] is the caller's answer to "is the chosen folder hidden inside this closed
 * one", and it is drawn where the tick would be. Without it the collapse could put the
 * only statement of the current scope out of sight, which is a chooser that stops saying
 * what it chose.
 */
@Composable
private fun RowScope.FolderCard(
    title: String,
    detail: String?,
    count: Int,
    selected: Boolean,
    holdsChoice: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalFlickColors.current
    val interaction = remember { MutableInteractionSource() }
    val insideDescription = stringResource(R.string.library_folder_a11y_holds_choice)
    Row(
        Modifier
            .weight(1f)
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
            // exclusive list, and TalkBack has to say which member is live. The dot is
            // spoken through the state rather than as an icon label, because it says
            // something about this row's SUBTREE and not about this row.
            .semantics(mergeDescendants = true) {
                role = Role.Button
                this.selected = selected
                if (holdsChoice) stateDescription = insideDescription
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
        when {
            selected -> {
                Spacer(Modifier.width(10.dp))
                Icon(
                    FlickIcons.CheckCircle,
                    contentDescription = null,
                    tint = colors.spark,
                    modifier = Modifier.size(20.dp),
                )
            }
            holdsChoice -> {
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .size(FolderChoiceDotSize)
                        .clip(CircleShape)
                        .background(colors.spark),
                )
            }
        }
    }
}
