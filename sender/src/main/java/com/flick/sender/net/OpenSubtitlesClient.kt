package com.flick.sender.net

import android.content.Context
import android.net.Uri
import com.flick.sender.media.SubtitleFiles
import com.flick.sender.util.FlickLog
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.accept
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
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
 * The user's own OpenSubtitles API key. Flick ships NO key: this repository is public,
 * so a bundled credential — even a placeholder — would be a published secret. With no
 * key stored the online tab makes no request at all.
 *
 * The key is written only to this app's private preferences and is never logged, never
 * sent anywhere except api.opensubtitles.com, and never attached to the CDN download
 * the API hands back.
 */
class OpenSubtitlesKeyStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("flick_subtitles_online", Context.MODE_PRIVATE)

    fun key(): String? = prefs.getString(API_KEY, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun save(value: String): Boolean = prefs.edit().putString(API_KEY, value.trim()).commit()

    fun clear(): Boolean = prefs.edit().remove(API_KEY).commit()

    private companion object { const val API_KEY = "api_key" }
}

/** One downloadable subtitle from a search. [fileId] is what the download call takes. */
data class OnlineSubtitle(
    val fileId: Long,
    val fileName: String,
    val language: String?,
    val release: String,
    val downloads: Int,
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
    data class Ready(val uri: Uri, val displayName: String, val language: String?) : SubtitleFetchOutcome
    data object NoKey : SubtitleFetchOutcome
    data object Offline : SubtitleFetchOutcome
    data object BadKey : SubtitleFetchOutcome
    data object RateLimited : SubtitleFetchOutcome

    /** The account's daily download allowance is spent — not an error Flick can retry. */
    data object QuotaSpent : SubtitleFetchOutcome
    data object TooLarge : SubtitleFetchOutcome
    data object Unavailable : SubtitleFetchOutcome
}

/**
 * Search and download against api.opensubtitles.com with a key the user supplies at
 * runtime. Nothing here runs without that key.
 */
class OpenSubtitlesClient(
    context: Context,
    private val keys: OpenSubtitlesKeyStore = OpenSubtitlesKeyStore(context),
) {
    private val appContext = context.applicationContext
    private val http by lazy { HttpClient(CIO) }

    fun hasKey(): Boolean = keys.key() != null

    fun close() {
        runCatching { http.close() }
    }

    suspend fun search(query: String, season: Int?, episode: Int?): SubtitleSearchOutcome =
        withContext(Dispatchers.IO) {
            val key = keys.key() ?: return@withContext SubtitleSearchOutcome.NoKey
            val term = query.trim()
            if (term.isEmpty()) return@withContext SubtitleSearchOutcome.Found(emptyList())
            val response = attempt {
                http.get("$API/subtitles") {
                    apiHeaders(key)
                    parameter("query", term)
                    season?.let { parameter("season_number", it) }
                    episode?.let { parameter("episode_number", it) }
                }
            } ?: return@withContext SubtitleSearchOutcome.Offline
            FlickLog.i("http", "opensubtitles search status=${response.status.value}")
            when (response.status.value) {
                200 -> Unit
                401, 403 -> return@withContext SubtitleSearchOutcome.BadKey
                429 -> return@withContext SubtitleSearchOutcome.RateLimited
                else -> return@withContext SubtitleSearchOutcome.Unavailable
            }
            val body = response.readBody(MAX_JSON_BYTES) ?: return@withContext SubtitleSearchOutcome.Offline
            val text = body.bytes?.toString(Charsets.UTF_8)
                ?: return@withContext SubtitleSearchOutcome.Unavailable
            runCatching { parseSearch(text) }
                .fold({ SubtitleSearchOutcome.Found(it) }, { SubtitleSearchOutcome.Unavailable })
        }

    /**
     * Resolves the download link, fetches it and writes the file into this app's own
     * cache. The returned Uri is a `file://` inside `cacheDir` — the media server reads
     * it through the same ContentResolver it reads a picked document with.
     */
    suspend fun download(subtitle: OnlineSubtitle): SubtitleFetchOutcome = withContext(Dispatchers.IO) {
        val key = keys.key() ?: return@withContext SubtitleFetchOutcome.NoKey
        val response = attempt {
            http.post("$API/download") {
                apiHeaders(key)
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
        val link = runCatching { JSONObject(ticket.bytes?.toString(Charsets.UTF_8) ?: "").optString("link") }
            .getOrNull()
            ?.takeIf { it.startsWith("https://") }
            ?: return@withContext SubtitleFetchOutcome.Unavailable

        // The link is a signed CDN URL on a host the API chose. The API key is NOT sent
        // with it: a credential must never travel to an address this code did not fix.
        val file = attempt {
            http.get(link) { header(HttpHeaders.UserAgent, USER_AGENT) }
        } ?: return@withContext SubtitleFetchOutcome.Offline
        if (file.status.value != 200) return@withContext SubtitleFetchOutcome.Unavailable
        val body = file.readBody(SubtitleFiles.MaxSubtitleBytes)
            ?: return@withContext SubtitleFetchOutcome.Offline
        val bytes = body.bytes ?: return@withContext SubtitleFetchOutcome.TooLarge
        if (bytes.isEmpty()) return@withContext SubtitleFetchOutcome.Unavailable

        val name = cacheFileName(subtitle.fileName)
        val written = runCatching {
            val directory = File(appContext.cacheDir, CACHE_DIR)
            directory.mkdirs()
            pruneCache(directory)
            File(directory, name).apply { writeBytes(bytes) }
        }.getOrNull() ?: return@withContext SubtitleFetchOutcome.Unavailable
        SubtitleFetchOutcome.Ready(Uri.fromFile(written), name, subtitle.language)
    }

    private fun HttpRequestBuilder.apiHeaders(key: String) {
        header("Api-Key", key)
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
    }
}
