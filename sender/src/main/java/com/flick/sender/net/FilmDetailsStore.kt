package com.flick.sender.net

import android.content.Context
import android.content.SharedPreferences
import com.flick.sender.BuildConfig
import com.flick.sender.media.FilmDetails
import com.flick.sender.media.FilmDetailsPolicy
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
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * A film's synopsis and credits from TMDB, asked for only when its detail page opens — never
 * per library tile — and remembered across launches.
 *
 * Details are kept for [DETAILS_TTL_MS] (a film's synopsis rarely changes, but it does get
 * written after release); a miss for a week, as [FilmPosters] does. A failure is never written
 * down, and within this process it backs off for [FAILURE_BACKOFF_MS] rather than asking again
 * on every visit.
 */
object FilmDetailsStore {
    private val tmdbKey = BuildConfig.TMDB_API_KEY.trim()

    val available: Boolean get() = tmdbKey.isNotEmpty()

    private const val PREFS = "flick_film_details"
    private const val DETAILS_TTL_MS = 90L * 24 * 60 * 60 * 1000
    private const val MISS_TTL_MS = 7L * 24 * 60 * 60 * 1000
    private const val FAILURE_BACKOFF_MS = 2L * 60 * 1000
    private const val TIMEOUT_MS = 8_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap<String, Deferred<FilmDetails?>>()
    private val failedUntil = ConcurrentHashMap<String, Long>()

    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var tmdbRejected = false

    /** Starts the cache file loading off the main thread, before any page reads it. */
    fun warm(context: Context) {
        if (available) prefs(context)
    }

    /** Whatever was stored, fresh or not: a stale synopsis is still the film's synopsis. */
    fun cached(context: Context, query: FilmPosterQuery, tmdbId: Long?): FilmDetails? =
        readDetails(context, detailsKey(query, tmdbId))?.details

    /**
     * [tmdbId] is the match the film's art was chosen from, when known: the details are then
     * that film's, by id, with no second search whose ranking could have moved (two films of
     * one name — a remake, a re-release — swap places in TMDB's popularity order). Without
     * one, the same search and match rule the art uses.
     */
    suspend fun details(context: Context, query: FilmPosterQuery, tmdbId: Long?): FilmDetails? {
        if (!available || tmdbRejected) return null
        val key = detailsKey(query, tmdbId)
        val stored = readDetails(context, key)
        if (stored != null) {
            val ttl = if (stored.details != null) DETAILS_TTL_MS else MISS_TTL_MS
            if (System.currentTimeMillis() - stored.atMs < ttl) return stored.details
        }
        if ((failedUntil[key] ?: 0L) > System.currentTimeMillis()) return stored?.details
        val appContext = context.applicationContext
        return once(key) { lookUpDetails(appContext, query, tmdbId, key) } ?: stored?.details
    }

    // Lazy, so the entry is in the map before the work that removes it can finish.
    private suspend fun once(key: String, work: () -> FilmDetails?): FilmDetails? {
        val lookup = inFlight.computeIfAbsent(key) { scope.async(start = CoroutineStart.LAZY) { work() } }
        lookup.invokeOnCompletion { inFlight.remove(key, lookup) }
        lookup.start()
        return lookup.await()
    }

    private fun lookUpDetails(context: Context, query: FilmPosterQuery, tmdbId: Long?, key: String): FilmDetails? = try {
        val id = tmdbId ?: FilmPosterPolicy.pickMatch(query, get(FilmPosterPolicy.searchUrl(query, tmdbKey)))?.id
        val details = id?.let { FilmDetailsPolicy.parse(query, get(FilmDetailsPolicy.detailsUrl(query, it, tmdbKey))) }
        // A match whose details came back unreadable is a failure, not a film with nothing to say.
        if (id != null && details == null) throw IllegalStateException("unreadable details")
        writeDetails(context, key, details)
        details
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        failed(key, e)
        null
    }

    /**
     * The body of a 200, or an exception naming why not — including a key already refused, so
     * that nothing after a refusal can be mistaken for an answer and cached.
     */
    private fun get(url: String): String {
        if (tmdbRejected) throw IllegalStateException("key refused")
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
                    // Every other title would be refused the same way; stop asking for this process.
                    tmdbRejected = true
                    throw IllegalStateException("status=$status")
                }
                else -> throw IllegalStateException("status=$status")
            }
        } finally {
            connection.disconnect()
        }
    }

    // The class name and our own status only: an exception's message can carry the URL, and
    // the URL carries the title and the key.
    private fun failed(key: String, e: Exception) {
        failedUntil[key] = System.currentTimeMillis() + FAILURE_BACKOFF_MS
        val why = (e as? IllegalStateException)?.message ?: e.javaClass.simpleName
        FlickLog.d("details", "lookup failed $why")
    }

    private class StoredDetails(val details: FilmDetails?, val atMs: Long)

    private fun detailsKey(query: FilmPosterQuery, tmdbId: Long?) =
        "d${FilmDetailsPolicy.FORMAT_VERSION}|${query.cacheKey}|${tmdbId ?: ""}"

    // "<epoch ms>|<details json>", the json empty for a miss.
    private fun readDetails(context: Context, key: String): StoredDetails? {
        val raw = prefs(context).getString(key, null) ?: return null
        val split = raw.indexOf('|').takeIf { it > 0 } ?: return null
        val atMs = raw.substring(0, split).toLongOrNull() ?: return null
        val body = raw.substring(split + 1)
        if (body.isEmpty()) return StoredDetails(null, atMs)
        return FilmDetailsPolicy.decode(body)?.let { StoredDetails(it, atMs) }
    }

    private fun writeDetails(context: Context, key: String, details: FilmDetails?) {
        val body = details?.let(FilmDetailsPolicy::encode).orEmpty()
        prefs(context).edit().putString(key, "${System.currentTimeMillis()}|$body").apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        prefs ?: context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .also { prefs = it }
}
