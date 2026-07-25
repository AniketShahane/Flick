package com.flick.sender.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import com.flick.sender.R
import com.flick.sender.media.MediaAccess
import com.flick.sender.media.MediaLibraryAction
import com.flick.sender.media.MediaLibraryActionPolicy
import com.flick.sender.media.MediaProbe
import com.flick.sender.model.HdrType
import com.flick.sender.model.MediaItem
import com.flick.sender.model.PlaybackPhase
import com.flick.sender.net.FlickController
import com.flick.sender.net.PairedTv
import com.flick.sender.ui.Format
import com.flick.sender.ui.components.FlickMark
import com.flick.sender.ui.components.FlickPrimaryButton
import com.flick.sender.ui.components.LiveDot
import com.flick.sender.ui.components.VideoTile
import com.flick.sender.ui.components.rememberVideoImageLoader
import com.flick.sender.ui.theme.FlickCorners
import com.flick.sender.ui.theme.FlickIcons
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.PillShape
import com.flick.sender.ui.theme.PrimaryShadow
import com.flick.sender.ui.theme.flickRipple
import com.flick.sender.ui.theme.pressScale
import com.flick.sender.ui.theme.rememberFlickTouchHaptics
import java.util.concurrent.ConcurrentHashMap

/** Room the floating nav needs at the foot of the scroll (design §5.4). */
private val NavClearance = 116.dp

/**
 * One snapshot cell per media id. A `SnapshotStateMap` records every read against the
 * map as a whole, so a single probe result would invalidate the screen and every
 * visible tile; a per-id cell keeps a resolved dynamic range local to the one tile
 * that asked for it. Whichever side resolves a file first publishes it here, so the
 * chip sweep mostly hits MediaProbe's own memo cache.
 */
@Stable
private class HdrCells {
    // Concurrent because the chip sweep runs in a coroutine while the grid composes.
    private val cells = ConcurrentHashMap<Long, MutableState<HdrType?>>()
    fun cell(id: Long): MutableState<HdrType?> = cells.computeIfAbsent(id) { mutableStateOf(null) }
}

/** S3 — the library. A gallery, not a file browser: real MediaStore videos. */
@Composable
fun LibraryScreen(
    controller: FlickController,
    onRequestVideoPermission: () -> Unit,
) {
    val colors = LocalFlickColors.current
    val context = LocalContext.current
    val items by controller.mediaItems.collectAsState()
    val loading by controller.libraryLoading.collectAsState()
    val mediaAccess by controller.mediaAccess.collectAsState()
    val connectedTv by controller.connectedTv.collectAsState()
    val castingItem by controller.castingItem.collectAsState()
    val imageLoader = rememberVideoImageLoader()
    // State, not a value: the 2 s telemetry poll must stop at the pill that shows it
    // rather than rebuilding the whole grid.
    val signal = rememberSignalState()
    val on24GHz by remember(signal) { derivedStateOf { signal.value.on24GHz } }
    val compactTiles = isCompactHeight(LocalConfiguration.current.screenHeightDp)
    val mediaAction = MediaLibraryActionPolicy.forAccess(mediaAccess)

    var filter by remember { mutableStateOf(LibFilter.ALL) }
    val hdrCells = remember { HdrCells() }
    var scanningDv by remember { mutableStateOf(false) }

    // Dolby Vision is not in MediaStore — it needs a container parse per file, so the
    // sweep only runs while that chip is selected, and sequentially: MediaProbe's
    // dispatcher is two wide and shared with Coil's frame decoders.
    LaunchedEffect(filter, items) {
        if (filter != LibFilter.DOLBY_VISION) {
            scanningDv = false
            return@LaunchedEffect
        }
        scanningDv = true
        items.forEach { item ->
            val cell = hdrCells.cell(item.id)
            if (cell.value == null) cell.value = MediaProbe.detectHdr(context, item.uri)
        }
        scanningDv = false
    }

    if (mediaAccess == MediaAccess.NONE || (items.isEmpty() && !loading)) {
        EmptyState(
            controller = controller,
            connectedTv = connectedTv,
            castingItem = castingItem,
            signal = signal,
            onChoose = onRequestVideoPermission,
        )
        return
    }

    // Derived so the Dolby Vision sweep only invalidates the grid when the visible set
    // actually changes, and so the other two axes never subscribe to a probe at all.
    val filtered by remember(items, filter, hdrCells) {
        derivedStateOf(structuralEqualityPolicy()) {
            LibraryFilterPolicy.apply(
                items = items,
                filter = filter,
                resolutionLabel = { it.resolutionLabel },
                isDolbyVision = { hdrCells.cell(it.id).value == HdrType.DOLBY_VISION },
            )
        }
    }

    LazyVerticalGrid(
        // Adaptive rather than a fixed pair: rotation and foldables widen the row, and
        // a fixed two-column split would stretch each 16:9 still into a letterbox sliver.
        columns = GridCells.Adaptive(minSize = TileMinWidth),
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas)
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = NavClearance),
        horizontalArrangement = Arrangement.spacedBy(13.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        fullWidth {
            Header(
                mediaAction = mediaAction,
                onMediaAction = {
                    when (mediaAction) {
                        MediaLibraryAction.SELECT_MORE -> onRequestVideoPermission()
                        MediaLibraryAction.REFRESH -> controller.refreshMediaLibrary()
                        MediaLibraryAction.HIDDEN -> Unit
                    }
                },
                onTune = { controller.toggleAdvisories(true) },
            )
        }
        fullWidth {
            LinkPill(
                controller = controller,
                connectedTv = connectedTv,
                castingItem = castingItem,
                signal = signal,
            )
        }
        fullWidth {
            FilterChips(
                filter = filter,
                totalCount = items.size,
                onSelect = { filter = it },
            )
        }
        if (on24GHz) {
            fullWidth { BandAdvisory(onClick = { controller.toggleAdvisories(true) }) }
        }
        when {
            loading -> fullWidth { Note(stringResource(R.string.library_loading)) }
            filter == LibFilter.DOLBY_VISION && scanningDv && filtered.isEmpty() ->
                fullWidth { Note(stringResource(R.string.library_scanning_dv)) }
            filtered.isEmpty() -> fullWidth { FilterEmpty(filter) }
        }
        items(filtered, key = { it.id }) { item ->
            LibraryTile(
                item = item,
                imageLoader = imageLoader,
                compact = compactTiles,
                cell = hdrCells.cell(item.id),
                onClick = { controller.openDetail(item) },
            )
        }
    }
}

/** Section blocks span both columns and carry the extra 5 dp that widens the 13 dp grid gap. */
private fun LazyGridScope.fullWidth(
    content: @Composable () -> Unit,
) = item(span = { GridItemSpan(maxLineSpan) }) {
    Box(Modifier.padding(bottom = 5.dp)) { content() }
}

@Composable
private fun Header(
    mediaAction: MediaLibraryAction,
    onMediaAction: () -> Unit,
    onTune: () -> Unit,
) {
    val colors = LocalFlickColors.current
    val tuneLabel = stringResource(R.string.a11y_library_tune)
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FlickMark(modifier = Modifier.size(42.dp))
        Text(
            text = stringResource(R.string.app_name),
            style = FlickText.displayLarge.copy(color = colors.onSurface),
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (mediaAction != MediaLibraryAction.HIDDEN) {
            val label = stringResource(
                if (mediaAction == MediaLibraryAction.SELECT_MORE) {
                    R.string.library_add_videos
                } else {
                    R.string.library_refresh_videos
                },
            )
            val interaction = remember { MutableInteractionSource() }
            Text(
                text = label,
                style = FlickText.labelMedium.copy(color = colors.onPrimaryContainer),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .pressScale(interaction)
                    .clip(PillShape)
                    .background(colors.primaryContainer)
                    .clickable(
                        interactionSource = interaction,
                        indication = flickRipple(colors.primary),
                        onClick = onMediaAction,
                    )
                    .semantics { role = Role.Button }
                    .padding(horizontal = 15.dp, vertical = 15.dp),
            )
        }
        val tuneInteraction = remember { MutableInteractionSource() }
        Box(
            Modifier
                .size(48.dp)
                .pressScale(tuneInteraction)
                .clip(RoundedCornerShape(FlickCorners.tuneBtn))
                .background(colors.inverseSurface)
                .clickable(
                    interactionSource = tuneInteraction,
                    indication = flickRipple(colors.onInverseSurface),
                    onClick = onTune,
                )
                .semantics {
                    role = Role.Button
                    contentDescription = tuneLabel
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                FlickIcons.Tune,
                contentDescription = null,
                tint = colors.onInverseSurface,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * One slot, three honest states: a cast in flight, paired but idle, nothing paired.
 * The wording follows the TV's own reported phase — `castingItem` stays set through
 * pause and end — and the throughput number only replaces the band once the server is
 * actually writing bytes.
 */
@Composable
private fun LinkPill(
    controller: FlickController,
    connectedTv: PairedTv?,
    castingItem: MediaItem?,
    signal: State<SignalInfo>,
) {
    val colors = LocalFlickColors.current
    when {
        connectedTv != null && castingItem != null -> {
            // Kept as State so the 10 Hz session clock stops at this pill instead of
            // invalidating the grid behind it.
            val playback = controller.playback.collectAsState()
            val phase by remember(playback) { derivedStateOf { playback.value.phase } }
            val status = stringResource(castPillLabel(phase), connectedTv.name)
            val restoreLabel = stringResource(R.string.a11y_restore_now_playing, castingItem.name)
            val interaction = remember { MutableInteractionSource() }
            Pill(
                container = colors.primary,
                modifier = Modifier
                    .pressScale(interaction)
                    // Clipped here too: Pill's own clip sits below this touch node, so
                    // it cannot bound the ripple.
                    .clip(PillShape)
                    .clickable(
                        interactionSource = interaction,
                        indication = flickRipple(colors.onPrimary),
                        onClick = { controller.restoreNowPlaying() },
                    )
                    // The merged description replaces the visible copy, so the state the
                    // dot animation carries is spoken separately.
                    .semantics(mergeDescendants = true) {
                        role = Role.Button
                        contentDescription = restoreLabel
                        stateDescription = status
                    },
            ) {
                LiveDot(
                    color = colors.sparkLight,
                    size = 10.dp,
                    pulsing = phase == PlaybackPhase.PLAYING,
                )
                Text(
                    text = status,
                    style = FlickText.labelMedium.copy(color = colors.onPrimary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                PillTelemetry(signal)
            }
        }

        connectedTv != null -> Pill(container = colors.primary) {
            LiveDot(color = colors.sparkLight, size = 10.dp)
            Text(
                text = stringResource(R.string.library_ready_pill, connectedTv.name),
                style = FlickText.labelMedium.copy(color = colors.onPrimary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            PillTelemetry(signal)
        }

        else -> {
            val connectLabel = stringResource(R.string.a11y_open_connect)
            val interaction = remember { MutableInteractionSource() }
            Pill(
                container = colors.primaryContainer,
                modifier = Modifier
                    .pressScale(interaction)
                    // Clipped here too: Pill's own clip sits below this touch node, so
                    // it cannot bound the ripple.
                    .clip(PillShape)
                    .clickable(
                        interactionSource = interaction,
                        indication = flickRipple(colors.primary),
                        onClick = { controller.openConnect() },
                    )
                    .semantics(mergeDescendants = true) {
                        role = Role.Button
                        contentDescription = connectLabel
                    },
            ) {
                Icon(
                    FlickIcons.Cast,
                    contentDescription = null,
                    tint = colors.onPrimaryContainer,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.empty_no_tv),
                    style = FlickText.labelMedium.copy(color = colors.onPrimaryContainer),
                )
            }
        }
    }
}

/**
 * TransferTelemetry only counts bytes this phone's server actually wrote, so a zero is
 * "nothing is moving", never a measured rate. The band is the honest stand-in.
 */
@Composable
private fun RowScope.PillTelemetry(signal: State<SignalInfo>) {
    val colors = LocalFlickColors.current
    val live = signal.value
    Text(
        text = if (live.serving) Format.megabits(live.throughputBitsPerSec) else live.bandLabel(),
        style = FlickText.monoSmall.copy(color = colors.onPrimaryMuted),
    )
}

/** The TV's phase, not the presence of a cast record: it survives pause and end. */
private fun castPillLabel(phase: PlaybackPhase): Int = when (phase) {
    PlaybackPhase.PLAYING -> R.string.library_live_pill
    PlaybackPhase.BUFFERING -> R.string.library_buffering_pill
    PlaybackPhase.PAUSED -> R.string.library_paused_pill
    PlaybackPhase.IDLE, PlaybackPhase.ENDED, PlaybackPhase.ERROR -> R.string.library_ready_pill
}

@Composable
private fun Pill(
    container: Color,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(PillShape)
            .background(container)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterChips(filter: LibFilter, totalCount: Int, onSelect: (LibFilter) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Only "All" carries a count: a Dolby Vision tally would need the whole library
        // probed before the chip could render, and a 4K count would imply that precision.
        Chip(stringResource(R.string.library_filter_all, totalCount), filter == LibFilter.ALL) {
            onSelect(LibFilter.ALL)
        }
        Chip(stringResource(R.string.library_filter_dolby_vision), filter == LibFilter.DOLBY_VISION) {
            onSelect(LibFilter.DOLBY_VISION)
        }
        Chip(stringResource(R.string.library_filter_4k), filter == LibFilter.FOUR_K) {
            onSelect(LibFilter.FOUR_K)
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalFlickColors.current
    val haptics = rememberFlickTouchHaptics()
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .pressScale(interaction)
            .clip(PillShape)
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onClick = {
                    // The three chips are one exclusive axis, so re-tapping the current
                    // one changes nothing and must stay silent.
                    if (!selected) haptics.toggle(true)
                    onClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = FlickText.labelMedium.copy(
                color = if (selected) colors.onInverseSurface else colors.onPrimaryContainer,
            ),
            modifier = Modifier
                .clip(PillShape)
                .background(if (selected) colors.inverseSurface else colors.primaryContainer)
                // The ripple is drawn on the chip rather than on the touch node above:
                // the 48 dp target is taller than the pill, so a state layer sized to
                // the target would halo it.
                .indication(
                    interactionSource = interaction,
                    indication = flickRipple(
                        if (selected) colors.onInverseSurface else colors.primary,
                    ),
                )
                .padding(horizontal = 18.dp, vertical = 11.dp),
        )
    }
}

@Composable
private fun BandAdvisory(onClick: () -> Unit) {
    val colors = LocalFlickColors.current
    val interaction = remember { MutableInteractionSource() }
    val label = stringResource(R.string.a11y_library_band_advisory)
    val copy = buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold)) {
            append(stringResource(R.string.advisory_band_title))
        }
        append(" ")
        append(stringResource(R.string.advisory_band_body))
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FlickCorners.warning))
            .background(colors.caution)
            .clickable(
                interactionSource = interaction,
                indication = flickRipple(colors.onCaution),
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = label
            }
            .padding(horizontal = 17.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Icon(
            FlickIcons.Warning,
            contentDescription = null,
            tint = colors.onCaution,
            modifier = Modifier.size(22.dp),
        )
        Text(text = copy, style = FlickText.bodySmall.copy(color = colors.onCaution))
    }
}

@Composable
private fun Note(text: String) {
    val colors = LocalFlickColors.current
    Text(
        text = text,
        style = FlickText.monoEyebrow.copy(color = colors.onSurfaceFaint),
        modifier = Modifier.padding(vertical = 18.dp),
    )
}

@Composable
private fun FilterEmpty(filter: LibFilter) {
    val colors = LocalFlickColors.current
    Column(Modifier.fillMaxWidth().padding(vertical = 26.dp)) {
        Text(
            text = stringResource(
                if (filter == LibFilter.DOLBY_VISION) {
                    R.string.library_empty_filter_dv
                } else {
                    R.string.library_empty_filter_4k
                },
            ),
            style = FlickText.titleMedium.copy(color = colors.onSurface),
        )
        Text(
            text = stringResource(R.string.library_empty_filter_body),
            style = FlickText.bodyMedium.copy(color = colors.onSurfaceDim),
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun LibraryTile(
    item: MediaItem,
    imageLoader: ImageLoader,
    compact: Boolean,
    cell: MutableState<HdrType?>,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val known = cell.value
    val hdr by produceState(initialValue = known ?: HdrType.NONE, item.uri, known) {
        if (known != null) {
            value = known
        } else {
            val probed = MediaProbe.detectHdr(context, item.uri)
            value = probed
            cell.value = probed
        }
    }
    VideoTile(
        item = item,
        hdr = hdr,
        imageLoader = imageLoader,
        compact = compact,
        onClick = onClick,
    )
}

@Composable
private fun EmptyState(
    controller: FlickController,
    connectedTv: PairedTv?,
    castingItem: MediaItem?,
    signal: State<SignalInfo>,
    onChoose: () -> Unit,
) {
    val colors = LocalFlickColors.current
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, bottom = NavClearance),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        LinkPill(
            controller = controller,
            connectedTv = connectedTv,
            castingItem = castingItem,
            signal = signal,
        )
        Spacer(Modifier.height(34.dp))
        Box(
            Modifier
                .size(104.dp)
                .shadow(18.dp, CircleShape, clip = false, ambientColor = PrimaryShadow, spotColor = PrimaryShadow)
                .clip(CircleShape)
                .background(colors.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            FlickMark(modifier = Modifier.size(56.dp))
        }
        Spacer(Modifier.height(22.dp))
        Text(
            text = stringResource(R.string.empty_title),
            style = FlickText.headlineMedium.copy(color = colors.onSurface),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.empty_body),
            style = FlickText.bodyMedium.copy(color = colors.onSurfaceDim),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        FlickPrimaryButton(
            text = stringResource(R.string.empty_choose),
            onClick = onChoose,
            modifier = Modifier.width(240.dp),
        )
        if (connectedTv != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.empty_tv_ready, connectedTv.name),
                style = FlickText.bodyMedium.copy(color = colors.onSurfaceDim),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The mock's 412 dp frame lands two 179 dp columns; keeping that as a minimum lets a
 * 360 dp phone still show two and a rotated one reflow to five.
 */
private val TileMinWidth = 150.dp
