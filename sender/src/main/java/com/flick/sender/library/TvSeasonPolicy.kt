package com.flick.sender.library

import com.flick.sender.media.FilmPosterPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Contract §6.3: the one TMDB request a `seasonWant` makes, and how its answer is read and kept. */
internal object TvSeasonPolicy {
    private const val API = "https://api.themoviedb.org/3"

    /** The cache format; part of the cache key, so a change retires every older entry. */
    const val FORMAT_VERSION = 1

    /** The TMDB show id a `tv:` show key names, or null for a `name:` key. */
    fun tmdbId(showKey: String): Long? =
        showKey.takeIf { it.startsWith("tv:") }?.removePrefix("tv:")?.toLongOrNull()?.takeIf { it > 0L }

    fun url(tvId: Long, season: Int, key: String): String {
        val auth = if (FilmPosterPolicy.isBearerToken(key)) "" else "&api_key=${URLEncoder.encode(key, "UTF-8")}"
        return "$API/tv/$tvId/season/$season?language=en-US$auth"
    }

    fun cacheKey(tvId: Long, season: Int): String = "s$FORMAT_VERSION|$tvId|$season"

    /** Null for a body that is not a season at all; otherwise its episodes, possibly none. */
    fun parse(body: String): List<LibraryFrames.EpisodeInfo>? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val episodes = json.optJSONArray("episodes") ?: return null
        return episodes(episodes, number = "episode_number", name = "name", overview = "overview", still = "still_path")
    }

    fun encode(episodes: List<LibraryFrames.EpisodeInfo>): String = JSONArray().apply {
        episodes.forEach { info ->
            put(
                JSONObject().apply {
                    put("e", info.episode)
                    put("n", info.name ?: JSONObject.NULL)
                    put("o", info.overview ?: JSONObject.NULL)
                    put("s", info.still ?: JSONObject.NULL)
                },
            )
        }
    }.toString()

    fun decode(raw: String): List<LibraryFrames.EpisodeInfo>? {
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return null
        return episodes(array, number = "e", name = "n", overview = "o", still = "s")
    }

    private fun episodes(array: JSONArray, number: String, name: String, overview: String, still: String) =
        (0 until array.length()).mapNotNull { index ->
            val episode = array.optJSONObject(index) ?: return@mapNotNull null
            if (!episode.has(number) || episode.isNull(number)) return@mapNotNull null
            val e = episode.optInt(number, -1).takeIf { it in 0..9_999 } ?: return@mapNotNull null
            LibraryFrames.EpisodeInfo(e, episode.text(name), episode.text(overview), episode.text(still))
        }

    private fun JSONObject.text(field: String): String? =
        if (!has(field) || isNull(field)) null else optString(field).trim().takeIf { it.isNotEmpty() }
}
