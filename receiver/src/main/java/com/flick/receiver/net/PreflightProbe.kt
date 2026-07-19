package com.flick.receiver.net

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Outcome of the pre-flight reachability probe run before playback starts. Each
 * variant maps to one specific diagnosis card, so a doomed session never starts
 * silently — the user is told WHICH problem they have rather than staring at a
 * black screen.
 */
sealed interface ProbeResult {
    /** TCP connect + byte-range GET both succeeded; [latencyMs] is the round trip. */
    data class Ok(val latencyMs: Long) : ProbeResult

    /** Connect timed out / no route — the phone can't be reached (router peer block?). */
    data object Unreachable : ProbeResult

    /** TCP refused — the phone was reached but nothing is serving on the port. */
    data object ConnectionRefused : ProbeResult

    /** Reached the server but it answered with a non-2xx/206 status. */
    data class HttpError(val code: Int) : ProbeResult

    /** The URL could not be parsed into a host:port. */
    data object BadUrl : ProbeResult
}

/**
 * Two-stage reachability check, always off the main thread:
 *  1. a raw [Socket] connect (2 s) — distinguishes "unreachable" from "refused";
 *  2. a real byte-range GET (`Range: bytes=0-1023`, 3 s timeouts) — expects 206
 *     (or 200), proving the sender actually serves media bytes, not just a port.
 *
 * Cancellation-safe: the caller cancels the enclosing coroutine job; the short
 * timeouts bound the blocking work and any late result is dropped by the
 * cancelled continuation. Never touches the UI thread.
 */
object PreflightProbe {

    private const val SOCKET_CONNECT_TIMEOUT_MS = 2_000
    private const val HTTP_TIMEOUT_MS = 3_000
    private const val RANGE_HEADER = "bytes=0-1023"
    private const val USER_AGENT = "FlickReceiver/0.1 (pre-flight probe)"

    suspend fun probe(url: String): ProbeResult = withContext(Dispatchers.IO) {
        val parsed = runCatching { URL(url) }.getOrNull() ?: return@withContext ProbeResult.BadUrl
        val host = parsed.host?.takeIf { it.isNotBlank() } ?: return@withContext ProbeResult.BadUrl
        val port = if (parsed.port != -1) parsed.port else parsed.defaultPort
        // java.net.URL accepts ports > 65535 (only negative ports are rejected), and
        // InetSocketAddress below throws IllegalArgumentException on an out-of-range
        // port — reject it here rather than let that crash escape the probe.
        if (port !in 1..65535) return@withContext ProbeResult.BadUrl

        // Stage 1: raw TCP connect. A refused connection means the phone is up but
        // not serving; a timeout / no-route means the phone can't be reached at all.
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), SOCKET_CONNECT_TIMEOUT_MS)
            }
        } catch (e: SocketTimeoutException) {
            return@withContext ProbeResult.Unreachable
        } catch (e: ConnectException) {
            return@withContext if (e.message?.contains("refused", ignoreCase = true) == true) {
                ProbeResult.ConnectionRefused
            } else {
                ProbeResult.Unreachable
            }
        } catch (e: IOException) {
            // NoRouteToHostException, UnknownHostException, PortUnreachableException, ...
            return@withContext ProbeResult.Unreachable
        }

        // Stage 2: real byte-range GET — proves the endpoint serves media bytes.
        val startedMs = SystemClock.elapsedRealtime()
        var connection: HttpURLConnection? = null
        try {
            connection = (parsed.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = HTTP_TIMEOUT_MS
                readTimeout = HTTP_TIMEOUT_MS
                setRequestProperty("Range", RANGE_HEADER)
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_PARTIAL || code == HttpURLConnection.HTTP_OK) {
                ProbeResult.Ok(SystemClock.elapsedRealtime() - startedMs)
            } else {
                ProbeResult.HttpError(code)
            }
        } catch (e: SocketTimeoutException) {
            // Stage 1 already proved TCP reachability, so a stall/error here is the
            // app accepting the socket but not answering HTTP — a wedged sender, not
            // a router peer block. Diagnose it as "app not serving", not unreachable.
            ProbeResult.ConnectionRefused
        } catch (e: IOException) {
            ProbeResult.ConnectionRefused
        } finally {
            connection?.disconnect()
        }
    }
}
