package com.flick.receiver.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.flick.receiver.R
import com.flick.receiver.library.LibraryItem
import com.flick.receiver.library.LibrarySeasonKey
import com.flick.receiver.library.LibraryState
import com.flick.receiver.library.PlayFrom
import com.flick.receiver.ui.components.FocusBeaconHost
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickSpace
import com.flick.receiver.ui.theme.FlickType

/** The detail page's washes: the art fills the right two thirds and full height, as in the mockup. */
private val DetailSideWash = Brush.horizontalGradient(
    0f to FlickColor.Canvas,
    0.24f to FlickColor.Canvas,
    0.42f to FlickColor.Canvas.copy(alpha = 0.86f),
    0.8f to FlickColor.Canvas.copy(alpha = 0.05f),
    1f to Color.Transparent,
)
private val DetailFootWash = Brush.verticalGradient(
    0f to Color.Transparent,
    0.55f to Color.Transparent,
    1f to FlickColor.Canvas.copy(alpha = 0.9f),
)

/** A film, home video or episode: its art, what the phone knows about it, and Play. */
@Composable
internal fun DetailPage(item: LibraryItem, state: LibraryState, pane: PaneContext) {
    val ui = LocalLibraryUi.current
    val details by ui.hub.details.collectAsState()
    val seasons by ui.hub.seasons.collectAsState()
    LaunchedEffect(item.id) { ui.hub.wantDetail(item.id) }
    val season = item.season
    if (item.isEpisode && hasSeasonData(item.showKey) && season != null) {
        LaunchedEffect(item.showKey, season) { ui.hub.wantSeason(item.showKey!!, season) }
    }
    val episodeInfo = if (item.isEpisode && season != null) {
        seasons[LibrarySeasonKey(item.showKey!!, season)]?.firstOrNull { it.episode == item.episode }
    } else {
        null
    }
    val detail = details[item.id]

    val focused = remember { mutableStateOf(false) }
    val primaryFocus = remember { FocusRequester() }
    LandFocus(pane, primaryFocus, focused)

    Box(
        Modifier
            .fillMaxSize()
            .tracksFocus(focused),
    ) {
        HeroBackdrop(
            backdropPath = item.art?.backdrop ?: episodeInfo?.still,
            item = item,
            modifier = Modifier.align(Alignment.TopEnd),
            width = 750.dp,
            height = 540.dp,
        )
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(DetailSideWash)
                    drawRect(DetailFootWash)
                },
        )
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = LibraryLayout.TopInset),
        ) {
            Breadcrumb(pane.crumb, Modifier.padding(start = LibraryLayout.Gutter))
            Spacer(Modifier.height(40.dp))
            Column(
                Modifier
                    .padding(start = LibraryLayout.Gutter)
                    .width(470.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val label = LibraryFormat.episodeLabel(item)
                if (item.isEpisode && label != null) {
                    Text(
                        text = stringResource(R.string.libui_join, (item.show ?: item.title).uppercase(), label),
                        style = FlickType.monoEyebrow(sizeSp = 14, trackingEm = 0.2f),
                        color = FlickColor.Spark,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                HeroTitle(
                    title = episodeInfo?.name ?: item.title,
                    logoPath = if (item.isEpisode) null else item.art?.logo,
                    sizeSp = if (item.isEpisode) 36 else 46,
                    maxWidth = 450.dp,
                    logoHeight = 66.dp,
                )
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    ItemChips(item, hdr = item.hdr ?: detail?.hdr, dimensions = true)
                    val subtitle = detail?.subtitle
                    val block = item.blocked
                    if (subtitle != null || block != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                            subtitle?.let { MetaChip(stringResource(R.string.libui_subs_from_phone, it.uppercase())) }
                            block?.let { BlockedBadge(it) }
                        }
                    }
                }
                detail?.tagline?.let {
                    Text(
                        text = it,
                        style = FlickType.body(sizeSp = 15),
                        color = FlickColor.OnSurfaceSoft,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                (episodeInfo?.overview ?: detail?.overview)?.let {
                    Text(
                        text = it,
                        style = FlickType.body(sizeSp = 14, weight = FontWeight.Medium),
                        color = FlickColor.OnSurfaceDim,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val resume = item.resumeMs
                val progress = LibraryFormat.progress(item)
                if (resume != null && progress != null) {
                    ProgressLine(
                        progress,
                        stringResource(R.string.libui_progress_of, LibraryFormat.clock(resume), LibraryFormat.clock(item.durationMs)),
                    )
                }
                DetailActions(item, state, primaryFocus, pane)
            }
        }
    }
}

@Composable
private fun DetailActions(item: LibraryItem, state: LibraryState, primaryFocus: FocusRequester, pane: PaneContext) {
    val ui = LocalLibraryUi.current
    var askedFrom by remember(item.id) { mutableStateOf(PlayFrom.RESUME) }
    val asking = LibraryFormat.asking(state.play, item.id)
    val resumable = item.resumeMs != null
    // Without a resume point the one button plays from the start; with one, Resume leads.
    val primaryFrom = if (resumable) PlayFrom.RESUME else PlayFrom.START
    FocusBeaconHost(Modifier.padding(top = FlickSpace.Xs)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryAction(
                text = when {
                    asking && askedFrom == primaryFrom -> stringResource(R.string.libui_asking)
                    resumable -> stringResource(R.string.libui_resume)
                    else -> stringResource(R.string.libui_play)
                },
                onClick = {
                    askedFrom = primaryFrom
                    ui.hub.play(item.id, primaryFrom)
                },
                modifier = Modifier
                    .remembersFocus(pane.memory, "detail/play")
                    .closedToModal(),
                focusRequester = primaryFocus,
            )
            if (resumable) {
                SecondaryAction(
                    text = if (asking && askedFrom == PlayFrom.START) {
                        stringResource(R.string.libui_asking)
                    } else {
                        stringResource(R.string.libui_play_from_start)
                    },
                    onClick = {
                        askedFrom = PlayFrom.START
                        ui.hub.play(item.id, PlayFrom.START)
                    },
                    modifier = Modifier
                        .remembersFocus(pane.memory, "detail/start")
                        .closedToModal(),
                )
            }
            val showKey = item.showKey
            // Not when this episode was opened from its own show: Back already goes there.
            if (item.isEpisode && showKey != null && pane.under != LibraryRoute.Show(showKey)) {
                SecondaryAction(
                    text = stringResource(R.string.libui_open_show),
                    onClick = { pane.open(LibraryRoute.Show(showKey)) },
                    modifier = Modifier
                        .remembersFocus(pane.memory, "detail/show")
                        .closedToModal(),
                )
            }
        }
    }
}
