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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

sealed interface ProbeResult {
    data class Ok(val latencyMs: Long) : ProbeResult
    data object Unreachable : ProbeResult
    data object ConnectionRefused : ProbeResult
    data class HttpError(val status: Int?) : ProbeResult
    data object BadResponse : ProbeResult
}

/** A deliberately exact range probe; a 200 or redirect is never direct-play success. */
object PreflightProbe {
    private const val TOTAL_DEADLINE_MS = 6_000L
    private const val TCP_DEADLINE_MS = 2_000L
    private const val HTTP_PHASE_MAX_MS = 3_000L

    suspend fun probe(raw: String): ProbeResult = withContext(Dispatchers.IO) {
        val url = runCatching { URL(raw) }.getOrNull() ?: return@withContext ProbeResult.BadResponse
        val started = SystemClock.elapsedRealtime()
        val deadline = started + TOTAL_DEADLINE_MS
        fun remainingMs(): Int = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        fun timedOut() = remainingMs() <= 0
        if (timedOut()) return@withContext ProbeResult.Unreachable
        try { Socket().use { it.connect(InetSocketAddress(url.host, url.port), minOf(TCP_DEADLINE_MS, remainingMs().toLong()).toInt()) } }
        catch (_: SocketTimeoutException) { return@withContext ProbeResult.Unreachable }
        catch (_: ConnectException) { return@withContext ProbeResult.ConnectionRefused }
        catch (_: IOException) { return@withContext ProbeResult.Unreachable }
        if (timedOut()) return@withContext ProbeResult.Unreachable
        var connection: HttpURLConnection? = null
        var deadlineExecutor: java.util.concurrent.ScheduledExecutorService? = null
        try {
            connection = (url.openConnection() as? HttpURLConnection) ?: return@withContext ProbeResult.BadResponse
            // An elapsed check between reads cannot interrupt one stalled read.
            // Disconnect at the same absolute deadline to unblock headers/body/EOF.
            val ownedConnection = connection
            deadlineExecutor = Executors.newSingleThreadScheduledExecutor()
            deadlineExecutor.schedule(
                { ownedConnection.disconnect() },
                remainingMs().toLong(),
                TimeUnit.MILLISECONDS,
            )
            connection.instanceFollowRedirects = false
            // Both individual blocking operations and the whole probe share the
            // same monotonic deadline. The body reader checks it between drips.
            val phaseTimeout = minOf(HTTP_PHASE_MAX_MS, remainingMs().toLong()).toInt()
            if (phaseTimeout <= 0) return@withContext ProbeResult.ConnectionRefused
            connection.connectTimeout = phaseTimeout; connection.readTimeout = phaseTimeout
            connection.requestMethod = "GET"; connection.setRequestProperty("Range", "bytes=0-1023")
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_PARTIAL) return@withContext ProbeResult.HttpError(status)
            val expected = RangeResponseValidator.expectedLength(status, connection.getHeaderField("Content-Range"), connection.contentLengthLong)
                ?: return@withContext ProbeResult.BadResponse
            if (timedOut()) return@withContext ProbeResult.ConnectionRefused
            // Update the per-read timeout after headers so a read begun at the
            // edge cannot outlive the absolute deadline.
            connection.readTimeout = minOf(HTTP_PHASE_MAX_MS, remainingMs().toLong()).toInt()
            if (!connection.inputStream.use {
                ExactBodyReader.readExactlyThenEof(it, expected, deadline, SystemClock::elapsedRealtime)
            }) return@withContext ProbeResult.ConnectionRefused
            ProbeResult.Ok(SystemClock.elapsedRealtime() - started)
        } catch (_: SocketTimeoutException) { ProbeResult.ConnectionRefused }
        catch (_: IOException) { ProbeResult.ConnectionRefused }
        finally {
            connection?.disconnect()
            deadlineExecutor?.shutdownNow()
        }
    }
}
