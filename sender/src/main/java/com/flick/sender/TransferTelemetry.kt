package com.flick.sender

import android.os.SystemClock
import com.flick.sender.net.LinkSample
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Immutable snapshot of the media server's live transfer stats. Turns any stall into an
 * instant answer to "did the TV stop asking, or did the phone stop serving?".
 *
 * [peakBitsPerSec] is the best single 1 s reading of the session, warm-up included — a
 * diagnostics number. The capacity verdict keeps its own peak over post-warm-up samples
 * only; this one is allowed to be flattered by the TV's opening burst.
 *
 * [lastRequestAtMs] is a [SystemClock.elapsedRealtime] stamp (monotonic), or 0 if
 * no video request has arrived this session.
 */
data class TransferStats(
    val bitsPerSec: Long = 0L,
    val peakBitsPerSec: Long = 0L,
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
 * no flow emission. [refresh] and [sampleNow] fold those counters into a published
 * [TransferStats].
 *
 * [sampleNow] keeps its OWN cursor over the byte counter, separate from the EMA's, because
 * its two callers run at different rates and must not eat each other's deltas: the serving
 * ticker in [CastServerService] takes a capacity reading every second for the whole
 * session, while [refresh] is called by whatever UI happens to be composed. The EMA cursor
 * is shared — an EMA over an irregular interval is still an EMA — but a capacity reading
 * that lost half its second to a UI tick would be a measurement of the tick.
 */
object TransferTelemetry {

    private val totalBytes = AtomicLong(0L)
    private val lastRequestAtMs = AtomicLong(0L)
    private val inFlight = AtomicInteger(0)

    // Sampling state, only touched inside the synchronized folds / reset().
    private var lastSampleBytes = 0L
    private var lastSampleAtMs = 0L
    private var emaBitsPerSec = 0.0
    private var haveSample = false

    private var lastCapacityBytes = 0L
    private var lastCapacityAtMs = 0L
    private var peakBitsPerSec = 0L

    private val _stats = MutableStateFlow(TransferStats())
    val stats: StateFlow<TransferStats> = _stats.asStateFlow()

    private val _samples = MutableSharedFlow<LinkSample>(replay = 0, extraBufferCapacity = 16)

    /**
     * The 1 Hz capacity readings, published as they are taken so the cast coordinator can
     * measure the link without owning the ticker. Live only: a collector that attaches
     * mid-cast has missed what it missed, and no sample is worth replaying stale.
     */
    val samples: SharedFlow<LinkSample> = _samples.asSharedFlow()

    /** Reset every counter for a fresh serving session. */
    @Synchronized
    fun reset() {
        totalBytes.set(0L)
        lastRequestAtMs.set(0L)
        // Deliberately NOT reset: inFlight tracks live server state (open transfers),
        // not per-session state. A re-target / stop->start calls reset() while a
        // previous GET can still be mid-flight; zeroing here would let that transfer's
        // exitTransfer() drive the counter to -1 for the rest of the session.
        val now = SystemClock.elapsedRealtime()
        lastSampleBytes = 0L
        lastSampleAtMs = now
        emaBitsPerSec = 0.0
        haveSample = false
        lastCapacityBytes = 0L
        lastCapacityAtMs = now
        peakBitsPerSec = 0L
        _stats.value = TransferStats()
    }

    /** Hot path: one atomic add per written chunk. Called from Ktor threads. */
    fun recordBytes(count: Int) {
        if (count > 0) totalBytes.addAndGet(count.toLong())
    }

    /** Stamp the arrival of a video request (any method). */
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
     * Fold the raw counters into a fresh [TransferStats] and publish it, so a single slow
     * tick cannot spike the number.
     *
     * The window it smooths over is the fold INTERVAL times roughly 1/[EMA_ALPHA] ≈ 2.5
     * folds of history, and the interval is not fixed: the serving ticker folds at 1 Hz for
     * ~2.5 s of history, while the signal sheet's own ticker folds every 2 s, so with only
     * the sheet open the window is ~5 s.
     */
    @Synchronized
    fun refresh() {
        fold()
        publish()
    }

    /**
     * Take one capacity reading — the delta since the last [sampleNow], unsmoothed,
     * because a verdict about the link has to be able to see one fast second.
     *
     * Folds the EMA on the way through: the serving ticker is the only caller alive for a
     * whole session, so this is also what keeps [TransferStats.bitsPerSec] advancing when
     * no UI is composed.
     */
    @Synchronized
    fun sampleNow(): LinkSample {
        fold()
        val now = SystemClock.elapsedRealtime()
        val total = totalBytes.get()
        val elapsedMs = (now - lastCapacityAtMs).coerceAtLeast(0L)
        val bytes = (total - lastCapacityBytes).coerceAtLeast(0L)
        lastCapacityAtMs = now
        lastCapacityBytes = total
        val sample = LinkSample(
            bytes = bytes,
            elapsedMs = elapsedMs,
            inFlight = inFlight.get().coerceAtLeast(0),
        )
        peakBitsPerSec = maxOf(peakBitsPerSec, sample.bitsPerSec)
        publish()
        _samples.tryEmit(sample)
        return sample
    }

    private fun fold() {
        val now = SystemClock.elapsedRealtime()
        val total = totalBytes.get()
        val deltaMs = now - lastSampleAtMs
        if (deltaMs <= 0L) return
        val deltaBytes = total - lastSampleBytes
        val instant = deltaBytes * 8_000.0 / deltaMs // bytes/ms -> bits/s
        emaBitsPerSec =
            if (haveSample) EMA_ALPHA * instant + (1 - EMA_ALPHA) * emaBitsPerSec else instant
        haveSample = true
        lastSampleBytes = total
        lastSampleAtMs = now
    }

    private fun publish() {
        _stats.value = TransferStats(
            bitsPerSec = emaBitsPerSec.toLong(),
            peakBitsPerSec = peakBitsPerSec,
            totalBytes = totalBytes.get(),
            lastRequestAtMs = lastRequestAtMs.get(),
            inFlight = inFlight.get().coerceAtLeast(0),
        )
    }

    private const val EMA_ALPHA = 0.4
}
