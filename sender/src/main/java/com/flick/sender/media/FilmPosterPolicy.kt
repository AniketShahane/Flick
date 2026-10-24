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
        get() = "v3|${if (show) "tv" else "movie"}|${FoldedText.fold(title)}|${year ?: ""}"
}

/**
 * The art TMDB has for one title. Any field may be missing; all missing is a miss. [tmdbId] is
 * the match the art was chosen from, so the detail page asks for that very film's synopsis
 * rather than searching again and trusting TMDB's ranking to hold still; null for an entry
 * cached before it was recorded. [show] is which catalogue that id belongs to — a film-shaped
 * name can match a show — and null when the entry predates it, meaning the query's own kind.
 */
data class FilmArt(
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    val tmdbId: Long? = null,
    val show: Boolean? = null,
) {
    val isEmpty: Boolean get() = poster == null && backdrop == null
}

/** The matched search result: its TMDB id, its catalogue, and the art the search itself carried. */
data class FilmMatch(val id: Long, val poster: String?, val backdrop: String?, val show: Boolean = false)

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

    // A backdrop is already landscape, so a portrait screen crops it to under half its width;
    // the original is often 4K, which Coil would decode whole for a Crop that only upscales.
    const val HERO_BACKDROP_WIDTH = "w1280"

    // The detail page fits its logo into a 200 × 64 dp slot, up to 750 px wide on a 600 dpi
    // phone; w500 upscales there. Coil decodes the original down to the slot's size.
    const val HERO_LOGO_WIDTH = "original"

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

    /** Results whose alternative titles are read when none of the titles agreed. */
    const val MAX_ALTERNATIVE_CHECKS = 2

    /**
     * Every request one title's lookup makes, in order, stopping at the first match believed.
     * [fetch] returns a 200's body or throws; a throw is a failure, never a miss, so nothing
     * half-asked is cached as "TMDB has nothing".
     *
     * A name with an episode marker is a show and is asked only as one. A name with a year is
     * asked as a film and then as a show: `The Rehearsal (2022).mkv` names a series, and a
     * film-only search answers it with nothing. A name with neither is asked only as a film —
     * `Friends.mp4` is far more often a home video than the series.
     *
     * Exact titles are compared across every catalogue asked before anything looser is
     * believed: a 2022 film called `The Rehearsal: Something` must not beat the series of
     * exactly that name. Then a dropped subtitle, then — for a name with a year or an episode
     * marker — the top candidates' alternative titles, because TMDB finds `Raatchasan`
     * through an alias its listing does not show.
     *
     * Last, a spaced name of two words or more ending in a year (`The Rehearsal 2022.mkv`) —
     * which the parser keeps whole, since `Wedding 2019` is as likely a home video — is asked
     * again as that title in that year. The year was only guessed, so it vouches for nothing:
     * the match needs the votes a yearless name does, and no subtitle counts.
     */
    fun findMatch(query: FilmPosterQuery, key: String, fetch: (String) -> String): FilmMatch? {
        search(query, key, fetch, yearGuessed = false)?.let { return it }
        val guess = trailingYear(query) ?: return null
        return search(guess, key, fetch, yearGuessed = true)
    }

    private fun search(query: FilmPosterQuery, key: String, fetch: (String) -> String, yearGuessed: Boolean): FilmMatch? {
        val episodic = query.show
        val kinds = when {
            episodic -> listOf(true)
            query.year != null -> listOf(false, true)
            else -> listOf(false)
        }
        val answers = mutableListOf<Pair<FilmPosterQuery, String>>()
        for (show in kinds) {
            val asked = query.copy(show = show)
            val body = fetch(searchUrl(asked, key))
            exactMatch(asked, body, episodic, yearGuessed)?.let { return it }
            answers += asked to body
        }
        if (!yearGuessed) {
            for ((asked, body) in answers) subtitledMatch(asked, body, episodic)?.let { return it }
        }
        // Aliases only for a name something vouches for: `Halloween 2018` is an alias of the
        // 2018 film, and a home video's name all the same.
        if (!episodic && (query.year == null || yearGuessed)) return null
        for ((asked, body) in answers) {
            for (candidate in aliasCandidates(asked, body, episodic)) {
                if (aliasMatches(asked, fetch(alternativeTitlesUrl(candidate, key)))) return candidate
            }
        }
        return null
    }

    private val TrailingYear = Regex("^(.*\\S)\\s+((?:18[7-9]\\d|19\\d{2}|20[0-3]\\d))$")

    /**
     * `Title Words 2022` as `Title Words` in 2022, for a film-shaped name the parser gave no
     * year. One word before the year is left alone: `Halloween 2018` and `Vacation 2015` are
     * the home videos this would otherwise hand a famous film's poster.
     */
    fun trailingYear(query: FilmPosterQuery): FilmPosterQuery? {
        if (query.show || query.year != null) return null
        val marker = TrailingYear.matchEntire(query.title.trim()) ?: return null
        val title = marker.groupValues[1].trim()
        val words = FoldedText.words(title)
        if (words.size < 2 || words.none { word -> word.any(Char::isLetter) }) return null
        return FilmPosterQuery(title, marker.groupValues[2].toInt(), show = false)
    }

    /**
     * The first result whose title is the filename's title, word for word once case, accents
     * and punctuation are folded away, and that has a poster. TMDB ranks fuzzily — a search
     * for `Beach Day` returns whatever it can — so a near miss is no poster at all, and the
     * file keeps its own frame.
     *
     * With a year, and only then, a result titled `<the filename's title>: <subtitle>` also
     * counts once no exact title does: the year has already narrowed TMDB to that one year,
     * and release names drop the subtitle far more often than a stranger's film shares both.
     *
     * [episodic] is a name that carried an episode marker. A home video does not come named
     * `S01E03`, so such a name needs no vote count to be believed.
     */
    fun pickMatch(
        query: FilmPosterQuery,
        body: String,
        episodic: Boolean = query.show,
        yearGuessed: Boolean = false,
    ): FilmMatch? = exactMatch(query, body, episodic, yearGuessed)
        ?: if (yearGuessed) null else subtitledMatch(query, body, episodic)

    private fun exactMatch(query: FilmPosterQuery, body: String, episodic: Boolean, yearGuessed: Boolean): FilmMatch? {
        val wanted = matchKeys(query.title)
        return candidates(query, body, episodic, yearGuessed)
            .firstOrNull { (_, titles) -> titles.any { agree(wanted, it) } }?.first
    }

    private fun subtitledMatch(query: FilmPosterQuery, body: String, episodic: Boolean): FilmMatch? {
        if (query.year == null) return null
        val wanted = matchKeys(query.title)
        return candidates(query, body, episodic, yearGuessed = false)
            .firstOrNull { (_, titles) -> titles.any { ':' in it && agree(wanted, it.substringBefore(':')) } }?.first
    }

    /**
     * Believable results, best ranked first, for their aliases to be read. Always with the
     * votes a yearless name needs, unless the name was an episode: TMDB ranks by aliases in
     * every country, so a year alone would let `Birthday (2019).mp4` match any 2019 film
     * some market retitled `Birthday`.
     */
    fun aliasCandidates(query: FilmPosterQuery, body: String, episodic: Boolean = query.show): List<FilmMatch> =
        candidates(query, body, episodic, yearGuessed = true).map { it.first }.take(MAX_ALTERNATIVE_CHECKS).toList()

    /** Each believable result with the titles it goes by in [query]'s catalogue. */
    private fun candidates(
        query: FilmPosterQuery,
        body: String,
        episodic: Boolean,
        yearGuessed: Boolean,
    ): Sequence<Pair<FilmMatch, List<String>>> {
        val results = JSONObject(body).optJSONArray("results") ?: return emptySequence()
        val fields = titleFields(query)
        return (0 until results.length()).asSequence()
            .mapNotNull { results.optJSONObject(it) }
            .mapNotNull { result ->
                believable(query, result, episodic, yearGuessed)?.let { it to fields.map(result::optString) }
            }
    }

    fun alternativeTitlesUrl(match: FilmMatch, key: String): String {
        val auth = if (isBearerToken(key)) "" else "?api_key=${encode(key)}"
        return "$API/${kindPath(match.show)}/${match.id}/alternative_titles$auth"
    }

    /** Films list their aliases under `titles`, shows under `results`; both carry `title`. */
    fun aliasMatches(query: FilmPosterQuery, body: String): Boolean {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return false
        val aliases = json.optJSONArray(if (query.show) "results" else "titles") ?: return false
        val wanted = matchKeys(query.title)
        return (0 until aliases.length()).any { i ->
            aliases.optJSONObject(i)?.optString("title")?.let { agree(wanted, it) } == true
        }
    }

    /** A result with art, an id, and — without a year or an episode marker to vouch for it — enough votes. */
    private fun believable(query: FilmPosterQuery, result: JSONObject, episodic: Boolean, yearGuessed: Boolean): FilmMatch? {
        val poster = result.path("poster_path")
        val backdrop = result.path("backdrop_path")
        if (poster == null && backdrop == null) return null
        val vouched = episodic || (query.year != null && !yearGuessed)
        if (!vouched && result.optInt("vote_count", 0) < MIN_VOTES_WITHOUT_YEAR) return null
        val id = result.optLong("id", 0L).takeIf { it > 0L } ?: return null
        return FilmMatch(id, poster, backdrop, query.show)
    }

    private fun titleFields(query: FilmPosterQuery) =
        if (query.show) listOf("name", "original_name") else listOf("title", "original_title")

    /**
     * The [FoldedText] spellings two titles agree on when they share any: `&` both dropped
     * and read as `and`, the apostrophe both spaced and dropped. A release name spells
     * `Fast & Furious` as `Fast Furious` or `Fast and Furious`, `There's` as `Theres` and
     * `Rock'n'Roll` as `Rock n Roll`; one rule for each would lose the other spelling.
     */
    private fun matchKeys(title: String): Set<String> =
        listOf(title, title.replace("&", " and ")).flatMap { spelling ->
            listOf(FoldedText.fold(spelling), FoldedText.fold(spelling.replace(Apostrophes, "")))
        }.filterTo(mutableSetOf()) { it.isNotEmpty() }

    private fun agree(wanted: Set<String>, title: String) = matchKeys(title).any { it in wanted }

    private val Apostrophes = Regex("['’ʼ`]")

    private fun kindPath(show: Boolean) = if (show) "tv" else "movie"

    /** Backdrops and logos for one match, in English and textless only. */
    fun imagesUrl(match: FilmMatch, key: String): String {
        val auth = if (isBearerToken(key)) "" else "&api_key=${encode(key)}"
        return "$API/${kindPath(match.show)}/${match.id}/images?include_image_language=en,null$auth"
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
            logo != null && textless != null -> FilmArt(match.poster, textless, logo, match.id, match.show)
            // Textless first even without a logo: the tile crops to its own shape, and a
            // titled backdrop cropped loses the ends of its title.
            textless != null -> FilmArt(match.poster, textless, null, match.id, match.show)
            else -> FilmArt(match.poster, match.backdrop, null, match.id, match.show)
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
