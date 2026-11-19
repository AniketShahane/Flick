package com.flick.sender.library

import com.flick.sender.model.HdrType

/** Contract §3: the wait before each library re-dial while control v2 stays up. */
internal class LibraryDialBackoff {
    private var failures = 0

    /** The wait before the next dial, counting the failure that just happened. */
    fun next(): Long {
        val delay = STEPS_MS.getOrElse(failures) { STEADY_MS }
        failures++
        return delay
    }

    fun reset() {
        failures = 0
    }

    /** An authenticated socket ended after [upMs]; only one that stayed up earns a fresh start. */
    fun socketEnded(upMs: Long) {
        if (upMs >= STABLE_MS) reset()
    }

    companion object {
        val STEPS_MS = listOf(2_000L, 5_000L, 15_000L)
        const val STEADY_MS = 60_000L
        const val STABLE_MS = 30_000L
    }
}

/** At most [max] passes in any [windowMs]; a refusal does not count against the window. */
internal class SlidingWindowLimiter(private val max: Int, private val windowMs: Long) {
    private val passes = ArrayDeque<Long>()

    fun tryPass(nowMs: Long): Boolean {
        while (passes.isNotEmpty() && nowMs - passes.first() >= windowMs) passes.removeFirst()
        if (passes.size >= max) return false
        passes.addLast(nowMs)
        return true
    }
}

/** Contract §6.2: at most [maxInFlight] unanswered and at most [maxPerWindow] begun in any [windowMs]. */
internal class RequestGate(private val maxInFlight: Int, maxPerWindow: Int, windowMs: Long) {
    private val window = SlidingWindowLimiter(maxPerWindow, windowMs)
    private var inFlight = 0

    fun tryBegin(nowMs: Long): Boolean {
        if (inFlight >= maxInFlight || !window.tryPass(nowMs)) return false
        inFlight++
        return true
    }

    fun end() {
        if (inFlight > 0) inFlight--
    }
}

/**
 * Contract §5.2: whether the catalog last built is still the phone's state. A change that
 * lands while a build runs leaves it stale, and so does a build that never finished.
 */
internal class CatalogFreshness {
    private var changes = 1L
    private var builtFrom = 0L

    val dirty: Boolean get() = builtFrom != changes

    fun changed() {
        changes++
    }

    /** Taken before a build reads anything; handed back to [built] once its result is kept. */
    fun buildStarting(): Long = changes

    fun built(token: Long) {
        builtFrom = maxOf(builtFrom, token)
    }
}

/** Contract §6.6: the live cast's `casting`, said again once on each new socket. */
internal class CastingMemory {
    private var live: Pair<String, String>? = null
    private var said = true

    fun loaded(castId: String, id: String) {
        live = castId to id
    }

    fun ended(castId: String) {
        if (live?.first == castId) live = null
    }

    fun socketOpened() {
        said = false
    }

    /** The (castId, item id) to send after a catalog delivery, at most once per socket. */
    fun afterDelivery(): Pair<String, String>? {
        if (said) return null
        said = true
        return live
    }
}

/** Contract §5.1: `hdr` is omitted unless the probe said something positive. */
internal object LibraryHdr {
    // HdrType.NONE is both "no HDR transfer seen" and "the probe failed", so it is never `sdr`.
    fun wire(type: HdrType?): CatalogItem.Hdr? = when (type) {
        HdrType.DOLBY_VISION -> CatalogItem.Hdr.DOLBY_VISION
        HdrType.HDR10 -> CatalogItem.Hdr.HDR10
        HdrType.NONE, null -> null
    }
}

/** One pass per [intervalMs]. */
internal class MinIntervalGate(private val intervalMs: Long) {
    private var lastPassMs: Long? = null

    fun tryPass(nowMs: Long): Boolean {
        val last = lastPassMs
        if (last != null && nowMs - last < intervalMs) return false
        lastPassMs = nowMs
        return true
    }
}

/** Contract §6.1 and §8: whether a TV's `play` may run, decided before anything is touched. */
internal object LibraryPlayPolicy {
    const val ANDROID_S = 31

    /**
     * Contract §8. Android 12+ lets a backgrounded app start a foreground service only under
     * listed exemptions; the documented one is "the user turned off battery optimisation for
     * the app". [processAllowed] is `foregroundStartAllowed` on this process's importance,
     * which AOSP also lets through for a process already holding a foreground service.
     */
    fun mayStartForegroundService(sdkInt: Int, visible: Boolean, batteryExempt: Boolean, processAllowed: Boolean): Boolean =
        sdkInt < ANDROID_S || visible || batteryExempt || processAllowed

    /** The refusal for a `play`, or null when the phone should run the cast path. */
    fun refusal(enabled: Boolean, listed: Boolean, startInFlight: Boolean, mayStartForeground: Boolean): PlayRefusal? = when {
        !enabled -> PlayRefusal.DISABLED
        !listed -> PlayRefusal.MISSING
        startInFlight -> PlayRefusal.BUSY
        !mayStartForeground -> PlayRefusal.BACKGROUND
        else -> null
    }
}

/** What to put on the wire for a `catWant` or a push. */
internal sealed interface CatalogAnswer {
    data object None : CatalogAnswer
    data class Patch(val frame: String) : CatalogAnswer
    data class Full(val frames: List<String>) : CatalogAnswer
}

/** Contract §5.2. [held] is the catalog the TV is known to hold: the last one sent, or the one its `have` named. */
internal object CatalogDelivery {

    fun answer(have: String?, current: EncodedCatalog, held: EncodedCatalog?): CatalogAnswer = when {
        have != null && have == current.rev -> CatalogAnswer.None
        have != null && held != null && have == held.rev -> push(current, held)
        else -> CatalogAnswer.Full(LibraryFrames.full(current))
    }

    fun push(current: EncodedCatalog, held: EncodedCatalog?): CatalogAnswer {
        if (held == null) return CatalogAnswer.Full(LibraryFrames.full(current))
        if (held.rev == current.rev) return CatalogAnswer.None
        return LibraryFrames.patch(held, current)?.let(CatalogAnswer::Patch)
            ?: CatalogAnswer.Full(LibraryFrames.full(current))
    }
}

/** Contract §6.4: the shape and byte budget of a thumbnail. */
internal object LibraryThumbPolicy {
    const val WIDTH = 384
    const val HEIGHT = 216

    /** Asked of the still pipeline as a square, so any shape still covers the crop without upscaling. */
    const val SOURCE_BOX = 768

    val QUALITIES = listOf(85, 75, 65, 55, 45, 35, 25)

    /** The centred 16:9 region of a [width] x [height] picture: left, top, width, height. */
    fun crop(width: Int, height: Int): IntArray {
        if (width <= 0 || height <= 0) return intArrayOf(0, 0, 0, 0)
        // Compared in Long: 16 * a 4K edge is far below overflow, but a corrupt dimension is not.
        return if (width.toLong() * HEIGHT >= height.toLong() * WIDTH) {
            val w = ((height.toLong() * WIDTH) / HEIGHT).toInt().coerceIn(1, width)
            intArrayOf((width - w) / 2, 0, w, height)
        } else {
            val h = ((width.toLong() * HEIGHT) / WIDTH).toInt().coerceIn(1, height)
            intArrayOf(0, (height - h) / 2, width, h)
        }
    }

    /** The first encoding at a falling quality that fits [maxBytes], or null when none does. */
    fun encodeWithin(maxBytes: Int, encode: (quality: Int) -> ByteArray?): ByteArray? {
        for (quality in QUALITIES) {
            val bytes = encode(quality) ?: return null
            if (bytes.size <= maxBytes) return bytes
        }
        return null
    }
}

/**
 * Contract §8: when the ready window may open, and when it closes.
 *
 * Main-confined. Times are on the monotonic clock.
 */
internal class TvReadyWindowPolicy(
    private val windowMs: Long = WINDOW_MS,
    private val controlGraceMs: Long = CONTROL_GRACE_MS,
) {
    private var lastActivityAtMs: Long? = null
    private var controlLostAtMs: Long? = null

    val open: Boolean get() = lastActivityAtMs != null

    fun opened(nowMs: Long) {
        lastActivityAtMs = nowMs
        controlLostAtMs = null
    }

    /** A cast ending, the app leaving the foreground, or any TV request: the window runs on from here. */
    fun touch(nowMs: Long) {
        if (lastActivityAtMs != null) lastActivityAtMs = nowMs
    }

    fun controlLost(nowMs: Long) {
        if (lastActivityAtMs != null && controlLostAtMs == null) controlLostAtMs = nowMs
    }

    fun controlRestored() {
        controlLostAtMs = null
    }

    fun closed() {
        lastActivityAtMs = null
        controlLostAtMs = null
    }

    /** When the window must close on its own, or null while it is not open. */
    fun deadlineMs(): Long? {
        val activity = lastActivityAtMs ?: return null
        val idle = activity + windowMs
        return controlLostAtMs?.let { minOf(idle, it + controlGraceMs) } ?: idle
    }

    fun expired(nowMs: Long): Boolean = deadlineMs()?.let { nowMs >= it } == true

    companion object {
        const val WINDOW_MS = 30L * 60 * 1000
        const val CONTROL_GRACE_MS = 2L * 60 * 1000

        /**
         * Whether a trigger may open the window now. A live cast's own foreground service
         * already keeps this process reachable, so the window opens only once that one is gone.
         */
        fun mayOpen(
            enabled: Boolean,
            controlAuthenticated: Boolean,
            linkOpen: Boolean,
            castServing: Boolean,
            mayStartForeground: Boolean,
        ): Boolean = enabled && controlAuthenticated && linkOpen && !castServing && mayStartForeground
    }
}
