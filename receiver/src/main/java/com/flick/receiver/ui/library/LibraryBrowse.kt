package com.flick.receiver.ui.library

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed as listItemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.flick.receiver.R
import com.flick.receiver.library.LibraryCatalog
import com.flick.receiver.library.LibraryFolderNode
import com.flick.receiver.library.LibraryFolderTree
import com.flick.receiver.library.LibraryShelves
import com.flick.receiver.library.LibrarySortOrder
import com.flick.receiver.library.LibrarySorting
import com.flick.receiver.ui.components.FlickTvButton
import com.flick.receiver.ui.components.FlickTvRow
import com.flick.receiver.ui.components.GlassPanel
import com.flick.receiver.ui.components.GlassPanelTone
import com.flick.receiver.ui.components.LocalShellRetained
import com.flick.receiver.ui.components.landTvFocus
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickShape
import com.flick.receiver.ui.theme.FlickSpace
import com.flick.receiver.ui.theme.FlickType
import kotlinx.coroutines.launch

/** The TV's own preferences file for library view choices; nothing here is ever sent anywhere. */
private const val PREFS = "library_ui"

/** The sort order, stored under the phone's enum name so the two read the same ([LibrarySortOrder.of]). */
private const val KEY_SORT = "sort"

private val GridGap: Dp = 14.dp
private const val POSTER_COLUMNS = 6
private const val VIDEO_COLUMNS = 5

/** The menu scrim: [FlickColor.CanvasPlayback] @ 55 %, as in the sort mockup. */
private val MenuScrim = Color(0x8C02040A)

/** The width each of [columns] cells gets inside the gutters. */
private fun cellWidth(total: Dp, columns: Int): Dp =
    (total - LibraryLayout.Gutter * 2 - GridGap * (columns - 1)) / columns

@Composable
internal fun ShowsPane(shelves: LibraryShelves, pane: PaneContext) {
    val shows = shelves.shows
    val focused = remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LandFocus(pane, if (shows.isNotEmpty()) first else null, focused)
    PosterGrid(focused, isEmpty = shows.isEmpty()) { cell ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(POSTER_COLUMNS),
            state = pane.memory.grid,
            modifier = Modifier.fillMaxSize(),
            contentPadding = gridPadding(),
            horizontalArrangement = Arrangement.spacedBy(GridGap),
            verticalArrangement = Arrangement.spacedBy(GridGap),
        ) {
            itemsIndexed(shows, key = { _, show -> show.key }) { index, show ->
                PosterShowTile(
                    show = show,
                    focusKey = "shows/" + show.key,
                    pane = pane,
                    width = cell,
                    height = cell * 1.5f,
                    focusRequester = if (index == 0) first else null,
                )
            }
        }
    }
}

@Composable
internal fun FilmsPane(shelves: LibraryShelves, pane: PaneContext) {
    val films = shelves.films
    val focused = remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LandFocus(pane, if (films.isNotEmpty()) first else null, focused)
    PosterGrid(focused, isEmpty = films.isEmpty()) { cell ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(POSTER_COLUMNS),
            state = pane.memory.grid,
            modifier = Modifier.fillMaxSize(),
            contentPadding = gridPadding(),
            horizontalArrangement = Arrangement.spacedBy(GridGap),
            verticalArrangement = Arrangement.spacedBy(GridGap),
        ) {
            itemsIndexed(films, key = { _, item -> item.id }) { index, item ->
                PosterItemTile(
                    item = item,
                    focusKey = "films/" + item.id,
                    pane = pane,
                    width = cell,
                    height = cell * 1.5f,
                    focusRequester = if (index == 0) first else null,
                )
            }
        }
    }
}

private fun gridPadding(): PaddingValues = PaddingValues(
    start = LibraryLayout.Gutter,
    end = LibraryLayout.Gutter,
    top = LibraryLayout.RingRoom,
    bottom = LibraryLayout.TopInset,
)

/** A grid under the top bar on the ambient field; [grid] receives one cell's width. */
@Composable
private fun PosterGrid(focused: MutableState<Boolean>, isEmpty: Boolean, grid: @Composable (Dp) -> Unit) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .libraryAmbient()
            .tracksFocus(focused),
    ) {
        val cell = cellWidth(maxWidth, POSTER_COLUMNS)
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = LibraryLayout.UnderTopBar),
        ) {
            if (isEmpty) {
                EmptyTab()
            } else {
                grid(cell)
            }
        }
    }
}

@Composable
private fun EmptyTab() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = stringResource(R.string.libui_empty_tab), style = FlickType.body(sizeSp = 16), color = FlickColor.OnSurfaceDim)
    }
}

private enum class AllMenu { SORT, FOLDER }

@Composable
internal fun AllVideosPane(catalog: LibraryCatalog, pane: PaneContext) {
    val context = LocalContext.current
    val prefs = remember(context) { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var sort by remember { mutableStateOf(LibrarySortOrder.of(prefs.getString(KEY_SORT, null))) }
    val folders = remember(catalog) { LibraryFolderTree.derive(catalog.items) }
    // A chosen folder the phone no longer has widens back to everything rather than showing nothing.
    val folder = pane.memory.folder?.takeIf { chosen -> folders.any { it.path == chosen } }
    val scoped = remember(catalog, folder) { LibraryFolderTree.scoped(catalog.items, folder) }
    val videos = remember(scoped, sort) { LibrarySorting.sorted(scoped, sort) }
    val chooser = LibraryFolderTree.chooserOffered(folders, folder, catalog.items.size)
    val scope = rememberCoroutineScope()
    val retained = LocalShellRetained.current

    val focused = remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val sortFocus = remember { FocusRequester() }
    val folderFocus = remember { FocusRequester() }
    val sortFocused = remember { mutableStateOf(false) }
    val folderFocused = remember { mutableStateOf(false) }
    LandFocus(pane, if (videos.isNotEmpty()) first else sortFocus, focused)

    var menu by remember { mutableStateOf<AllMenu?>(null) }
    // Where focus goes once a menu has closed: back to the control that opened it, a frame
    // later, when the menu's modal hold has let go and that control can take focus again.
    var returnTo by remember { mutableStateOf<AllMenu?>(null) }
    var returns by remember { mutableIntStateOf(0) }
    LaunchedEffect(returns) {
        if (returns == 0 || retained) return@LaunchedEffect
        when (returnTo) {
            AllMenu.SORT -> landTvFocus(sortFocus, sortFocus) { sortFocused.value }
            AllMenu.FOLDER -> landTvFocus(folderFocus, sortFocus) { folderFocused.value }
            null -> Unit
        }
    }
    fun closeMenu() {
        returnTo = menu
        menu = null
        returns++
    }
    fun restart() {
        pane.memory.focusKey = null
        scope.launch { pane.memory.grid.scrollToItem(0) }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .libraryAmbient()
            .tracksFocus(focused),
    ) {
        val cell = cellWidth(maxWidth, VIDEO_COLUMNS)
        val cellHeight = cell * 9f / 16f
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = LibraryLayout.UnderTopBar),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = LibraryLayout.Gutter),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                if (chooser) {
                    val folderName = folder?.let { path -> folders.firstOrNull { it.path == path }?.name }
                        ?: stringResource(R.string.libui_folder_all)
                    FlickTvButton(
                        onClick = { menu = AllMenu.FOLDER },
                        modifier = Modifier
                            .tracksFocus(folderFocused)
                            .closedToModal(),
                        focusRequester = folderFocus,
                        contentDescription = stringResource(R.string.libui_folder_a11y, folderName),
                        borderColor = FlickColor.GlassBorder,
                        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(imageVector = LibraryIcons.Folder, contentDescription = null, tint = FlickColor.OnSurface, modifier = Modifier.size(15.dp))
                        Text(
                            text = folderName,
                            style = FlickType.body(sizeSp = 14),
                            color = FlickColor.OnSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 220.dp),
                        )
                    }
                }
                val sortName = stringResource(sortLabel(sort))
                FlickTvButton(
                    onClick = { menu = AllMenu.SORT },
                    modifier = Modifier
                        .tracksFocus(sortFocused)
                        .closedToModal(),
                    selected = true,
                    focusRequester = sortFocus,
                    contentDescription = stringResource(R.string.libui_sort_a11y, sortName),
                    contentPadding = PaddingValues(horizontal = 13.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(imageVector = LibraryIcons.Sort, contentDescription = null, tint = FlickColor.SparkLight, modifier = Modifier.size(15.dp))
                    Text(text = sortName, style = FlickType.body(sizeSp = 14), color = FlickColor.SparkLight, maxLines = 1)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = pluralText(R.plurals.libui_video_count, videos.size, formatCount(videos.size)),
                    style = FlickType.monoEyebrow(sizeSp = 14, trackingEm = 0.12f),
                    color = FlickColor.OnSurfaceFaint,
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(VIDEO_COLUMNS),
                state = pane.memory.grid,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 40.dp),
                contentPadding = gridPadding(),
                horizontalArrangement = Arrangement.spacedBy(GridGap),
                verticalArrangement = Arrangement.spacedBy(GridGap),
            ) {
                itemsIndexed(videos, key = { _, item -> item.id }) { index, item ->
                    LandscapeItemTile(
                        item = item,
                        focusKey = "all/" + item.id,
                        pane = pane,
                        title = item.title,
                        meta = itemMeta(item),
                        width = cell,
                        height = cellHeight,
                        focusRequester = if (index == 0) first else null,
                        badges = true,
                    )
                }
            }
        }
    }

    if (menu != null) OverTopBar {
        when (menu) {
            AllMenu.SORT -> SortMenu(
                current = sort,
                onPick = { order ->
                    if (order != sort) {
                        sort = order
                        prefs.edit().putString(KEY_SORT, order.name).apply()
                        restart()
                    }
                    closeMenu()
                },
                onDismiss = { closeMenu() },
            )
            AllMenu.FOLDER -> FolderMenu(
                folders = folders,
                current = folder,
                total = catalog.items.size,
                onPick = { path ->
                    if (path != folder) {
                        pane.memory.folder = path
                        restart()
                    }
                    closeMenu()
                },
                onDismiss = { closeMenu() },
            )
            null -> Unit
        }
    }
}

private fun sortLabel(order: LibrarySortOrder): Int = when (order) {
    LibrarySortOrder.RECENT -> R.string.libui_sort_recent
    LibrarySortOrder.NAME -> R.string.libui_sort_name
    LibrarySortOrder.NAME_REVERSED -> R.string.libui_sort_name_reversed
    LibrarySortOrder.LONGEST -> R.string.libui_sort_longest
    LibrarySortOrder.SHORTEST -> R.string.libui_sort_shortest
}

/** A menu over the whole screen: a scrim, and a panel that alone takes focus while it is open. */
@Composable
private fun ChoiceMenu(
    title: String,
    initial: FocusRequester,
    onDismiss: () -> Unit,
    width: Dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    HoldModal()
    val inside = remember { mutableStateOf(false) }
    val retained = LocalShellRetained.current
    LaunchedEffect(Unit) { if (!retained) landTvFocus(initial, initial) { inside.value } }
    BackHandler(enabled = !retained) { onDismiss() }
    Box(
        Modifier
            .fillMaxSize()
            .background(MenuScrim),
    ) {
        GlassPanel(
            modifier = Modifier
                .padding(start = LibraryLayout.Gutter + 140.dp, top = LibraryLayout.UnderTopBar + 44.dp)
                .width(width)
                .tracksFocus(inside),
            shape = FlickShape.Hero,
            tone = GlassPanelTone.Panel,
            contentPadding = PaddingValues(9.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = title,
                style = FlickType.monoEyebrow(sizeSp = 14, trackingEm = 0.2f),
                color = FlickColor.OnPanelLabel,
                modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 6.dp),
            )
            content()
        }
    }
}

@Composable
private fun SortMenu(current: LibrarySortOrder, onPick: (LibrarySortOrder) -> Unit, onDismiss: () -> Unit) {
    val initial = remember { FocusRequester() }
    ChoiceMenu(stringResource(R.string.libui_sort_title), initial, onDismiss, width = 260.dp) {
        LibrarySortOrder.entries.forEach { order ->
            val on = order == current
            FlickTvRow(
                onClick = { onPick(order) },
                modifier = Modifier.fillMaxWidth(),
                focusRequester = if (on) initial else null,
                selected = on,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
            ) {
                Text(
                    text = stringResource(sortLabel(order)),
                    style = FlickType.body(sizeSp = 15),
                    color = if (on) FlickColor.SparkLight else FlickColor.OnSurface,
                    modifier = Modifier.weight(1f),
                )
                if (on) {
                    Icon(imageVector = LibraryIcons.Check, contentDescription = null, tint = FlickColor.SparkLight, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun FolderMenu(
    folders: List<LibraryFolderNode>,
    current: String?,
    total: Int,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val initial = remember { FocusRequester() }
    // Opened scrolled to the folder being shown, so its row is composed and can take focus.
    val list = remember {
        val at = current?.let { path -> folders.indexOfFirst { it.path == path } }?.takeIf { it >= 0 }?.plus(1) ?: 0
        LazyListState(firstVisibleItemIndex = (at - 2).coerceAtLeast(0))
    }
    ChoiceMenu(stringResource(R.string.libui_folder_title), initial, onDismiss, width = 360.dp) {
        LazyColumn(
            state = list,
            modifier = Modifier.heightIn(max = 330.dp),
            contentPadding = PaddingValues(vertical = LibraryLayout.RingRoom, horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            item(key = "all") {
                FolderRow(
                    name = stringResource(R.string.libui_folder_all),
                    count = total,
                    depth = 0,
                    on = current == null,
                    focusRequester = if (current == null) initial else null,
                    onClick = { onPick(null) },
                )
            }
            listItemsIndexed(folders, key = { _, node -> "f:" + node.path }) { _, node ->
                FolderRow(
                    name = node.name,
                    count = node.count,
                    depth = node.depth + 1,
                    on = node.path == current,
                    focusRequester = if (node.path == current) initial else null,
                    onClick = { onPick(node.path) },
                )
            }
        }
    }
}

@Composable
private fun FolderRow(name: String, count: Int, depth: Int, on: Boolean, focusRequester: FocusRequester?, onClick: () -> Unit) {
    FlickTvRow(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        focusRequester = focusRequester,
        selected = on,
        contentPadding = PaddingValues(start = 12.dp + 14.dp * depth.coerceAtMost(6), end = 12.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Icon(imageVector = LibraryIcons.Folder, contentDescription = null, tint = if (on) FlickColor.SparkLight else FlickColor.OnSurfaceDim, modifier = Modifier.size(15.dp))
        Text(
            text = name,
            style = FlickType.body(sizeSp = 15, weight = if (on) FontWeight.SemiBold else FontWeight.Medium),
            color = if (on) FlickColor.SparkLight else FlickColor.OnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(text = formatCount(count), style = FlickType.monoTabular(sizeSp = 14), color = FlickColor.OnSurfaceFaint)
    }
}
