package com.flick.receiver.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.flick.receiver.R
import com.flick.receiver.library.LibraryEpisodeInfo
import com.flick.receiver.library.LibraryHdr
import com.flick.receiver.library.LibraryItem
import com.flick.receiver.library.LibraryLink
import com.flick.receiver.library.LibrarySeasonKey
import com.flick.receiver.library.LibraryShelving
import com.flick.receiver.library.LibraryShow
import com.flick.receiver.library.LibraryState
import com.flick.receiver.library.PlayFrom
import com.flick.receiver.ui.components.FlickTvButton
import com.flick.receiver.ui.components.FocusBeaconHost
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickSpace
import com.flick.receiver.ui.theme.FlickType

private val EpisodeWidth: Dp = 172.dp
private val EpisodeHeight: Dp = 97.dp

/** Season tabs to the episodes under them: past a lifted episode's ring, and closer than the header above. */
private val EpisodesTop: Dp = 20.dp

/** Only TMDB-matched shows (`tv:` keys) have season data to ask for (§6.3). */
internal fun hasSeasonData(showKey: String?): Boolean = showKey?.startsWith("tv:") == true

@Composable
internal fun ShowPage(show: LibraryShow, state: LibraryState, pane: PaneContext) {
    val ui = LocalLibraryUi.current
    val pick = remember(show) { LibraryShelving.showPick(show) }
    val season = pane.memory.season?.takeIf { it in show.seasons } ?: pick?.season ?: show.seasons.first()
    val episodes = show.episodes[season].orEmpty()
    val seasons by ui.hub.seasons.collectAsState()
    val details by ui.hub.details.collectAsState()
    // Keyed on the link too: a want sent while it was down was dropped, and is sent again.
    val connected = state.link == LibraryLink.CONNECTED
    LaunchedEffect(show.key, season, connected, state.epoch) { if (hasSeasonData(show.key)) ui.hub.wantSeason(show.key, season) }
    pick?.let { LaunchedEffect(it.id, connected, state.epoch) { ui.hub.wantDetail(it.id) } }
    val info = remember(seasons, show.key, season) {
        seasons[LibrarySeasonKey(show.key, season)]?.associateBy { it.episode }.orEmpty()
    }

    val focused = remember { mutableStateOf(false) }
    val primaryFocus = remember { FocusRequester() }
    LandFocus(pane, if (pick != null) primaryFocus else null, focused)

    Box(
        Modifier
            .fillMaxSize()
            .tracksFocus(focused),
    ) {
        HeroBackdrop(show.art?.backdrop, item = null, modifier = Modifier.align(Alignment.TopEnd), width = 720.dp, height = 405.dp)
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(HeroSideWash)
                    drawRect(HeroFootWash)
                },
        )
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = LibraryLayout.TopInset, bottom = LibraryLayout.TopInset),
        ) {
            Breadcrumb(pane.crumb, Modifier.padding(start = LibraryLayout.Gutter))
            Spacer(Modifier.height(20.dp))
            ShowHeader(show, pick, state, pick?.let { details[it.id] }?.overview, primaryFocus, pane)
            Spacer(Modifier.weight(1f))
            SeasonTabs(show, season, episodes, pane)
            LazyRow(
                state = pane.memory.row("season/$season"),
                contentPadding = PaddingValues(start = LibraryLayout.Gutter, end = LibraryLayout.Gutter, top = EpisodesTop),
                horizontalArrangement = Arrangement.spacedBy(LibraryLayout.TileGap),
            ) {
                items(episodes, key = { it.id }) { episode ->
                    EpisodeTile(episode, info[episode.episode], pane)
                }
            }
        }
    }
}

@Composable
private fun ShowHeader(
    show: LibraryShow,
    pick: LibraryItem?,
    state: LibraryState,
    overview: String?,
    primaryFocus: FocusRequester,
    pane: PaneContext,
) {
    val ui = LocalLibraryUi.current
    Column(
        Modifier
            .padding(start = LibraryLayout.Gutter)
            .width(460.dp),
    ) {
        HeroTitle(show.title, show.art?.logo, sizeSp = 40, maxWidth = 420.dp, logoHeight = 50.dp)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(LibraryLayout.ChipGap), verticalAlignment = Alignment.CenterVertically) {
            MetaChip(
                stringResource(
                    R.string.libui_join,
                    pluralText(R.plurals.libui_seasons, show.seasons.size, show.seasons.size),
                    pluralText(R.plurals.libui_episodes, show.episodeCount, show.episodeCount),
                ),
            )
            val all = remember(show) { show.all }
            if (all.any { it.hdr == LibraryHdr.DOLBY_VISION }) HdrChip(LibraryHdr.DOLBY_VISION)
            if (all.any { LibraryFormat.resolution(it.width, it.height) == LibraryResolution.UHD }) {
                MetaChip(resolutionText(LibraryResolution.UHD))
            }
        }
        if (overview != null || show.art?.tmdbId != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = overview.orEmpty(),
                style = FlickType.body(sizeSp = 14, weight = FontWeight.Medium),
                color = FlickColor.OnSurfaceDim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.heightIn(min = 40.dp),
            )
        }
        if (pick != null) {
            var askedFrom by remember(pick.id) { mutableStateOf(PlayFrom.RESUME) }
            val asking = LibraryFormat.asking(state.play, pick.id)
            val label = LibraryFormat.episodeLabel(pick).orEmpty()
            val resumable = pick.resumeMs != null
            Spacer(Modifier.height(18.dp))
            FocusBeaconHost {
                Row(horizontalArrangement = Arrangement.spacedBy(LibraryLayout.ActionGap)) {
                    PrimaryAction(
                        text = when {
                            asking && askedFrom == PlayFrom.RESUME -> stringResource(R.string.libui_asking)
                            resumable -> LibraryFormat.minutesLeft(pick)?.let { left ->
                                stringResource(R.string.libui_resume_episode, label, pluralText(R.plurals.libui_minutes_left_sentence, left, left))
                            } ?: stringResource(R.string.libui_resume_episode_plain, label)
                            else -> stringResource(R.string.libui_play_episode, label)
                        },
                        onClick = {
                            askedFrom = PlayFrom.RESUME
                            ui.hub.play(pick.id, PlayFrom.RESUME)
                        },
                        modifier = Modifier
                            .remembersFocus(pane.memory, "show/play")
                            .closedToModal(),
                        focusRequester = primaryFocus,
                    )
                    if (resumable) {
                        SecondaryAction(
                            text = if (asking && askedFrom == PlayFrom.START) {
                                stringResource(R.string.libui_asking)
                            } else {
                                stringResource(R.string.libui_start_episode_over, label)
                            },
                            onClick = {
                                askedFrom = PlayFrom.START
                                ui.hub.play(pick.id, PlayFrom.START)
                            },
                            modifier = Modifier
                                .remembersFocus(pane.memory, "show/start")
                                .closedToModal(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SeasonTabs(show: LibraryShow, season: Int, episodes: List<LibraryItem>, pane: PaneContext) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(end = LibraryLayout.Gutter, top = LibraryLayout.RingRoom),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tabs = pane.memory.row("seasons")
        // Opened on a later season, its tab would otherwise start past the screen edge.
        LaunchedEffect(Unit) {
            if (pane.memory.seasonTabsPlaced) return@LaunchedEffect
            pane.memory.seasonTabsPlaced = true
            val index = show.seasons.indexOf(season)
            if (index > 0 && tabs.firstVisibleItemIndex == 0 && tabs.firstVisibleItemScrollOffset == 0) {
                tabs.scrollToItem((index - 1).coerceAtLeast(0))
            }
        }
        // Lazy and scrolling, so a long-running show's tabs past the screen edge keep their width.
        FocusBeaconHost(Modifier.weight(1f, fill = false)) {
            LazyRow(
                state = tabs,
                contentPadding = PaddingValues(start = LibraryLayout.Gutter, end = LibraryLayout.RingRoom),
                horizontalArrangement = Arrangement.spacedBy(LibraryLayout.PillGap),
            ) {
                items(show.seasons, key = { it }) { number ->
                    val on = number == season
                    FlickTvButton(
                        onClick = { pane.memory.season = number },
                        modifier = Modifier
                            .remembersFocus(pane.memory, "season/$number")
                            .closedToModal(),
                        selected = on,
                        containerColor = if (on) FlickColor.OnSurface else Color.Transparent,
                        borderColor = if (on) Color.Transparent else FlickColor.Outline,
                        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 5.dp),
                    ) {
                        Text(
                            text = if (number == 0) stringResource(R.string.libui_specials) else stringResource(R.string.libui_season, number),
                            style = FlickType.body(sizeSp = 14),
                            color = if (on) FlickColor.Canvas else FlickColor.OnSurfaceSoft,
                        )
                    }
                }
            }
        }
        Text(
            text = stringResource(
                R.string.libui_join,
                pluralText(R.plurals.libui_episodes, episodes.size, episodes.size),
                episodes.count { it.watched }.let { pluralText(R.plurals.libui_watched_count, it, it) },
            ),
            style = FlickType.monoEyebrow(sizeSp = 14, trackingEm = 0.12f),
            color = FlickColor.OnSurfaceFaint,
            modifier = Modifier.padding(start = FlickSpace.Md),
        )
    }
}

@Composable
private fun EpisodeTile(episode: LibraryItem, info: LibraryEpisodeInfo?, pane: PaneContext) {
    val number = episode.episode ?: 0
    var meta = stringResource(R.string.libui_episode_short, number)
    LibraryFormat.runtimeMinutes(episode.durationMs)?.let { meta = stringResource(R.string.libui_join, meta, pluralText(R.plurals.libui_minutes, it, it)) }
    LibraryFormat.minutesLeft(episode)?.let { meta = stringResource(R.string.libui_join, meta, pluralText(R.plurals.libui_minutes_left_short, it, it)) }
    val name = info?.name ?: episode.title
    LibraryTile(
        width = EpisodeWidth,
        height = EpisodeHeight,
        memory = pane.memory,
        focusKey = "ep/" + episode.id,
        contentDescription = name,
        onClick = { pane.open(LibraryRoute.Detail(episode.id)) },
        picture = {
            ItemArt(episode, ArtKind.LANDSCAPE, Modifier.fillMaxSize(), stillPath = info?.still, title = name)
            ItemMarks(episode)
        },
        caption = {
            TileMeta(meta)
            TileTitle(name)
            info?.overview?.let {
                Text(
                    text = it,
                    style = FlickType.body(sizeSp = 14, weight = FontWeight.Medium),
                    color = FlickColor.OnSurfaceDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
    )
}
