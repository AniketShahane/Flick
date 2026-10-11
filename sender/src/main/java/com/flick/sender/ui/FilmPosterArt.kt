package com.flick.sender.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.flick.sender.media.FilmArt
import com.flick.sender.media.FilmPosterPolicy
import com.flick.sender.media.VideoNames
import com.flick.sender.model.MediaItem
import com.flick.sender.net.FilmPosters
import com.flick.sender.ui.components.videoImageLoader
import kotlin.math.max
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

@Composable
private fun rememberArtRequest(path: String, width: String): ImageRequest? {
    val context = LocalContext.current
    return remember(path, width) {
        FilmPosterPolicy.imageUrl(path, width)?.let { url ->
            ImageRequest.Builder(context).data(url).crossfade(true).build()
        }
    }
}

/**
 * The library tile's art. A film's backdrop fills the landscape seat edge to edge with its
 * title logo set low on the leading side, opposite the running time; with no backdrop the
 * poster stands whole on a wash of itself.
 */
@Composable
fun FilmTileArt(
    art: FilmArt,
    imageLoader: ImageLoader,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    colorFilter: ColorFilter? = null,
) {
    val backdrop = art.backdrop?.let { rememberArtRequest(it, FilmPosterPolicy.BACKDROP_WIDTH) }
    if (backdrop == null) {
        art.poster?.let { FilmPosterOnGround(it, imageLoader, contentDescription, modifier, colorFilter) }
        return
    }
    val logo = art.logo?.let { rememberArtRequest(it, FilmPosterPolicy.LOGO_WIDTH) }
    Box(modifier.background(Color.Black)) {
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
private fun rememberPosterRequest(posterPath: String, width: String): ImageRequest? =
    rememberHeroRequest(posterPath, width, FilmPosterPolicy.TILE_WIDTH)

@Composable
private fun rememberHeroRequest(path: String, width: String, tileWidth: String): ImageRequest? {
    val context = LocalContext.current
    return remember(path, width, tileWidth) {
        val url = FilmPosterPolicy.imageUrl(path, width) ?: return@remember null
        // The tile's smaller copy stands in while the larger one arrives, so art that flies
        // from the grid into the detail page never flashes empty on landing.
        val tileUrl = FilmPosterPolicy.imageUrl(path, tileWidth)
        ImageRequest.Builder(context)
            .data(url)
            .crossfade(true)
            .apply { if (tileUrl != null && tileUrl != url) placeholderMemoryCacheKey(MemoryCache.Key(tileUrl)) }
            .build()
    }
}

/**
 * The poster whole, at its own 2:3, standing on a blurred wash of itself that fills whatever
 * shape the seat is. The library's seats are landscape; cropping a poster into one keeps a
 * strip of somebody's face and loses the title, which is the one part that says which film.
 */
@Composable
fun FilmPosterOnGround(
    posterPath: String,
    imageLoader: ImageLoader,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    colorFilter: ColorFilter? = null,
) {
    val request = rememberPosterRequest(posterPath, FilmPosterPolicy.TILE_WIDTH) ?: return
    Box(modifier.background(Color.Black)) {
        AsyncImage(
            model = request,
            contentDescription = null,
            imageLoader = imageLoader,
            contentScale = ContentScale.Crop,
            colorFilter = colorFilter,
            alpha = GroundAlpha,
            // Blur is a no-op before Android 12; the dimmer alpha there keeps the sharp copy
            // underneath from competing with the poster in front of it.
            modifier = Modifier.fillMaxSize().blur(GroundBlur),
        )
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

/** The poster filling its seat edge to edge (Crop): the detail page's card, or its backdrop. */
@Composable
fun FilmPosterBackdrop(
    posterPath: String,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
) {
    val request = rememberPosterRequest(posterPath, FilmPosterPolicy.HERO_WIDTH) ?: return
    AsyncImage(
        model = request,
        contentDescription = null,
        imageLoader = imageLoader,
        contentScale = ContentScale.Crop,
        alignment = alignment,
        modifier = modifier,
    )
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
private val GroundBlur = 28.dp
private val GroundAlpha = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 0.7f else 0.35f

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
