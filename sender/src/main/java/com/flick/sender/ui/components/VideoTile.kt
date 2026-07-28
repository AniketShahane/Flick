package com.flick.sender.ui.components

import android.net.Uri
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
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
import coil.size.Precision
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
import com.flick.sender.ui.theme.flickRipple
import com.flick.sender.ui.theme.pressMorph
import com.flick.sender.ui.theme.pressScale
import com.flick.sender.ui.theme.rememberReduceMotion

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

// Coil validates a cached bitmap against the pixel size the request asks for, so an
// unpinned tile decode is rejected by a full-bleed request and extracted again. That
// re-decode is exactly the blank frame a shared-element flight cannot survive, so every
// surface that shows a file's frame asks for these pixels and INEXACT accepts them.
// 960 wide is the largest pin a full grid of 4K stills can hold at once.
private const val FrameWidthPx = 960
private const val FrameHeightPx = 540

/** Shared-element key for a library item's still, matched by Library and Detail. */
fun posterKey(itemId: Long): String = "poster-$itemId"

/** Shared-element key for the frame that travels Connecting -> NowPlaying. */
const val CastPosterKey = "cast-poster"

/**
 * The one request builder for local video stills. [crossfade] is a fade of the decode,
 * not of a transition: it belongs on first-sight surfaces only, because a hero landing
 * already carries its own motion.
 */
@Composable
fun rememberVideoFrameRequest(
    uri: Uri?,
    durationMs: Long,
    crossfade: Boolean = false,
): ImageRequest? {
    val context = LocalContext.current
    return remember(uri, durationMs, crossfade) {
        uri?.let {
            ImageRequest.Builder(context)
                .data(it)
                // A third in: the head of a film is usually black or a title card.
                .videoFrameMillis((durationMs / 3L).coerceAtLeast(1_000L))
                .size(FrameWidthPx, FrameHeightPx)
                .precision(Precision.INEXACT)
                .crossfade(crossfade)
                .build()
        }
    }
}

/**
 * Marks a decoded frame as the surface that becomes the next screen. Both scopes null
 * leaves the modifier inert, so anything composed outside the shell's
 * `SharedTransitionLayout` renders exactly as it did before.
 *
 * [renderInOverlay] must stay true wherever the flight starts or ends inside a clip —
 * a grid cell, a rounded poster — because the overlay copy is the only one that can
 * leave it.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.flickSharedFrame(
    sharedScope: SharedTransitionScope?,
    animatedScope: AnimatedVisibilityScope?,
    key: String,
    renderInOverlay: Boolean = true,
): Modifier {
    if (sharedScope == null || animatedScope == null) return this
    val reduceMotion = rememberReduceMotion()
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Rect>()
    val bounds = remember(reduceMotion, spatial) {
        BoundsTransform { _, _ -> if (reduceMotion) snap<Rect>() else spatial }
    }
    return with(sharedScope) {
        this@flickSharedFrame.sharedElement(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = animatedScope,
            boundsTransform = bounds,
            renderInOverlayDuringTransition = renderInOverlay,
        )
    }
}

/**
 * Library tile (design §5.2.5): a real decoded frame under a bottom scrim, the
 * resolution + dynamic-range chip top-left, the duration bottom-right, then the
 * title and a size/length caption. The frame itself is the hero — tapping the tile
 * expands these pixels into DetailScreen's backdrop.
 */
@Composable
fun VideoTile(
    item: MediaItem,
    hdr: HdrType?,
    imageLoader: ImageLoader,
    onClick: () -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
) {
    val colors = LocalFlickColors.current
    val shape = RoundedCornerShape(FlickCorners.tile)
    val interaction = remember { MutableInteractionSource() }
    val request = rememberVideoFrameRequest(item.uri, item.durationMs, crossfade = true)

    Column(
        modifier = modifier
            .pressScale(interaction)
            // The whole tile is the target but the poster below draws the press: the
            // caption sits outside any rounded shape, and clipping this column would
            // cut the poster's deliberately unclipped shadow.
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
                .pressMorph(interaction, restRadius = FlickCorners.tile, pressedRadius = 20.dp)
                .background(colors.surfaceTonal)
                // The amber media accent rather than the action blue: this surface is a
                // decoded frame, not a palette surface, and only a light, high-chroma
                // ripple survives both a blown-out still and a near-black one.
                .indication(interaction, flickRipple(colors.spark)),
        ) {
            // Only the frame travels. The badges below belong to the grid, so they stay
            // out of the shared node and cross-fade with the route.
            AsyncImage(
                model = request,
                contentDescription = item.name,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .flickSharedFrame(sharedScope, animatedScope, posterKey(item.id))
                    .fillMaxSize(),
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
                // onSurfaceFaint is cleared only for tracked uppercase labels: this line
                // is 11 sp mixed case and it is under every tile in the grid, which makes
                // it the most-read text in the app and the least readable ink for it.
                style = FlickText.bodyMedium.copy(fontSize = 11.sp, color = colors.onSurfaceDim),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

/**
 * "4K DV" / "4K HDR10" / "4K" — the dynamic range is only claimed once probed, which is
 * why null (still probing) and [HdrType.NONE] (probed, no HDR) render the same badge:
 * the resolution is all this tile knows in either case.
 */
@Composable
private fun badgeLabel(hdr: HdrType?, resolutionLabel: String): String = when (hdr) {
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
    HdrType.NONE, null -> resolutionLabel
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
