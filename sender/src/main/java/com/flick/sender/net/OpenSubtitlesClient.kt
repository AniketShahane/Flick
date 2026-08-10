package com.flick.sender.net

import android.content.Context
import android.net.Uri
import com.flick.sender.BuildConfig
import com.flick.sender.media.MovieHash
import com.flick.sender.media.SubtitleFiles
import com.flick.sender.util.FlickLog
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.accept
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The one credential the online subtitle tab runs on.
 *
 * An **API key** identifies the APP — OpenSubtitles calls it an API Consumer key, it is
 * reviewed and approved per app, and the one this build shipped is in `BuildConfig`. It is
 * the only credential this app holds: nothing is read from storage, nothing is written
 * there, and no value from this file ever reaches a log, an exception or a notification.
 *
 * The interface exists so a JVM test can hold the key still without a Context.
 */
internal interface OpenSubtitlesCredentials {
    fun resolved(): ResolvedApiKey?
}

internal class OpenSubtitlesKeyStore : OpenSubtitlesCredentials {
    /**
     * The key a request will carry, or null when this build shipped none. A null is the
     * honest, expected state of a clone of this repository.
     */
    override fun resolved(): ResolvedApiKey? =
        OpenSubtitlesWire.resolveKey(userKey = null, bundledKey = BuildConfig.OPENSUBTITLES_API_KEY)
}

/**
 * One downloadable subtitle from a search. [fileId] is what the download call takes.
 * [hashMatch] is the server saying this file was uploaded against the exact video being
 * cast, which is the whole reason the hash is computed at all.
 */
data class OnlineSubtitle(
    val fileId: Long,
    val fileName: String,
    val language: String?,
    val release: String,
    val downloads: Int,
    val hashMatch: Boolean = false,
    val trusted: Boolean = false,
    val aiTranslated: Boolean = false,
    val machineTranslated: Boolean = false,
    val hearingImpaired: Boolean = false,
    val foreignPartsOnly: Boolean = false,
    val rating: Double = 0.0,
    val votes: Int = 0,
    val featureType: String? = null,
    /**
     * Which work this subtitle is filed under. The API answers a text query with whatever
     * its fuzzy match produced, so these are what lets Flick tell a film from the series
     * that shares its name instead of ranking both on popularity alone.
     * [featureName] is the catalogue's decorated form and [featureParentTitle] the series
     * an episode belongs to; both are absent for plenty of entries.
     */
    val featureTitle: String? = null,
    val featureName: String? = null,
    val featureParentTitle: String? = null,
    val featureYear: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
)

/** Every way a search can end. Each arm gets its own honest sentence in the sheet. */
sealed interface SubtitleSearchOutcome {
    data class Found(val results: List<OnlineSubtitle>) : SubtitleSearchOutcome
    data object NoKey : SubtitleSearchOutcome
    data object Offline : SubtitleSearchOutcome
    data object BadKey : SubtitleSearchOutcome
    data object RateLimited : SubtitleSearchOutcome
    data object Unavailable : SubtitleSearchOutcome
}

/** Every way a download can end. [Ready] carries a file this phone can now serve. */
sealed interface SubtitleFetchOutcome {
    data class Ready(
        val uri: Uri,
        val displayName: String,
        val language: String?,
        /** What the API said is left of today's allowance, or null when it said nothing. */
        val quota: SubtitleQuota? = null,
    ) : SubtitleFetchOutcome

    data object NoKey : SubtitleFetchOutcome
    data object Offline : SubtitleFetchOutcome
    data object BadKey : SubtitleFetchOutcome
    data object RateLimited : SubtitleFetchOutcome

    /** The key's daily download allowance is spent — not an error Flick can retry. */
    data object QuotaSpent : SubtitleFetchOutcome

    /** The API answered with a download address outside OpenSubtitles' own domains. */
    data object LinkRejected : SubtitleFetchOutcome
    data object TooLarge : SubtitleFetchOutcome
    data object Unavailable : SubtitleFetchOutcome
}

/** Records the exact search request below orchestration without replacing its policy. */
internal fun interface OpenSubtitlesSearchTransport {
    suspend fun get(url: String, apiKey: String): SubtitleSearchOutcome
}

/**
 * Search and download against api.opensubtitles.com.
 *
 * Every request carries the key this build shipped and nothing else, so the daily
 * allowance behind it is shared by every install of the same APK. Nothing here runs
 * without a key.
 */
class OpenSubtitlesClient private constructor(
    private val appContext: Context?,
    private val keys: OpenSubtitlesCredentials,
    private val searchTransport: OpenSubtitlesSearchTransport?,
) {
    constructor(context: Context) : this(
        context.applicationContext,
        OpenSubtitlesKeyStore(),
        null,
    )

    internal constructor(context: Context, keys: OpenSubtitlesKeyStore) :
        this(context.applicationContext, keys, null)

    internal constructor(
        keys: OpenSubtitlesCredentials,
        searchTransport: OpenSubtitlesSearchTransport,
    ) : this(null, keys, searchTransport)

    /**
     * Redirects are refused for every call this client makes.
     *
     * On the API calls that is what stops the `Api-Key` from being replayed to whatever
     * host a `Location` header happens to name — Ktor copies headers across a redirect, so
     * following one would send a credential to an address this code did not fix. On the CDN
     * fetch it is what stops a hostile or compromised link from bouncing the download to an
     * arbitrary host. The one legitimate hop — a CDN that answers 3xx — is taken by hand in
     * [fetch], once, and only to another allow-listed OpenSubtitles address.
     */
    private val lazyHttp = lazy { HttpClient(CIO) { followRedirects = false } }
    private val http: HttpClient get() = lazyHttp.value

    fun close() {
        if (lazyHttp.isInitialized()) runCatching { http.close() }
    }

    /**
     * Searches for an exact [movieFingerprint] first and by [query] only when that finds none.
     *
     * The hash names THIS file, so a match is a subtitle already in sync with it. It is
     * also the half allowed to fail quietly: a hash the server has never seen, or a
     * request that does not land, leaves the structured text fallback available.
     */
    suspend fun search(
        query: String,
        year: Int? = null,
        season: Int?,
        episode: Int?,
        movieFingerprint: MovieHash.Fingerprint? = null,
        language: OpenSubtitlesLanguage = OpenSubtitlesSearchPolicy.DefaultLanguage,
    ): SubtitleSearchOutcome = withContext(Dispatchers.IO) {
        val key = keys.resolved() ?: return@withContext SubtitleSearchOutcome.NoKey
        val term = OpenSubtitlesSearchPolicy.textQuery(query)
        val hashParameters = OpenSubtitlesWire.hashSearchParameters(
            fingerprint = movieFingerprint,
            language = language,
        )
        val textParameters = OpenSubtitlesWire.textSearchParameters(
            query = query,
            year = year,
            season = season,
            episode = episode,
            language = language,
        )
        if (term.value == null && hashParameters.isEmpty()) {
            return@withContext SubtitleSearchOutcome.Found(emptyList())
        }

        val hashOutcome = hashParameters.takeIf { it.isNotEmpty() }?.let { subtitles(key.value, it) }
        // A refused key is the same fault for both halves; answering it once is better
        // than running the text query with a credential just refused.
        if (hashOutcome != null && hashOutcome.isCredentialFault()) return@withContext hashOutcome
        val hashResults = (hashOutcome as? SubtitleSearchOutcome.Found)?.results.orEmpty()
        fun found(textResults: List<OnlineSubtitle>) = SubtitleSearchOutcome.Found(
            OpenSubtitlesWire.merged(
                hashResults = hashResults,
                textResults = textResults,
                limit = MAX_RESULTS,
                // The normalized term, not the raw field: results are judged against the
                // same words the request carried, never against stray spacing or marks.
                title = term.value,
                year = year,
                season = season,
                episode = episode,
            ),
        )
        if (!OpenSubtitlesSearchPolicy.shouldRunTextFallback(hashResults, term)) {
            return@withContext found(emptyList())
        }

        val text = subtitles(
            key = key.value,
            query = textParameters,
        )
        when {
            text is SubtitleSearchOutcome.Found -> found(text.results)
            // A failed text query is still an honest failure — unless the hash already
            // answered, in which case the user gets the better results rather than an
            // error about the weaker half of the same search.
            hashResults.isNotEmpty() -> found(emptyList())
            else -> text
        }
    }

    /**
     * Resolves the download link, fetches it and writes the file into this app's own
     * cache. The returned Uri is a `file://` inside `cacheDir` — the media server reads
     * it through the same ContentResolver it reads a picked document with.
     */
    suspend fun download(subtitle: OnlineSubtitle): SubtitleFetchOutcome = withContext(Dispatchers.IO) {
        val key = keys.resolved() ?: return@withContext SubtitleFetchOutcome.NoKey
        val response = attempt {
            http.post("$API/download") {
                apiHeaders(key.value)
                contentType(ContentType.Application.Json)
                setBody(JSONObject().put("file_id", subtitle.fileId).toString())
            }
        } ?: return@withContext SubtitleFetchOutcome.Offline
        FlickLog.i("http", "opensubtitles download status=${response.status.value}")
        when (response.status.value) {
            200 -> Unit
            401, 403 -> return@withContext SubtitleFetchOutcome.BadKey
            406 -> return@withContext SubtitleFetchOutcome.QuotaSpent
            429 -> return@withContext SubtitleFetchOutcome.RateLimited
            else -> return@withContext SubtitleFetchOutcome.Unavailable
        }
        val ticket = response.readBody(MAX_JSON_BYTES) ?: return@withContext SubtitleFetchOutcome.Offline
        val answer = runCatching { JSONObject(ticket.bytes?.toString(Charsets.UTF_8) ?: "") }.getOrNull()
            ?: return@withContext SubtitleFetchOutcome.Unavailable
        val link = answer.optString("link")
        val quota = OpenSubtitlesWire.quotaOf(
            remaining = answer.optInt("remaining", -1),
            resetTime = answer.optString("reset_time"),
        )
        if (!OpenSubtitlesWire.downloadLinkIsAllowed(link)) {
            return@withContext SubtitleFetchOutcome.LinkRejected
        }

        val bytes = when (val fetched = fetch(link, MAX_LINK_HOPS)) {
            is Fetched.Failed -> return@withContext fetched.outcome
            is Fetched.Bytes -> fetched.value
        }
        val name = cacheFileName(subtitle.fileName)
        val written = runCatching {
            val directory = File(appContext?.cacheDir ?: return@runCatching null, CACHE_DIR)
            directory.mkdirs()
            pruneCache(directory)
            File(directory, name).apply { writeBytes(bytes) }
        }.getOrNull() ?: return@withContext SubtitleFetchOutcome.Unavailable
        SubtitleFetchOutcome.Ready(Uri.fromFile(written), name, subtitle.language, quota)
    }

    /** One `/subtitles` request. [query] is the only thing the two searches differ in. */
    private suspend fun subtitles(key: String, query: List<Pair<String, Any>>): SubtitleSearchOutcome {
        // Built before the transport boundary so production CIO and the JVM request
        // recorder exercise the exact same canonical spelling.
        val target = buildString {
            append("$API/subtitles?")
            OpenSubtitlesWire.canonicalQuery(query).forEachIndexed { index, (name, value) ->
                if (index > 0) append('&')
                append(name)
                append('=')
                append(value.encodeURLParameter(spaceToPlus = true))
            }
        }
        val injected = searchTransport
        if (injected != null) {
            return injected.get(target, key)
        }
        val response = attempt {
            http.get(target) { apiHeaders(key) }
        } ?: return SubtitleSearchOutcome.Offline
        FlickLog.i("http", "opensubtitles search status=${response.status.value}")
        searchStatusFailure(response.status.value)?.let { return it }
        val body = response.readBody(MAX_JSON_BYTES) ?: return SubtitleSearchOutcome.Offline
        val text = body.bytes?.toString(Charsets.UTF_8) ?: return SubtitleSearchOutcome.Unavailable
        return runCatching { parseSearch(text) }
            .fold({ SubtitleSearchOutcome.Found(it) }, { SubtitleSearchOutcome.Unavailable })
    }

    private fun searchStatusFailure(status: Int): SubtitleSearchOutcome? = when (status) {
        200 -> null
        401, 403 -> SubtitleSearchOutcome.BadKey
        429 -> SubtitleSearchOutcome.RateLimited
        else -> SubtitleSearchOutcome.Unavailable
    }

    /**
     * The signed CDN link. No credential travels with it — not even the `Api-Key` — because
     * a secret must never be sent to an address this code did not fix. A 3xx is inspected
     * rather than followed: at most [MAX_LINK_HOPS] hop, and only to a target that passes
     * the same allow-list the original link passed.
     */
    private suspend fun fetch(link: String, hopsLeft: Int): Fetched {
        val response = attempt {
            http.get(link) { header(HttpHeaders.UserAgent, USER_AGENT) }
        } ?: return Fetched.Failed(SubtitleFetchOutcome.Offline)
        FlickLog.i("http", "opensubtitles file status=${response.status.value}")
        if (response.status.value in 300..399) {
            val next = OpenSubtitlesWire.allowedRedirect(response.headers[HttpHeaders.Location])
            return if (hopsLeft > 0 && next != null) {
                fetch(next, hopsLeft - 1)
            } else {
                Fetched.Failed(SubtitleFetchOutcome.LinkRejected)
            }
        }
        if (response.status.value != 200) return Fetched.Failed(SubtitleFetchOutcome.Unavailable)
        val body = response.readBody(SubtitleFiles.MaxSubtitleBytes)
            ?: return Fetched.Failed(SubtitleFetchOutcome.Offline)
        val bytes = body.bytes ?: return Fetched.Failed(SubtitleFetchOutcome.TooLarge)
        if (bytes.isEmpty()) return Fetched.Failed(SubtitleFetchOutcome.Unavailable)
        return Fetched.Bytes(bytes)
    }

    /** The bytes, or the outcome the caller should answer with. */
    private sealed interface Fetched {
        class Bytes(val value: ByteArray) : Fetched
        class Failed(val outcome: SubtitleFetchOutcome) : Fetched
    }

    private fun SubtitleSearchOutcome.isCredentialFault(): Boolean =
        this is SubtitleSearchOutcome.NoKey || this is SubtitleSearchOutcome.BadKey

    private fun HttpRequestBuilder.apiHeaders(key: String) {
        header("Api-Key", key)
        header(HttpHeaders.UserAgent, USER_AGENT)
        accept(ContentType.Application.Json)
    }

    private fun parseSearch(body: String): List<OnlineSubtitle> {
        val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
        val out = ArrayList<OnlineSubtitle>()
        var index = 0
        while (index < data.length() && out.size < MAX_PARSED_RESULTS) {
            val attributes = data.optJSONObject(index)?.optJSONObject("attributes")
            index++
            val files = attributes?.optJSONArray("files") ?: continue
            val first = files.optJSONObject(0) ?: continue
            val fileId = first.optLong("file_id", -1L)
            if (fileId <= 0L) continue
            val fileName = ControlProtocolV2.normalizedLabel(first.optString("file_name"), 120)
                ?: ControlProtocolV2.normalizedLabel(attributes.optString("release"), 120)
                ?: continue
            val feature = attributes.optJSONObject("feature_details")
            out += OnlineSubtitle(
                fileId = fileId,
                fileName = fileName,
                language = attributes.optString("language").takeIf { it.isNotBlank() && it != "null" },
                release = ControlProtocolV2.normalizedLabel(attributes.optString("release"), 120).orEmpty(),
                downloads = attributes.optInt("download_count", 0).coerceAtLeast(0),
                hashMatch = attributes.optBoolean("moviehash_match", false),
                trusted = attributes.optBoolean("from_trusted", false),
                aiTranslated = attributes.optBoolean("ai_translated", false),
                machineTranslated = attributes.optBoolean("machine_translated", false),
                hearingImpaired = attributes.optBoolean("hearing_impaired", false),
                foreignPartsOnly = attributes.optBoolean("foreign_parts_only", false),
                rating = attributes.optDouble("ratings", 0.0).takeIf { it.isFinite() } ?: 0.0,
                votes = attributes.optInt("votes", 0).coerceAtLeast(0),
                featureType = feature?.label("feature_type", 16),
                featureTitle = feature?.label("title", MAX_TITLE_CHARS),
                featureName = feature?.label("movie_name", MAX_TITLE_CHARS),
                featureParentTitle = feature?.label("parent_title", MAX_TITLE_CHARS),
                featureYear = feature?.optInt("year", -1)
                    ?.takeIf(OpenSubtitlesSearchPolicy::validYear),
                season = feature?.optInt("season_number", -1)
                    ?.takeIf(OpenSubtitlesSearchPolicy::validSeason),
                episode = feature?.optInt("episode_number", -1)
                    ?.takeIf(OpenSubtitlesSearchPolicy::validEpisode),
            )
        }
        return out
    }

    /**
     * A server-chosen label, bounded and stripped of anything that could not be shown.
     * `optString` answers a JSON null with the four-character string `null`, which is a
     * value that would otherwise reach the sheet as a title and match nothing.
     */
    private fun JSONObject.label(name: String, maximum: Int): String? =
        optString(name).takeIf { it != "null" }
            ?.let { ControlProtocolV2.normalizedLabel(it, maximum) }

    /**
     * The server picks this name, so it is treated as hostile: only a safe alphabet
     * survives, and the extension has to be one the receiver can actually load.
     */
    private fun cacheFileName(fileName: String): String {
        val cleaned = fileName.substringAfterLast('/').substringAfterLast('\\')
            .map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }
            .joinToString("")
            .trimStart('.')
            .takeLast(80)
        val stem = SubtitleFiles.baseName(cleaned).ifBlank { "subtitle" }
        val extension = SubtitleFiles.extensionOf(cleaned)?.takeIf { it in SubtitleFiles.SubtitleExtensions } ?: "srt"
        return "$stem.$extension"
    }

    /** The cache is a convenience, not a library: only the newest few files survive. */
    private fun pruneCache(directory: File) {
        val files = directory.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(MAX_CACHED_FILES).forEach { runCatching { it.delete() } }
    }

    /**
     * Null on every transport fault or timeout, so no raw exception text can ever reach
     * the UI. Cancellation is re-thrown: it is the caller leaving, not a failure.
     */
    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        withTimeoutOrNull(REQUEST_TIMEOUT_MS) { block() }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }

    /**
     * A body that was read. [bytes] is null when the response exceeded its cap — kept
     * distinct from a null [Body], which means the transfer itself failed.
     */
    private class Body(val bytes: ByteArray?)

    private suspend fun HttpResponse.readBody(limit: Long): Body? =
        attempt { Body(bodyAsChannel().readCapped(limit)) }

    /** Reads at most [limit] bytes; null once the body proves larger than that. */
    private suspend fun ByteReadChannel.readCapped(limit: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(READ_CHUNK_BYTES)
        while (true) {
            val read = readAvailable(chunk, 0, chunk.size)
            if (read < 0) break
            if (read == 0) {
                if (isClosedForRead) break else continue
            }
            if (out.size().toLong() + read > limit) return null
            out.write(chunk, 0, read)
        }
        return out.toByteArray()
    }

    private companion object {
        const val API = "https://api.opensubtitles.com/api/v1"

        /** OpenSubtitles requires an app-identifying User-Agent on every call. */
        const val USER_AGENT = "Flick v0.2.1"
        const val REQUEST_TIMEOUT_MS = 20_000L
        const val MAX_JSON_BYTES = 2L * 1024L * 1024L
        const val MAX_RESULTS = 30

        /**
         * A page is read past the row cap on purpose, because the cap used to be applied
         * in the API's own order: a fuzzy answer that led with the wrong work cut the right
         * one off before anything had judged either. Rank first, then keep [MAX_RESULTS].
         */
        const val MAX_PARSED_RESULTS = 100

        /** A catalogue title is a title, not prose; a longer one is a mis-filed entry. */
        const val MAX_TITLE_CHARS = 160
        const val MAX_CACHED_FILES = 8
        const val READ_CHUNK_BYTES = 16 * 1024
        const val CACHE_DIR = "subtitles"

        /** One hop, to an allow-listed host. A chain of redirects is a chain of hosts. */
        const val MAX_LINK_HOPS = 1
    }
}
