package com.flick.sender.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.flick.sender.media.FilmDetails
import com.flick.sender.media.FilmPosterPolicy
import com.flick.sender.media.FilmRatings
import com.flick.sender.media.VideoNames
import com.flick.sender.model.MediaItem
import com.flick.sender.net.FilmDetailsStore

/**
 * This file's TMDB synopsis and credits, or null while there are none — the film-art setting
 * is off (it is the user's word on asking TMDB anything), the build has no key, the name is
 * not a title, or TMDB has nothing that matches it. Only a detail page calls this.
 *
 * [tmdbId] is the match the film's art came from, when it is known, so the synopsis is that
 * same film's.
 */
@Composable
fun MediaItem.rememberFilmDetails(tmdbId: Long?): FilmDetails? {
    val enabled = LocalFilmPosters.current && FilmDetailsStore.available
    val context = LocalContext.current.applicationContext
    val query = remember(name, enabled) {
        if (enabled) FilmPosterPolicy.queryFor(VideoNames.parse(name)) else null
    }
    // Remembered: a prefs read and a JSON decode are not for every recomposition.
    val stored = remember(query, tmdbId) { query?.let { FilmDetailsStore.cached(context, it, tmdbId) } }
    val details by produceState(initialValue = stored, query, tmdbId) {
        // Re-read on a new key: produceState keeps the previous value across a key change.
        value = stored
        if (query == null) return@produceState
        FilmDetailsStore.details(context, query, tmdbId)?.let { value = it }
    }
    return details?.takeIf { enabled }
}

/** IMDb and Rotten Tomatoes for [imdbId], or null while neither is known. */
@Composable
fun rememberFilmRatings(imdbId: String?): FilmRatings? {
    val context = LocalContext.current.applicationContext
    val id = imdbId?.takeIf { FilmDetailsStore.ratingsAvailable }
    val stored = remember(id) { id?.let { FilmDetailsStore.cachedRatings(context, it) } }
    val ratings by produceState(initialValue = stored, id) {
        value = stored
        if (id == null) return@produceState
        FilmDetailsStore.ratings(context, id)?.let { value = it }
    }
    return ratings?.takeIf { id != null && !it.isEmpty }
}
