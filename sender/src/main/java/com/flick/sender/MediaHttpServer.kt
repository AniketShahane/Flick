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
import io.ktor.server.request.httpMethod
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
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
 *   GET/HEAD /s/{token} — the sideloaded subtitle file, whole-file 200 only, under
 *                         its OWN token and a hard size cap. Same Host pin, same
 *                         constant-time compare, same identical 404.
 *
 * Those are the ONLY routes. There is deliberately no unauthenticated health check:
 * a route that answers before the Host pin and the token is a "Flick is serving here"
 * oracle reachable by DNS rebinding, and nothing in either module ever called one.
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

    // Subtitles get their own pool: their source is an arbitrary DocumentsProvider whose
    // read can block far longer than a local file, and that must not consume a permit the
    // video body needs.
    private val subtitlePermits = Semaphore(MAX_CONCURRENT_SUBTITLE_READS, true)

    /**
     * The (uri, token) pair currently served, published as ONE immutable object via a
     * single atomic reference. They were two separate @Volatile fields, which let a
     * retarget from A/tokenA to B/tokenB be observed half-applied (new uri B still
     * paired with the not-yet-overwritten tokenA) — a TOCTOU that could stream video B
     * under the stale token. Each request captures this reference once, so uri and
     * token are always the matched pair the session actually published.
     *
     * The sideloaded subtitle rides INSIDE the same object for the same reason: its
     * own (uri, token) pair must never be observable against a different video, and
     * swapping or revoking it must never be observable half-applied either.
     */
    private data class ServedSession(val uri: Uri, val token: String, val subtitle: SubtitleSource?)

    /** The sideloaded subtitle currently served, under a token independent of the video's. */
    data class SubtitleSource(val uri: Uri, val token: String)

    private val servedSession = AtomicMediaSession<ServedSession>()

    // The LAN IP the socket is bound to; the video handler pins the request Host
    // to this literal to reject DNS-rebinding. Read on every request thread.
    @Volatile
    private var boundHost: String? = null

    val isRunning: Boolean get() = synchronized(lock) { server != null }

    /**
     * Start serving [uri] under [token] (plus the optional [subtitle] under its own
     * independent token), bound to [bindHost] (the phone's LAN IP). If the server is
     * already up on the same host, just swaps the served session (no restart). If the
     * host changed (LAN IP moved), the engine is torn down and rebound. Throws if the
     * socket cannot be bound.
     */
    fun start(uri: Uri, token: String, subtitle: SubtitleSource?, bindHost: String) {
        synchronized(lock) {
            // Publish the new session target atomically before (re)binding so no request
            // can observe a live socket with a stale token/URI (or a mismatched pair).
            servedSession.publish(ServedSession(uri, token, subtitle))

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

    /**
     * Attach, swap or (with a null [subtitle]) revoke the sideloaded subtitle of the
     * live session, leaving the video's URI and token untouched so playback is not
     * interrupted. Every mutation of the served session happens under [lock] and lands
     * as one atomic publish, so a request thread still only ever sees a matched set.
     * Returns false when nothing is being served — a subtitle can never be armed
     * against no video.
     */
    fun setSubtitle(subtitle: SubtitleSource?): Boolean {
        synchronized(lock) {
            if (server == null) return false
            val current = servedSession.snapshot() ?: return false
            servedSession.publish(current.copy(subtitle = subtitle))
            return true
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
            // Serve GET and HEAD from the same handler; handleVideo() branches on
            // the request method to emit a body (GET) or headers only (HEAD).
            // pathParameters (not the query-merged parameters) so a "?token=" query
            // can never shadow the real path segment we authenticate against.
            get("/v/{token}") { handleVideo(call, call.pathParameters["token"]) }
            head("/v/{token}") { handleVideo(call, call.pathParameters["token"]) }
            get("/s/{token}") { handleSubtitle(call, call.pathParameters["token"]) }
            head("/s/{token}") { handleSubtitle(call, call.pathParameters["token"]) }
        }
    }

    /**
     * Serves the sideloaded subtitle as a whole-file 200. Deliberately has no Range
     * machinery: a subtitle is kilobytes, Media3 reads it in one shot, and an
     * unranged route is one fewer parser between the LAN and a file descriptor.
     */
    private suspend fun handleSubtitle(call: ApplicationCall, pathToken: String?) {
        TransferTelemetry.markRequest()

        // Same anti-DNS-rebinding pin as the video route: the real TV addresses us by
        // the bound LAN IP literal, so anything carrying a DNS name is rejected.
        if (!hostPinned(call)) {
            FlickLog.w("http", "reject reason=host_pin status=403")
            call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
            return
        }

        // Independent token, captured from the same single session snapshot as the
        // video's so the pair can never be observed mid-retarget. Constant-time
        // compare, and every miss answers the byte-identical 404 the video route
        // answers, so a probe cannot learn whether a subtitle is even attached.
        val subtitle = servedSession.snapshot()?.subtitle
        if (subtitle == null || pathToken == null ||
            !MessageDigest.isEqual(pathToken.toByteArray(), subtitle.token.toByteArray())
        ) {
            FlickLog.w("http", "reject reason=bad_token status=404")
            call.respondText("Not found", status = HttpStatusCode.NotFound)
            return
        }

        // Hard cap, enforced before a byte is opened. A file whose size the provider
        // will not report is refused for the same reason an oversized one is: this
        // route must never become a bulk-file egress path.
        val size = MediaMeta.resolveSize(resolver, subtitle.uri)
        if (size < 0L || size > MAX_SUBTITLE_BYTES) {
            FlickLog.w("http", "reject reason=subtitle_size status=404")
            call.respondText("Not found", status = HttpStatusCode.NotFound)
            return
        }

        val contentType = subtitleContentType(
            runCatching { MediaMeta.resolveName(resolver, subtitle.uri) }.getOrNull(),
        )
        if (call.request.httpMethod == HttpMethod.Head) {
            call.respond(HeadResponse(HttpStatusCode.OK, contentType, size))
            return
        }

        // A separate pool from the video permits on purpose: a SAF pick can be backed by
        // a cloud DocumentsProvider whose read blocks for minutes, and a subtitle must
        // never be able to starve the stream this server exists to serve.
        if (!subtitlePermits.tryAcquire()) {
            FlickLog.w("http", "reject reason=busy status=503")
            call.respondText("Server busy", status = HttpStatusCode.ServiceUnavailable)
            return
        }
        val body = try {
            // The blocking read cannot be interrupted, so the timeout bounds the permit
            // rather than the thread: the TV gives up long before a stalled provider does.
            withTimeoutOrNull(SUBTITLE_READ_TIMEOUT_MS) { readSubtitle(subtitle.uri) }
        } finally {
            subtitlePermits.release()
        }
        if (body == null) {
            FlickLog.w("http", "reject reason=subtitle_read status=404")
            call.respondText("Not found", status = HttpStatusCode.NotFound)
            return
        }
        call.respondBytes(body, contentType, HttpStatusCode.OK)
    }

    /**
     * Reads the whole subtitle into memory, refusing anything past the cap. The
     * declared size is only a hint, so the ceiling is re-checked against the bytes
     * actually produced — a provider that under-reports cannot widen the route.
     */
    private suspend fun readSubtitle(uri: Uri): ByteArray? = withContext<ByteArray?>(Dispatchers.IO) {
        try {
            resolver.openInputStream(uri).use { input ->
                if (input == null) return@withContext null
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(SUBTITLE_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    total += read
                    if (total > MAX_SUBTITLE_BYTES) return@withContext null
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Revoked grant, deleted file, unreadable provider: never take the server down.
            FlickLog.w("http", "subtitle read failed ${e.javaClass.simpleName}")
            null
        }
    }

    private suspend fun handleVideo(call: ApplicationCall, pathToken: String?) {
        TransferTelemetry.markRequest()

        // Anti-DNS-rebinding: the real TV addresses us by the bound LAN IP literal,
        // so its Host is "<ip>:8080". A rebinding page carries a DNS name instead.
        if (!hostPinned(call)) {
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

    /**
     * The anti-DNS-rebinding Host pin, in exactly the shape `ControlServer` uses:
     * **one** Host header carrying the bound LAN IP literal and this port.
     *
     * The count is half the check. `ApplicationRequest.host()` collapses a request
     * that carries two Host headers down to whichever one the parser kept, so a
     * duplicated Host would be judged on one value while anything ahead of the
     * socket routed on the other. Reading them all and demanding a single-element
     * list removes that disagreement instead of picking a side of it.
     *
     * A rebinding page always carries a DNS name here; the TV always carries the
     * literal it was given in the cast URL.
     */
    private fun hostPinned(call: ApplicationCall): Boolean {
        val boundIp = boundHost ?: return false
        return call.request.headers.getAll(HttpHeaders.Host) == listOf("$boundIp:$SERVER_PORT")
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
        } catch (e: FileNotFoundException) {
            // BEFORE the IOException arm, which it would otherwise be swallowed by: a
            // FileNotFoundException can only come out of the open above — the explicit
            // throw for a null descriptor, or the resolver's own for a row that is gone —
            // so unlike every other IOException here it can never be the TV closing the
            // socket. It is the file that went away, and this is the commonest way one
            // does: deleted or unshared under a live cast.
            FlickLog.w("http", "stream failed ${e.javaClass.simpleName}")
            ServerStateHolder.publishSourceFault(SourceFault.midStream(e))
        } catch (e: IOException) {
            // Typically the TV closed the connection mid-transfer (seek/stop).
            FlickLog.d("http", "stream stopped ${e.javaClass.simpleName}")
        } catch (e: Exception) {
            // Revoked URI grant, etc. Never let it take down the server.
            FlickLog.w("http", "stream failed ${e.javaClass.simpleName}")
            // Raised here and NOT in the IOException arm above: that one is the TV
            // closing the socket on a seek, and it is correctly silent. This one is a
            // revoked grant, the other half of the file going away under a live cast —
            // and this and the arm above it are the only places in the system that know
            // so, because the TV can only report a body that stopped.
            ServerStateHolder.publishSourceFault(SourceFault.midStream(e))
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
        private const val SUBTITLE_BUFFER_SIZE = 16 * 1024
        private const val MAX_SUBTITLE_BYTES = SubtitlePolicy.MAX_BYTES

        // One TV plays one stream; a small pool absorbs its parallel range probes
        // while capping what a LAN flood can open at once.
        private const val MAX_CONCURRENT_TRANSFERS = 4
        private const val MAX_CONCURRENT_SUBTITLE_READS = 2

        // Shorter than the engine's idle timeout, so a stalled provider frees the permit
        // before the TV's own read gives up on the connection.
        private const val SUBTITLE_READ_TIMEOUT_MS = 20_000L
        private const val IDLE_TIMEOUT_SECONDS = 30

        private val FALLBACK_TYPE = ContentType("video", "mp4")
        private val SUBTITLE_FALLBACK_TYPE = ContentType("text", "plain")

        private fun subtitleContentType(displayName: String?): ContentType =
            try {
                ContentType.parse(SubtitlePolicy.mimeFor(displayName))
            } catch (_: Exception) {
                SUBTITLE_FALLBACK_TYPE
            }

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

/**
 * Pure serving policy for the sideloaded-subtitle route, kept out of the server
 * class so both the cap and the type mapping are testable on a plain JVM.
 */
internal object SubtitlePolicy {

    /**
     * A subtitle is kilobytes. The cap is what stops `/s/{token}` from being turned
     * into a bulk file-exfiltration path once a token leaks.
     */
    const val MAX_BYTES: Long = 5L * 1024L * 1024L

    private const val SUBRIP = "application/x-subrip"
    private const val WEBVTT = "text/vtt"
    private const val SSA = "text/x-ssa"
    private const val PLAIN = "text/plain"

    /**
     * Content type keyed off the file's own extension, never the provider's declared
     * MIME: DocumentsProviders routinely report `application/octet-stream` for both
     * formats, and Media3 picks its parser from what we send.
     */
    fun mimeFor(displayName: String?): String {
        val name = displayName?.trim().orEmpty()
        val dot = name.lastIndexOf('.')
        // A leading dot is a hidden file, not an extension, and a trailing one names nothing.
        if (dot <= 0 || dot == name.length - 1) return PLAIN
        return when (name.substring(dot + 1).lowercase()) {
            "srt" -> SUBRIP
            "vtt", "webvtt" -> WEBVTT
            // SubStation Alpha has its own Media3 parser; declaring it as SubRip would
            // hand an ASS payload to the wrong one and render nothing.
            "ass", "ssa" -> SSA
            else -> PLAIN
        }
    }
}
