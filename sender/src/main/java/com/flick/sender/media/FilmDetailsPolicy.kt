package com.flick.sender.media

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * What TMDB says about one film or show, as the detail page's synopsis reads it. Every field
 * may be missing; [creators] are a film's directors, or a show's creators when [show].
 */
data class FilmDetails(
    val overview: String?,
    val tagline: String?,
    val genres: List<String>,
    val year: Int?,
    val runtimeMinutes: Int?,
    val creators: List<String>,
    val cast: List<String>,
    val imdbId: String?,
    val show: Boolean,
) {
    /** Only a film with something to read opens the synopsis. */
    val hasSynopsis: Boolean get() = !overview.isNullOrBlank()
}

/**
 * The one extra TMDB request a detail page makes, and how its answer is read and stored.
 * Pure, so a missing field, a show, or an old cache entry is a JVM test and not a crash.
 */
object FilmDetailsPolicy {
    private const val API = "https://api.themoviedb.org/3"

    const val CAST_LIMIT = 4
    const val CREATOR_LIMIT = 2
    const val GENRE_LIMIT = 3

    /** The cache format. An entry carrying any other version reads as unknown and is asked again. */
    const val FORMAT_VERSION = 1

    /** Details, IMDb id and credits in one call. */
    fun detailsUrl(query: FilmPosterQuery, id: Long, key: String): String {
        val path = if (query.show) "tv" else "movie"
        val auth = if (FilmPosterPolicy.isBearerToken(key)) "" else "&api_key=${encode(key)}"
        return "$API/$path/$id?append_to_response=external_ids,credits&language=en-US$auth"
    }

    /** Null for a body that is not a details object at all; otherwise whatever fields it had. */
    fun parse(query: FilmPosterQuery, body: String): FilmDetails? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (!json.has("id")) return null
        val credits = json.optJSONObject("credits")
        val creators = if (query.show) {
            json.optJSONArray("created_by").names("name")
        } else {
            credits?.optJSONArray("crew").names("name") { it.optString("job") == "Director" }
        }
        val cast = credits?.optJSONArray("cast")?.let { array ->
            (0 until array.length())
                .mapNotNull { array.optJSONObject(it) }
                .sortedBy { it.optInt("order", Int.MAX_VALUE) }
                .mapNotNull { it.text("name") }
        }.orEmpty()
        val runtime = if (query.show) {
            json.optJSONArray("episode_run_time")?.let { if (it.length() > 0) it.optInt(0, 0) else 0 }
                ?.takeIf { it > 0 }
                ?: json.optJSONObject("last_episode_to_air")?.optInt("runtime", 0)
        } else {
            json.optInt("runtime", 0)
        }
        val imdbId = (json.text("imdb_id") ?: json.optJSONObject("external_ids")?.text("imdb_id"))
            ?.takeIf(FilmRatingsPolicy::isImdbId)
        return FilmDetails(
            overview = json.text("overview"),
            tagline = json.text("tagline"),
            genres = json.optJSONArray("genres").names("name").take(GENRE_LIMIT),
            year = yearOf(json.text(if (query.show) "first_air_date" else "release_date")),
            runtimeMinutes = runtime?.takeIf { it in 1..1000 },
            creators = creators.distinct().take(CREATOR_LIMIT),
            cast = cast.distinct().take(CAST_LIMIT),
            imdbId = imdbId,
            show = query.show,
        )
    }

    fun encode(details: FilmDetails): String = JSONObject().apply {
        put("v", FORMAT_VERSION)
        put("overview", details.overview ?: JSONObject.NULL)
        put("tagline", details.tagline ?: JSONObject.NULL)
        put("genres", JSONArray(details.genres))
        put("year", details.year ?: JSONObject.NULL)
        put("runtime", details.runtimeMinutes ?: JSONObject.NULL)
        put("creators", JSONArray(details.creators))
        put("cast", JSONArray(details.cast))
        put("imdb", details.imdbId ?: JSONObject.NULL)
        put("show", details.show)
    }.toString()

    fun decode(raw: String): FilmDetails? {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (json.optInt("v", -1) != FORMAT_VERSION) return null
        return FilmDetails(
            overview = json.text("overview"),
            tagline = json.text("tagline"),
            genres = json.optJSONArray("genres").strings(),
            year = if (json.isNull("year")) null else json.optInt("year"),
            runtimeMinutes = if (json.isNull("runtime")) null else json.optInt("runtime"),
            creators = json.optJSONArray("creators").strings(),
            cast = json.optJSONArray("cast").strings(),
            imdbId = json.text("imdb")?.takeIf(FilmRatingsPolicy::isImdbId),
            show = json.optBoolean("show", false),
        )
    }

    private fun yearOf(date: String?): Int? =
        date?.take(4)?.toIntOrNull()?.takeIf { it in 1870..2200 }

    /** A trimmed, single-spaced string, or null for absent, JSON null or blank. */
    private fun JSONObject.text(field: String): String? =
        if (!has(field) || isNull(field)) null else optString(field).collapse()

    private fun String.collapse(): String? = trim().replace(Whitespace, " ").takeIf { it.isNotEmpty() }

    private fun JSONArray?.names(field: String, accept: (JSONObject) -> Boolean = { true }): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i -> optJSONObject(i)?.takeIf(accept)?.text(field) }
    }

    private fun JSONArray?.strings(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i -> optString(i).collapse() }
    }

    private val Whitespace = Regex("\\s+")

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
