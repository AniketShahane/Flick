package com.flick.receiver.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.flick.receiver.R
import com.flick.receiver.library.LibraryCatalog
import com.flick.receiver.library.LibraryDetail
import com.flick.receiver.library.LibraryHdr
import com.flick.receiver.library.LibraryImages
import com.flick.receiver.library.LibraryItem
import com.flick.receiver.library.LibraryItemKind
import com.flick.receiver.library.LibraryLink
import com.flick.receiver.library.LibraryPlay
import com.flick.receiver.library.LibraryShelves
import com.flick.receiver.library.LibraryShow
import com.flick.receiver.library.LibraryState
import com.flick.receiver.library.PlayFrom
import com.flick.receiver.ui.components.FocusBeaconHost
import com.flick.receiver.ui.components.LocalShellRetained
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickSpace
import com.flick.receiver.ui.theme.FlickType
import com.flick.receiver.ui.theme.LocalReducedMotion
import kotlinx.coroutines.launch

/** The hero art's box on the 960 × 540 dp canvas: the mockup's 1500 × 844 px, right-aligned. */
private val HeroArtWidth: Dp = 750.dp
private val HeroArtHeight: Dp = 422.dp

/**
 * The hero's copy keeps the mockup's 480 dp measure, where the side wash still darkens the art;
 * only the action row runs wider, so the progress label beside the buttons stays on one line.
 */
private val HeroTextWidth: Dp = 480.dp
private val HeroCopyWidth: Dp = 540.dp

/** Lets the hero copy read over any backdrop: the mockup's two washes, as fractions of the screen. */
internal val HeroSideWash = Brush.horizontalGradient(
    0f to FlickColor.Canvas,
    0.2f to FlickColor.Canvas,
    0.38f to FlickColor.Canvas.copy(alpha = 0.86f),
    0.72f to FlickColor.Canvas.copy(alpha = 0.15f),
    1f to Color.Transparent,
)
internal val HeroFootWash = Brush.verticalGradient(
    0f to FlickColor.Canvas.copy(alpha = 0.7f),
    0.18f to Color.Transparent,
    0.42f to Color.Transparent,
    0.74f to FlickColor.Canvas,
    1f to FlickColor.Canvas,
)

/**
 * Home moves its column itself, a whole item at a time (see [HomePane]); the column's own
 * bring-into-view must not also scroll it, or the two fight over every focus move.
 */
private object HoldStill : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

/**
 * The column's top inset under the top bar. Kept small so that at rest the first row's captions
 * still land on the screen below the hero; nothing focusable sits at the top of an item.
 */
private val HomeTop: Dp = 4.dp

/** The column's trailing room, as a share of its height: more than any row leaves free at the top. */
private const val TRAILING_ROOM = 0.6f

/** How far the hero scrolls before the backdrop behind it has faded fully. */
private val HeroFadeDistance: Dp = 200.dp

/** How much of the backdrop the rows hide once the hero has gone: enough that captions read over any art. */
private const val BACKDROP_HIDDEN = 0.9f

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun HomePane(state: LibraryState, catalog: LibraryCatalog, shelves: LibraryShelves, pane: PaneContext) {
    val ui = LocalLibraryUi.current
    val hero = remember(shelves) { LibraryFormat.heroPick(shelves) }
    val focused = remember { mutableStateOf(false) }
    val heroFocus = remember { FocusRequester() }
    val column = pane.memory.column
    val retained = LocalShellRetained.current
    // Ahead of LandFocus, whose effect runs after this one. A remembered control that has gone
    // (a finished item, a pruned row) cannot be restored, and the hero that landing falls back
    // to is not composed while the column is scrolled past it, so both go back to the top.
    LaunchedEffect(pane.land) {
        if (!pane.land || retained) return@LaunchedEffect
        val key = pane.memory.focusKey
        if (key == null || key !in homeFocusKeys(shelves)) {
            pane.memory.focusKey = null
            column.scrollToItem(0)
        }
    }
    LandFocus(pane, if (hero != null) heroFocus else null, focused)
    val details by ui.hub.details.collectAsState()
    // Keyed on the link too: a want sent while it was down was dropped, and is sent again.
    val connected = state.link == LibraryLink.CONNECTED
    hero?.let { pick -> LaunchedEffect(pick.item.id, connected, state.epoch) { ui.hub.wantDetail(pick.item.id) } }

    val scope = rememberCoroutineScope()
    val reduced = LocalReducedMotion.current
    // The rows' own horizontal scrolling keeps the platform's bring-into-view; only the column holds still.
    val rowSpec = LocalBringIntoViewSpec.current
    val fadePx = with(LocalDensity.current) { HeroFadeDistance.toPx() }

    // The item holding focus goes to the top of the column, so the row in use always starts just
    // under the top bar, and the hero shows only while it has focus.
    fun settle(index: Int) {
        scope.launch { if (reduced) column.scrollToItem(index) else column.animateScrollToItem(index) }
    }
    val settles = { index: Int -> Modifier.onFocusChanged { if (it.hasFocus) settle(index) } }

    Box(
        Modifier
            .fillMaxSize()
            .tracksFocus(focused),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    val gone = if (column.firstVisibleItemIndex > 0) 1f else (column.firstVisibleItemScrollOffset / fadePx).coerceIn(0f, 1f)
                    if (gone > 0f) drawRect(FlickColor.Canvas.copy(alpha = gone * BACKDROP_HIDDEN))
                },
        ) {
            hero?.let { HeroBackdrop(it.item.art?.backdrop, it.item, Modifier.align(Alignment.TopEnd)) }
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        drawRect(HeroSideWash)
                        drawRect(HeroFootWash)
                    },
            )
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides HoldStill) {
            LazyColumn(
                state = column,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = LibraryLayout.UnderTopBar),
                contentPadding = PaddingValues(top = HomeTop),
                verticalArrangement = Arrangement.spacedBy(LibraryLayout.ShelfGap),
            ) {
                var index = 0
                hero?.let { pick ->
                    val at = index++
                    item(key = "hero") {
                        HomeHero(
                            pick = pick,
                            detail = details[pick.item.id],
                            play = state.play,
                            heroFocus = heroFocus,
                            pane = pane,
                            modifier = settles(at),
                        )
                    }
                }
                if (shelves.continueWatching.isNotEmpty()) {
                    val at = index++
                    item(key = "continue") {
                        ShelfRow(stringResource(R.string.libui_row_continue), "continue", shelves.continueWatching, { it.id }, pane, rowSpec, settles(at)) { item, key ->
                            LandscapeItemTile(item, key, pane, title = rowTitle(item), meta = continueMeta(item))
                        }
                    }
                }
                if (shelves.nextEpisodes.isNotEmpty()) {
                    val at = index++
                    item(key = "next") {
                        ShelfRow(stringResource(R.string.libui_row_next), "next", shelves.nextEpisodes, { it.id }, pane, rowSpec, settles(at)) { item, key ->
                            LandscapeItemTile(item, key, pane, title = rowTitle(item), meta = LibraryFormat.episodeLabel(item).orEmpty())
                        }
                    }
                }
                if (shelves.recentlyAdded.isNotEmpty()) {
                    val at = index++
                    item(key = "recent") {
                        ShelfRow(stringResource(R.string.libui_row_recent), "recent", shelves.recentlyAdded, { it.id }, pane, rowSpec, settles(at)) { item, key ->
                            LandscapeItemTile(item, key, pane, title = item.title, meta = itemMeta(item))
                        }
                    }
                }
                if (shelves.shows.isNotEmpty()) {
                    val at = index++
                    item(key = "shows") {
                        ShelfRow(stringResource(R.string.libui_row_shows), "shows", shelves.shows, { it.key }, pane, rowSpec, settles(at)) { show, key ->
                            PosterShowTile(show, key, pane)
                        }
                    }
                }
                if (shelves.films.isNotEmpty()) {
                    val at = index++
                    item(key = "films") {
                        ShelfRow(stringResource(R.string.libui_row_films), "films", shelves.films, { it.id }, pane, rowSpec, settles(at)) { item, key ->
                            PosterItemTile(item, key, pane)
                        }
                    }
                }
                if (shelves.homeVideos.isNotEmpty()) {
                    val at = index++
                    item(key = "homeVideos") {
                        ShelfRow(stringResource(R.string.libui_row_home_videos), "homeVideos", shelves.homeVideos, { it.id }, pane, rowSpec, settles(at)) { item, key ->
                            LandscapeItemTile(item, key, pane, title = item.title, meta = itemMeta(item))
                        }
                    }
                }
                // Room for the last row to reach the top too; without it the column stops short and
                // leaves the row above sliced by the top edge.
                item(key = "end") { Spacer(Modifier.fillParentMaxHeight(TRAILING_ROOM)) }
            }
        }
    }
}

/** Every focus key a Home control can leave in its screen memory, for the current shelves. */
private fun homeFocusKeys(shelves: LibraryShelves): Set<String> = buildSet {
    add("hero/play")
    add("hero/info")
    shelves.continueWatching.forEach { add("continue/" + it.id) }
    shelves.nextEpisodes.forEach { add("next/" + it.id) }
    shelves.recentlyAdded.forEach { add("recent/" + it.id) }
    shelves.shows.forEach { add("shows/" + it.key) }
    shelves.films.forEach { add("films/" + it.id) }
    shelves.homeVideos.forEach { add("homeVideos/" + it.id) }
}

/** A Continue watching or Next episodes title: the show for an episode, the film otherwise. */
private fun rowTitle(item: LibraryItem): String = if (item.isEpisode) item.show ?: item.title else item.title

/** "S2:E4 · 31 MIN LEFT", or just the time left for a film. */
@Composable
private fun continueMeta(item: LibraryItem): String {
    val left = LibraryFormat.minutesLeft(item)?.let { pluralText(R.plurals.libui_minutes_left, it, it) }
    val label = LibraryFormat.episodeLabel(item)
    return when {
        label != null && left != null -> stringResource(R.string.libui_join, label, left)
        else -> left ?: label.orEmpty()
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun <T> ShelfRow(
    title: String,
    rowKey: String,
    entries: List<T>,
    entryKey: (T) -> String,
    pane: PaneContext,
    rowSpec: BringIntoViewSpec,
    modifier: Modifier,
    tile: @Composable (T, String) -> Unit,
) {
    Column(modifier) {
        SectionTitle(title, Modifier.padding(start = LibraryLayout.Gutter))
        CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec) {
            LazyRow(
                state = pane.memory.row(rowKey),
                contentPadding = PaddingValues(start = LibraryLayout.Gutter, end = LibraryLayout.Gutter, top = LibraryLayout.TitleGap),
                horizontalArrangement = Arrangement.spacedBy(LibraryLayout.TileGap),
            ) {
                items(entries, key = entryKey) { entry -> tile(entry, rowKey + "/" + entryKey(entry)) }
            }
        }
    }
}

@Composable
private fun HomeHero(
    pick: HeroPick,
    detail: LibraryDetail?,
    play: LibraryPlay,
    heroFocus: FocusRequester,
    pane: PaneContext,
    modifier: Modifier = Modifier,
) {
    val ui = LocalLibraryUi.current
    val item = pick.item
    Column(
        modifier
            .padding(start = LibraryLayout.Gutter)
            .width(HeroCopyWidth),
    ) {
        val reason = stringResource(if (pick.continuing) R.string.libui_hero_continue else R.string.libui_hero_newest)
        val what = LibraryFormat.episodeLabel(item) ?: stringResource(kindLabel(item.kind))
        Text(
            text = stringResource(R.string.libui_join, reason, what),
            style = FlickType.monoEyebrow(sizeSp = 14, trackingEm = 0.22f),
            color = FlickColor.Spark,
        )
        Spacer(Modifier.height(6.dp))
        HeroTitle(
            title = if (item.isEpisode) item.show ?: item.title else item.title,
            logoPath = item.art?.logo,
            sizeSp = 42,
            maxWidth = 420.dp,
            logoHeight = 60.dp,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(LibraryLayout.ChipGap), verticalAlignment = Alignment.CenterVertically) {
            ItemChips(item, hdr = item.hdr ?: detail?.hdr)
            LibraryFormat.folderName(item.folder)?.let { MetaChip(it.uppercase(), Modifier.weight(1f, fill = false)) }
        }
        // Details exist only for items the phone matched on TMDB; reserving their two lines for
        // those alone keeps the rows from jumping down when the overview arrives.
        val overview = detail?.overview
        if (overview != null || item.art?.tmdbId != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = overview.orEmpty(),
                style = FlickType.body(sizeSp = 14, weight = FontWeight.Medium),
                color = FlickColor.OnSurfaceDim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .width(HeroTextWidth)
                    .heightIn(min = 40.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FlickSpace.Lg)) {
            FocusBeaconHost {
                Row(horizontalArrangement = Arrangement.spacedBy(LibraryLayout.ActionGap)) {
                    PrimaryAction(
                        text = when {
                            LibraryFormat.asking(play, item.id) -> stringResource(R.string.libui_asking)
                            item.resumeMs != null -> stringResource(R.string.libui_resume)
                            else -> stringResource(R.string.libui_play)
                        },
                        onClick = { ui.hub.play(item.id, PlayFrom.RESUME) },
                        modifier = Modifier
                            .remembersFocus(pane.memory, "hero/play")
                            .closedToModal(),
                        focusRequester = heroFocus,
                    )
                    SecondaryAction(
                        text = stringResource(R.string.libui_more_info),
                        onClick = { pane.open(LibraryRoute.Detail(item.id)) },
                        modifier = Modifier
                            .remembersFocus(pane.memory, "hero/info")
                            .closedToModal(),
                    )
                }
            }
            val progress = LibraryFormat.progress(item)
            val left = LibraryFormat.minutesLeft(item)
            if (progress != null && left != null) {
                ProgressLine(progress, pluralText(R.plurals.libui_minutes_left, left, left), barWidth = 120.dp)
            }
        }
    }
}

internal fun kindLabel(kind: LibraryItemKind): Int = when (kind) {
    LibraryItemKind.FILM -> R.string.libui_kind_film
    LibraryItemKind.EPISODE -> R.string.libui_kind_episode
    LibraryItemKind.VIDEO -> R.string.libui_kind_video
}

/**
 * The title over a hero: TMDB's logo when there is one, with the title as text until it has
 * loaded — and for good if it never does — so the hero is never untitled.
 */
@Composable
internal fun HeroTitle(title: String, logoPath: String?, sizeSp: Int, maxWidth: Dp, logoHeight: Dp, maxLines: Int = 2) {
    val ui = LocalLibraryUi.current
    val url = LibraryImages.logo(logoPath)
    if (url == null) {
        Text(
            text = title,
            style = FlickType.display(sizeSp = sizeSp, lineHeightRatio = 1.3f),
            color = FlickColor.OnSurface,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(maxWidth),
        )
        return
    }
    var loaded by remember(url) { mutableStateOf(false) }
    Box(
        Modifier
            .width(maxWidth)
            .height(logoHeight),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (!loaded) {
            Text(
                text = title,
                style = FlickType.display(sizeSp = sizeSp),
                color = FlickColor.OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        RemoteImage(
            url = url,
            imageLoader = ui.imageLoader,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
            onLoaded = { loaded = true },
        )
    }
}

/**
 * The art behind a hero, right-aligned. TMDB's backdrop when there is one; otherwise the
 * phone's own still for an item with no TMDB art at all, and nothing for the rest.
 */
@Composable
internal fun HeroBackdrop(
    backdropPath: String?,
    item: LibraryItem?,
    modifier: Modifier = Modifier,
    width: Dp = HeroArtWidth,
    height: Dp = HeroArtHeight,
) {
    val ui = LocalLibraryUi.current
    val url = LibraryImages.heroBackdrop(backdropPath)
    when {
        url != null -> RemoteImage(url, ui.imageLoader, modifier.size(width, height))
        item != null && item.art == null -> PhoneThumbnail(item.id, title = null, modifier = modifier.size(width, height))
    }
}

/** A 16:9 tile for one item, as on the Home rows. */
@Composable
internal fun LandscapeItemTile(
    item: LibraryItem,
    focusKey: String,
    pane: PaneContext,
    title: String,
    meta: String,
    width: Dp = LibraryLayout.RowTileWidth,
    height: Dp = LibraryLayout.RowTileHeight,
    onClick: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    badges: Boolean = false,
) {
    LibraryTile(
        width = width,
        height = height,
        memory = pane.memory,
        focusKey = focusKey,
        contentDescription = title,
        onClick = onClick ?: { pane.open(LibraryRoute.Detail(item.id)) },
        focusRequester = focusRequester,
        picture = {
            ItemArt(item, ArtKind.LANDSCAPE, Modifier.fillMaxSize())
            if (badges) ItemBadges(item)
            ItemMarks(item)
        },
        caption = {
            TileTitle(title)
            TileMeta(meta)
        },
    )
}

/** Resolution and Dolby Vision top left, running time bottom right — the All videos grid's tile. */
@Composable
internal fun BoxScope.ItemBadges(item: LibraryItem) {
    Row(
        Modifier
            .align(Alignment.TopStart)
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        LibraryFormat.resolution(item.width, item.height)?.let { ArtBadge(resolutionText(it)) }
        if (item.hdr == LibraryHdr.DOLBY_VISION) HdrChip(LibraryHdr.DOLBY_VISION, short = true)
    }
    if (item.durationMs > 0L && item.blocked == null) {
        ArtBadge(
            LibraryFormat.clock(item.durationMs),
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 5.dp, bottom = 7.dp),
        )
    }
}

/** A 2:3 poster tile for a film. */
@Composable
internal fun PosterItemTile(
    item: LibraryItem,
    focusKey: String,
    pane: PaneContext,
    width: Dp = LibraryLayout.PosterWidth,
    height: Dp = LibraryLayout.PosterHeight,
    focusRequester: FocusRequester? = null,
) {
    LibraryTile(
        width = width,
        height = height,
        memory = pane.memory,
        focusKey = focusKey,
        focusRequester = focusRequester,
        contentDescription = item.title,
        onClick = { pane.open(LibraryRoute.Detail(item.id)) },
        picture = {
            ItemArt(item, ArtKind.POSTER, Modifier.fillMaxSize())
            ItemMarks(item, showBlocked = false)
        },
        caption = {
            TileTitle(item.title)
            item.year?.let { TileMeta(it.toString()) }
        },
    )
}

/** A 2:3 poster tile for a show; opens the show page. */
@Composable
internal fun PosterShowTile(
    show: LibraryShow,
    focusKey: String,
    pane: PaneContext,
    width: Dp = LibraryLayout.PosterWidth,
    height: Dp = LibraryLayout.PosterHeight,
    focusRequester: FocusRequester? = null,
) {
    LibraryTile(
        width = width,
        height = height,
        memory = pane.memory,
        focusKey = focusKey,
        focusRequester = focusRequester,
        contentDescription = show.title,
        onClick = { pane.open(LibraryNav.showRoute(show)) },
        picture = { ShowArt(show, ArtKind.POSTER, Modifier.fillMaxSize()) },
        caption = {
            TileTitle(show.title)
            TileMeta(pluralText(R.plurals.libui_seasons, show.seasons.size, show.seasons.size))
        },
    )
}
