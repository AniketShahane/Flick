package com.flick.sender.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.memory.MemoryCache
import coil.request.ImageRequest
import com.flick.sender.media.FilmPosterPolicy
import com.flick.sender.media.VideoNames
import com.flick.sender.model.MediaItem
import com.flick.sender.net.FilmPosters

val LocalFilmPosters = staticCompositionLocalOf { true }

/**
 * This file's TMDB poster path, or null while there is none to draw — the setting is off, the
 * build has no key, the name is not a title, or TMDB has nothing that matches it. Null is
 * always "draw the film's own frame", never an empty seat.
 */
@Composable
fun MediaItem.rememberFilmPosterPath(): String? {
    val enabled = LocalFilmPosters.current && FilmPosters.available
    val context = LocalContext.current.applicationContext
    val query = remember(name, enabled) {
        if (enabled) FilmPosterPolicy.queryFor(VideoNames.parse(name)) else null
    }
    val path by produceState(initialValue = query?.let { FilmPosters.cached(context, it) }, query) {
        // Re-read on a new query: produceState keeps the previous value across a key change.
        value = query?.let { FilmPosters.cached(context, it) }
        if (query != null && value == null) value = FilmPosters.poster(context, query)
    }
    return path?.takeIf { enabled }
}

@Composable
private fun rememberPosterRequest(posterPath: String, width: String): ImageRequest? {
    val context = LocalContext.current
    return remember(posterPath, width) {
        val url = FilmPosterPolicy.imageUrl(posterPath, width) ?: return@remember null
        // The tile's smaller copy stands in while the larger one arrives, so a poster that
        // flies from the grid into the detail sheet never flashes empty on landing.
        val tileUrl = FilmPosterPolicy.imageUrl(posterPath, FilmPosterPolicy.TILE_WIDTH)
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

/** The poster filling a full-screen seat, as the detail sheet's backdrop. */
@Composable
fun FilmPosterBackdrop(
    posterPath: String,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier,
) {
    val request = rememberPosterRequest(posterPath, FilmPosterPolicy.HERO_WIDTH) ?: return
    AsyncImage(
        model = request,
        contentDescription = null,
        imageLoader = imageLoader,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}

private val GroundBlur = 28.dp
private val GroundAlpha = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 0.7f else 0.35f
