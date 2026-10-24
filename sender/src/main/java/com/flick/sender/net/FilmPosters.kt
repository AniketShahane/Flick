package com.flick.sender.net

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.flick.sender.BuildConfig
import com.flick.sender.media.FilmArt
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Film art from TMDB — poster, backdrop and title logo — looked up once per title and
 * remembered across launches.
 *
 * A hit is kept for good: a film's poster path does not change under it. A miss is kept for
 * a week so a home video is not asked about on every scroll. A failure — no network, a
 * refusal, a timeout — is never written down, so the next launch on a better link tries
 * again; within this process it backs off instead of asking on every tile that binds, until a
 * network arrives and [retries] moves.
 */
object FilmPosters {
    private val key = BuildConfig.TMDB_API_KEY.trim()

    /** No key is the normal state of a clone, and every tile then keeps its own frame. */
    val available: Boolean get() = key.isNotEmpty()

    private const val PREFS = "flick_film_posters"
    private const val MISS = ""
    private const val KIND_TV = "tv"
    private const val KIND_MOVIE = "movie"
    private const val MISS_TTL_MS = 7L * 24 * 60 * 60 * 1000
    private const val FAILURE_BACKOFF_MS = 10L * 60 * 1000
    private const val TIMEOUT_MS = 8_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // A grid binding a screenful of tiles at once must not open a screenful of sockets.
    private val gate = Semaphore(4)
    private val inFlight = ConcurrentHashMap<String, Deferred<FilmArt?>>()
    private val failedUntil = ConcurrentHashMap<String, Long>()

    @Volatile private var prefs: SharedPreferences? = null

    @Volatile private var keyRejected = false

    private val _retries = MutableStateFlow(0)

    /** Moves when a network arrives after a lookup failed: what failed is worth asking again. */
    val retries: StateFlow<Int> = _retries.asStateFlow()

    private val watchingNetwork = AtomicBoolean(false)

    /**
     * What is already known, without touching the network: a tile that has its art from
     * last time draws it on its first frame rather than flashing the still first.
     */
    fun cached(context: Context, query: FilmPosterQuery): FilmArt? =
        readEntry(context, query)?.art

    /** The art for [query], or null when TMDB has none this app believes. */
    suspend fun art(context: Context, query: FilmPosterQuery): FilmArt? {
        if (!available || keyRejected) return null
        readEntry(context, query)?.let { entry ->
            val fresh = entry.art != null || System.currentTimeMillis() - entry.atMs < MISS_TTL_MS
            if (fresh) return entry.art
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

    private fun lookUp(context: Context, query: FilmPosterQuery): FilmArt? = try {
        val match = FilmPosterPolicy.findMatch(query, key, ::get)
        // The images call can fail on its own; the search's own art is still worth keeping,
        // so only a failed search is a failure.
        val art = match?.let { FilmPosterPolicy.pickArt(it, runCatching { get(FilmPosterPolicy.imagesUrl(it, key)) }.getOrNull()) }
            ?.takeUnless { it.isEmpty }
        writeEntry(context, query, art)
        art
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        failed(context, query, e.message ?: e.javaClass.simpleName)
        null
    }

    /** The body of a 200, or an exception naming why not. */
    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            if (FilmPosterPolicy.isBearerToken(key)) {
                connection.setRequestProperty("Authorization", "Bearer $key")
            }
            return when (val status = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> connection.inputStream.bufferedReader().use { it.readText() }
                HttpURLConnection.HTTP_UNAUTHORIZED -> {
                    // Every other title would be refused the same way; stop asking.
                    keyRejected = true
                    throw IllegalStateException("tmdb refused the key status=$status")
                }
                else -> throw IllegalStateException("status=$status")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun failed(context: Context, query: FilmPosterQuery, why: String) {
        failedUntil[query.cacheKey] = System.currentTimeMillis() + FAILURE_BACKOFF_MS
        FlickLog.d("posters", "lookup failed $why")
        watchNetwork(context)
    }

    // Registered on the first failure and kept for the process: a phone that started offline
    // would otherwise draw frames until the next launch. Validated, not merely available: a
    // retry before DNS answers fails again and backs off for the full window.
    private fun watchNetwork(context: Context) {
        if (!watchingNetwork.compareAndSet(false, true)) return
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            @Volatile private var online = runCatching {
                manager.getNetworkCapabilities(manager.activeNetwork)?.validated() == true
            }.getOrDefault(false)

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val now = capabilities.validated()
                val arrived = now && !online
                online = now
                if (!arrived || failedUntil.isEmpty()) return
                failedUntil.clear()
                _retries.update { it + 1 }
            }

            override fun onLost(network: Network) {
                online = false
            }
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onFailure { FlickLog.d("posters", "network watch unavailable ${it.javaClass.simpleName}") }
    }

    private fun NetworkCapabilities.validated(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    private class Entry(val art: FilmArt?, val atMs: Long)

    // Stored as "<poster>|<backdrop>|<logo>|<epoch ms>|<tmdb id>|<tv or movie>", empty fields
    // for missing art and all three empty for a miss. The shorter forms written before the id
    // and the kind were kept still read, with neither: the query's own kind is then the
    // match's. Anything else, including the older two-field form, reads as unknown and is
    // looked up again.
    private fun readEntry(context: Context, query: FilmPosterQuery): Entry? {
        val fields = prefs(context).getString(query.cacheKey, null)?.split('|') ?: return null
        if (fields.size !in 4..6) return null
        val atMs = fields[3].toLongOrNull() ?: return null
        val art = FilmArt(
            poster = fields[0].takeIf { it != MISS },
            backdrop = fields[1].takeIf { it != MISS },
            logo = fields[2].takeIf { it != MISS },
            tmdbId = fields.getOrNull(4)?.toLongOrNull()?.takeIf { it > 0L },
            show = when (fields.getOrNull(5)) {
                KIND_TV -> true
                KIND_MOVIE -> false
                else -> null
            },
        )
        return Entry(art.takeUnless { it.isEmpty }, atMs)
    }

    private fun writeEntry(context: Context, query: FilmPosterQuery, art: FilmArt?) {
        val value = listOf(art?.poster, art?.backdrop, art?.logo).joinToString("|") { it ?: MISS }
        val kind = when (art?.show) {
            true -> KIND_TV
            false -> KIND_MOVIE
            null -> ""
        }
        prefs(context).edit()
            .putString(query.cacheKey, "$value|${System.currentTimeMillis()}|${art?.tmdbId ?: ""}|$kind")
            .apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        prefs ?: context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .also { prefs = it }
}
