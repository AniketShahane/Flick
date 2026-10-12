package com.flick.sender.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import com.flick.sender.media.FilmArt
import com.flick.sender.media.FilmPosterPolicy
import com.flick.sender.media.VideoNames
import com.flick.sender.model.MediaItem
import com.flick.sender.net.FilmPosters
import com.flick.sender.ui.components.videoImageLoader
import com.flick.sender.ui.theme.LocalFlickColors
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val LocalFilmPosters = staticCompositionLocalOf { true }

/**
 * This file's TMDB art, or null while there is none to draw — the setting is off, the build
 * has no key, the name is not a title, or TMDB has nothing that matches it. Null is always
 * "draw the film's own frame", never an empty seat.
 */
@Composable
fun MediaItem.rememberFilmArt(): FilmArt? {
    val enabled = LocalFilmPosters.current && FilmPosters.available
    val context = LocalContext.current.applicationContext
    val query = remember(name, enabled) {
        if (enabled) FilmPosterPolicy.queryFor(VideoNames.parse(name)) else null
    }
    val art by produceState(initialValue = query?.let { FilmPosters.cached(context, it) }, query) {
        // Re-read on a new query: produceState keeps the previous value across a key change.
        value = query?.let { FilmPosters.cached(context, it) }
        if (query != null && value == null) value = FilmPosters.art(context, query)
    }
    return art?.takeIf { enabled }
}

/**
 * [sizePx], when given, is the size a tile drew this art at: asking for exactly that makes the
 * card the tile turns into a memory hit on the tile's own decode. Without it the request is
 * sized by its layout, and a seat measured larger than the tile misses the cache — so the copy
 * cached under the plain url (the key Coil gives a request with no transformations) stands in
 * while the larger one decodes, rather than an empty ground.
 */
@Composable
private fun rememberArtRequest(path: String, width: String, sizePx: IntSize? = null): ImageRequest? {
    val context = LocalContext.current
    return remember(path, width, sizePx) {
        FilmPosterPolicy.imageUrl(path, width)?.let { url ->
            ImageRequest.Builder(context)
                .data(url)
                .crossfade(true)
                .placeholderMemoryCacheKey(MemoryCache.Key(url))
                .sizedTo(sizePx)
                .build()
        }
    }
}

private fun ImageRequest.Builder.sizedTo(sizePx: IntSize?): ImageRequest.Builder = apply {
    if (sizePx != null && sizePx.width > 0 && sizePx.height > 0) {
        size(sizePx.width, sizePx.height)
        precision(Precision.INEXACT)
    }
}

/**
 * The library tile's art. A film's backdrop fills the landscape seat edge to edge with its
 * title logo set low on the leading side, opposite the running time; with no backdrop the
 * poster stands whole on a wash of itself. [requestSizePx] is the size the grid's tile drew
 * this at, for a copy of it drawn elsewhere — see [rememberArtRequest].
 *
 * The ground under the art is the tile's own surface, never black: it is what shows for the
 * frame or two before a decode lands, and a black rect on a light grid reads as a flash.
 */
@Composable
fun FilmTileArt(
    art: FilmArt,
    imageLoader: ImageLoader,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    colorFilter: ColorFilter? = null,
    requestSizePx: IntSize? = null,
) {
    val backdrop = art.backdrop?.let { rememberArtRequest(it, FilmPosterPolicy.BACKDROP_WIDTH, requestSizePx) }
    if (backdrop == null) {
        art.poster?.let {
            FilmPosterOnGround(it, imageLoader, contentDescription, modifier, colorFilter, requestSizePx)
        }
        return
    }
    val logo = art.logo?.let { rememberArtRequest(it, FilmPosterPolicy.LOGO_WIDTH) }
    Box(modifier.background(LocalFlickColors.current.surfaceTonal)) {
        AsyncImage(
            model = backdrop,
            contentDescription = contentDescription,
            imageLoader = imageLoader,
            contentScale = ContentScale.Crop,
            colorFilter = colorFilter,
            modifier = Modifier.fillMaxSize(),
        )
        if (logo != null) {
            // A soft pool of shade under the logo's corner: a white wordmark on a pale sky
            // otherwise loses its first letters.
            Box(
                Modifier.matchParentSize().drawBehind {
                    drawRect(
                        Brush.radialGradient(
                            colors = listOf(Color.Black.copy(alpha = LogoShadeAlpha), Color.Transparent),
                            center = Offset(0f, size.height),
                            radius = size.width * LogoShadeReach,
                        ),
                    )
                },
            )
            AsyncImage(
                model = logo,
                contentDescription = null,
                imageLoader = imageLoader,
                contentScale = ContentScale.Fit,
                alignment = Alignment.BottomStart,
                colorFilter = colorFilter,
                // Bounded on both axes: a wide wordmark is held by the width, a stacked one
                // by the height, and either way the running time keeps its corner.
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 12.dp, bottom = 12.dp)
                    .fillMaxWidth(LogoWidthFraction)
                    .fillMaxHeight(LogoHeightFraction),
            )
        }
    }
}

@Composable
private fun rememberPosterRequest(posterPath: String, width: String, sizePx: IntSize? = null): ImageRequest? =
    rememberHeroRequest(posterPath, width, FilmPosterPolicy.TILE_WIDTH, sizePx)

@Composable
private fun rememberHeroRequest(
    path: String,
    width: String,
    tileWidth: String,
    sizePx: IntSize? = null,
): ImageRequest? {
    val context = LocalContext.current
    return remember(path, width, tileWidth, sizePx) {
        val url = FilmPosterPolicy.imageUrl(path, width) ?: return@remember null
        // The tile's smaller copy stands in while the larger one arrives, so art that flies
        // from the grid into the detail page never flashes empty on landing. At the tile's
        // own width it is this request's own url, decoded at whatever size it was drawn.
        val tileUrl = FilmPosterPolicy.imageUrl(path, tileWidth) ?: url
        ImageRequest.Builder(context)
            .data(url)
            .crossfade(true)
            .placeholderMemoryCacheKey(MemoryCache.Key(tileUrl))
            .sizedTo(sizePx)
            .build()
    }
}

/**
 * The poster whole, at its own 2:3, standing on a blurred wash of itself that fills whatever
 * shape the seat is. The library's seats are landscape; cropping a poster into one keeps a
 * strip of somebody's face and loses the title, which is the one part that says which film.
 *
 * The wash is the same poster blurred once into a bitmap a few dozen pixels wide, not a live
 * blur: a grid of these would otherwise re-blur every visible tile on every frame of a fling.
 */
@Composable
fun FilmPosterOnGround(
    posterPath: String,
    imageLoader: ImageLoader,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    colorFilter: ColorFilter? = null,
    requestSizePx: IntSize? = null,
) {
    val request = rememberPosterRequest(posterPath, FilmPosterPolicy.TILE_WIDTH, requestSizePx) ?: return
    val ground = rememberSoftArtRequest(
        path = posterPath,
        width = FilmPosterPolicy.TILE_WIDTH,
        boxWidthPx = GroundWidthPx,
        boxHeightPx = GroundHeightPx,
        radiusPx = GroundRadiusPx,
        biasY = 0.5f,
        crossfade = true,
    )
    Box(modifier.background(LocalFlickColors.current.surfaceTonal)) {
        if (ground != null) {
            AsyncImage(
                model = ground,
                contentDescription = null,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                colorFilter = colorFilter,
                alpha = GroundAlpha,
                modifier = Modifier.fillMaxSize(),
            )
        }
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            imageLoader = imageLoader,
            contentScale = ContentScale.Fit,
            colorFilter = colorFilter,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * TMDB art blurred once, off the main thread, into a [boxWidthPx] × [boxHeightPx] bitmap
 * already cropped to that box at [biasY]. Drawn with [ContentScale.Crop] at the same
 * alignment it is only scaled up, so the box's aspect should be the seat's.
 */
@Composable
internal fun rememberSoftArtRequest(
    path: String,
    width: String,
    boxWidthPx: Int,
    boxHeightPx: Int,
    radiusPx: Int,
    biasY: Float,
    crossfade: Boolean = false,
): ImageRequest? {
    val context = LocalContext.current
    return remember(path, width, boxWidthPx, boxHeightPx, radiusPx, biasY, crossfade) {
        FilmPosterPolicy.imageUrl(path, width)?.let { url ->
            ImageRequest.Builder(context)
                .data(url)
                .softBlur(boxWidthPx, boxHeightPx, radiusPx, biasY)
                .crossfade(crossfade)
                .build()
        }
    }
}

/** The size, scale and transformation every soft-blurred request shares. */
internal fun ImageRequest.Builder.softBlur(
    boxWidthPx: Int,
    boxHeightPx: Int,
    radiusPx: Int,
    biasY: Float,
): ImageRequest.Builder = this
    .size(boxWidthPx.coerceAtLeast(1), boxHeightPx.coerceAtLeast(1))
    .scale(Scale.FILL)
    .precision(Precision.INEXACT)
    .transformations(SoftBlurTransformation(radiusPx, biasY))

/**
 * The detail page's ground: [path]'s art at TMDB [width], pre-blurred into a bitmap
 * [SoftBackdropWidthPx] across with the viewport's own aspect, and drawn top-aligned so the
 * crop keeps the backdrop's subject. No crossfade: the page fades it in with everything else.
 */
@Composable
fun FilmSoftBackdrop(
    path: String,
    width: String,
    imageLoader: ImageLoader,
    viewportAspect: Float,
    modifier: Modifier = Modifier,
) {
    val request = rememberSoftArtRequest(
        path = path,
        width = width,
        boxWidthPx = SoftBackdropWidthPx,
        boxHeightPx = softBackdropHeightPx(viewportAspect),
        radiusPx = SoftBackdropRadiusPx,
        biasY = 0f,
    ) ?: return
    AsyncImage(
        model = request,
        contentDescription = null,
        imageLoader = imageLoader,
        contentScale = ContentScale.Crop,
        alignment = Alignment.TopCenter,
        modifier = modifier,
    )
}

/** Height of the soft backdrop's box for a viewport [aspect] of height over width. */
internal fun softBackdropHeightPx(aspect: Float): Int =
    (SoftBackdropWidthPx * aspect.coerceIn(SoftBackdropMinAspect, SoftBackdropMaxAspect))
        .roundToInt()
        .coerceAtLeast(1)

/**
 * Sized against the 24 dp live blur this replaced: on a phone a few hundred dp wide that blur
 * spread about 3.4% of the width (its sigma), and a 3-pass box of radius 6 spreads 6.5 px —
 * the same 3.4% of a 200 px box.
 */
internal const val SoftBackdropWidthPx = 200
internal const val SoftBackdropRadiusPx = 6
private const val SoftBackdropMinAspect = 0.25f
private const val SoftBackdropMaxAspect = 4f

/**
 * The poster filling its seat edge to edge (Crop): the detail page's card, or its backdrop.
 *
 * Over the small poster the tap warmed ([warmFilmPoster]), asked for at that warm's size so it
 * is the same decode: the card turns over to this face a tenth of a second after the tap, far
 * sooner than the full-size poster arrives. Until either lands, the full one's placeholder is
 * whatever copy of the small poster is in memory ([warmTilePoster]), so the back is never an
 * empty ground.
 */
@Composable
fun FilmPosterBackdrop(
    posterPath: String,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
) {
    val context = LocalContext.current
    val request = rememberPosterRequest(posterPath, FilmPosterPolicy.HERO_WIDTH) ?: return
    val base = remember(posterPath) {
        FilmPosterPolicy.imageUrl(posterPath, FilmPosterPolicy.TILE_WIDTH)?.let { warmPosterRequest(context, it) }
    }
    Box(modifier) {
        if (base != null) {
            AsyncImage(
                model = base,
                contentDescription = null,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                alignment = alignment,
                modifier = Modifier.fillMaxSize(),
            )
        }
        AsyncImage(
            model = request,
            contentDescription = null,
            imageLoader = imageLoader,
            contentScale = ContentScale.Crop,
            alignment = alignment,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** The film's landscape backdrop filling a full-screen seat, as the detail page's ground. */
@Composable
fun FilmBackdropHero(
    backdropPath: String,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
) {
    val request = rememberHeroRequest(
        backdropPath,
        FilmPosterPolicy.HERO_BACKDROP_WIDTH,
        FilmPosterPolicy.BACKDROP_WIDTH,
    ) ?: return
    AsyncImage(
        model = request,
        contentDescription = null,
        imageLoader = imageLoader,
        contentScale = ContentScale.Crop,
        alignment = alignment,
        modifier = modifier,
    )
}

/**
 * TMDB's title logo at no more than its own pixel size, fitted whole inside [modifier]'s
 * maximum bounds. [onFailed] fires when the image cannot be drawn at all, so the caller can
 * set the title in type instead of leaving a hole.
 *
 * A logo whose ink is dark is drawn white: TMDB carries black wordmarks made for light
 * posters, and on the dark page they would vanish.
 */
@Composable
fun FilmTitleLogo(
    logoPath: String,
    imageLoader: ImageLoader,
    contentDescription: String,
    onFailed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val request = remember(logoPath) {
        FilmPosterPolicy.imageUrl(logoPath, FilmPosterPolicy.HERO_LOGO_WIDTH)?.let { url ->
            ImageRequest.Builder(context)
                .data(url)
                .crossfade(true)
                // Software, so its pixels can be read to choose its ink.
                .allowHardware(false)
                .build()
        }
    }
    if (request == null) {
        LaunchedEffect(logoPath) { onFailed() }
        return
    }
    var ink by remember(logoPath) { mutableStateOf<ColorFilter?>(null) }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        imageLoader = imageLoader,
        contentScale = ContentScale.Fit,
        colorFilter = ink,
        onSuccess = { success ->
            val bitmap = (success.result.drawable as? BitmapDrawable)?.bitmap
            ink = if (bitmap != null && LogoInk.isDark(bitmap.sampledOpaqueLuma())) {
                ColorFilter.tint(Color.White)
            } else {
                null
            }
        },
        onError = { onFailed() },
        modifier = modifier,
    )
}

/**
 * Mean luma of a sparse grid of [this]'s pixels. Runs on the main thread inside the load
 * callback, before the crossfade's first frame, so the grid stays at most about 64 × 64.
 */
private fun Bitmap.sampledOpaqueLuma(): Float? {
    if (config == Bitmap.Config.HARDWARE || width <= 0 || height <= 0) return null
    val stride = max(1, max(width, height) / LogoInk.SAMPLE_SPAN)
    val row = IntArray(width)
    val samples = IntArray(((width + stride - 1) / stride) * ((height + stride - 1) / stride))
    var count = 0
    for (y in 0 until height step stride) {
        getPixels(row, 0, width, 0, y, width, 1)
        for (x in 0 until width step stride) samples[count++] = row[x]
    }
    return LogoInk.meanOpaqueLuma(samples, count)
}

/** Whether a title logo's own ink is too dark to stand on the cinema page. */
internal object LogoInk {
    const val SAMPLE_SPAN = 64
    const val DARK_LUMA = 0.35f
    private const val OPAQUE_ALPHA = 128

    /** Rec. 601 luma, 0..1, averaged over the samples at least half opaque; null if none are. */
    fun meanOpaqueLuma(argb: IntArray, count: Int = argb.size): Float? {
        var sum = 0f
        var opaque = 0
        for (i in 0 until count) {
            val pixel = argb[i]
            if ((pixel ushr 24) < OPAQUE_ALPHA) continue
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            sum += (0.299f * r + 0.587f * g + 0.114f * b) / 255f
            opaque++
        }
        return if (opaque == 0) null else sum / opaque
    }

    fun isDark(luma: Float?): Boolean = luma != null && luma < DARK_LUMA
}

// Leaves the trailing corner to the running time, which is about a quarter of a tile wide.
private const val LogoWidthFraction = 0.42f
private const val LogoHeightFraction = 0.36f
private const val LogoShadeAlpha = 0.6f
private const val LogoShadeReach = 0.75f
// The tile's 179:122 seat, small. The 28 dp live blur this replaced spread about 9% of a
// tile's width; radius 6 spreads 6.5 px, the same share of 72.
private const val GroundWidthPx = 72
private const val GroundHeightPx = 49
private const val GroundRadiusPx = 6
private const val GroundAlpha = 0.7f

/**
 * The small poster in memory, at a thumbnail's size, for as long as its tile is on screen, so
 * the detail card's back has a picture from its first frame even if the tap's own warm has not
 * landed — and the poster is on disk before anyone taps. Tiny, because the memory cache is
 * shared with the grid and every tile on screen pays it; an unsized decode would be a
 * display-sized bitmap that evicts the tiles around it.
 */
fun warmTilePoster(context: Context, imageLoader: ImageLoader, posterPath: String) {
    val url = FilmPosterPolicy.imageUrl(posterPath, FilmPosterPolicy.TILE_WIDTH) ?: return
    imageLoader.enqueue(
        ImageRequest.Builder(context.applicationContext)
            .data(url)
            .size(PlaceholderPosterWidthPx, PlaceholderPosterHeightPx)
            .precision(Precision.INEXACT)
            .build(),
    )
}

/**
 * At the tap: the small poster at the size the card's back draws it under the full one
 * ([warmPosterRequest]), and the full-size one onto disk only, so the seat's own request
 * decodes it at the seat's size without fetching it. In memory it would be a display-sized
 * bitmap evicting the grid the card is about to land back on.
 */
fun warmFilmPoster(context: Context, imageLoader: ImageLoader, posterPath: String) {
    val appContext = context.applicationContext
    FilmPosterPolicy.imageUrl(posterPath, FilmPosterPolicy.TILE_WIDTH)?.let { url ->
        imageLoader.enqueue(warmPosterRequest(appContext, url))
    }
    val heroUrl = FilmPosterPolicy.imageUrl(posterPath, FilmPosterPolicy.HERO_WIDTH) ?: return
    imageLoader.enqueue(
        ImageRequest.Builder(appContext)
            .data(heroUrl)
            .size(WarmPosterWidthPx, WarmPosterHeightPx)
            .precision(Precision.INEXACT)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .build(),
    )
}

/** The request the tap's warm and the card's base layer share, so they are one decode. */
internal fun warmPosterRequest(context: Context, url: String): ImageRequest =
    ImageRequest.Builder(context)
        .data(url)
        .size(WarmPosterWidthPx, WarmPosterHeightPx)
        .precision(Precision.INEXACT)
        .build()

// Two thirds of w342: about 0.3 MB, enough for the card while the full poster loads.
internal const val WarmPosterWidthPx = 228
internal const val WarmPosterHeightPx = 342

// A third of w342, about 80 KB: paid by every tile on screen, and only ever a stand-in.
private const val PlaceholderPosterWidthPx = 114
private const val PlaceholderPosterHeightPx = 171

/**
 * Looks up art for every title in [items] and puts the images a tile draws into Coil's disk
 * cache, so a film added since the last visit already has its art when it is first scrolled
 * to — and still has it offline. Titles already known cost nothing: the lookup answers from
 * its own cache and Coil from its disk.
 */
suspend fun prefetchFilmArt(context: Context, items: List<MediaItem>) {
    if (!FilmPosters.available) return
    val appContext = context.applicationContext
    val loader = videoImageLoader(appContext)
    val queries = withContext(Dispatchers.Default) {
        items.mapNotNull { FilmPosterPolicy.queryFor(VideoNames.parse(it.name)) }.distinctBy { it.cacheKey }
    }
    for (query in queries) {
        val art = FilmPosters.art(appContext, query) ?: continue
        listOfNotNull(
            art.backdrop?.let { FilmPosterPolicy.imageUrl(it, FilmPosterPolicy.BACKDROP_WIDTH) },
            art.logo?.let { FilmPosterPolicy.imageUrl(it, FilmPosterPolicy.LOGO_WIDTH) },
            art.poster?.let { FilmPosterPolicy.imageUrl(it, FilmPosterPolicy.TILE_WIDTH) },
        ).forEach { url ->
            // Disk only: the memory cache belongs to what is on screen.
            loader.enqueue(
                ImageRequest.Builder(appContext)
                    .data(url)
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .build(),
            )
        }
    }
}
