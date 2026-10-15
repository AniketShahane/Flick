package com.flick.sender.media

import org.json.JSONObject
import java.net.URLEncoder

/**
 * The two scores the detail page shows. [imdb] is IMDb's own text ("8.5"), kept as written so
 * the page never invents a precision IMDb did not give; [rottenTomatoes] is the Tomatometer in
 * percent.
 */
data class FilmRatings(val imdb: String?, val rottenTomatoes: Int?) {
    val isEmpty: Boolean get() = imdb == null && rottenTomatoes == null
}

/** OMDb's answer for one IMDb id, read and stored. Pure, so "N/A" is a test and not a chip. */
object FilmRatingsPolicy {
    private const val API = "https://www.omdbapi.com/"

    /** Under this the Tomatometer is "rotten", and the chip wears the splat. */
    const val FRESH_FROM = 60

    private val ImdbId = Regex("^tt\\d{7,10}$")
    private val ImdbScore = Regex("^(\\d{1,2})(\\.\\d)?$")
    private val TomatoScore = Regex("^(\\d{1,3})%$")
    private val ImdbOutOfTen = Regex("^(\\d{1,2}(?:\\.\\d)?)/10$")

    fun isImdbId(id: String): Boolean = ImdbId.matches(id)

    fun rotten(score: Int): Boolean = score < FRESH_FROM

    /** Null for anything that is not an IMDb id, so nothing else is ever sent. */
    fun ratingsUrl(imdbId: String, key: String): String? =
        imdbId.takeIf(::isImdbId)?.let { "$API?i=$it&apikey=${encode(key)}" }

    /**
     * The scores in [body]: both null for a film OMDb does not know or scores of "N/A" — a
     * real answer, worth keeping. Null for anything that is not an answer about the film at
     * all — an unreadable body, or OMDb's own error (a busy server, a spent quota) — which
     * must be asked again rather than remembered. IMDb's own field first, its entry in
     * `Ratings` when that is missing.
     */
    fun parse(body: String): FilmRatings? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (json.optString("Response").equals("False", ignoreCase = true)) {
            val error = json.optString("Error")
            return None.takeIf { NotFound.any { error.contains(it, ignoreCase = true) } }
        }
        val ratings = json.optJSONArray("Ratings")
        fun rating(source: String): String? {
            if (ratings == null) return null
            for (i in 0 until ratings.length()) {
                val entry = ratings.optJSONObject(i) ?: continue
                if (entry.optString("Source") == source) return entry.optString("Value").trim()
            }
            return null
        }
        val imdb = imdbScore(json.optString("imdbRating").trim())
            ?: rating("Internet Movie Database")?.let { ImdbOutOfTen.find(it)?.groupValues?.get(1) }?.let(::imdbScore)
        val tomato = rating("Rotten Tomatoes")?.let { TomatoScore.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?.takeIf { it in 0..100 }
        return FilmRatings(imdb, tomato)
    }

    private fun imdbScore(text: String): String? {
        if (!ImdbScore.matches(text)) return null
        val value = text.toDoubleOrNull() ?: return null
        return text.takeIf { value in 0.0..10.0 }
    }

    /** `<imdb>|<rotten tomatoes>`, empty fields for missing scores. */
    fun encode(ratings: FilmRatings): String =
        "${ratings.imdb.orEmpty()}|${ratings.rottenTomatoes?.toString().orEmpty()}"

    fun decode(raw: String): FilmRatings? {
        val fields = raw.split('|')
        if (fields.size != 2) return null
        val imdb = fields[0].takeIf { it.isNotEmpty() }?.let(::imdbScore)
        if (fields[0].isNotEmpty() && imdb == null) return null
        val tomato = fields[1].takeIf { it.isNotEmpty() }?.let { it.toIntOrNull()?.takeIf { s -> s in 0..100 } ?: return null }
        return FilmRatings(imdb, tomato)
    }

    private val None = FilmRatings(null, null)

    /** OMDb's wording for an id it has no film for: the one error that is an answer. */
    private val NotFound = listOf("not found", "Incorrect IMDb ID")

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
