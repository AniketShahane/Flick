package com.flick.sender.media

import org.json.JSONObject
import java.net.URLEncoder

/** What TMDB says about one film or show, as the detail page's synopsis reads it. Every field may be missing. */
data class FilmDetails(
    val overview: String?,
    val tagline: String?,
    val year: Int?,
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

    /**
     * The cache format; it is part of the cache key too. An entry carrying any other version
     * reads as unknown and is asked again. 2 dropped the credits, genres and runtime.
     */
    const val FORMAT_VERSION = 2

    fun detailsUrl(query: FilmPosterQuery, id: Long, key: String): String {
        val path = if (query.show) "tv" else "movie"
        val auth = if (FilmPosterPolicy.isBearerToken(key)) "" else "&api_key=${encode(key)}"
        return "$API/$path/$id?language=en-US$auth"
    }

    /** Null for a body that is not a details object at all; otherwise whatever fields it had. */
    fun parse(query: FilmPosterQuery, body: String): FilmDetails? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (!json.has("id")) return null
        return FilmDetails(
            overview = json.text("overview"),
            tagline = json.text("tagline"),
            year = yearOf(json.text(if (query.show) "first_air_date" else "release_date")),
        )
    }

    fun encode(details: FilmDetails): String = JSONObject().apply {
        put("v", FORMAT_VERSION)
        put("overview", details.overview ?: JSONObject.NULL)
        put("tagline", details.tagline ?: JSONObject.NULL)
        put("year", details.year ?: JSONObject.NULL)
    }.toString()

    fun decode(raw: String): FilmDetails? {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (json.optInt("v", -1) != FORMAT_VERSION) return null
        return FilmDetails(
            overview = json.text("overview"),
            tagline = json.text("tagline"),
            year = if (json.isNull("year") || !json.has("year")) null else json.optInt("year"),
        )
    }

    private fun yearOf(date: String?): Int? =
        date?.take(4)?.toIntOrNull()?.takeIf { it in 1870..2200 }

    /** A trimmed, single-spaced string, or null for absent, JSON null or blank. */
    private fun JSONObject.text(field: String): String? =
        if (!has(field) || isNull(field)) null else optString(field).collapse()

    private fun String.collapse(): String? = trim().replace(Whitespace, " ").takeIf { it.isNotEmpty() }

    private val Whitespace = Regex("\\s+")

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
