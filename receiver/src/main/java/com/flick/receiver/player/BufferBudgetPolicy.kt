package com.flick.receiver.player

/**
 * The `DefaultLoadControl` numbers a TV can actually afford, derived from the heap it
 * was actually granted.
 *
 * `DefaultAllocator` allocates `byte[]` on the **Java heap** and `allocate()` has no
 * ceiling of its own — it calls `new byte[individualAllocationSize]` whenever its free
 * list is empty, so the only thing that stops it is the LoadControl declining to load.
 * Two facts about Media3 1.10.1's `DefaultLoadControl.shouldContinueLoading` therefore
 * decide everything here, both read off the shipped AAR's bytecode:
 *
 *  1. The comparison is against **`getTotalBufferBytesAllocated`** — every allocation
 *     the player holds. Retained back-buffer samples are allocated bytes, so the back
 *     buffer and the forward buffer spend ONE budget.
 *  2. Below `minBufferMs` it computes
 *     `isLoading = prioritizeTimeOverSizeThresholds || !targetBufferSizeReached`. With
 *     that flag set the byte target is ignored outright, so **no byte budget can bound
 *     the min-buffer allocation.**
 *
 * Together those made the previous fixed tuning a live out-of-memory bug on the very
 * device it was measured on, not merely an unportable one: 30 s of back buffer plus a
 * 15 s forward floor is 45 s of media resident whenever the byte target is reached
 * first (rate ≥ ~47.7 Mbps), and 45 s is unbounded in rate — 429 MiB at 80 Mbps,
 * **536 MiB at 100 Mbps, against a 512 MB grant.** A genuine UHD remux could exhaust
 * the heap; it survived only because the DV 8.1 test material sat lower.
 *
 * So [BufferBudget.prioritizeTimeOverSizeThresholds] is always false. That is the
 * correction that makes the byte target binding at every instant and at every bitrate,
 * and it costs nothing: below the planned peak the target is never reached before
 * `minBufferMs` anyway, so the behaviour is unchanged on everything except the content
 * that was breaking it.
 *
 * The heap figure has to be [Runtime.getRuntime].maxMemory, not
 * `ActivityManager.memoryClass`/`largeMemoryClass`. `android:largeHeap="true"` is
 * ignored on `isLowRamDevice()` hardware, which is exactly the class of TV at risk, and
 * `maxMemory()` is the only one of the three that reports the grant that actually
 * happened rather than the one the manifest asked for.
 */
data class BufferBudget(
    val targetBufferBytes: Int,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int,
    val backBufferMs: Int,
    val prioritizeTimeOverSizeThresholds: Boolean,
) {
    /** Milliseconds of [PLANNED_PEAK_BITRATE_BPS] content the byte budget holds. */
    val plannedPeakFitMs: Int get() = fitMsFor(targetBufferBytes)

    /**
     * Seconds of serving outage this budget rides out at [bitrateBps] — the honest
     * protection figure, and the only one that may ever be quoted.
     *
     * It is the **forward** buffer that rides out an outage, and the back buffer has
     * already spent its share of the budget by the time one starts, so it is deducted
     * at the same bitrate. [maxBufferMs] caps the result but only binds on content
     * light enough that the byte budget is not reached first.
     */
    fun protectionSecondsAt(bitrateBps: Long): Float {
        if (bitrateBps <= 0L) return 0f
        val bytesPerSecond = bitrateBps / 8f
        val forwardBytes = targetBufferBytes - backBufferMs / 1000f * bytesPerSecond
        if (forwardBytes <= 0f) return 0f
        return minOf(forwardBytes / bytesPerSecond, maxBufferMs / 1000f)
    }
}

/**
 * Fraction of the granted heap the sample buffer may hold.
 *
 * Media3's own default target for a video+audio stream is 137.5 MiB, which is 27 % of a
 * 512 MB grant; 40 % is already more generous than the library on hardware weaker than
 * the phones it was tuned for. It is a fraction rather than a constant so the same
 * *ratio* — never the same absolute size — reaches every other TV.
 */
private const val HEAP_FRACTION = 0.4f

/**
 * The byte ceiling, and it is Media3's own: `DEFAULT_MAX_BUFFER_SIZE = 210239488`
 * (200.5 MiB) is the largest total the library will ever compute for itself. The
 * previous 256 MiB was **above** what Media3 considers safe, on a device class weaker
 * than a phone, and there is no evidence for exceeding it — the 45-second resident set
 * it was part of is exactly what could exhaust the heap.
 */
const val MAX_TARGET_BUFFER_BYTES: Int = 210_239_488

/**
 * Below this a buffer has stopped being outage protection. Kept as a floor anyway: a
 * device that cannot spare 32 MiB will stall, and a stall is a recoverable annoyance
 * where an `OutOfMemoryError` inside the allocator is a dead cast.
 */
const val MIN_TARGET_BUFFER_BYTES: Int = 32 * 1024 * 1024

/**
 * The bitrate the durations are planned against — 4K remux VBR peaks on the material
 * this app exists to direct-play.
 *
 * Every millisecond figure in a budget is "this many ms *of 100 Mbps content*". Above
 * the peak the same budget holds proportionally less time, and that is fine: with the
 * priority flag off the byte target still binds, so the failure is a shorter buffer,
 * never a larger allocation.
 */
const val PLANNED_PEAK_BITRATE_BPS: Long = 100_000_000L

/** No device pre-buffers longer than this, however much heap it was granted. */
private const val MIN_BUFFER_CEILING_MS = 15_000

/** `DefaultLoadControl.Builder` rejects a non-positive `minBufferMs`. */
private const val MIN_BUFFER_FLOOR_MS = 1_000

/**
 * Time ceiling on the forward buffer. It binds only on content light enough that the
 * byte budget is not reached first — 180 s fits inside 200.5 MiB below ~9.3 Mbps — so
 * it is the operative limit for light 1080p SDR and **never** for 4K. Left generous
 * because the case it governs costs nothing. Nothing may quote it as ride-out; see
 * [BufferBudget.protectionSecondsAt].
 */
private const val MAX_BUFFER_MS = 180_000

/**
 * The share of the budget the back buffer may take, and its ceiling.
 *
 * Small on purpose, and the previous 30 s was the second half of the bug. Back-buffer
 * retention is **passive** — it keeps samples already loaded and is not gated by the
 * byte target — so it does not merely compete with the forward buffer, it *crowds it
 * out*: whatever the back buffer holds, the forward buffer gets the remainder of the
 * target. Let it reach the whole budget and `targetBufferSizeReached` is permanently
 * true, forward loading never resumes, and the player rebuffers forever with no error
 * raised. A 15 % share keeps that impossible up to ~667 Mbps, far past what Wi-Fi 5
 * can deliver.
 *
 * The 10 s ceiling is not the seek-back increment and does not try to be. Serving the
 * 10 s `seekBack` from memory would cost 125 MiB at the planned peak — 60 % of the whole
 * budget to make one gesture instant, paid for out of outage protection. What this size
 * does buy is `retainBackBufferFromKeyframe`: a step back inside the current GOP
 * re-anchors without a refetch, and a longer seek costs one range request plus
 * [BufferBudget.bufferForPlaybackMs], which on a LAN is about a second.
 */
private const val BACK_BUFFER_SHARE = 0.15f
private const val BACK_BUFFER_CEILING_MS = 10_000

private const val BUFFER_FOR_PLAYBACK_MS = 2_500
private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 5_000

/**
 * How much larger the sustainable forward cushion has to be than the threshold that
 * resumes playback after a rebuffer.
 *
 * Capping the thresholds at the min buffer is not enough, and the difference is another
 * freeze. The steady-state cushion at bitrate *r* is `(target − backBytes) / r`, which
 * equals `minBufferMs` exactly at the planned peak and is **smaller above it**. So a
 * resume threshold merely *equal* to the min buffer means the player sits permanently at
 * the level it resumes from — any dip rebuffers, and above the peak the cushion drops
 * below the threshold outright and playback can never resume at all.
 *
 * A third of the min buffer keeps the cushion at 3x the resume threshold at the peak and
 * 8x at 40 Mbps, and pushes the point where the cushion falls under the threshold out to
 * ~231 Mbps — past UHD Blu-ray's 128 Mbps ceiling and past what Wi-Fi 5 sustains. On a
 * device that can only hold 2.7 s, waiting 5 s to resume would mean never resuming;
 * scaling the threshold down with the cushion is what makes the small tiers playable
 * rather than merely non-fatal.
 */
private const val PLAYBACK_THRESHOLD_DIVISOR = 3

private fun fitMsFor(targetBufferBytes: Int): Int =
    (targetBufferBytes.toLong() * 8_000L / PLANNED_PEAK_BITRATE_BPS).toInt()

/**
 * The load-control budget for a heap of [maxHeapBytes].
 *
 * The whole configuration is **satisfiable**, which the previous one was not:
 * `backBufferMs + minBufferMs <= plannedPeakFitMs` at every tier, so the buffer it asks
 * to hold at once fits the budget it has to hold it in. The forward buffer is allocated
 * first because it is the one that rides out an outage; the back buffer takes a small
 * share and then only as much of it as genuine headroom allows.
 *
 * There is no ride-out property to preserve from the previous tuning, because it never
 * had one. `MAX_BUFFER_MS = 180_000` was reachable only below ~10 Mbps: at 60 Mbps the
 * receiver rode out 15 s, so the ~70 s wireless outage the old comment cited as the
 * *reason* for the tuning would have stalled anyway. Ride-out is
 * [BufferBudget.protectionSecondsAt] and nothing else.
 *
 * Turning the priority flag off cannot wedge startup, and that is not luck:
 * `shouldStartPlayback` will start on the byte target being reached instead of on
 * [BufferBudget.bufferForPlaybackMs] of media — but only in the branch guarded by the
 * flag being **false**. The escape hatch exists exactly where the budget is tight
 * enough to need it.
 */
fun bufferBudgetFor(maxHeapBytes: Long): BufferBudget {
    val target = (maxHeapBytes.coerceAtLeast(0L) * HEAP_FRACTION)
        .toLong()
        .coerceIn(MIN_TARGET_BUFFER_BYTES.toLong(), MAX_TARGET_BUFFER_BYTES.toLong())
        .toInt()

    val fitMs = fitMsFor(target)

    // Forward first: it is what an outage is ridden out with.
    val backShareMs = minOf(BACK_BUFFER_CEILING_MS, (fitMs * BACK_BUFFER_SHARE).toInt())
    val minBufferMs = minOf(MIN_BUFFER_CEILING_MS, fitMs - backShareMs)
        .coerceAtLeast(MIN_BUFFER_FLOOR_MS)
    // The forward floor outranks the back buffer: on a device too small for both, the
    // back buffer is what goes, down to nothing.
    val backBufferMs = minOf(backShareMs, (fitMs - minBufferMs).coerceAtLeast(0))

    // Both playback thresholds are a FRACTION of the min buffer, not merely capped by it
    // — see [PLAYBACK_THRESHOLD_DIVISOR]. Capping alone satisfies the builder and still
    // leaves the player resuming at the only level it can sustain.
    val afterRebufferMs = minOf(
        BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        minBufferMs / PLAYBACK_THRESHOLD_DIVISOR,
    ).coerceAtLeast(1)
    // Starting is never allowed to demand more than resuming does.
    val forPlaybackMs = minOf(BUFFER_FOR_PLAYBACK_MS, afterRebufferMs)

    return BufferBudget(
        targetBufferBytes = target,
        minBufferMs = minBufferMs,
        maxBufferMs = MAX_BUFFER_MS,
        bufferForPlaybackMs = forPlaybackMs,
        bufferForPlaybackAfterRebufferMs = afterRebufferMs,
        backBufferMs = backBufferMs,
        // Never true. See the class comment: with it set, no byte budget can bound the
        // min-buffer allocation, and that is precisely what made the OOM reachable.
        prioritizeTimeOverSizeThresholds = false,
    )
}
