package com.flick.sender.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.flick.sender.R
import com.flick.sender.model.HdrType
import com.flick.sender.model.MediaItem
import com.flick.sender.ui.Format
import com.flick.sender.ui.theme.FlickCorners
import com.flick.sender.ui.theme.FlickGradients
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.PillShape
import com.flick.sender.ui.theme.TileShadow
import com.flick.sender.ui.theme.pressScale

// One process-wide loader (application-scoped) so the video-frame memory cache
// survives navigation. Building a fresh ImageLoader per screen visit threw the cache
// away on every Library <-> Detail hop — re-running an expensive frame extract on every
// visible 4K tile — and leaked the abandoned loaders until GC. Keyed off the
// application context so it never holds an Activity.
@Volatile
private var sharedVideoImageLoader: ImageLoader? = null
private val videoImageLoaderLock = Any()

/**
 * The app-scoped Coil [ImageLoader] that decodes a still frame straight out of a local
 * video (design: the tiles are filmic stills, not generic icons).
 */
@Composable
fun rememberVideoImageLoader(): ImageLoader {
    val appContext = LocalContext.current.applicationContext
    return sharedVideoImageLoader ?: synchronized(videoImageLoaderLock) {
        sharedVideoImageLoader ?: ImageLoader.Builder(appContext)
            .components { add(VideoFrameDecoder.Factory()) }
            .crossfade(true)
            .build()
            .also { sharedVideoImageLoader = it }
    }
}

/**
 * Library tile (design §5.2.5): a real decoded frame under a bottom scrim, the
 * resolution + dynamic-range chip top-left, the duration bottom-right, then the
 * title and a size/length caption.
 */
@Composable
fun VideoTile(
    item: MediaItem,
    hdr: HdrType,
    imageLoader: ImageLoader,
    onClick: () -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFlickColors.current
    val context = LocalContext.current
    val shape = RoundedCornerShape(FlickCorners.tile)
    val interaction = remember { MutableInteractionSource() }

    val request = remember(item.uri, item.durationMs) {
        ImageRequest.Builder(context)
            .data(item.uri)
            .videoFrameMillis((item.durationMs / 3L).coerceAtLeast(1000L))
            .crossfade(true)
            .build()
    }

    Column(
        modifier = modifier
            .pressScale(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { role = Role.Button },
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                // A ratio, not a fixed height: the grid reflows with the window, and a
                // pinned dp would crop the still to a sliver on a wide column.
                .aspectRatio(if (compact) CompactPosterRatio else PosterRatio)
                .shadow(10.dp, shape, clip = false, ambientColor = TileShadow, spotColor = TileShadow)
                .clip(shape)
                .background(colors.surfaceTonal),
        ) {
            AsyncImage(
                model = request,
                contentDescription = item.name,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize().background(FlickGradients.posterScrim))
            Text(
                text = badgeLabel(hdr, item.resolutionLabel),
                style = FlickText.monoBadge.copy(color = colors.onSurface),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .clip(PillShape)
                    .background(colors.canvas)
                    .padding(horizontal = 9.dp, vertical = 6.dp),
            )
            // A zero duration means MediaStore had no value, not a zero-length film.
            if (item.durationMs > 0L) {
                Text(
                    text = Format.timecode(item.durationMs),
                    style = FlickText.monoSmall.copy(fontSize = 10.5.sp, color = Color.White),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 10.dp, end = 11.dp),
                )
            }
        }
        Column {
            Text(
                text = item.name,
                style = FlickText.labelLarge.copy(color = colors.onSurface),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = metaLabel(item, stringResource(R.string.media_unknown)),
                style = FlickText.bodyMedium.copy(fontSize = 11.sp, color = colors.onSurfaceFaint),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

/** "4K DV" / "4K HDR10" / "4K" — the dynamic range is only claimed once probed. */
@Composable
private fun badgeLabel(hdr: HdrType, resolutionLabel: String): String = when (hdr) {
    HdrType.DOLBY_VISION -> stringResource(
        R.string.library_tile_badge,
        resolutionLabel,
        stringResource(R.string.media_dv_badge),
    )
    HdrType.HDR10 -> stringResource(
        R.string.library_tile_badge,
        resolutionLabel,
        stringResource(R.string.media_hdr10_badge),
    )
    HdrType.NONE -> resolutionLabel
}

private fun metaLabel(item: MediaItem, unknown: String): String {
    val length = if (item.durationMs > 0L) Format.durationHuman(item.durationMs) else unknown
    val size = if (item.sizeBytes > 0L) Format.bytes(item.sizeBytes) else unknown
    return "$length · $size"
}

// The mock's 179 dp column carries a 122 dp still. Short screens drop to 16:9 so the
// poster does not eat the row on a landscape phone.
private const val PosterRatio = 179f / 122f
private const val CompactPosterRatio = 16f / 9f
