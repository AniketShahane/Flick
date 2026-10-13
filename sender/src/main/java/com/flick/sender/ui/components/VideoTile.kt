package com.flick.sender.ui.components

import android.app.ActivityManager
import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.util.Size as AndroidSize
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.DataSource
import coil.decode.VideoFrameDecoder
import coil.fetch.DrawableResult
import coil.fetch.Fetcher
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.request.Options
import coil.request.videoFrameMillis
import coil.size.Dimension
import coil.size.Precision
import com.flick.sender.R
import com.flick.sender.media.FilmArt
import com.flick.sender.media.ResumeProgress
import com.flick.sender.media.Still
import com.flick.sender.media.VideoStills
import com.flick.sender.model.HdrType
import com.flick.sender.model.MediaItem
import com.flick.sender.ui.FilmTileArt
import com.flick.sender.ui.Format
import com.flick.sender.ui.displayName
import com.flick.sender.ui.rememberFilmArt
import com.flick.sender.ui.softBlur
import com.flick.sender.ui.theme.FlickColors
import com.flick.sender.ui.theme.FlickCorners
import com.flick.sender.ui.theme.FlickGradients
import com.flick.sender.ui.theme.FlickIcons
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.PillShape
import com.flick.sender.ui.theme.TileShadow
import com.flick.sender.ui.theme.flickRipple
import com.flick.sender.ui.theme.pressMorph
import com.flick.sender.ui.theme.pressScale
import com.flick.sender.ui.theme.rememberReduceMotion
import com.flick.sender.ui.warmFilmPoster
import com.flick.sender.ui.warmTilePoster
import kotlinx.coroutines.Dispatchers
import java.io.IOException

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
fun rememberVideoImageLoader(): ImageLoader = videoImageLoader(LocalContext.current)

/** The same loader, for work outside composition such as warming the film-art cache. */
fun videoImageLoader(context: Context): ImageLoader {
    val appContext = context.applicationContext
    return sharedVideoImageLoader ?: synchronized(videoImageLoaderLock) {
        sharedVideoImageLoader ?: ImageLoader.Builder(appContext)
            .components { add(VideoFrameDecoder.Factory()) }
            .crossfade(true)
            // Two at a time: a fling or a flip asks for a dozen decodes at once, and an
            // unbounded fan-out spends the frame's CPU on lock contention and garbage.
            .decoderDispatcher(ArtDecodeDispatcher)
            // Apart from the decodes, so the detail page's blur never queues behind a fling's.
            .transformationDispatcher(ArtTransformDispatcher)
            // An absolute ceiling keeps a large-heap phone from turning a proportional
            // default into an oversized gallery cache. The proportional half still lets
            // the platform's low-memory verdict shrink it further.
            .memoryCache {
                MemoryCache.Builder(appContext)
                    .maxSizeBytes(frameCacheBudgetBytes(appContext))
                    .build()
            }
            .build()
            .also { sharedVideoImageLoader = it }
    }
}

private val ArtDecodeDispatcher = Dispatchers.IO.limitedParallelism(2)
private val ArtTransformDispatcher = Dispatchers.Default.limitedParallelism(1)

/**
 * The smaller of a heap share and a hard byte ceiling. Low-RAM devices get both a smaller
 * share and a smaller ceiling.
 *
 * Sized to hold a screen of tiles AND the detail page opened over them (its poster at the
 * seat's size, the blurred ground, the logo, the hero frame): any less and every open evicted
 * the grid it closes back onto, and every close and scroll decoded it again. A quarter, above
 * Coil's 0.2 default, for that reason. Hardware bitmaps count against it without being Java heap.
 */
private fun frameCacheBudgetBytes(context: Context): Int {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    return frameCacheBudgetBytes(
        memoryClassMb = activityManager?.memoryClass?.takeIf { it > 0 } ?: DefaultMemoryClassMb,
        lowRam = activityManager?.isLowRamDevice == true,
    )
}

internal fun frameCacheBudgetBytes(memoryClassMb: Int, lowRam: Boolean): Int {
    val heapBytes = memoryClassMb.coerceAtLeast(1).toLong() * BytesPerMiB
    val proportional = heapBytes / if (lowRam) LowRamBudgetDivisor else FrameBudgetDivisor
    val cap = if (lowRam) LowRamFrameCacheCapBytes else FrameCacheCapBytes
    return minOf(proportional, cap.toLong()).toInt()
}

private const val BytesPerMiB = 1_048_576L
private const val DefaultMemoryClassMb = 96
private const val FrameBudgetDivisor = 4L
private const val LowRamBudgetDivisor = 12L
private const val FrameCacheCapBytes = 48 * 1_048_576
private const val LowRamFrameCacheCapBytes = 8 * 1_048_576

// Library and Detail ask at their own sizes; the cast surfaces take the larger box because
// a poster is the biggest a still is ever drawn here. Whatever the size, all of them go
// through the same chosen frame, so the picture a tile shows is the picture that flies into
// the detail sheet and lands on the remote. Detail reads the library entry as its placeholder.
private const val FrameWidthPx = 960
private const val FrameHeightPx = 540
private const val LibraryFrameWidthPx = 512
private const val LibraryFrameHeightPx = 288

/**
 * The still, chosen rather than taken on faith — see [VideoStills]. A frame that judges as
 * a picture of nothing (the black open nearly every film has) is escalated to a small
 * bounded search through the body of the film, off this thread and off any thread the grid
 * can multiply.
 *
 * Returning null hands the request back to Coil's own [VideoFrameDecoder], which decodes
 * the fixed one-third frame the request already names: a file this cannot open at all is
 * drawn exactly as it was before any of this existed. A file that decodes only to one
 * colour fails the request instead, since that decoder would paint the same colour, and the
 * seat's own ground shows through.
 */
private object VideoStillFetcher : Fetcher.Factory<Uri> {
    override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher = Fetcher {
        val target = options.thumbnailTargetSize()
        val still = VideoStills.still(
            context = options.context,
            uri = data,
            durationMs = options.parameters.value<Long>(SourceDurationKey) ?: 0L,
            width = target.width,
            height = target.height,
        )
        when (still) {
            Still.NoPicture -> throw NoPictureException(data)
            null -> null
            is Still.Picture -> DrawableResult(
                drawable = BitmapDrawable(options.context.resources, still.bitmap),
                // Every request for a key asks the same box, and this is already the best still
                // that box gets. Marked sampled, any still short of the box on both axes (a
                // portrait or low-resolution film, a provider thumbnail under the hero's larger
                // box) fails Coil's memory check and is searched for again on every request.
                isSampled = false,
                dataSource = DataSource.DISK,
            )
        }
    }
}

private class NoPictureException(uri: Uri) : IOException("only uniform frames decode from $uri")

private fun Options.thumbnailTargetSize(): AndroidSize = AndroidSize(
    (size.width as? Dimension.Pixels)?.px ?: LibraryFrameWidthPx,
    (size.height as? Dimension.Pixels)?.px ?: LibraryFrameHeightPx,
)

/**
 * The file's own length, carried to the fetcher so the search knows where the middle of the
 * film is. The null cache key is what keeps it OUT of the computed memory-cache key: the
 * duration is a property of the very bytes the URI already names, and letting it in would
 * fork the cache for every surface that rounds it differently.
 */
private fun ImageRequest.Builder.sourceDurationMs(durationMs: Long): ImageRequest.Builder =
    setParameter(SourceDurationKey, durationMs, null)

private const val SourceDurationKey = "flick#source_duration_ms"

internal fun libraryThumbnailCacheKey(
    uri: String,
    dateModifiedSeconds: Long,
    generationModified: Long?,
    mediaStoreVersion: String?,
    sizeBytes: Long,
    durationMs: Long,
    width: Int,
    height: Int,
): String {
    val revision = if (generationModified != null && mediaStoreVersion != null) {
        "generation:${mediaStoreVersion.length}:$mediaStoreVersion:$generationModified"
    } else {
        "legacy:$dateModifiedSeconds:$sizeBytes:$durationMs:${width}x$height"
    }
    return "library-thumb-v2:${uri.length}:$uri:$revision"
}

private fun libraryThumbnailCacheKey(item: MediaItem): String = libraryThumbnailCacheKey(
    uri = item.uriKey,
    dateModifiedSeconds = item.dateModifiedSeconds,
    generationModified = item.generationModified,
    mediaStoreVersion = item.mediaStoreVersion,
    sizeBytes = item.sizeBytes,
    durationMs = item.durationMs,
    width = item.width,
    height = item.height,
)

private fun heroFrameCacheKey(item: MediaItem): String = "${libraryThumbnailCacheKey(item)}:hero"

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
    memoryCacheKey: String? = null,
    placeholderMemoryCacheKey: String? = null,
): ImageRequest? {
    val context = LocalContext.current
    return remember(uri, durationMs, crossfade, memoryCacheKey, placeholderMemoryCacheKey) {
        uri?.let {
            ImageRequest.Builder(context)
                .data(it)
                // Where Coil's own decoder looks when every path in the fetcher failed. A
                // third in: the head of a film is usually black or a title card.
                .videoFrameMillis((durationMs / 3L).coerceAtLeast(1_000L))
                .sourceDurationMs(durationMs)
                .size(FrameWidthPx, FrameHeightPx)
                .precision(Precision.INEXACT)
                .crossfade(crossfade)
                .fetcherFactory(VideoStillFetcher, Uri::class.java)
                .apply {
                    memoryCacheKey?.let { key -> memoryCacheKey(key) }
                    placeholderMemoryCacheKey?.let { key -> placeholderMemoryCacheKey(key) }
                }
                .build()
        }
    }
}

@Composable
private fun rememberLibraryThumbnailRequest(item: MediaItem): ImageRequest {
    val context = LocalContext.current
    val cacheKey = libraryThumbnailCacheKey(item)
    return remember(item.uri, item.durationMs, cacheKey) {
        ImageRequest.Builder(context)
            .data(item.uri)
            .videoFrameMillis((item.durationMs / 3L).coerceAtLeast(1_000L))
            .sourceDurationMs(item.durationMs)
            .size(LibraryFrameWidthPx, LibraryFrameHeightPx)
            .precision(Precision.INEXACT)
            .memoryCacheKey(cacheKey)
            .fetcherFactory(VideoStillFetcher, Uri::class.java)
            .crossfade(true)
            .build()
    }
}

/**
 * The film's own frame for the detail page's ground, pre-blurred into a [boxWidthPx] ×
 * [boxHeightPx] bitmap. Made from the library's thumbnail when the grid holds it in memory,
 * which it does for the tile that was just tapped, so the page does not search the film for
 * a second frame that will end up a few hundred pixels of blur.
 */
@Composable
fun rememberSoftFrameRequest(
    item: MediaItem,
    imageLoader: ImageLoader,
    boxWidthPx: Int,
    boxHeightPx: Int,
    radiusPx: Int,
    biasY: Float,
): ImageRequest {
    val context = LocalContext.current
    val thumbnailKey = libraryThumbnailCacheKey(item)
    return remember(thumbnailKey, boxWidthPx, boxHeightPx, radiusPx, biasY) {
        val thumbnail = imageLoader.memoryCache?.get(MemoryCache.Key(thumbnailKey))?.bitmap
        ImageRequest.Builder(context)
            .data(thumbnail ?: item.uri)
            .apply {
                if (thumbnail == null) {
                    videoFrameMillis((item.durationMs / 3L).coerceAtLeast(1_000L))
                    sourceDurationMs(item.durationMs)
                    fetcherFactory(VideoStillFetcher, Uri::class.java)
                }
            }
            .softBlur(boxWidthPx, boxHeightPx, radiusPx, biasY)
            // Named, because a bitmap has no key of its own, and named the same whichever
            // source made it: both are the same frame.
            .memoryCacheKey("$thumbnailKey:soft:${boxWidthPx}x$boxHeightPx:$radiusPx:$biasY")
            .crossfade(false)
            .build()
    }
}

@Composable
fun rememberDetailVideoFrameRequest(item: MediaItem): ImageRequest = rememberVideoFrameRequest(
    uri = item.uri,
    durationMs = item.durationMs,
    memoryCacheKey = heroFrameCacheKey(item),
    placeholderMemoryCacheKey = libraryThumbnailCacheKey(item),
)!!

/**
 * The spring every shared frame travels on, spelled out rather than taken from the motion
 * scheme because of the threshold. A spec that names none falls back to a hundredth of a
 * pixel per edge, and the overlay copy is handed back to its parent — corner and all —
 * when the bounds animation ENDS, not when it has visibly landed: a window-sized return
 * settles to the eye in a quarter of a second and does not terminate for three quarters of
 * one. [Rect.VisibilityThreshold] is a pixel per edge, which is where a photograph is
 * actually home. Critically damped for a second reason: a still that springs past its
 * landing reads as a bounce, and nothing about a still growing into a poster is bouncy.
 */
private val FrameFlight = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
    visibilityThreshold = Rect.VisibilityThreshold,
)

/**
 * The same flight home.
 *
 * Leaving is quicker than arriving here for the reason it already is everywhere else in the
 * shell — the route a frame flies with fades its outgoing half at the scheme's FAST effects
 * spring and its incoming half at the default one. A frame growing into the remote is the
 * point of the gesture and is worth watching; the same frame going back is a screen the
 * viewer has already finished with, and every millisecond of it is spent waiting.
 *
 * The tempo is the scheme's own fast-spatial stiffness rather than a number chosen for
 * feel. Only the tempo is borrowed: that spring is damped 0.6 and would land the still with
 * a bounce, which is what the note above rules out for both directions. Against the flight
 * out, a spring this much stiffer settles in about seven tenths of the time.
 */
private val FrameReturn = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = 800f,
    visibilityThreshold = Rect.VisibilityThreshold,
)

/**
 * Whether a flight is a return — the frame is on its way to a seat smaller than the one it
 * left.
 *
 * Read off the geometry rather than declared by the caller, because it is true of every
 * pair this modifier serves: a connecting card grows into the cast poster and comes back
 * the other way. An interrupted return that is sent
 * forward again therefore picks the outward spring on its own, which is what a frame that
 * has changed its mind should do.
 */
internal fun frameReturning(from: Rect, to: Rect): Boolean =
    to.width * to.height < from.width * from.height

/**
 * Marks a decoded frame as the surface that becomes the next screen. Both scopes null
 * leaves the modifier inert, so anything composed outside the shell's
 * `SharedTransitionLayout` renders exactly as it did before.
 *
 * [renderInOverlay] must stay true wherever the flight starts or ends inside a clip —
 * a grid cell, a rounded poster — because the overlay copy is the only one that can
 * leave it.
 *
 * [restCorner] is the radius of the SEAT the frame flies out of and back into. An overlay
 * copy is drawn from the transition layout's own draw scope, so every ancestor layer is
 * bypassed — including the one that gives the seat its corner — and the library's default
 * clip resolves to none for a frame that is inside no enclosing shared element. Naming the
 * radius here is what keeps the copy rounded for the whole flight instead of arriving as a
 * hard rectangle and squaring off the moment it lands. Unspecified passes no clip argument
 * at all and leaves that default in force, which is what a seat with no corner wants.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.flickSharedFrame(
    sharedScope: SharedTransitionScope?,
    animatedScope: AnimatedVisibilityScope?,
    key: String,
    renderInOverlay: Boolean = true,
    restCorner: Dp = Dp.Unspecified,
): Modifier {
    if (sharedScope == null || animatedScope == null) return this
    val reduceMotion = rememberReduceMotion()
    val bounds = remember(reduceMotion) {
        BoundsTransform { from, to ->
            when {
                reduceMotion -> snap()
                frameReturning(from, to) -> FrameReturn
                else -> FrameFlight
            }
        }
    }
    val clip = if (restCorner.isSpecified) rememberFrameMorphClip(sharedScope, restCorner) else null
    return with(sharedScope) {
        val state = rememberSharedContentState(key)
        if (clip == null) {
            this@flickSharedFrame.sharedElement(
                sharedContentState = state,
                animatedVisibilityScope = animatedScope,
                boundsTransform = bounds,
                renderInOverlayDuringTransition = renderInOverlay,
            )
        } else {
            this@flickSharedFrame.sharedElement(
                sharedContentState = state,
                animatedVisibilityScope = animatedScope,
                boundsTransform = bounds,
                renderInOverlayDuringTransition = renderInOverlay,
                clipInOverlayDuringTransition = clip,
            )
        }
    }
}

/**
 * The silhouette a travelling frame is clipped to. As with the dock's morph, the radius is
 * a function of the copy's own measured height rather than of a second animation, so it
 * cannot drift out of step with the bounds it is rounding.
 *
 * The small end is anchored to the WINDOW and never to the tile: an adaptive grid makes a
 * tile's height a variable, and a small end set under it would leave the radius already
 * part-resolved where the frame comes to rest — which is the snap this exists to remove.
 * A grid still is under an eighth of the window on any phone this ships to, so holding the
 * full radius for everything below [CornerHold] of it puts the corner home well before the
 * bounds are. That margin is what the clip being handed back at the END of the flight
 * requires.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun rememberFrameMorphClip(
    sharedScope: SharedTransitionScope,
    restCorner: Dp,
): SharedTransitionScope.OverlayClip {
    val density = LocalDensity.current
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val shape = remember(density, screenHeightDp, restCorner) {
        with(density) {
            val windowPx = screenHeightDp.dp.toPx()
            FrameMorphShape(
                restRadiusPx = restCorner.toPx(),
                seatPx = windowPx * CornerHold,
                spanPx = (windowPx * (1f - CornerHold)).coerceAtLeast(1f),
            )
        }
    }
    return remember(sharedScope, shape) { with(sharedScope) { OverlayClip(shape) } }
}

private class FrameMorphShape(
    private val restRadiusPx: Float,
    private val seatPx: Float,
    private val spanPx: Float,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val grown = ((size.height - seatPx) / spanPx).coerceIn(0f, 1f)
        return Outline.Rounded(
            RoundRect(
                left = 0f,
                top = 0f,
                right = size.width,
                bottom = size.height,
                cornerRadius = CornerRadius(restRadiusPx * (1f - grown)),
            ),
        )
    }
}

/** Share of the window's height under which a travelling frame keeps its seat's full corner. */
private const val CornerHold = 0.25f

/**
 * Library tile (design §5.2.5): a real decoded frame under a bottom scrim, the
 * resolution + dynamic-range chip top-left, the duration bottom-right, then the
 * title and a size/length caption. The face is a card — tapping the tile turns it over
 * into DetailScreen's poster seat (see [FlipCard]); [cardFlip] is the shell's word that the
 * route pair this tile is part of is that one.
 *
 * [unplayable] is a fact this app witnessed — a receiver refused these exact bytes —
 * and it is the only state here allowed to read as a fault. A file MediaStore simply
 * never scanned is a different thing entirely: the badge withholds the claim it cannot
 * make, and nothing about the tile says the file is broken.
 *
 * [silentAudio] is the milder witness beside it: the TV played this film and had no
 * decoder for its sound. The film is watchable, so the tile keeps its full colour and its
 * full target and the chip takes the withheld register rather than the failure one. It is
 * suppressed under [unplayable], which is not merely precedence — a file that will not
 * play at all has nothing to say about its audio.
 *
 * [resume] is the watched line under the still, and it is a resolved value rather than a
 * checkpoint map on purpose: the caller resolves it per tile through
 * [com.flick.sender.media.resumeProgress], which is the same rule the detail sheet's
 * resume CTA goes through. Null draws nothing, which is what every call site that has not
 * been given one gets.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun VideoTile(
    item: MediaItem,
    hdr: HdrType?,
    imageLoader: ImageLoader,
    onClick: () -> Unit,
    unplayable: Boolean = false,
    silentAudio: Boolean = false,
    compact: Boolean = false,
    resume: ResumeProgress? = null,
    modifier: Modifier = Modifier,
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
    cardFlip: Boolean = false,
    placed: () -> Boolean = { true },
) {
    val colors = LocalFlickColors.current
    val context = LocalContext.current
    val displayName = item.displayName()
    val shape = RoundedCornerShape(FlickCorners.tile)
    val interaction = remember { MutableInteractionSource() }
    val filmArt = item.rememberFilmArt()
    val reduceMotion = rememberReduceMotion()
    val refusedDescription = stringResource(R.string.a11y_tile_unplayable)
    val silentDescription = stringResource(R.string.a11y_tile_no_sound)
    val watchedDescription = resume?.let {
        stringResource(R.string.a11y_tile_watched, Format.timecode(it.positionMs))
    }
    // Decided once and read by both the chip and the state line, so what TalkBack says
    // and what the eye sees can never disagree about which of the two facts is showing.
    val noSound = silentAudio && !unplayable
    // One node, one state line. A refused file may also carry a resume, and TalkBack
    // reads a single stateDescription — so the two facts are joined here rather than
    // raising a second focusable thing on a tile that is one button.
    val tileState = listOfNotNull(
        refusedDescription.takeIf { unplayable },
        silentDescription.takeIf { noSound },
        watchedDescription,
    )
        .joinToString(" · ")
        .takeIf { it.isNotEmpty() }
    // The card is the only thing that leaves the grid. Everything else on the tile is the
    // slot it lifted off, and fades out from under it.
    val flipScopes = if (sharedScope != null && animatedScope != null && !reduceMotion) {
        sharedScope to animatedScope
    } else {
        null
    }
    val currentPlaced by rememberUpdatedState(placed)
    val flipState = flipScopes?.let { (shared, animated) ->
        val tileTransition = animated.transition
        val config = remember(tileTransition) {
            FlipEndConfig(tileTransition, EnterExitState.PostExit) { currentPlaced() }
        }
        with(shared) { rememberSharedContentState(cardFlipKey(item.id), config) }
    }
    val handoff = LocalCardFlipHandoff.current
    val flipKey = cardFlipKey(item.id)
    val badge = flipScopes?.let { (_, animated) -> rememberTileBadgeTrack(animated.transition, cardFlip) }
    // How much of the slot — its ground and shadow — is gone from under
    // the card. Coming back it follows the card's own travel, so it is whole as the card lands.
    val slot: () -> Float = {
        if (badge != null && flipState?.isMatchFound == true) {
            val flight = handoff.flightFor(flipKey)?.takeIf { it.backLeg }
            1f - (flight?.let { flipBadgeHidden(it.move) } ?: badge.value.coerceIn(0f, 1f))
        } else {
            1f
        }
    }
    // Derived, so the overlay switch below recomposes this tile only when the card touches down,
    // not on every frame of the flight or whenever any seat comes and goes.
    val landedState = remember(handoff, flipKey) {
        derivedStateOf { handoff.flightFor(flipKey)?.landed == true }
    }
    val landed: () -> Boolean = { landedState.value }
    // The card carries the marks while it is away from the slot, so the slot's own copy goes
    // and comes back with the face's.
    val cardAway: () -> Boolean = {
        flipScopes != null && flipState != null &&
            flipState.isMatchFound && flipScopes.first.isTransitionActive && !landed()
    }
    // The tile's own colours travel with the marks: the detail page draws the card under its
    // cinematic palette, and the marks must land looking as the tile draws them.
    val marks = TileMarks(
        hdr = hdr,
        unplayable = unplayable,
        noSound = noSound,
        resumeFraction = resume?.fraction,
        colors = colors,
    )
    if (flipScopes != null) {
        SideEffect { handoff.refreshMarks(flipKey, marks) }
    }
    // Plain, not state: read once, at the tap.
    val faceSize = remember { FaceSize() }
    // The detail card turns over to this poster about a tenth of a second after a tap.
    val posterPath = filmArt?.poster
    // Only for a tile that does not draw the poster itself: one that does already holds it under
    // the same key, and a second, smaller decode racing it would leave the tile's own too small.
    val warmPath = posterPath?.takeIf { filmArt?.backdrop != null }
    LaunchedEffect(warmPath) {
        warmPath?.let { warmTilePoster(context, imageLoader, it) }
    }
    val currentOnClick by rememberUpdatedState(onClick)
    val open = {
        // A second tap while the first is still lifting the card off would open the page
        // under a card already in the air; so would a tap on any tile while a card is coming
        // back down onto the grid.
        val leaving = animatedScope != null && animatedScope.transition.targetState != EnterExitState.Visible
        val flying = sharedScope?.isTransitionActive == true
        if (!leaving && !flying) {
            handoff.armTile(flipKey, faceSize.px, marks)
            posterPath?.let { warmFilmPoster(context, imageLoader, it) }
            currentOnClick()
        }
    }

    Column(
        modifier = modifier
            .pressScale(interaction)
            // The whole tile is the target but the poster below draws the press: the
            // caption sits outside any rounded shape, and clipping this column would
            // cut the poster's deliberately unclipped shadow.
            .clickable(interactionSource = interaction, indication = null, onClick = open)
            .semantics {
                role = Role.Button
                // The chip and the line are both decorative to TalkBack — the tile speaks
                // its own state, so neither is announced as a second, tappable thing.
                tileState?.let { stateDescription = it }
            },
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                // A ratio, not a fixed height: the grid reflows with the window, and a
                // pinned dp would crop the still to a sliver on a wide column.
                .aspectRatio(if (compact) CompactPosterRatio else PosterRatio)
                // The shadow's own elevation rather than the layer's opacity: an opacity
                // under 1 would draw the slot offscreen and cut the shadow at its edge.
                .graphicsLayer {
                    shadowElevation = TileElevation.toPx() * slot()
                    this.shape = shape
                    clip = false
                    ambientShadowColor = TileShadow
                    spotShadowColor = TileShadow
                }
                .pressMorph(interaction, restRadius = FlickCorners.tile, pressedRadius = 20.dp)
                // The amber media accent rather than the action blue: this surface is a
                // decoded frame, not a palette surface, and only a light, high-chroma
                // ripple survives both a blown-out still and a near-black one.
                .indication(interaction, flickRipple(colors.spark)),
        ) {
            // The slot's ground on a layer of its own: the face above it must be whole the
            // instant the card is handed back, whatever the slot is still doing. One fill, so
            // its fade needs no offscreen copy.
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        alpha = slot()
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }
                    .background(colors.surfaceTonal),
            )
            TileFace(
                item = item,
                filmArt = filmArt,
                imageLoader = imageLoader,
                unplayable = unplayable,
                contentDescription = displayName,
                // Measured outside the flip's bounds, which remeasure the face while it flies:
                // this is the size it rests at in the slot.
                modifier = if (flipScopes != null && flipState != null) {
                    Modifier
                        .onSizeChanged { faceSize.px = it }
                        // Back in the slot's own layer the moment the card lands, so the marks
                        // drawn over it here are not covered by an overlay copy for the rest of
                        // the route's settle.
                        .cardFlipBounds(
                            flipScopes.first,
                            flipScopes.second,
                            flipState,
                            seat = false,
                            renderInOverlay = !landed(),
                        )
                        .onPlaced { face ->
                            // Before any coordinates are touched: reading them here makes every
                            // tile's placement depend on them, and the grid re-place on each scroll.
                            if (handoff.flightFor(flipKey) == null) return@onPlaced
                            val slotCoords = face.parentLayoutCoordinates ?: return@onPlaced
                            handoff.reportFace(
                                flipKey,
                                flipFaceHome(face.positionInParent(), face.size, slotCoords.size),
                                face.size,
                            )
                        }
                        .hideWhileFlipping(flipScopes.first, flipState, landed)
                        // Its own corner as well, for any frame it still draws in the overlay,
                        // where the slot's clip does not reach.
                        .graphicsLayer {
                            this.shape = shape
                            clip = true
                        }
                        .fillMaxSize()
                } else {
                    Modifier.onSizeChanged { faceSize.px = it }.fillMaxSize()
                },
            )
            // The slot's marks. While the card is away they ride on its front instead
            // (CardFlipSeat.marks), fading off it as it leaves and back on as it lands.
            TileMarkings(
                item = item,
                marks = marks,
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        alpha = if (cardAway()) 0f else slot()
                        this.shape = shape
                        clip = true
                    },
            )
        }
        Column {
            Text(
                text = displayName,
                style = FlickText.labelLarge.copy(color = colors.onSurface),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val unknown = stringResource(R.string.media_unknown)
            // Remembered: a tile composed into a fling row would otherwise run a String.format
            // for it on that row's first frame.
            val meta = remember(item.durationMs, item.sizeBytes, unknown) { metaLabel(item, unknown) }
            Text(
                text = meta,
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

/** What a tile marks its face with, captured so the flying card can draw the same marks. */
@Immutable
internal data class TileMarks(
    val hdr: HdrType?,
    val unplayable: Boolean,
    val noSound: Boolean,
    val resumeFraction: Float?,
    val colors: FlickColors,
)

/**
 * The tile's marks over its face: the claim chips top-left, the running time bottom-right and
 * the watched line along the bottom edge. Drawn by the tile at rest and by the card in flight,
 * in the tile's own colours either way.
 */
@Composable
internal fun TileMarkings(item: MediaItem, marks: TileMarks, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalFlickColors provides marks.colors) {
        Box(modifier) {
            // Stacked, not swapped: the two chips answer different questions, and a file
            // the TV refused still has whatever metadata it always had.
            Column(
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MetadataBadge(hdr = marks.hdr, item = item)
                if (marks.unplayable) RefusedBadge()
                if (marks.noSound) NoSoundBadge()
            }
            // A zero duration means MediaStore had no value, not a zero-length film.
            if (item.knowsDuration) {
                Text(
                    text = remember(item.durationMs) { Format.timecode(item.durationMs) },
                    style = FlickText.monoSmall.copy(fontSize = 10.5.sp, color = Color.White),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 10.dp, end = 11.dp),
                )
            }
            marks.resumeFraction?.let { ResumeLine(fraction = it, played = marks.colors.spark) }
        }
    }
}

/**
 * The card face a library tile shows: the film's art, else a decoded frame, under the scrim
 * the tile's badges read against. It is also the front of the card that turns over into the
 * detail page's poster, so the detail page draws exactly this to fly it — at [requestSizePx],
 * the size the tile drew it, so its art is the tile's own decode rather than a fresh one.
 *
 * [unplayable] holds the still back rather than dimming it: a scrim on top would fight the
 * one the badges already read against, and a frame at full chroma beside a refusal chip
 * reads as an unrelated decoration stuck to it.
 */
@Composable
fun TileFace(
    item: MediaItem,
    filmArt: FilmArt?,
    imageLoader: ImageLoader,
    unplayable: Boolean,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    requestSizePx: IntSize? = null,
) {
    val frameFilter = remember(unplayable) {
        if (unplayable) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(RefusedSaturation) }) else null
    }
    Box(modifier) {
        if (filmArt != null) {
            FilmTileArt(
                art = filmArt,
                imageLoader = imageLoader,
                contentDescription = contentDescription,
                colorFilter = frameFilter,
                modifier = Modifier.fillMaxSize(),
                requestSizePx = requestSizePx,
            )
        } else {
            AsyncImage(
                model = rememberLibraryThumbnailRequest(item),
                contentDescription = contentDescription,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                colorFilter = frameFilter,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(Modifier.fillMaxSize().background(FlickGradients.posterScrim))
    }
}

/**
 * How much of this file has been watched, pinned to the bottom edge of the still and
 * inside its rounded clip, so the corners take the ends of the rule as they take the
 * frame's. It is a fact about the file, not a control: no target, no ripple, and slim
 * enough that the timecode above it is still the thing in that corner.
 *
 * The played span is [com.flick.sender.ui.theme.FlickColors.spark] for the reason the
 * ripple on this same surface is — a decoded frame is not a palette surface, and only a
 * light, high-chroma mark survives both a blown-out still and a near-black one. The
 * unplayed remainder takes a white wash rather than a dark one for the other half of that
 * argument: the bottom of the frame is already under the poster scrim's full 62% black,
 * so black would vanish into the very stills the track has to be legible on.
 */
@Composable
private fun BoxScope.ResumeLine(fraction: Float, played: Color) {
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(ResumeLineHeight)
            .background(ResumeLineTrack)
            .drawBehind {
                val width = size.width * fraction.coerceIn(0f, 1f)
                if (width > 0f) drawRect(color = played, size = Size(width, size.height))
            },
    )
}

/**
 * The tile's metadata claim, and never more of one than MediaStore supplied. With no
 * pixels reported the badge states the dynamic range alone if the probe found one, and
 * otherwise says so outright — the solid pill is reserved for a fact, so the withheld
 * form loses the fill and takes the dim ink and a hairline instead. It stays the same
 * pill in the same seat: a missing badge would read as a tile that failed to draw.
 */
@Composable
private fun MetadataBadge(hdr: HdrType?, item: MediaItem) {
    val colors = LocalFlickColors.current
    val range = hdrBadge(hdr)
    val withheld = !item.knowsResolution && range == null
    val text = if (range != null) {
        // A probed dynamic range is a fact on its own, and the only one left to state
        // when MediaStore reported no pixels to pair it with.
        if (item.knowsResolution) {
            stringResource(R.string.library_tile_badge, item.resolutionLabel, range)
        } else {
            range
        }
    } else {
        if (item.knowsResolution) item.resolutionLabel else stringResource(R.string.library_badge_unknown)
    }
    Text(
        text = text,
        style = FlickText.monoBadge.copy(color = if (withheld) colors.onSurfaceDim else colors.onSurface),
        modifier = Modifier
            .clip(PillShape)
            .background(if (withheld) colors.canvas.copy(alpha = WithheldFill) else colors.canvas)
            .then(if (withheld) Modifier.border(1.dp, colors.outline, PillShape) else Modifier)
            .padding(horizontal = 9.dp, vertical = 6.dp),
    )
}

/**
 * The remembered refusal. Crimson is the app's failure role and this is a failure that
 * genuinely happened — but it is one attempt on one TV, so the copy stays in the past
 * tense and the tile stays tappable: the sheet behind it still offers to cast.
 */
@Composable
private fun RefusedBadge() {
    val colors = LocalFlickColors.current
    Row(
        modifier = Modifier
            .clip(PillShape)
            .background(colors.trouble)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = FlickIcons.Warning,
            contentDescription = null,
            // FlickColors pairs no ink with `trouble`; this is the one role that clears
            // it in both sets — white on the light crimson, near-black on the dark one.
            tint = colors.onPrimary,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = stringResource(R.string.library_badge_unplayable),
            style = FlickText.monoBadge.copy(color = colors.onPrimary),
        )
    }
}

/**
 * The remembered silence, in the register [MetadataBadge] withholds a claim in rather than
 * the one [RefusedBadge] states a failure in.
 *
 * Nothing here is allowed to discourage casting, because the film plays: no crimson, no
 * held-back still, no icon. A text-only pill in the dim ink is the whole treatment — the
 * icon set carries a speaker with sound coming out of it and nothing that means the
 * absence of one, and a drawn-on mute glyph would be a second thing to read at 12 dp when
 * the two words already say it.
 */
@Composable
private fun NoSoundBadge() {
    val colors = LocalFlickColors.current
    Text(
        text = stringResource(R.string.library_badge_no_sound),
        style = FlickText.monoBadge.copy(color = colors.onSurfaceDim),
        modifier = Modifier
            .clip(PillShape)
            .background(colors.canvas.copy(alpha = WithheldFill))
            .border(1.dp, colors.outline, PillShape)
            .padding(horizontal = 9.dp, vertical = 6.dp),
    )
}

/** DV / HDR10 only once probed: null is "nobody has looked", never "no HDR". */
@Composable
private fun hdrBadge(hdr: HdrType?): String? = when (hdr) {
    HdrType.DOLBY_VISION -> stringResource(R.string.media_dv_badge)
    HdrType.HDR10 -> stringResource(R.string.media_hdr10_badge)
    HdrType.NONE, null -> null
}

private fun metaLabel(item: MediaItem, unknown: String): String {
    val length = if (item.knowsDuration) Format.durationHuman(item.durationMs) else unknown
    val size = if (item.sizeBytes > 0L) Format.bytes(item.sizeBytes) else unknown
    return "$length · $size"
}

// The mock's 179 dp column carries a 122 dp still. Short screens drop to 16:9 so the
// poster does not eat the row on a landscape phone.
private const val PosterRatio = 179f / 122f
private const val CompactPosterRatio = 16f / 9f

// A withheld badge is the same pill with its confidence taken out: enough fill to keep
// mono type legible over any frame, not enough to read as the solid claim beside it.
private const val WithheldFill = 0.62f

// Held back, not greyed out. Far enough down that the tile reads as answered-for at a
// glance, far enough up that the frame is still the film.
private const val RefusedSaturation = 0.45f

private val TileElevation = 10.dp

/** The size the face was last laid out at; plain, so a layout pass never recomposes the tile. */
private class FaceSize {
    var px: IntSize = IntSize.Zero
}

// A rule, not a bar. Thin enough to read as a property of the still rather than as a
// control laid over it, thick enough to survive a 1x-density phone rounding it away.
private val ResumeLineHeight = 3.dp
private val ResumeLineTrack = Color.White.copy(alpha = 0.3f)
