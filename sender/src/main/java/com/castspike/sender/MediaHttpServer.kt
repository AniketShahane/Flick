package com.castspike.sender

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.routing
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream

/**
 * Embedded Ktor (CIO) HTTP server that direct-plays the currently selected video
 * to the Android TV over the LAN. Implements the Phase 0 contract:
 *
 *   GET/HEAD /video  — full 200 or ranged 206 with correct byte-range headers,
 *                      streamed straight off the content:// file descriptor.
 *   GET      /ping   — 200 "ok" health check.
 *
 * The whole point of the spike is zero-stall direct play: we NEVER copy the file
 * into cache and NEVER transcode — we seek the fd and copy exactly the requested
 * slice.
 *
 * Start/stop are synchronized and idempotent so the owning foreground service can
 * call them freely (e.g. re-picking a video while already serving).
 */
class MediaHttpServer(context: Context) {

    private val appContext: Context = context.applicationContext
    private val resolver: ContentResolver get() = appContext.contentResolver

    private val lock = Any()
    private var server: EmbeddedServer<*, *>? = null

    /** The URI currently being served. Read on every request thread. */
    @Volatile
    var currentUri: Uri? = null
        private set

    val isRunning: Boolean get() = synchronized(lock) { server != null }

    /**
     * Start serving [uri]. If the server is already up, just swaps the served
     * URI (no restart). Throws if the socket cannot be bound.
     */
    fun start(uri: Uri) {
        synchronized(lock) {
            currentUri = uri
            if (server != null) return
            val engine = embeddedServer(CIO, port = SERVER_PORT, host = SERVER_HOST) {
                configureRouting()
            }
            engine.start(false)
            server = engine
        }
    }

    /** Stop the server and clear the served URI. Safe to call repeatedly. */
    fun stop() {
        val engine: EmbeddedServer<*, *>?
        synchronized(lock) {
            currentUri = null
            engine = server
            server = null
        }
        if (engine != null) {
            try {
                // Keep the grace/timeout small: stop() blocks the calling thread
                // until the engine drains, and this is invoked from the service's
                // main-thread teardown (onDestroy / ACTION_STOP). A short window is
                // plenty to release the listening socket without janking the UI.
                engine.stop(100, 300)
            } catch (e: Exception) {
                Log.w(TAG, "Error while stopping server", e)
            }
        }
    }

    // --- Routing ------------------------------------------------------------

    private fun Application.configureRouting() {
        routing {
            get("/ping") { call.respondText("ok", status = HttpStatusCode.OK) }
            // Serve GET and HEAD from the same handler; handleVideo() branches on
            // the request method to emit a body (GET) or headers only (HEAD).
            get("/video") { handleVideo(call) }
            head("/video") { handleVideo(call) }
        }
    }

    private suspend fun handleVideo(call: ApplicationCall) {
        val uri = currentUri
        val method = call.request.httpMethod

        if (uri == null) {
            call.respondText("No video selected", status = HttpStatusCode.NotFound)
            return
        }
        if (method != HttpMethod.Get && method != HttpMethod.Head) {
            call.respondText("Method not allowed", status = HttpStatusCode.MethodNotAllowed)
            return
        }

        val contentType = safeContentType(runCatching { resolver.getType(uri) }.getOrNull())
        val total = MediaMeta.resolveSize(resolver, uri)

        // We always advertise range support.
        call.response.headers.append(HttpHeaders.AcceptRanges, "bytes")

        val rangeHeader = call.request.headers[HttpHeaders.Range]

        // Resolve the request into either a full-body (200) or partial (206)
        // response. A missing header, unknown size, or malformed Range all fall
        // through to the full body; only a well-formed-but-unsatisfiable range
        // short-circuits with 416.
        val partial: RangeResult.Partial? =
            if (rangeHeader == null || total < 0) {
                null
            } else {
                when (val parsed = parseRange(rangeHeader, total)) {
                    RangeResult.Full -> null
                    RangeResult.Unsatisfiable -> {
                        // 416 with Content-Range: bytes */total.
                        call.response.headers.append(HttpHeaders.ContentRange, "bytes */$total")
                        call.respondText(
                            "Requested range not satisfiable",
                            status = HttpStatusCode.RequestedRangeNotSatisfiable,
                        )
                        return
                    }
                    is RangeResult.Partial -> parsed
                }
            }

        // No usable range: serve the whole thing as 200.
        if (partial == null) {
            val length = if (total >= 0) total else null
            if (method == HttpMethod.Head) {
                call.respond(HeadResponse(HttpStatusCode.OK, contentType, length))
            } else {
                call.respondOutputStream(
                    contentType = contentType,
                    status = HttpStatusCode.OK,
                    contentLength = length,
                ) {
                    streamSlice(uri, start = 0L, maxLength = length ?: Long.MAX_VALUE, out = this)
                }
            }
            return
        }

        val start = partial.start
        val end = partial.end
        val length = end - start + 1
        call.response.headers.append(HttpHeaders.ContentRange, "bytes $start-$end/$total")

        if (method == HttpMethod.Head) {
            call.respond(HeadResponse(HttpStatusCode.PartialContent, contentType, length))
        } else {
            call.respondOutputStream(
                contentType = contentType,
                status = HttpStatusCode.PartialContent,
                contentLength = length,
            ) {
                streamSlice(uri, start = start, maxLength = length, out = this)
            }
        }
    }

    // --- Byte streaming -----------------------------------------------------

    /**
     * Copies exactly [maxLength] bytes (or up to EOF) starting at byte [start]
     * from the content URI into [out]. Streams straight off the file descriptor
     * — never buffers the whole file. Client disconnects and revoked grants are
     * swallowed so the server stays up.
     */
    private fun streamSlice(uri: Uri, start: Long, maxLength: Long, out: OutputStream) {
        try {
            val pfd = resolver.openFileDescriptor(uri, "r")
                ?: throw FileNotFoundException("Cannot open $uri")
            // AutoCloseInputStream owns the ParcelFileDescriptor, so a single
            // .use closes both the stream and the fd with no double-close.
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                if (start > 0L) {
                    // FileInputStream and its channel share the fd offset, so
                    // seeking the channel positions subsequent reads.
                    input.channel.position(start)
                }
                val buffer = ByteArray(BUFFER_SIZE)
                var remaining = maxLength
                while (remaining > 0L) {
                    val toRead = if (remaining < buffer.size) remaining.toInt() else buffer.size
                    val read = input.read(buffer, 0, toRead)
                    if (read == -1) break
                    out.write(buffer, 0, read)
                    remaining -= read
                }
                out.flush()
            }
        } catch (e: IOException) {
            // Typically the TV closed the connection mid-transfer (seek/stop).
            Log.d(TAG, "Streaming stopped: ${e.message}")
        } catch (e: Exception) {
            // Revoked URI grant, etc. Never let it take down the server.
            Log.w(TAG, "Streaming failed for $uri", e)
        }
    }

    // --- Helpers ------------------------------------------------------------

    /**
     * Empty-body response carrying only headers/status, used to answer HEAD with
     * the same Content-Type / Content-Length a GET would produce.
     */
    private class HeadResponse(
        override val status: HttpStatusCode,
        override val contentType: ContentType,
        override val contentLength: Long?,
    ) : OutgoingContent.NoContent()

    companion object {
        private const val TAG = "MediaHttpServer"
        private const val BUFFER_SIZE = 256 * 1024

        private val FALLBACK_TYPE = ContentType("video", "mp4")

        private fun safeContentType(mime: String?): ContentType {
            if (mime.isNullOrBlank()) return FALLBACK_TYPE
            return try {
                ContentType.parse(mime)
            } catch (_: Exception) {
                FALLBACK_TYPE
            }
        }

        /**
         * Outcome of parsing a `Range` header, per RFC 9110 §14.1–14.4.
         *
         * The distinction matters: a *syntactically invalid* Range header MUST be
         * ignored (serve the full 200 body), whereas a *well-formed but
         * unsatisfiable* range MUST be answered with 416.
         */
        internal sealed interface RangeResult {
            /** No usable range: ignore the header and serve the full 200 body. */
            object Full : RangeResult

            /** Well-formed but cannot be satisfied against [total] -> 416. */
            object Unsatisfiable : RangeResult

            /** Inclusive `[start, end]`, clamped to the file -> 206. */
            data class Partial(val start: Long, val end: Long) : RangeResult
        }

        /**
         * Parses a single-range `Range` header against a known [total] size.
         * Supports `bytes=start-end`, open-ended `bytes=start-`, and suffix
         * `bytes=-N`.
         *
         * Returns [RangeResult.Full] for anything malformed (unrecognised unit,
         * empty/garbled spec, non-numeric bounds, or first-byte-pos >
         * last-byte-pos) so the caller ignores the header and serves 200.
         * Returns [RangeResult.Unsatisfiable] for a well-formed range that lies
         * outside the file (-> 416), and [RangeResult.Partial] otherwise.
         */
        internal fun parseRange(header: String, total: Long): RangeResult {
            // Malformed / unrecognised Range unit: ignore the header (serve 200).
            if (!header.startsWith("bytes=")) return RangeResult.Full

            // Only honour the first range if a client sends a set.
            val spec = header.removePrefix("bytes=").substringBefore(',').trim()
            if (spec.isEmpty()) return RangeResult.Full
            val dash = spec.indexOf('-')
            if (dash < 0) return RangeResult.Full

            val startStr = spec.substring(0, dash).trim()
            val endStr = spec.substring(dash + 1).trim()

            if (startStr.isEmpty()) {
                // Suffix form: last N bytes. "bytes=-" is malformed.
                if (endStr.isEmpty()) return RangeResult.Full
                val suffix = endStr.toLongOrNull() ?: return RangeResult.Full
                // A suffix length of 0 is a well-formed but unsatisfiable range.
                if (suffix <= 0L) return RangeResult.Unsatisfiable
                if (total <= 0L) return RangeResult.Unsatisfiable
                val start = if (suffix >= total) 0L else total - suffix
                return RangeResult.Partial(start, total - 1)
            }

            val start = startStr.toLongOrNull() ?: return RangeResult.Full
            // A negative first-byte-pos can't occur here (leading '-' empties
            // startStr), but guard anyway: treat it as malformed.
            if (start < 0L) return RangeResult.Full

            val end: Long
            if (endStr.isEmpty()) {
                end = total - 1
            } else {
                val e = endStr.toLongOrNull() ?: return RangeResult.Full
                // first-byte-pos > last-byte-pos is invalid syntax: ignore header.
                if (e < start) return RangeResult.Full
                end = minOf(e, total - 1)
            }

            // Well-formed but out of range (incl. empty/unknown file) -> 416.
            if (total <= 0L || start >= total) return RangeResult.Unsatisfiable
            return RangeResult.Partial(start, end)
        }
    }
}
