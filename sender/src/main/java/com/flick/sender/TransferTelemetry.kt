package com.flick.sender

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Immutable snapshot of the media server's live transfer stats, sampled ~1s by
 * the UI. Turns any stall into an instant answer to "did the TV stop asking, or
 * did the phone stop serving?".
 *
 * [lastRequestAtMs] is a [SystemClock.elapsedRealtime] stamp (monotonic), or 0 if
 * no /video request has arrived this session.
 */
data class TransferStats(
    val bitsPerSec: Long = 0L,
    val totalBytes: Long = 0L,
    val lastRequestAtMs: Long = 0L,
    val inFlight: Int = 0,
)

/**
 * Process-wide telemetry for the byte-serving loop, mirroring [ServerStateHolder]:
 * the Ktor CIO request threads write raw counters, the Compose UI reads a derived
 * [StateFlow].
 *
 * The per-chunk hot path ([recordBytes]) touches only atomics — no allocation and
 * no flow emission. [refresh] (called ~1s from the UI ticker) folds those counters
 * into a rolling-window throughput EMA and publishes a fresh [TransferStats].
 */
object TransferTelemetry {

    private val totalBytes = AtomicLong(0L)
    private val lastRequestAtMs = AtomicLong(0L)
    private val inFlight = AtomicInteger(0)

    // Sampling state, only touched inside the synchronized refresh() / reset().
    private var lastSampleBytes = 0L
    private var lastSampleAtMs = 0L
    private var emaBitsPerSec = 0.0
    private var haveSample = false

    private val _stats = MutableStateFlow(TransferStats())
    val stats: StateFlow<TransferStats> = _stats.asStateFlow()

    /** Reset every counter for a fresh serving session. */
    @Synchronized
    fun reset() {
        totalBytes.set(0L)
        lastRequestAtMs.set(0L)
        // Deliberately NOT reset: inFlight tracks live server state (open transfers),
        // not per-session state. A re-target / stop->start calls reset() while a
        // previous GET can still be mid-flight; zeroing here would let that transfer's
        // exitTransfer() drive the counter to -1 for the rest of the session.
        lastSampleBytes = 0L
        lastSampleAtMs = SystemClock.elapsedRealtime()
        emaBitsPerSec = 0.0
        haveSample = false
        _stats.value = TransferStats()
    }

    /** Hot path: one atomic add per written chunk. Called from Ktor threads. */
    fun recordBytes(count: Int) {
        if (count > 0) totalBytes.addAndGet(count.toLong())
    }

    /** Stamp the arrival of a /video request (any method). */
    fun markRequest() {
        lastRequestAtMs.set(SystemClock.elapsedRealtime())
    }

    /** Bracket an in-flight byte transfer; always pair enter/exit in a finally. */
    fun enterTransfer() {
        inFlight.incrementAndGet()
    }

    fun exitTransfer() {
        inFlight.decrementAndGet()
    }

    /**
     * Fold the raw counters into a fresh [TransferStats] and publish it. Throughput
     * is an EMA over the sampling interval (~1s ticks -> a ~2-3s effective window),
     * so a single slow tick doesn't spike the number. Call from a single ticker.
     */
    @Synchronized
    fun refresh() {
        val now = SystemClock.elapsedRealtime()
        val total = totalBytes.get()
        val deltaMs = now - lastSampleAtMs
        if (deltaMs > 0L) {
            val deltaBytes = total - lastSampleBytes
            val instant = deltaBytes * 8_000.0 / deltaMs // bytes/ms -> bits/s
            emaBitsPerSec =
                if (haveSample) EMA_ALPHA * instant + (1 - EMA_ALPHA) * emaBitsPerSec else instant
            haveSample = true
            lastSampleBytes = total
            lastSampleAtMs = now
        }
        _stats.value = TransferStats(
            bitsPerSec = emaBitsPerSec.toLong(),
            totalBytes = total,
            lastRequestAtMs = lastRequestAtMs.get(),
            inFlight = inFlight.get().coerceAtLeast(0),
        )
    }

    private const val EMA_ALPHA = 0.4
}
