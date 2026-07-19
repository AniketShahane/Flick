package com.flick.sender.ui.screens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flick.sender.R
import com.flick.sender.media.MediaProbe
import com.flick.sender.model.HdrType
import com.flick.sender.model.MediaItem
import com.flick.sender.net.FlickController
import com.flick.sender.ui.components.ConnectChip
import com.flick.sender.ui.components.LiveDot
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
    val hasPermission by controller.hasPermission.collectAsState()
    val connectedTv by controller.connectedTv.collectAsState()
    val imageLoader = rememberVideoImageLoader()
    var filter by remember { mutableStateOf(LibFilter.RECENTS) }

    if (!hasPermission || (items.isEmpty() && !loading)) {
        EmptyState(
            connectedTvName = connectedTv?.name,
            onChoose = onRequestVideoPermission,
            onConnect = { controller.openConnect() },
        )
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
            .statusBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.library_title),
                style = FlickText.heading.copy(color = colors.onSurface),
                modifier = Modifier.weight(1f),
            )
            Box(contentAlignment = Alignment.TopEnd) {
                Icon(
                    FlickIcons.Cast,
                    contentDescription = null,
                    tint = if (connectedTv != null) colors.link else colors.onSurfaceDim,
                    modifier = Modifier
                        .size(22.dp)
                        .clickable { controller.openConnect() },
                )
                if (connectedTv != null) {
                    LiveDot(
                        color = colors.live,
                        size = 7.dp,
                        modifier = Modifier.padding(1.dp),
                    )
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(stringResource(R.string.library_filter_recents), filter == LibFilter.RECENTS) { filter = LibFilter.RECENTS }
            FilterChip(stringResource(R.string.library_filter_camera), filter == LibFilter.CAMERA) { filter = LibFilter.CAMERA }
            FilterChip(stringResource(R.string.library_filter_downloads), filter == LibFilter.DOWNLOADS) { filter = LibFilter.DOWNLOADS }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.weight(1f).padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            items(filtered, key = { it.id }) { item ->
                LibraryTile(
                    item = item,
                    imageLoader = imageLoader,
                    onClick = { controller.openDetail(item) },
                )
            }
        }

        Box(Modifier.fillMaxWidth().padding(14.dp), contentAlignment = Alignment.Center) {
            if (connectedTv != null) {
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
                    modifier = Modifier.clickable { controller.openConnect() }.padding(6.dp),
                )
            }
        }
    }
}

@Composable
private fun LibraryTile(
    item: MediaItem,
    imageLoader: coil.ImageLoader,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val hdr by produceState(initialValue = HdrType.NONE, item.uri) {
        value = MediaProbe.detectHdr(context, item.uri)
    }
    VideoTile(item = item, hdr = hdr, imageLoader = imageLoader, onClick = onClick)
}

@Composable
private fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalFlickColors.current
    Text(
        text = text,
        style = FlickText.caption.copy(
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) colors.surface else colors.onSurfaceDim,
        ),
        modifier = Modifier
            .clip(PillShape)
            .then(
                if (selected) Modifier.background(colors.onSurface)
                else Modifier.border(1.dp, colors.outline, PillShape),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
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
