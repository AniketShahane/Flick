package com.flick.receiver.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.flick.receiver.R
import com.flick.receiver.library.LibraryCatalog
import com.flick.receiver.library.LibraryShelves
import com.flick.receiver.library.LibraryState
import com.flick.receiver.library.PlayFrom
import com.flick.receiver.ui.components.FlickTvButton
import com.flick.receiver.ui.components.FocusBeaconHost
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickDimens
import com.flick.receiver.ui.theme.FlickShape
import com.flick.receiver.ui.theme.FlickSpace
import com.flick.receiver.ui.theme.FlickType

private val AsideWidth: Dp = 300.dp
/** A key's ring stays off its neighbours. */
private val KeyGap: Dp = 8.dp
private val KeyHeight: Dp = 36.dp
private val KeyWidth: Dp = (AsideWidth - KeyGap * (LibrarySearchModel.KEY_COLUMNS - 1)) / LibrarySearchModel.KEY_COLUMNS
private val ResultsStart: Dp = 400.dp
private const val RESULT_COLUMNS = 3

/** The query field's fill, the mockup's `rgba(9,17,42,.85)`. */
private val FieldFill = Color(0xD909112A)

/**
 * Search the phone's library with the remote: an on-screen keyboard on the left, results on
 * the right that update on every key. Down from the keyboard's bottom row jumps into the
 * results; OK on a video asks the phone to play it, OK on a show opens it.
 */
@Composable
internal fun SearchPane(catalog: LibraryCatalog, shelves: LibraryShelves, state: LibraryState, pane: PaneContext) {
    val ui = LocalLibraryUi.current
    val memory = pane.memory
    val query = memory.query
    val hits = remember(catalog, shelves, query) { LibrarySearchModel.hits(catalog.items, shelves.shows, query) }
    val focused = remember { mutableStateOf(false) }
    val firstKey = remember { FocusRequester() }
    val firstResult = remember { FocusRequester() }
    // The first result is only composed, and so only focusable, while the grid is at its top.
    val resultsAtTop by remember(memory) { derivedStateOf { memory.grid.firstVisibleItemIndex == 0 } }
    LandFocus(pane, firstKey, focused)

    // A new query starts its results from the top; coming back to the same query does not.
    val shownQuery = remember { arrayOf(query) }
    LaunchedEffect(query) {
        if (shownQuery[0] != query) {
            shownQuery[0] = query
            memory.focusKey = memory.focusKey?.takeIf { it.startsWith("key/") }
            memory.grid.scrollToItem(0)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .libraryAmbient()
            .tracksFocus(focused),
    ) {
        Column(
            Modifier
                .padding(start = LibraryLayout.Gutter, top = LibraryLayout.TopInset)
                .width(AsideWidth),
            verticalArrangement = Arrangement.spacedBy(FlickSpace.Md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FlickSpace.Xs)) {
                Icon(imageVector = LibraryIcons.Back, contentDescription = null, tint = FlickColor.OnSurfaceFaint, modifier = Modifier.size(14.dp))
                Text(
                    text = stringResource(R.string.libui_search_eyebrow),
                    style = FlickType.monoEyebrow(sizeSp = 14, trackingEm = 0.16f),
                    color = FlickColor.OnSurfaceFaint,
                )
            }
            QueryField(query)
            Keyboard(
                memory = memory,
                firstKey = firstKey,
                down = when {
                    hits.isEmpty() -> null
                    resultsAtTop -> firstResult
                    else -> FocusRequester.Default
                },
                onType = { memory.query = LibrarySearchModel.typed(memory.query, it) },
                onDelete = { memory.query = LibrarySearchModel.deleted(memory.query) },
                onClear = { memory.query = "" },
            )
        }

        Column(
            Modifier
                .padding(start = ResultsStart, top = LibraryLayout.TopInset, end = LibraryLayout.Gutter)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(FlickSpace.Sm),
        ) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(
                    text = if (query.isBlank()) stringResource(R.string.libui_search) else pluralText(R.plurals.libui_results, hits.size, hits.size),
                    style = FlickType.display(sizeSp = 17, weight = FontWeight.Bold),
                    color = FlickColor.OnSurface,
                )
                Text(
                    text = stringResource(R.string.libui_search_rule),
                    style = FlickType.body(sizeSp = 14, weight = FontWeight.Medium),
                    color = FlickColor.OnSurfaceFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when {
                query.isBlank() -> SearchNote(
                    stringResource(R.string.libui_search_prompt, pane.phoneName) + "\n" + stringResource(R.string.libui_search_help),
                )
                hits.isEmpty() -> SearchNote(stringResource(R.string.libui_search_none, pane.phoneName))
                else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                    val width = (maxWidth - LibraryLayout.RingRoom * 2 - LibraryLayout.TileGap * (RESULT_COLUMNS - 1)) / RESULT_COLUMNS
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(RESULT_COLUMNS),
                        state = memory.grid,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = LibraryLayout.RingRoom,
                            bottom = LibraryLayout.TopInset,
                            start = LibraryLayout.RingRoom,
                            end = LibraryLayout.RingRoom,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(LibraryLayout.TileGap),
                        verticalArrangement = Arrangement.spacedBy(LibraryLayout.GridRowGap),
                    ) {
                        itemsIndexed(hits, key = { _, hit -> hit.key }) { index, hit ->
                            val requester = if (index == 0) firstResult else null
                            when (hit) {
                                is SearchHit.Video -> LandscapeItemTile(
                                    item = hit.item,
                                    focusKey = "hit/" + hit.key,
                                    pane = pane,
                                    title = hit.item.title,
                                    meta = itemMeta(hit.item),
                                    width = width,
                                    height = width * 9f / 16f,
                                    onClick = { ui.hub.play(hit.item.id, PlayFrom.RESUME) },
                                    focusRequester = requester,
                                )
                                is SearchHit.Show -> LibraryTile(
                                    width = width,
                                    height = width * 9f / 16f,
                                    memory = memory,
                                    focusKey = "hit/" + hit.key,
                                    contentDescription = hit.show.title,
                                    onClick = { pane.open(LibraryNav.showRoute(hit.show)) },
                                    focusRequester = requester,
                                    picture = { ShowArt(hit.show, ArtKind.LANDSCAPE, Modifier.fillMaxSize()) },
                                    caption = {
                                        TileTitle(hit.show.title)
                                        TileMeta(
                                            stringResource(
                                                R.string.libui_show_meta,
                                                pluralText(R.plurals.libui_seasons, hit.show.seasons.size, hit.show.seasons.size),
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueryField(query: String) {
    val spoken = stringResource(R.string.libui_search_query_a11y, query)
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(FieldFill, FlickShape.Lg)
            .border(FlickDimens.Hairline, FlickColor.GlassBorderCool, FlickShape.Lg)
            .padding(horizontal = 14.dp)
            .semantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlickSpace.Sm),
    ) {
        Icon(imageVector = LibraryIcons.Search, contentDescription = null, tint = FlickColor.OnSurfaceFaint, modifier = Modifier.size(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = query,
                style = FlickType.body(sizeSp = 20),
                color = FlickColor.OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            // A still caret: a blinking one would be the only animation on the page, forever.
            Box(
                Modifier
                    .padding(start = 1.dp)
                    .size(width = 1.5.dp, height = 22.dp)
                    .background(FlickColor.Spark),
            )
        }
    }
}

@Composable
private fun Keyboard(
    memory: ScreenMemory,
    firstKey: FocusRequester,
    down: FocusRequester?,
    onType: (Char) -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
) {
    val rows = remember { LibrarySearchModel.KEYS.toList().chunked(LibrarySearchModel.KEY_COLUMNS) }
    FocusBeaconHost {
        Column(verticalArrangement = Arrangement.spacedBy(KeyGap)) {
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
                    row.forEach { key ->
                        KeyButton(
                            label = key.toString(),
                            width = KeyWidth,
                            focusKey = "key/$key",
                            memory = memory,
                            focusRequester = if (key == LibrarySearchModel.KEYS.first()) firstKey else null,
                            onClick = { onType(key) },
                        )
                    }
                }
            }
            val wide = (AsideWidth - KeyGap * 2) / 3
            Row(
                Modifier.padding(top = KeyGap),
                horizontalArrangement = Arrangement.spacedBy(KeyGap),
            ) {
                KeyButton(stringResource(R.string.libui_key_space), wide, "key/space", memory, down = down, onClick = { onType(' ') })
                KeyButton(stringResource(R.string.libui_key_delete), wide, "key/delete", memory, down = down, onClick = onDelete)
                KeyButton(stringResource(R.string.libui_key_clear), wide, "key/clear", memory, down = down, onClick = onClear)
            }
        }
    }
}

@Composable
private fun KeyButton(
    label: String,
    width: Dp,
    focusKey: String,
    memory: ScreenMemory,
    focusRequester: FocusRequester? = null,
    down: FocusRequester? = null,
    onClick: () -> Unit,
) {
    FlickTvButton(
        onClick = onClick,
        modifier = Modifier
            .remembersFocus(memory, focusKey)
            .closedToModal()
            .then(if (down != null) Modifier.focusProperties { this.down = down } else Modifier)
            .size(width, KeyHeight),
        focusRequester = focusRequester,
        shape = FlickShape.Md,
        borderColor = Color.Transparent,
        contentPadding = PaddingValues(0.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            style = FlickType.body(sizeSp = if (label.length == 1) 16 else 14),
            color = FlickColor.OnChrome,
            maxLines = 1,
        )
    }
}

@Composable
private fun SearchNote(text: String) {
    Box(
        Modifier
            .padding(top = FlickSpace.Xl)
            .width(440.dp)
            .background(FlickColor.Surface, FlickShape.Lg)
            .border(FlickDimens.Hairline, FlickColor.GlassBorderCool, FlickShape.Lg)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(text = text, style = FlickType.body(sizeSp = 15, weight = FontWeight.Medium), color = FlickColor.OnSurfaceDim)
    }
}
