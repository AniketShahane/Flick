package com.flick.receiver.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Raised before a Location can be followed. It deliberately carries no URL. */
class RedirectRejectedException(val statusCode: Int) : IOException("HTTP redirect rejected: $statusCode")

/** A safe status-only error for the session taxonomy; response bodies stay local. */
class PlaybackHttpStatusException(val statusCode: Int) : IOException("HTTP response rejected: $statusCode")

/** Carries the ceiling, never the URL or any part of the body. */
class SubtitleTooLargeException(val limitBytes: Long) : IOException("subtitle body exceeds $limitBytes bytes")

/**
 * The subtitle route's byte ceiling, matching the sender's own
 * `SubtitlePolicy.MAX_BYTES` deliberately — the two are one convention and a TV
 * that trusted the phone's copy of it would be enforcing nothing.
 *
 * It exists because Media3's `SingleSampleMediaSource` reads a sideloaded subtitle
 * into a single in-memory sample, growing the array as bytes arrive. An already
 * paired phone — or anything that has taken over that session — can therefore hand
 * the TV an arbitrarily large body and exhaust its heap. A subtitle is kilobytes.
 */
internal const val SUBTITLE_BODY_MAX_BYTES: Long = 5L * 1024L * 1024L

/**
 * Whether [path] is the sideloaded-subtitle route, and so the one route [SUBTITLE_BODY_MAX_BYTES]
 * applies to.
 *
 * The cap is deliberately NOT global: the media route legitimately streams
 * gigabytes of 4K HDR through this same factory, and a ceiling there would cap the
 * product. The prefix is all this needs to decide — `MediaUrlValidator` has already
 * pinned the full `^/s/{22}$` shape, the peer, the port and the scheme before any
 * URL reaches the player, so this is route selection rather than validation.
 */
internal fun isSubtitleRoute(path: String?): Boolean = path != null && path.startsWith("/s/")

/**
 * The route ceiling as a pure seam, so what the data source enforces is what a test
 * can exercise without a socket — the same reason [NoRedirectRequestGate] exists.
 *
 * A null [cap] is the uncapped media route and both checks then pass everything: one
 * factory serves both routes, and the gigabytes of a 4K stream must not be measured
 * against a subtitle's ceiling. Build one per `open()`; it counts a single body.
 */
internal class SubtitleBodyGate(private val cap: Long?) {
    var produced: Long = 0L
        private set

    /**
     * The declared size, refused before a byte is read. [length] is
     * `C.LENGTH_UNSET` when neither the `DataSpec` nor `Content-Length` states one,
     * which is not itself a refusal: the sender's subtitle route answers a
     * whole-file 200 with no range, and [verifyProduced] is what covers a body that
     * declares nothing — or under-reports.
     */
    fun verifyDeclaredLength(length: Long) {
        val limit = cap ?: return
        if (length != C.LENGTH_UNSET.toLong() && length > limit) throw SubtitleTooLargeException(limit)
    }

    /** One chunk of body, counted and refused as the running total overruns. */
    fun verifyProduced(read: Int) {
        produced += read.toLong()
        val limit = cap ?: return
        if (produced > limit) throw SubtitleTooLargeException(limit)
    }
}

/** Pure one-request seam used to prove a 3xx never becomes a follow-up request. */
class NoRedirectRequestGate {
    var requestCount: Int = 0
        private set

    fun verifyResponse(statusCode: Int) {
        requestCount++
        if (statusCode in 300..399) throw RedirectRejectedException(statusCode)
    }
}

/**
 * A deliberately narrow HTTP data source for the pinned, byte-range media URL.
 *
 * Media3's stock data source only guarantees cross-protocol redirects can be
 * disabled in the version pinned by this app. Checking its URI after `open()`
 * is too late because a same-protocol redirect has already made a second request.
 * This implementation gives each [DataSpec] one `HttpURLConnection` with
 * `instanceFollowRedirects=false`, and fails on every 3xx before opening a body.
 */
class NoRedirectHttpDataSourceFactory(
    private val connectTimeoutMs: Int,
    private val readTimeoutMs: Int,
    private val userAgent: String,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = NoRedirectHttpDataSource(
        connectTimeoutMs = connectTimeoutMs,
        readTimeoutMs = readTimeoutMs,
        userAgent = userAgent,
    )
}

private class NoRedirectHttpDataSource(
    private val connectTimeoutMs: Int,
    private val readTimeoutMs: Int,
    private val userAgent: String,
) : BaseDataSource(true) {
    private var connection: HttpURLConnection? = null
    private var stream: InputStream? = null
    private var opened = false
    private var bytesRemaining = C.LENGTH_UNSET.toLong()
    private var resolvedUri: Uri? = null

    /** One per body, capped on the subtitle route and uncapped on the media route. */
    private var bodyGate = SubtitleBodyGate(null)

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        if (dataSpec.httpMethod != DataSpec.HTTP_METHOD_GET) {
            throw IOException("only GET is permitted for direct-play media")
        }
        val url = runCatching { URL(dataSpec.uri.toString()) }.getOrElse { throw IOException("invalid media URL", it) }
        if (url.protocol != "http") throw IOException("only HTTP is permitted for direct-play media")
        val openedConnection = (url.openConnection() as? HttpURLConnection)
            ?: throw IOException("not an HTTP connection")
        connection = openedConnection
        bodyGate = SubtitleBodyGate(if (isSubtitleRoute(dataSpec.uri.path)) SUBTITLE_BODY_MAX_BYTES else null)
        try {
            openedConnection.instanceFollowRedirects = false
            openedConnection.connectTimeout = connectTimeoutMs
            openedConnection.readTimeout = readTimeoutMs
            openedConnection.requestMethod = "GET"
            openedConnection.setRequestProperty("User-Agent", userAgent)
            for ((name, value) in dataSpec.httpRequestHeaders) {
                openedConnection.setRequestProperty(name, value)
            }
            if (dataSpec.position != 0L || dataSpec.length != C.LENGTH_UNSET.toLong()) {
                val end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) "" else (dataSpec.position + dataSpec.length - 1L).toString()
                openedConnection.setRequestProperty("Range", "bytes=${dataSpec.position}-$end")
            }

            val status = openedConnection.responseCode
            // This is the sole connection request; no Location is resolved.
            NoRedirectRequestGate().verifyResponse(status)
            if (status !in 200..299) throw PlaybackHttpStatusException(status)

            stream = openedConnection.inputStream
            resolvedUri = dataSpec.uri
            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else {
                openedConnection.contentLengthLong.takeIf { it >= 0L } ?: C.LENGTH_UNSET.toLong()
            }
            // Half of the convention the sender enforces on its own side; [read]
            // carries the other half, because a body that under-reports its size
            // would otherwise walk straight past this one.
            bodyGate.verifyDeclaredLength(bytesRemaining)
            opened = true
            transferStarted(dataSpec)
            return bytesRemaining
        } catch (error: IOException) {
            closeConnectionOnly()
            throw error
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val read = try {
            stream?.read(buffer, offset, if (bytesRemaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), bytesRemaining).toInt())
                ?: C.RESULT_END_OF_INPUT
        } catch (error: IOException) {
            throw error
        }
        if (read == C.RESULT_END_OF_INPUT) return C.RESULT_END_OF_INPUT
        // Ahead of `bytesTransferred`: the single in-memory sample downstream grows
        // on whatever this call returns, so an overrun has to end the read rather
        // than be counted once the bytes are already handed over.
        bodyGate.verifyProduced(read)
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= read.toLong()
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = resolvedUri

    override fun close() {
        try { stream?.close() } finally {
            stream = null
            closeConnectionOnly()
            resolvedUri = null
            bytesRemaining = C.LENGTH_UNSET.toLong()
            bodyGate = SubtitleBodyGate(null)
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    private fun closeConnectionOnly() {
        connection?.disconnect()
        connection = null
    }
}
