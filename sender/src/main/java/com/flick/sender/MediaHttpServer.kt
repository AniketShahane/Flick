package com.flick.sender

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.flick.sender.util.FlickLog
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.host
import io.ktor.server.request.httpMethod
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.Semaphore
import kotlin.coroutines.cancellation.CancellationException

/**
 * Embedded Ktor (CIO) HTTP server that direct-plays the currently selected video
 * to the Android TV over the LAN. Implements the hardened contract:
 *
 *   GET/HEAD /v/{token} — full 200 or ranged 206 with correct byte-range headers,
 *                         streamed straight off the content:// file descriptor.
 *                         {token} must match the current per-session token or the
 *                         request is answered 404 (never revealing why).
 *   GET      /ping      — 200 "ok" health check (unauthenticated: exposes no bytes).
 *
 * The whole point of the spike is zero-stall direct play: we NEVER copy the file
 * into cache and NEVER transcode — we seek the fd and copy exactly the requested
 * slice.
 *
 * The socket binds ONLY the phone's LAN IP (not 0.0.0.0), the video handler pins
 * the Host header to that IP (anti-DNS-rebinding), and concurrent body transfers
 * are capped so a LAN flood cannot exhaust the engine.
 *
 * Start/stop are synchronized and idempotent so the owning foreground service can
 * call them freely (e.g. re-picking a video while already serving).
 */
class MediaHttpServer(context: Context) {

    private val appContext: Context = context.applicationContext
    private val resolver: ContentResolver get() = appContext.contentResolver

    private val lock = Any()
    private var server: EmbeddedServer<*, *>? = null

    // Caps concurrent GET *body* transfers so a LAN flood can't exhaust the CIO
    // engine (HEAD/404/403/416 are cheap and stay ungated). Fair so a burst of
    // clients can't starve an earlier waiter. Process-wide: one server at a time.
    private val transferPermits = Semaphore(MAX_CONCURRENT_TRANSFERS, true)

    /**
     * The (uri, token) pair currently served, published as ONE immutable object via a
     * single atomic reference. They were two separate @Volatile fields, which let a
     * retarget from A/tokenA to B/tokenB be observed half-applied (new uri B still
     * paired with the not-yet-overwritten tokenA) — a TOCTOU that could stream video B
     * under the stale token. Each request captures this reference once, so uri and
     * token are always the matched pair the session actually published.
     */
    private data class ServedSession(val uri: Uri, val token: String)

    private val servedSession = AtomicMediaSession<ServedSession>()

    // The LAN IP the socket is bound to; the video handler pins the request Host
    // to this literal to reject DNS-rebinding. Read on every request thread.
    @Volatile
    private var boundHost: String? = null

    val isRunning: Boolean get() = synchronized(lock) { server != null }

    /**
     * Start serving [uri] under [token], bound to [bindHost] (the phone's LAN IP).
     * If the server is already up on the same host, just swaps the served URI +
     * token (no restart). If the host changed (LAN IP moved), the engine is torn
     * down and rebound. Throws if the socket cannot be bound.
     */
    fun start(uri: Uri, token: String, bindHost: String) {
        synchronized(lock) {
            // Publish the new session target atomically before (re)binding so no request
            // can observe a live socket with a stale token/URI (or a mismatched pair).
            servedSession.publish(ServedSession(uri, token))

            val running = server
            if (running != null && boundHost == bindHost) return
            if (running != null) {
                // Bound host changed: stop the old engine before rebinding so we
                // never keep a socket listening on a stale address.
                stopEngine(running)
                server = null
            }

            boundHost = bindHost
            val engine = embeddedServer(
                CIO,
                environment = applicationEnvironment { },
                configure = {
                    connector {
                        host = bindHost
                        port = SERVER_PORT
                    }
                    // Reap idle sockets so a client that opens a connection and
                    // then stalls can't hold an engine slot indefinitely.
                    connectionIdleTimeoutSeconds = IDLE_TIMEOUT_SECONDS
                },
            ) {
                configureRouting()
            }
            engine.start(false)
            server = engine
            // Length only — the token itself is the media capability.
            FlickLog.i("bind", "media server $bindHost:$SERVER_PORT tokenLen=${token.length}")
        }
    }

    /** Stop the server and clear the served URI/token. Safe to call repeatedly. */
    fun stop() {
        val engine: EmbeddedServer<*, *>?
        synchronized(lock) {
            servedSession.clear()
            boundHost = null
            engine = server
            server = null
        }
        if (engine != null) stopEngine(engine)
    }

    private fun stopEngine(engine: EmbeddedServer<*, *>) {
        try {
            // Keep the grace/timeout small: stop() blocks the calling thread until
            // the engine drains, and this is invoked from the service's main-thread
            // teardown (onDestroy / ACTION_STOP). A short window is plenty to
            // release the listening socket without janking the UI.
            engine.stop(100, 300)
        } catch (e: Exception) {
            FlickLog.w("http", "server stop failed ${e.javaClass.simpleName}", e)
        }
    }

    // --- Routing ------------------------------------------------------------

    private fun Application.configureRouting() {
        routing {
            get("/ping") { call.respondText("ok", status = HttpStatusCode.OK) }
            // Serve GET and HEAD from the same handler; handleVideo() branches on
            // the request method to emit a body (GET) or headers only (HEAD).
            // pathParameters (not the query-merged parameters) so a "?token=" query
            // can never shadow the real path segment we authenticate against.
            get("/v/{token}") { handleVideo(call, call.pathParameters["token"]) }
            head("/v/{token}") { handleVideo(call, call.pathParameters["token"]) }
        }
    }

    private suspend fun handleVideo(call: ApplicationCall, pathToken: String?) {
        TransferTelemetry.markRequest()

        // Anti-DNS-rebinding: the real TV addresses us by the bound LAN IP literal,
        // so its Host is "<ip>:8080" and host() == <ip>. A rebinding page carries a
        // DNS name instead. Reject anything that doesn't pin to the bound IP.
        val boundIp = boundHost
        if (boundIp == null ||
            !call.request.host().trim().equals(boundIp, ignoreCase = true)
        ) {
            FlickLog.w("http", "reject reason=host_pin status=403")
            call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
            return
        }

        // Per-session token gate. Capture the served (uri, token) as ONE object so the
        // pair is always consistent (no TOCTOU across a retarget). Constant-time compare,
        // and answer any miss (no session, wrong/absent token) with an identical 404 so
        // a probe learns nothing about whether a valid token exists.
        val served = servedSession.snapshot()
        if (served == null || pathToken == null ||
            !MessageDigest.isEqual(pathToken.toByteArray(), served.token.toByteArray())
        ) {
            // The reason never leaves the device: the wire answer stays the
            // byte-identical 404 that reveals nothing about token validity.
            FlickLog.w("http", "reject reason=bad_token status=404")
            call.respondText("Not found", status = HttpStatusCode.NotFound)
            return
        }
        val uri = served.uri

        val method = call.request.httpMethod
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
                        FlickLog.w("http", "reject reason=range status=416")
                        call.respondText(
                            "Requested range not satisfiable",
                            status = HttpStatusCode.RequestedRangeNotSatisfiable,
                        )
                        return
                    }
                    is RangeResult.Partial -> parsed
                }
            }

        // Collapse the 200/206 cases to a single set of stream parameters, so the
        // HEAD-vs-GET split (and the concurrency gate around the GET body) is
        // expressed once.
        val status: HttpStatusCode
        val bodyLength: Long?
        val streamStart: Long
        val streamMax: Long
        if (partial == null) {
            status = HttpStatusCode.OK
            bodyLength = if (total >= 0) total else null
            streamStart = 0L
            streamMax = bodyLength ?: Long.MAX_VALUE
        } else {
            status = HttpStatusCode.PartialContent
            bodyLength = partial.end - partial.start + 1
            streamStart = partial.start
            streamMax = bodyLength
            call.response.headers.append(
                HttpHeaders.ContentRange,
                "bytes ${partial.start}-${partial.end}/$total",
            )
        }

        // HEAD is cheap (headers only): answer it without touching the transfer cap.
        if (method == HttpMethod.Head) {
            call.respond(HeadResponse(status, contentType, bodyLength))
            return
        }

        // GET body: bound concurrent transfers. tryAcquire() is non-blocking, so a
        // flood is shed with 503 rather than queued. The permit is released in a
        // finally so it survives a client disconnect or streaming exception.
        if (!transferPermits.tryAcquire()) {
            FlickLog.w("http", "reject reason=busy status=503")
            call.respondText("Server busy", status = HttpStatusCode.ServiceUnavailable)
            return
        }
        try {
            call.respondOutputStream(
                contentType = contentType,
                status = status,
                contentLength = bodyLength,
            ) {
                streamSlice(uri, start = streamStart, maxLength = streamMax, out = this)
            }
        } finally {
            transferPermits.release()
        }
    }

    // --- Byte streaming -----------------------------------------------------

    /**
     * Copies exactly [maxLength] bytes (or up to EOF) starting at byte [start]
     * from the content URI into [out]. Streams straight off the file descriptor
     * — never buffers the whole file. Client disconnects and revoked grants are
     * swallowed so the server stays up.
     */
    private suspend fun streamSlice(uri: Uri, start: Long, maxLength: Long, out: OutputStream) {
        TransferTelemetry.enterTransfer()
        try {
            // The fd seek + read/write loop is blocking; run it on Dispatchers.IO so
            // a stalled socket write can never pin a CIO engine worker thread.
            withContext(Dispatchers.IO) {
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
                        TransferTelemetry.recordBytes(read)
                        remaining -= read
                    }
                    out.flush()
                }
            }
        } catch (e: CancellationException) {
            // A client disconnect cancels the call's coroutine: propagate it so the
            // engine tears the exchange down cleanly (don't mistake it for an error).
            throw e
        } catch (e: IOException) {
            // Typically the TV closed the connection mid-transfer (seek/stop).
            FlickLog.d("http", "stream stopped ${e.javaClass.simpleName}")
        } catch (e: Exception) {
            // Revoked URI grant, etc. Never let it take down the server.
            FlickLog.w("http", "stream failed ${e.javaClass.simpleName}")
        } finally {
            TransferTelemetry.exitTransfer()
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
        private const val BUFFER_SIZE = 256 * 1024

        // One TV plays one stream; a small pool absorbs its parallel range probes
        // while capping what a LAN flood can open at once.
        private const val MAX_CONCURRENT_TRANSFERS = 4
        private const val IDLE_TIMEOUT_SECONDS = 30

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
