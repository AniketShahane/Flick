package com.flick.sender.media

import org.json.JSONObject
import java.net.URLEncoder

/** What a filename is asked of TMDB as: a film, or the show an episode belongs to. */
data class FilmPosterQuery(val title: String, val year: Int?, val show: Boolean) {
    /** Folded so two spellings of one release share a cache entry and a single lookup. */
    val cacheKey: String
        get() = "${if (show) "tv" else "movie"}|${FoldedText.fold(title)}|${year ?: ""}"
}

/**
 * Which file gets a poster, what is asked for it, and which answer is believed.
 *
 * Pure, so the rule that decides whether a stranger's poster lands on someone's home video
 * can be tested without a network.
 */
object FilmPosterPolicy {
    private const val API = "https://api.themoviedb.org/3"

    /**
     * TMDB's image host. Its docs prefer reading this from `/configuration`; it has not moved
     * in years, and a second request before every first poster is not worth that insurance.
     */
    private const val IMAGE_BASE = "https://image.tmdb.org/t/p"

    /** Poster widths TMDB serves: a grid tile and a full-screen backdrop. */
    const val TILE_WIDTH = "w342"
    const val HERO_WIDTH = "w780"

    /**
     * Only names the parser already judged safe to send to a search API. A camera roll's
     * `PXL_20240101_120000.mp4` never leaves the phone.
     */
    fun queryFor(parsed: ParsedVideoName): FilmPosterQuery? {
        val title = parsed.searchQuery.trim()
        if (title.isEmpty() || FoldedText.fold(title).isEmpty()) return null
        return FilmPosterQuery(
            title = title,
            year = parsed.year,
            show = parsed.season != null || parsed.episode != null,
        )
    }

    /**
     * The search URL. A 32-character v3 key travels as `api_key`; a v4 read token (a JWT,
     * so it starts `eyJ`) goes in the `Authorization` header instead and is left out here.
     */
    fun searchUrl(query: FilmPosterQuery, key: String): String {
        val params = buildList {
            add("query" to query.title)
            add("include_adult" to "false")
            query.year?.let { add((if (query.show) "first_air_date_year" else "year") to it.toString()) }
            if (!isBearerToken(key)) add("api_key" to key)
        }
        val path = if (query.show) "search/tv" else "search/movie"
        return "$API/$path?" + params.joinToString("&") { (k, v) -> "$k=${encode(v)}" }
    }

    fun isBearerToken(key: String): Boolean = key.startsWith("eyJ")

    /**
     * Votes a match must carry when the name gave no year. `Birthday.mp4` is far more often a
     * home video than the obscure film of that name; `Inception.mkv` is the famous one.
     */
    const val MIN_VOTES_WITHOUT_YEAR = 100

    /**
     * The first result whose title is the filename's title, word for word once case, accents
     * and punctuation are folded away, and that has a poster. TMDB ranks fuzzily — a search
     * for `Beach Day` returns whatever it can — so a near miss is no poster at all, and the
     * file keeps its own frame. Returns the poster path (`/abc.jpg`), or null.
     */
    fun pickPoster(query: FilmPosterQuery, body: String): String? {
        val results = JSONObject(body).optJSONArray("results") ?: return null
        val wanted = FoldedText.fold(query.title)
        val titleFields = if (query.show) listOf("name", "original_name") else listOf("title", "original_title")
        for (i in 0 until results.length()) {
            val result = results.optJSONObject(i) ?: continue
            val poster = result.optString("poster_path").takeIf { it.startsWith("/") } ?: continue
            if (query.year == null && result.optInt("vote_count", 0) < MIN_VOTES_WITHOUT_YEAR) continue
            if (titleFields.any { FoldedText.fold(result.optString(it)) == wanted }) return poster
        }
        return null
    }

    /** Null for anything that is not a bare TMDB image path, so no URL is built from junk. */
    fun imageUrl(posterPath: String, width: String): String? =
        posterPath.takeIf { PosterPath.matches(it) }?.let { "$IMAGE_BASE/$width$it" }

    private val PosterPath = Regex("^/[A-Za-z0-9_-]+\\.(?:jpg|jpeg|png|webp)$")

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
