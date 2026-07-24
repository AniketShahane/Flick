package com.flick.receiver.player

/**
 * An immutable, point-in-time view of a [ThroughputHistory] — what the stream
 * metrics histogram draws.
 *
 * [samplesBps] holds ONLY bars that were really measured, oldest first. A list
 * shorter than [capacity] means the session is younger than the window, never
 * that the missing bars were zero: pad the drawing, never the data.
 */
data class ThroughputSnapshot(
    /** Committed bars, oldest → newest; each is a mean of the samples folded into it. */
    val samplesBps: List<Long>,
    /** Highest retained bar; 0 while nothing has been measured. */
    val peakBps: Long,
    /** Newest bar, which is still open and may rise or fall on the next sample; 0 when empty. */
    val latestBps: Long,
    /** Bars the window holds once it is full. */
    val capacity: Int,
) {
    val size: Int get() = samplesBps.size

    val isEmpty: Boolean get() = samplesBps.isEmpty()

    /**
     * Height of the bar at [index] as a 0..1 fraction of [peakBps]. Returns 0 for
     * an out-of-range index and while no throughput has been measured, so a bar
     * is never taller than the peak it is drawn against.
     */
    fun ratioAt(index: Int): Float {
        if (peakBps <= 0L) return 0f
        val value = samplesBps.getOrNull(index) ?: return 0f
        return (value.toDouble() / peakBps.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    companion object {
        val EMPTY = ThroughputSnapshot(
            samplesBps = emptyList(),
            peakBps = 0L,
            latestBps = 0L,
            capacity = ThroughputHistory.CAPACITY,
        )
    }
}

/**
 * Fixed-window history of the bandwidth meter's bitrate estimate, sized to the
 * stream metrics histogram: [CAPACITY] bars over a rolling peak.
 *
 * Fed from the existing ~2 Hz diagnostics tick — there is no timer in here. Two
 * samples fold into one bar ([SAMPLES_PER_SLOT]), so the window spans the 40
 * seconds the panel's eyebrow promises rather than the 20 the raw feed would
 * give. The newest bar is *open*: it shows the running mean of the samples seen
 * so far, so the panel is never blank for a second after playback starts.
 *
 * Appending allocates nothing (the ring is a [LongArray] and the peak is a
 * bounded rescan of at most [CAPACITY] longs); the only allocation is the
 * per-read [snapshot], which is the immutable value Compose holds as state.
 *
 * Not thread-safe: like the rest of the player telemetry, it is written and read
 * on the main thread only.
 */
class ThroughputHistory(
    val capacity: Int = CAPACITY,
    private val samplesPerSlot: Int = SAMPLES_PER_SLOT,
) {
    init {
        require(capacity > 0) { "capacity must be positive" }
        require(samplesPerSlot > 0) { "samplesPerSlot must be positive" }
    }

    private val slots = LongArray(capacity)

    /** Index of the oldest retained bar. */
    private var start = 0

    /** Retained bars, including the open newest one. */
    private var filled = 0

    private var openSum = 0L
    private var openCount = 0
    private var peak = 0L

    val size: Int get() = filled

    val isEmpty: Boolean get() = filled == 0

    /** Highest retained bar; drops when the bar that held the peak ages out. */
    val peakBps: Long get() = peak

    val latestBps: Long get() = if (filled == 0) 0L else slots[newestIndex()]

    /**
     * Fold one bitrate estimate into the window. Negative estimates (Media3
     * reports one before it has enough data) are clamped to zero rather than
     * dragging the mean below the axis.
     */
    fun append(bitrateBps: Long) {
        val sample = bitrateBps.coerceAtLeast(0L)
        if (openCount == 0) {
            if (filled < capacity) filled++ else start = (start + 1) % capacity
            slots[newestIndex()] = 0L
        }
        openSum += sample
        openCount++
        slots[newestIndex()] = openSum / openCount
        if (openCount >= samplesPerSlot) {
            openSum = 0L
            openCount = 0
        }
        recomputePeak()
    }

    /** Drop the whole window — a new cast must not inherit the previous film's bars. */
    fun clear() {
        slots.fill(0L)
        start = 0
        filled = 0
        openSum = 0L
        openCount = 0
        peak = 0L
    }

    /** Cheap immutable read for Compose; the returned list never aliases the ring. */
    fun snapshot(): ThroughputSnapshot {
        if (filled == 0) {
            return ThroughputSnapshot(
                samplesBps = emptyList(),
                peakBps = 0L,
                latestBps = 0L,
                capacity = capacity,
            )
        }
        val values: List<Long> = List(filled) { slots[(start + it) % capacity] }
        return ThroughputSnapshot(
            samplesBps = values,
            peakBps = peak,
            latestBps = values[filled - 1],
            capacity = capacity,
        )
    }

    private fun newestIndex(): Int = (start + filled - 1) % capacity

    // The open bar mutates in place and eviction can remove the peak holder, so
    // incremental peak tracking would need the evicted value anyway; a bounded
    // 40-long rescan at 2 Hz is cheaper than getting that wrong.
    private fun recomputePeak() {
        var max = 0L
        for (i in 0 until filled) {
            val value = slots[(start + i) % capacity]
            if (value > max) max = value
        }
        peak = max
    }

    companion object {
        /** Bars in the histogram. */
        const val CAPACITY = 40

        /** Diagnostics ticks folded into one bar: a ~2 Hz feed yields one bar per second. */
        const val SAMPLES_PER_SLOT = 2
    }
}
