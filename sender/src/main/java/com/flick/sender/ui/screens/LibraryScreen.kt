package com.flick.sender.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.flick.sender.R
import com.flick.sender.media.MediaAccess
import com.flick.sender.media.MediaLibraryAction
import com.flick.sender.media.MediaLibraryActionPolicy
import com.flick.sender.media.MediaProbe
import com.flick.sender.model.HdrType
import com.flick.sender.model.MediaItem
import com.flick.sender.model.PlaybackPhase
import com.flick.sender.net.FlickController
import com.flick.sender.ui.components.ConnectChip
import com.flick.sender.ui.components.FlickTonalButton
import com.flick.sender.ui.components.LiveDot
import com.flick.sender.ui.components.MiniNowPlayingBar
import com.flick.sender.ui.components.VideoTile
import com.flick.sender.ui.components.rememberVideoImageLoader
import com.flick.sender.ui.theme.FlickIcons
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.PillShape

private enum class LibFilter { RECENTS, CAMERA, DOWNLOADS }

/** S3 — the library. A gallery, not a file browser: real MediaStore videos. */
@Composable
fun LibraryScreen(
    controller: FlickController,
    onRequestVideoPermission: () -> Unit,
) {
    val colors = LocalFlickColors.current
    val items by controller.mediaItems.collectAsState()
    val loading by controller.libraryLoading.collectAsState()
    val mediaAccess by controller.mediaAccess.collectAsState()
    val connectedTv by controller.connectedTv.collectAsState()
    val castingItem by controller.castingItem.collectAsState()
    val imageLoader = rememberVideoImageLoader()
    val connectLabel = stringResource(R.string.a11y_open_connect)
    val compactHeight = isCompactHeight(LocalConfiguration.current.screenHeightDp)
    var filter by remember { mutableStateOf(LibFilter.RECENTS) }
    val mediaAction = MediaLibraryActionPolicy.forAccess(mediaAccess)

    if (mediaAccess == MediaAccess.NONE || (items.isEmpty() && !loading)) {
        Box(Modifier.fillMaxSize()) {
            EmptyState(
                connectedTvName = connectedTv?.name,
                onChoose = onRequestVideoPermission,
                onConnect = { controller.openConnect() },
            )
            castingItem?.let { item ->
                LibraryNowPlayingBar(
                    controller = controller,
                    item = item,
                    tvName = connectedTv?.name,
                    imageLoader = imageLoader,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
        return
    }

    val filtered = when (filter) {
        LibFilter.RECENTS -> items
        LibFilter.CAMERA -> items.filter { it.bucket?.contains("camera", true) == true }
        LibFilter.DOWNLOADS -> items.filter { it.bucket?.contains("download", true) == true }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.surface)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(
                    start = 18.dp,
                    end = 18.dp,
                    top = if (compactHeight) 6.dp else 16.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.library_title),
                style = FlickText.heading.copy(color = colors.onSurface),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (mediaAction != MediaLibraryAction.HIDDEN) {
                FlickTonalButton(
                    text = stringResource(
                        if (mediaAction == MediaLibraryAction.SELECT_MORE) {
                            R.string.library_add_videos
                        } else {
                            R.string.library_refresh_videos
                        },
                    ),
                    onClick = {
                        when (mediaAction) {
                            MediaLibraryAction.SELECT_MORE -> onRequestVideoPermission()
                            MediaLibraryAction.REFRESH -> controller.refreshMediaLibrary()
                            MediaLibraryAction.HIDDEN -> Unit
                        }
                    },
                )
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = connectLabel }
                    .clickable { controller.openConnect() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    FlickIcons.Cast,
                    contentDescription = null,
                    tint = if (connectedTv != null) colors.link else colors.onSurfaceDim,
                    modifier = Modifier.size(22.dp),
                )
                if (connectedTv != null) {
                    LiveDot(
                        color = colors.live,
                        size = 7.dp,
                        modifier = Modifier.align(Alignment.TopEnd).padding(3.dp),
                    )
                }
            }
        }

        val filterGroupShape = RoundedCornerShape(18.dp)
        Row(
            Modifier
                .padding(horizontal = 16.dp, vertical = if (compactHeight) 3.dp else 8.dp)
                .clip(filterGroupShape)
                .background(colors.surfaceRaisedAlt)
                .border(1.dp, colors.outline.copy(alpha = 0.65f), filterGroupShape)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            FilterChip(
                text = stringResource(R.string.library_filter_recents),
                selected = filter == LibFilter.RECENTS,
            ) { filter = LibFilter.RECENTS }
            FilterChip(
                text = stringResource(R.string.library_filter_camera),
                selected = filter == LibFilter.CAMERA,
            ) { filter = LibFilter.CAMERA }
            FilterChip(
                text = stringResource(R.string.library_filter_downloads),
                selected = filter == LibFilter.DOWNLOADS,
            ) { filter = LibFilter.DOWNLOADS }
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 156.dp),
            modifier = Modifier.weight(1f).padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            items(filtered, key = { it.id }) { item ->
                LibraryTile(
                    item = item,
                    imageLoader = imageLoader,
                    compact = compactHeight,
                    onClick = { controller.openDetail(item) },
                )
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .padding(if (compactHeight) 6.dp else 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (castingItem != null) {
                LibraryNowPlayingBar(
                    controller = controller,
                    item = castingItem!!,
                    tvName = connectedTv?.name,
                    imageLoader = imageLoader,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (connectedTv != null) {
                Row(
                    Modifier
                        .clip(PillShape)
                        .background(colors.live.copy(alpha = 0.09f))
                        .border(1.dp, colors.live.copy(alpha = 0.25f), PillShape)
                        .padding(horizontal = 13.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LiveDot(colors.live, pulsing = true, modifier = Modifier.padding(end = 8.dp))
                    Text(
                        stringResource(R.string.library_connected_hint, connectedTv!!.name),
                        style = FlickText.caption.copy(color = colors.live),
                    )
                }
            } else {
                Text(
                    stringResource(R.string.library_not_connected_hint),
                    style = FlickText.caption.copy(color = colors.onSurfaceFaint),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = connectLabel }
                        .clickable { controller.openConnect() }
                        .padding(6.dp),
                )
            }
        }
    }
}

@Composable
private fun LibraryNowPlayingBar(
    controller: FlickController,
    item: MediaItem,
    tvName: String?,
    imageLoader: coil.ImageLoader,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val playback by controller.playback.collectAsState()
    val displayTv = tvName ?: stringResource(R.string.np_tv_generic)
    val status = when (playback.phase) {
        PlaybackPhase.BUFFERING -> stringResource(R.string.library_now_playing_buffering, displayTv)
        PlaybackPhase.PAUSED -> stringResource(R.string.library_now_playing_paused, displayTv)
        PlaybackPhase.ENDED -> stringResource(R.string.library_now_playing_ended, displayTv)
        else -> if (playback.playing) {
            stringResource(R.string.library_now_playing_playing, displayTv)
        } else {
            stringResource(R.string.library_now_playing_paused, displayTv)
        }
    }
    val request = remember(item.uri, item.durationMs) {
        ImageRequest.Builder(context)
            .data(item.uri)
            .videoFrameMillis((item.durationMs / 3L).coerceAtLeast(1_000L))
            .crossfade(true)
            .build()
    }
    MiniNowPlayingBar(
        title = item.name,
        status = status,
        actionLabel = stringResource(R.string.library_now_playing_controls),
        accessibilityLabel = stringResource(R.string.a11y_restore_now_playing, item.name),
        playing = playback.playing,
        onClick = { controller.restoreNowPlaying() },
        modifier = modifier,
        poster = {
            AsyncImage(
                model = request,
                contentDescription = null,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        },
    )
}

@Composable
private fun LibraryTile(
    item: MediaItem,
    imageLoader: coil.ImageLoader,
    compact: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val hdr by produceState(initialValue = HdrType.NONE, item.uri) {
        value = MediaProbe.detectHdr(context, item.uri)
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
private fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalFlickColors.current
    val motionScheme = MaterialTheme.motionScheme
    val container by animateColorAsState(
        targetValue = if (selected) colors.spark.copy(alpha = 0.14f) else Color.Transparent,
        animationSpec = motionScheme.fastEffectsSpec(),
        label = "library filter container",
    )
    val content by animateColorAsState(
        targetValue = if (selected) {
            if (colors.isLight) colors.spark else colors.sparkLight
        } else {
            colors.onSurfaceDim
        },
        animationSpec = motionScheme.fastEffectsSpec(),
        label = "library filter content",
    )
    val indicator by animateColorAsState(
        targetValue = if (selected) colors.spark else Color.Transparent,
        animationSpec = motionScheme.fastEffectsSpec(),
        label = "library filter indicator",
    )
    val visualShape = RoundedCornerShape(13.dp)

    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(visualShape)
            .selectable(
                selected = selected,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(36.dp)
                .clip(visualShape)
                .background(container)
                .then(
                    if (selected) {
                        Modifier.border(1.dp, colors.spark.copy(alpha = 0.30f), visualShape)
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(5.dp)
                    .clip(PillShape)
                    .background(indicator),
            )
            Text(
                text = text,
                style = FlickText.caption.copy(
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = content,
                ),
            )
        }
    }
}

@Composable
private fun EmptyState(
    connectedTvName: String?,
    onChoose: () -> Unit,
    onConnect: () -> Unit,
) {
    val colors = LocalFlickColors.current
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.surface)
            .statusBarsPadding()
            .padding(26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        com.flick.sender.ui.components.FlickMark(
            modifier = Modifier.size(48.dp),
            tint = colors.spark,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.empty_title),
            style = FlickText.heading.copy(color = colors.onSurface),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.empty_body),
            style = FlickText.body.copy(color = colors.onSurfaceDim),
            modifier = Modifier.padding(horizontal = 8.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(22.dp))
        com.flick.sender.ui.components.FlickPrimaryButton(
            text = stringResource(R.string.empty_choose),
            onClick = onChoose,
            modifier = Modifier.width(200.dp),
        )
        Spacer(Modifier.height(20.dp))
        if (connectedTvName != null) {
            ConnectChip(name = stringResource(R.string.empty_tv_ready, connectedTvName))
        } else {
            Text(
                stringResource(R.string.empty_no_tv),
                style = FlickText.caption.copy(color = colors.onSurfaceFaint),
                modifier = Modifier.clickable(onClick = onConnect).padding(8.dp),
            )
        }
    }
}
