package com.flick.sender.net

import android.content.Context
import android.net.Uri
import com.flick.sender.BuildConfig
import com.flick.sender.media.SubtitleFiles
import com.flick.sender.util.FlickLog
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.accept
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
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
 * The credentials the online subtitle tab runs on, all of them in this app's own private
 * preferences and none of them anywhere else.
 *
 * Two different things live here. An **API key** identifies the APP — OpenSubtitles calls
 * it an API Consumer key, it is reviewed and approved per app, and the one this build
 * shipped is in `BuildConfig` rather than in these preferences. A key the user pasted
 * themselves is kept here and outranks it. A **session** is the user's own: `/login`
 * returns a bearer token, and quota attaches to the account behind that token, not to the
 * key. Only the token, the name to display and the stated expiry are stored — never the
 * password, which is sent once and held in no field.
 *
 * `flick_subtitles_online` is classified as a credential store in `BackupExclusionsTest`
 * and excluded from cloud backup and device transfer, so everything in here stays on this
 * phone. Nothing in this file is ever written to a log, an exception or a notification.
 */
internal interface OpenSubtitlesCredentials {
    fun resolved(): ResolvedApiKey?
    fun session(): OpenSubtitlesSession?
    fun saveSession(session: OpenSubtitlesSession): Boolean
    fun clearSession(): Boolean
}

internal class OpenSubtitlesKeyStore(context: Context) : OpenSubtitlesCredentials {
    private val prefs = context.applicationContext
        .getSharedPreferences("flick_subtitles_online", Context.MODE_PRIVATE)

    /** The key the user pasted, or null. Kept for power users and builds from source. */
    fun userKey(): String? = prefs.getString(API_KEY, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun saveUserKey(value: String): Boolean = prefs.edit().putString(API_KEY, value.trim()).commit()

    fun clearUserKey(): Boolean = prefs.edit().remove(API_KEY).commit()

    /**
     * The key a request will carry, or null when this build shipped none and the user has
     * pasted none. A null is the honest, expected state of a clone of this repository.
     */
    override fun resolved(): ResolvedApiKey? =
        OpenSubtitlesWire.resolveKey(userKey(), BuildConfig.OPENSUBTITLES_API_KEY)

    override fun session(): OpenSubtitlesSession? = OpenSubtitlesWire.restoredSession(
        token = prefs.getString(SESSION_TOKEN, null),
        username = prefs.getString(SESSION_USER, null),
        expiresAtMillis = prefs.getLong(SESSION_EXPIRY, 0L),
    )

    override fun saveSession(session: OpenSubtitlesSession): Boolean = prefs.edit()
        .putString(SESSION_TOKEN, session.token)
        .putString(SESSION_USER, session.username)
        .putLong(SESSION_EXPIRY, session.expiresAtMillis)
        .commit()

    override fun clearSession(): Boolean = prefs.edit()
        .remove(SESSION_TOKEN)
        .remove(SESSION_USER)
        .remove(SESSION_EXPIRY)
        .commit()

    private companion object {
        const val API_KEY = "api_key"
        const val SESSION_TOKEN = "session_token"
        const val SESSION_USER = "session_user"
        const val SESSION_EXPIRY = "session_expiry"
    }
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
)

/** Every way a search can end. Each arm gets its own honest sentence in the sheet. */
sealed interface SubtitleSearchOutcome {
    data class Found(val results: List<OnlineSubtitle>) : SubtitleSearchOutcome
    data object NoKey : SubtitleSearchOutcome
    data object Offline : SubtitleSearchOutcome
    data object BadKey : SubtitleSearchOutcome

    /** A stored token the API refused. It is dropped, not retried: the user signs in again. */
    data object SignInExpired : SubtitleSearchOutcome
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
    data object SignInExpired : SubtitleFetchOutcome
    data object RateLimited : SubtitleFetchOutcome

    /** The account's daily download allowance is spent — not an error Flick can retry. */
    data object QuotaSpent : SubtitleFetchOutcome

    /** The API answered with a download address outside OpenSubtitles' own domains. */
    data object LinkRejected : SubtitleFetchOutcome
    data object TooLarge : SubtitleFetchOutcome
    data object Unavailable : SubtitleFetchOutcome
}

/** Every way an optional sign-in can end. */
sealed interface SubtitleLoginOutcome {
    /** [username] is the name the user typed, normalized for display. */
    data class Signed(val username: String) : SubtitleLoginOutcome
    data object NoKey : SubtitleLoginOutcome
    data object Offline : SubtitleLoginOutcome
    data object BadKey : SubtitleLoginOutcome
    data object BadCredentials : SubtitleLoginOutcome
    data object RateLimited : SubtitleLoginOutcome
    data object Unavailable : SubtitleLoginOutcome
}

/** Records the exact search request below orchestration without replacing its policy. */
internal fun interface OpenSubtitlesSearchTransport {
    suspend fun get(
        url: String,
        apiKey: String,
        session: OpenSubtitlesSession?,
    ): SubtitleSearchOutcome
}

/**
 * Search and download against api.opensubtitles.com.
 *
 * The key is the app's (or one the user pasted); the allowance is the signed-in account's,
 * which is why sign-in is offered at all — one app key's daily downloads shared across
 * every install is not an allowance anybody can use. Nothing here runs without a key.
 */
class OpenSubtitlesClient private constructor(
    private val appContext: Context?,
    private val keys: OpenSubtitlesCredentials,
    private val searchTransport: OpenSubtitlesSearchTransport?,
) {
    constructor(context: Context) : this(
        context.applicationContext,
        OpenSubtitlesKeyStore(context),
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
     * On the API calls that is what stops an `Api-Key` or a bearer token from being
     * replayed to whatever host a `Location` header happens to name — Ktor copies headers
     * across a redirect, so following one would send a credential to an address this code
     * did not fix. On the CDN fetch it is what stops a hostile or compromised link from
     * bouncing the download to an arbitrary host. The one legitimate hop — a CDN that
     * answers 3xx — is taken by hand in [fetch], once, and only to another allow-listed
     * OpenSubtitles address.
     */
    private val lazyHttp = lazy { HttpClient(CIO) { followRedirects = false } }
    private val http: HttpClient get() = lazyHttp.value

    /** The signed-in account, or null. Reading it drops a session whose life is over. */
    fun session(): OpenSubtitlesSession? = liveSession()

    fun close() {
        if (lazyHttp.isInitialized()) runCatching { http.close() }
    }

    /**
     * Signs in so downloads count against the user's own daily allowance instead of the
     * allowance this build's key shares with every other install.
     *
     * [password] is written straight into the request body and is held nowhere else: not
     * in a field that outlives this call, not in the preferences, not in a log line.
     */
    suspend fun signIn(username: String, password: String): SubtitleLoginOutcome =
        withContext(Dispatchers.IO) {
            val key = keys.resolved() ?: return@withContext SubtitleLoginOutcome.NoKey
            val name = username.trim()
            if (name.isEmpty() || password.isEmpty()) {
                return@withContext SubtitleLoginOutcome.BadCredentials
            }
            val response = attempt {
                http.post("$API/login") {
                    apiHeaders(key.value)
                    contentType(ContentType.Application.Json)
                    setBody(JSONObject().put("username", name).put("password", password).toString())
                }
            } ?: return@withContext SubtitleLoginOutcome.Offline
            FlickLog.i("http", "opensubtitles login status=${response.status.value}")
            when (response.status.value) {
                200 -> Unit
                401 -> return@withContext SubtitleLoginOutcome.BadCredentials
                403 -> return@withContext SubtitleLoginOutcome.BadKey
                429 -> return@withContext SubtitleLoginOutcome.RateLimited
                else -> return@withContext SubtitleLoginOutcome.Unavailable
            }
            val body = response.readBody(MAX_JSON_BYTES) ?: return@withContext SubtitleLoginOutcome.Offline
            val text = body.bytes?.toString(Charsets.UTF_8)
                ?: return@withContext SubtitleLoginOutcome.Unavailable
            // The answer also carries `base_url`, a host for VIP accounts. It is
            // deliberately ignored: a host the server chooses is a host this code did not
            // fix, and every request here stays on the one address above.
            val token = runCatching { JSONObject(text).optString("token") }.getOrNull()
            val session = OpenSubtitlesWire.sessionOf(token, name, System.currentTimeMillis())
                ?: return@withContext SubtitleLoginOutcome.Unavailable
            keys.saveSession(session)
            SubtitleLoginOutcome.Signed(session.username)
        }

    /** Best effort on the wire, certain locally: the token is gone either way. */
    suspend fun signOut() {
        withContext(Dispatchers.IO) {
            val key = keys.resolved()
            val session = keys.session()
            if (key != null && session != null) {
                val response = attempt {
                    http.delete("$API/logout") { apiHeaders(key.value, session) }
                }
                response?.let { FlickLog.i("http", "opensubtitles logout status=${it.status.value}") }
            }
            keys.clearSession()
        }
    }

    /**
     * Searches by [movieHash] first and by [query] second, hash answers ranked first.
     *
     * The hash names THIS file, so a match is a subtitle already in sync with it. It is
     * also the half allowed to fail quietly: a hash the server has never seen, or a
     * request that does not land, leaves the text search — the one that works today —
     * exactly as it was.
     */
    suspend fun search(
        query: String,
        year: Int? = null,
        season: Int?,
        episode: Int?,
        movieHash: String? = null,
        movieByteSize: Long = -1L,
        language: OpenSubtitlesLanguage = OpenSubtitlesSearchPolicy.DefaultLanguage,
    ): SubtitleSearchOutcome = withContext(Dispatchers.IO) {
        val key = keys.resolved() ?: return@withContext SubtitleSearchOutcome.NoKey
        val term = OpenSubtitlesSearchPolicy.textQuery(query)
        val hashParameters = OpenSubtitlesWire.hashSearchParameters(movieHash, movieByteSize, language)
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
        // A refused key or a spent session is the same fault for both halves; answering it
        // once is better than running the text query with a credential just refused.
        if (hashOutcome != null && hashOutcome.isCredentialFault()) return@withContext hashOutcome
        val hashResults = (hashOutcome as? SubtitleSearchOutcome.Found)?.results.orEmpty()
        if (!OpenSubtitlesSearchPolicy.shouldRunTextFallback(hashResults, term)) {
            return@withContext SubtitleSearchOutcome.Found(OpenSubtitlesWire.ordered(hashResults))
        }

        val text = subtitles(
            key = key.value,
            query = textParameters,
        )
        when {
            text is SubtitleSearchOutcome.Found ->
                SubtitleSearchOutcome.Found(
                    OpenSubtitlesWire.merged(hashResults, text.results, MAX_RESULTS),
                )
            // A failed text query is still an honest failure — unless the hash already
            // answered, in which case the user gets the better results rather than an
            // error about the weaker half of the same search.
            hashResults.isNotEmpty() ->
                SubtitleSearchOutcome.Found(OpenSubtitlesWire.ordered(hashResults))
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
        val session = liveSession()
        val response = attempt {
            http.post("$API/download") {
                apiHeaders(key.value, session)
                contentType(ContentType.Application.Json)
                setBody(JSONObject().put("file_id", subtitle.fileId).toString())
            }
        } ?: return@withContext SubtitleFetchOutcome.Offline
        FlickLog.i("http", "opensubtitles download status=${response.status.value}")
        when (response.status.value) {
            200 -> Unit
            401 -> return@withContext if (session != null) {
                keys.clearSession()
                SubtitleFetchOutcome.SignInExpired
            } else {
                SubtitleFetchOutcome.BadKey
            }
            403 -> return@withContext SubtitleFetchOutcome.BadKey
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
        val session = liveSession()
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
            return injected.get(target, key, session)
        }
        val response = attempt {
            http.get(target) { apiHeaders(key, session) }
        } ?: return SubtitleSearchOutcome.Offline
        FlickLog.i("http", "opensubtitles search status=${response.status.value}")
        searchStatusFailure(response.status.value, session)?.let { return it }
        val body = response.readBody(MAX_JSON_BYTES) ?: return SubtitleSearchOutcome.Offline
        val text = body.bytes?.toString(Charsets.UTF_8) ?: return SubtitleSearchOutcome.Unavailable
        return runCatching { parseSearch(text) }
            .fold({ SubtitleSearchOutcome.Found(it) }, { SubtitleSearchOutcome.Unavailable })
    }

    private fun searchStatusFailure(
        status: Int,
        session: OpenSubtitlesSession?,
    ): SubtitleSearchOutcome? = when (status) {
        200 -> null
        401 -> if (session != null) {
                keys.clearSession()
                SubtitleSearchOutcome.SignInExpired
            } else {
                SubtitleSearchOutcome.BadKey
            }
        403 -> SubtitleSearchOutcome.BadKey
        429 -> SubtitleSearchOutcome.RateLimited
        else -> SubtitleSearchOutcome.Unavailable
    }

    /**
     * The signed CDN link. No credential travels with it — not the `Api-Key`, not the
     * bearer token — because a secret must never be sent to an address this code did not
     * fix. A 3xx is inspected rather than followed: at most [MAX_LINK_HOPS] hop, and only
     * to a target that passes the same allow-list the original link passed.
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

    /**
     * The stored session, after dropping one whose stated life is over. The 24-hour life
     * OpenSubtitles documents is not restated in any answer, so this is a hint and not the
     * authority: a 401 drops the token the same way, and neither path ever retries with it.
     */
    private fun liveSession(): OpenSubtitlesSession? {
        val session = keys.session() ?: return null
        if (OpenSubtitlesWire.sessionIsLive(session, System.currentTimeMillis())) return session
        keys.clearSession()
        return null
    }

    private fun SubtitleSearchOutcome.isCredentialFault(): Boolean =
        this is SubtitleSearchOutcome.NoKey ||
            this is SubtitleSearchOutcome.BadKey ||
            this is SubtitleSearchOutcome.SignInExpired

    private fun HttpRequestBuilder.apiHeaders(key: String, session: OpenSubtitlesSession? = null) {
        header("Api-Key", key)
        session?.let { header(HttpHeaders.Authorization, "Bearer ${it.token}") }
        header(HttpHeaders.UserAgent, USER_AGENT)
        accept(ContentType.Application.Json)
    }

    private fun parseSearch(body: String): List<OnlineSubtitle> {
        val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
        val out = ArrayList<OnlineSubtitle>()
        var index = 0
        while (index < data.length() && out.size < MAX_RESULTS) {
            val attributes = data.optJSONObject(index)?.optJSONObject("attributes")
            index++
            val files = attributes?.optJSONArray("files") ?: continue
            val first = files.optJSONObject(0) ?: continue
            val fileId = first.optLong("file_id", -1L)
            if (fileId <= 0L) continue
            val fileName = ControlProtocolV2.normalizedLabel(first.optString("file_name"), 120)
                ?: ControlProtocolV2.normalizedLabel(attributes.optString("release"), 120)
                ?: continue
            out += OnlineSubtitle(
                fileId = fileId,
                fileName = fileName,
                language = attributes.optString("language").takeIf { it.isNotBlank() && it != "null" },
                release = ControlProtocolV2.normalizedLabel(attributes.optString("release"), 120).orEmpty(),
                downloads = attributes.optInt("download_count", 0),
                hashMatch = attributes.optBoolean("moviehash_match", false),
            )
        }
        return out
    }

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
        const val MAX_CACHED_FILES = 8
        const val READ_CHUNK_BYTES = 16 * 1024
        const val CACHE_DIR = "subtitles"

        /** One hop, to an allow-listed host. A chain of redirects is a chain of hosts. */
        const val MAX_LINK_HOPS = 1
    }
}
