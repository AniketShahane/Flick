package com.flick.sender.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.flick.sender.model.HdrType
import com.flick.sender.model.MediaItem
import com.flick.sender.ui.Format
import com.flick.sender.ui.theme.FlickCorners
import com.flick.sender.ui.theme.FlickGradients
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.PremiumGoldB
import com.flick.sender.ui.theme.PremiumInk

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
 * video (design: the tiles are filmic 16:9 stills, not generic icons).
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
 * Library tile (design S3 / component kit): 16:9 still with a DV/HDR badge
 * top-left (premium sheen), the duration bottom-right (mono tabular), then the
 * title + `4K · 8.4 GB` caption.
 */
@Composable
fun VideoTile(
    item: MediaItem,
    hdr: HdrType,
    imageLoader: ImageLoader,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFlickColors.current
    val context = LocalContext.current
    val shape = RoundedCornerShape(13.dp)

    val request = remember(item.uri, item.durationMs) {
        ImageRequest.Builder(context)
            .data(item.uri)
            .videoFrameMillis((item.durationMs / 3L).coerceAtLeast(1000L))
            .crossfade(true)
            .build()
    }

    Column(
        modifier = modifier
            .clip(shape)
            .background(colors.surfaceRaised)
            .border(1.dp, colors.outlineHairline, shape)
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
        ) {
            AsyncImage(
                model = request,
                contentDescription = item.name,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.4f to Color.Transparent,
                            1f to Color(0xBF08070C),
                        ),
                    ),
            )
            QualityBadge(
                hdr = hdr,
                resolutionLabel = item.resolutionLabel,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
            )
            Text(
                text = Format.timecode(item.durationMs),
                style = FlickText.mono.copy(color = Color.White, fontWeight = FontWeight.Bold),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xBF08070C))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 9.dp)) {
            Text(
                text = item.name,
                style = FlickText.caption.copy(fontWeight = FontWeight.SemiBold, color = colors.onSurface),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${item.resolutionLabel} · ${Format.bytes(item.sizeBytes)}",
                style = FlickText.mono.copy(color = colors.onSurfaceFaint),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * Quality chip: Dolby Vision / HDR10 in the premium gold sheen, otherwise the
 * plain resolution label in a hairline outline.
 */
@Composable
fun QualityBadge(
    hdr: HdrType,
    resolutionLabel: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFlickColors.current
    when (hdr) {
        HdrType.DOLBY_VISION -> Text(
            text = "DV",
            style = FlickText.mono.copy(fontSize = 8.sp, fontWeight = FontWeight.Bold, color = PremiumInk),
            modifier = modifier
                .clip(RoundedCornerShape(4.dp))
                .background(FlickGradients.premiumSheen)
                .padding(horizontal = 5.dp, vertical = 2.dp),
        )
        HdrType.HDR10 -> Text(
            text = "HDR10",
            style = FlickText.mono.copy(fontSize = 8.sp, fontWeight = FontWeight.Bold, color = PremiumGoldB),
            modifier = modifier
                .clip(RoundedCornerShape(4.dp))
                .border(1.dp, PremiumGoldB.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                .padding(horizontal = 5.dp, vertical = 2.dp),
        )
        HdrType.NONE -> Text(
            text = resolutionLabel,
            style = FlickText.mono.copy(fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color.White),
            modifier = modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0x8008070C))
                .border(1.dp, Color(0x4DFFFFFF), RoundedCornerShape(4.dp))
                .padding(horizontal = 5.dp, vertical = 2.dp),
        )
    }
}

/** Alias to the corner token so callers can reference the tile radius. */
internal val TileCorner = FlickCorners.md
