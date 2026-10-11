package com.flick.sender.media

import org.json.JSONObject
import java.net.URLEncoder

/** What a filename is asked of TMDB as: a film, or the show an episode belongs to. */
data class FilmPosterQuery(val title: String, val year: Int?, val show: Boolean) {
    /**
     * Folded so two spellings of one release share a cache entry and a single lookup. The
     * leading version retires every entry chosen under an older art rule.
     */
    val cacheKey: String
        get() = "v2|${if (show) "tv" else "movie"}|${FoldedText.fold(title)}|${year ?: ""}"
}

/** The art TMDB has for one title. Any field may be missing; all missing is a miss. */
data class FilmArt(val poster: String?, val backdrop: String?, val logo: String?) {
    val isEmpty: Boolean get() = poster == null && backdrop == null
}

/** The matched search result: its TMDB id and the art the search itself carried. */
data class FilmMatch(val id: Long, val poster: String?, val backdrop: String?)

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

    /** Widths TMDB serves for each kind of image, at the size each seat draws it. */
    const val TILE_WIDTH = "w342"
    const val BACKDROP_WIDTH = "w780"
    const val LOGO_WIDTH = "w300"

    // A full-screen phone backdrop is wider than TMDB's largest poster width, w780, which
    // upscales visibly; the original is the next size up.
    const val HERO_WIDTH = "original"

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
    fun pickMatch(query: FilmPosterQuery, body: String): FilmMatch? {
        val results = JSONObject(body).optJSONArray("results") ?: return null
        val wanted = FoldedText.fold(query.title)
        val titleFields = if (query.show) listOf("name", "original_name") else listOf("title", "original_title")
        for (i in 0 until results.length()) {
            val result = results.optJSONObject(i) ?: continue
            val poster = result.path("poster_path")
            val backdrop = result.path("backdrop_path")
            if (poster == null && backdrop == null) continue
            if (query.year == null && result.optInt("vote_count", 0) < MIN_VOTES_WITHOUT_YEAR) continue
            val id = result.optLong("id", 0L).takeIf { it > 0L } ?: continue
            if (titleFields.any { FoldedText.fold(result.optString(it)) == wanted }) return FilmMatch(id, poster, backdrop)
        }
        return null
    }

    /** Backdrops and logos for one match, in English and textless only. */
    fun imagesUrl(query: FilmPosterQuery, id: Long, key: String): String {
        val path = if (query.show) "tv" else "movie"
        val auth = if (isBearerToken(key)) "" else "&api_key=${encode(key)}"
        return "$API/$path/$id/images?include_image_language=en,null$auth"
    }

    /**
     * The tile's landscape art. A textless backdrop with the English title logo laid over it
     * is the best of it: the title is TMDB's own lettering, and Flick decides where it sits,
     * clear of the badges. Without a logo the textless backdrop stands alone, and only
     * without one of those does the search's own backdrop stand in, title and all. PNG logos only: an SVG would need a decoder this
     * app does not carry.
     */
    fun pickArt(match: FilmMatch, imagesBody: String?): FilmArt {
        val images = imagesBody?.let { runCatching { JSONObject(it) }.getOrNull() }
        val logo = images?.optJSONArray("logos")?.best { image ->
            image.optString("iso_639_1") == "en" && image.path("file_path")?.endsWith(".png") == true
        }
        val textless = images?.optJSONArray("backdrops")?.best { image ->
            image.isNull("iso_639_1") && image.optDouble("aspect_ratio", 0.0) >= 1.5
        }
        return when {
            logo != null && textless != null -> FilmArt(match.poster, textless, logo)
            // Textless first even without a logo: the tile crops to its own shape, and a
            // titled backdrop cropped loses the ends of its title.
            textless != null -> FilmArt(match.poster, textless, null)
            else -> FilmArt(match.poster, match.backdrop, null)
        }
    }

    private fun org.json.JSONArray.best(accept: (JSONObject) -> Boolean): String? {
        var best: String? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (i in 0 until length()) {
            val image = optJSONObject(i) ?: continue
            val path = image.path("file_path") ?: continue
            if (!accept(image)) continue
            val score = image.optDouble("vote_average", 0.0) * 1000 + image.optInt("vote_count", 0)
            if (score > bestScore) {
                best = path
                bestScore = score
            }
        }
        return best
    }

    private fun JSONObject.path(field: String): String? =
        if (isNull(field)) null else optString(field).takeIf { it.startsWith("/") }

    /** Null for anything that is not a bare TMDB image path, so no URL is built from junk. */
    fun imageUrl(posterPath: String, width: String): String? =
        posterPath.takeIf { PosterPath.matches(it) }?.let { "$IMAGE_BASE/$width$it" }

    private val PosterPath = Regex("^/[A-Za-z0-9_-]+\\.(?:jpg|jpeg|png|webp)$")

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
