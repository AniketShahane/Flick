package com.flick.sender.library

import com.flick.sender.model.HdrType
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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

/**
 * Contract §6.2: at most [maxPerWindow] admitted in any [windowMs], and those beyond are dropped;
 * the admitted run at most [maxInFlight] at a time and the rest wait for a slot. The TV asks for
 * a detail or season once per socket, so a request that is admitted must be answered.
 */
internal class RequestGate(maxInFlight: Int, maxPerWindow: Int, windowMs: Long) {
    private val window = SlidingWindowLimiter(maxPerWindow, windowMs)
    private val slots = Semaphore(maxInFlight)

    fun tryAdmit(nowMs: Long): Boolean = window.tryPass(nowMs)

    suspend fun <T> run(block: suspend () -> T): T = slots.withPermit { block() }
}

/**
 * What the dial in progress is opening. Owned per attempt rather than per value: a cancelled
 * dial's `finally` can run after the next dial, possibly to an equal endpoint, has begun.
 */
internal class AttemptSlot<T : Any> {
    private class Attempt<T>(val value: T)

    private var attempt: Attempt<T>? = null

    val value: T? get() = attempt?.value

    /** The token to hand back to [end]. */
    fun begin(value: T): Any = Attempt(value).also { attempt = it }

    fun end(token: Any) {
        if (attempt === token) attempt = null
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
 * Contract §8: when the ready window may open, and when it closes. In ready mode it never
 * closes on its own: its idle deadline hangs up the TV's sockets instead, and then nothing
 * is scheduled until the next activity.
 *
 * Main-confined. Times are on the monotonic clock.
 */
internal class TvReadyWindowPolicy(
    private val windowMs: Long = WINDOW_MS,
    private val controlGraceMs: Long = CONTROL_GRACE_MS,
) {
    private var lastActivityAtMs: Long? = null
    private var controlLostAtMs: Long? = null
    private var hungUp = false

    val open: Boolean get() = lastActivityAtMs != null

    var readyMode: Boolean = false
        private set

    fun opened(nowMs: Long, ready: Boolean = false) {
        lastActivityAtMs = nowMs
        controlLostAtMs = null
        readyMode = ready
        hungUp = false
    }

    /** Ready mode gained or lost while the window is open; the activity clock runs on. */
    fun modeChanged(ready: Boolean) {
        if (lastActivityAtMs == null) return
        readyMode = ready
        if (!ready) hungUp = false
    }

    /** A cast ending, the app leaving the foreground, a knock or any TV request: the window runs on from here. */
    fun touch(nowMs: Long) {
        if (lastActivityAtMs != null) {
            lastActivityAtMs = nowMs
            hungUp = false
        }
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
        readyMode = false
        hungUp = false
    }

    /** When the window must act on its own, or null while it is not open or has nothing left to do. */
    fun deadlineMs(): Long? {
        val activity = lastActivityAtMs ?: return null
        if (readyMode && hungUp) return null
        val idle = activity + windowMs
        if (readyMode) return idle
        return controlLostAtMs?.let { minOf(idle, it + controlGraceMs) } ?: idle
    }

    fun expired(nowMs: Long): Boolean = deadlineMs()?.let { nowMs >= it } == true

    /** What a deadline that has passed asks for. */
    enum class Expiry { CLOSE, HANG_UP, TOUCH }

    /**
     * Ready mode keeps the service and only hangs up, and never while a cast is live, a
     * connection attempt is running or the activity is in front of the person; a hang-up then
     * leaves nothing scheduled. [dialInFlight] must not count a sheet merely left open: that
     * would postpone the hang-up for as long as the phone stays pocketed.
     */
    fun onExpired(nowMs: Long, castLive: Boolean, dialInFlight: Boolean, visible: Boolean): Expiry {
        if (!readyMode) return Expiry.CLOSE
        if (castLive || dialInFlight || visible) {
            touch(nowMs)
            return Expiry.TOUCH
        }
        hungUp = true
        return Expiry.HANG_UP
    }

    companion object {
        const val WINDOW_MS = 30L * 60 * 1000
        const val CONTROL_GRACE_MS = 2L * 60 * 1000

        /**
         * Whether a trigger may open the window now. A live cast's own foreground service
         * already keeps this process reachable, so the window opens only once that one is gone.
         * In ready mode the doorbell is the point, so neither control nor the library need be up.
         */
        fun mayOpen(
            enabled: Boolean,
            controlAuthenticated: Boolean,
            linkOpen: Boolean,
            castServing: Boolean,
            mayStartForeground: Boolean,
            readyMode: Boolean = false,
        ): Boolean = enabled && !castServing && mayStartForeground && (readyMode || (controlAuthenticated && linkOpen))
    }
}
