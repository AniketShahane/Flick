package com.flick.sender.library

import android.content.Context
import android.content.SharedPreferences
import com.flick.sender.BuildConfig
import com.flick.sender.media.FilmPosterPolicy
import com.flick.sender.util.FlickLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * One season's TMDB episode list, asked for only when the TV opens that season and kept for a
 * week. Same key, failure backoff and network rules as `FilmPosters` and `FilmDetailsStore`: a
 * failure is never written down and backs off within this process; a refused key stops asking.
 */
internal object TvSeasons {
    private val tmdbKey = BuildConfig.TMDB_API_KEY.trim()

    val available: Boolean get() = tmdbKey.isNotEmpty()

    private const val PREFS = "flick_tv_seasons"
    private const val TTL_MS = 7L * 24 * 60 * 60 * 1000
    private const val FAILURE_BACKOFF_MS = 10L * 60 * 1000
    private const val TIMEOUT_MS = 8_000
    private const val MAX_ENTRIES = 50

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap<String, Deferred<List<LibraryFrames.EpisodeInfo>?>>()
    private val failedUntil = ConcurrentHashMap<String, Long>()

    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var keyRejected = false

    /** The season's episodes, or null when there is nothing to tell (no key, a failure, no data). */
    suspend fun episodes(context: Context, tvId: Long, season: Int): List<LibraryFrames.EpisodeInfo>? {
        if (!available || keyRejected) return null
        val key = TvSeasonPolicy.cacheKey(tvId, season)
        val appContext = context.applicationContext
        // The first SharedPreferences read waits on the file, and callers are on Main.
        val stored = withContext(Dispatchers.IO) { read(appContext, key) }
        if (stored != null && System.currentTimeMillis() - stored.atMs < TTL_MS) return stored.episodes
        if ((failedUntil[key] ?: 0L) > System.currentTimeMillis()) return stored?.episodes
        val lookup = inFlight.computeIfAbsent(key) {
            scope.async(start = CoroutineStart.LAZY) { lookUp(appContext, tvId, season, key) }
        }
        lookup.invokeOnCompletion { inFlight.remove(key, lookup) }
        lookup.start()
        return lookup.await() ?: stored?.episodes
    }

    private fun lookUp(context: Context, tvId: Long, season: Int, key: String): List<LibraryFrames.EpisodeInfo>? = try {
        val episodes = TvSeasonPolicy.parse(get(TvSeasonPolicy.url(tvId, season, tmdbKey)))
            ?: throw IllegalStateException("unreadable season")
        write(context, key, episodes)
        episodes
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        failedUntil[key] = System.currentTimeMillis() + FAILURE_BACKOFF_MS
        // Our own status text or the class name only: a message can carry the URL and the key.
        FlickLog.d("library", "season lookup failed ${(e as? IllegalStateException)?.message ?: e.javaClass.simpleName}")
        null
    }

    private fun get(url: String): String {
        if (keyRejected) throw IllegalStateException("key refused")
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            if (FilmPosterPolicy.isBearerToken(tmdbKey)) {
                connection.setRequestProperty("Authorization", "Bearer $tmdbKey")
            }
            return when (val status = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> connection.inputStream.bufferedReader().use { it.readText() }
                HttpURLConnection.HTTP_UNAUTHORIZED -> {
                    keyRejected = true
                    throw IllegalStateException("status=$status")
                }
                else -> throw IllegalStateException("status=$status")
            }
        } finally {
            connection.disconnect()
        }
    }

    private class Stored(val episodes: List<LibraryFrames.EpisodeInfo>, val atMs: Long)

    // "<epoch ms>|<episodes json>".
    private fun read(context: Context, key: String): Stored? {
        val raw = prefs(context).getString(key, null) ?: return null
        val split = raw.indexOf('|').takeIf { it > 0 } ?: return null
        val atMs = raw.substring(0, split).toLongOrNull() ?: return null
        return TvSeasonPolicy.decode(raw.substring(split + 1))?.let { Stored(it, atMs) }
    }

    private fun write(context: Context, key: String, episodes: List<LibraryFrames.EpisodeInfo>) {
        val store = prefs(context)
        val editor = store.edit().putString(key, "${System.currentTimeMillis()}|${TvSeasonPolicy.encode(episodes)}")
        // Bounded by age: the oldest seasons are the ones least likely to be opened again.
        val entries = store.all
        if (entries.size >= MAX_ENTRIES) {
            entries.entries
                .mapNotNull { (k, v) -> (v as? String)?.takeIf { k != key }?.substringBefore('|')?.toLongOrNull()?.let { k to it } }
                .sortedBy { it.second }
                .take(entries.size - MAX_ENTRIES + 1)
                .forEach { editor.remove(it.first) }
        }
        editor.apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        prefs ?: context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .also { prefs = it }
}
