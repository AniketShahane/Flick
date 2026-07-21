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
