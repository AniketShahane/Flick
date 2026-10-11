package com.flick.sender.net

import android.content.Context
import android.content.SharedPreferences
import com.flick.sender.BuildConfig
import com.flick.sender.media.FilmPosterPolicy
import com.flick.sender.media.FilmPosterQuery
import com.flick.sender.util.FlickLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Film posters from TMDB, looked up once per title and remembered across launches.
 *
 * A hit is kept for good: a film's poster path does not change under it. A miss is kept for
 * a week so a home video is not asked about on every scroll. A failure — no network, a
 * refusal, a timeout — is never written down, so the next launch on a better link tries
 * again; within this process it backs off instead of asking on every tile that binds.
 */
object FilmPosters {
    private val key = BuildConfig.TMDB_API_KEY.trim()

    /** No key is the normal state of a clone, and every tile then keeps its own frame. */
    val available: Boolean get() = key.isNotEmpty()

    private const val PREFS = "flick_film_posters"
    private const val MISS = ""
    private const val MISS_TTL_MS = 7L * 24 * 60 * 60 * 1000
    private const val FAILURE_BACKOFF_MS = 10L * 60 * 1000
    private const val TIMEOUT_MS = 8_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // A grid binding a screenful of tiles at once must not open a screenful of sockets.
    private val gate = Semaphore(4)
    private val inFlight = ConcurrentHashMap<String, Deferred<String?>>()
    private val failedUntil = ConcurrentHashMap<String, Long>()

    @Volatile private var prefs: SharedPreferences? = null

    @Volatile private var keyRejected = false

    /**
     * What is already known, without touching the network: a tile that has its poster from
     * last time draws it on its first frame rather than flashing the still first.
     */
    fun cached(context: Context, query: FilmPosterQuery): String? =
        readEntry(context, query)?.path

    /** The poster path for [query], or null when TMDB has none this app believes. */
    suspend fun poster(context: Context, query: FilmPosterQuery): String? {
        if (!available || keyRejected) return null
        readEntry(context, query)?.let { entry ->
            val fresh = entry.path != null || System.currentTimeMillis() - entry.atMs < MISS_TTL_MS
            if (fresh) return entry.path
        }
        if ((failedUntil[query.cacheKey] ?: 0L) > System.currentTimeMillis()) return null
        val appContext = context.applicationContext
        // Lazy, so the entry is in the map before the work that removes it can finish.
        val lookup = inFlight.computeIfAbsent(query.cacheKey) {
            scope.async(start = CoroutineStart.LAZY) { gate.withPermit { lookUp(appContext, query) } }
        }
        lookup.invokeOnCompletion { inFlight.remove(query.cacheKey, lookup) }
        lookup.start()
        return lookup.await()
    }

    private fun lookUp(context: Context, query: FilmPosterQuery): String? {
        val connection = URL(FilmPosterPolicy.searchUrl(query, key)).openConnection() as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            if (FilmPosterPolicy.isBearerToken(key)) {
                connection.setRequestProperty("Authorization", "Bearer $key")
            }
            when (val status = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    val path = FilmPosterPolicy.pickPoster(query, body)
                    writeEntry(context, query, path)
                    path
                }
                HttpURLConnection.HTTP_UNAUTHORIZED -> {
                    // Every other title would be refused the same way; stop asking.
                    keyRejected = true
                    FlickLog.w("posters", "tmdb refused the key status=$status")
                    null
                }
                else -> {
                    failed(query, "status=$status")
                    null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(query, e.javaClass.simpleName)
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun failed(query: FilmPosterQuery, why: String) {
        failedUntil[query.cacheKey] = System.currentTimeMillis() + FAILURE_BACKOFF_MS
        FlickLog.d("posters", "lookup failed $why")
    }

    private class Entry(val path: String?, val atMs: Long)

    // Stored as "<path>|<epoch ms>", with an empty path for a miss.
    private fun readEntry(context: Context, query: FilmPosterQuery): Entry? {
        val raw = prefs(context).getString(query.cacheKey, null) ?: return null
        val split = raw.lastIndexOf('|').takeIf { it >= 0 } ?: return null
        val atMs = raw.substring(split + 1).toLongOrNull() ?: return null
        val path = raw.substring(0, split).takeIf { it != MISS }
        return Entry(path, atMs)
    }

    private fun writeEntry(context: Context, query: FilmPosterQuery, path: String?) {
        prefs(context).edit()
            .putString(query.cacheKey, "${path ?: MISS}|${System.currentTimeMillis()}")
            .apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        prefs ?: context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .also { prefs = it }
}
