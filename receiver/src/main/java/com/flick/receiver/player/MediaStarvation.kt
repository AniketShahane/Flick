package com.flick.receiver.player

import java.util.concurrent.atomic.AtomicLong

/**
 * How long the player has wanted media bytes and received none.
 *
 * Written from two threads — bytes from the loader thread, wanting from the
 * player's application thread — and read from a third, so every field is atomic
 * and nothing here touches the player. Times are `elapsedRealtime` milliseconds.
 */
internal class MediaStarvation {
    private val lastMediaByteAtMs = AtomicLong(NEVER)
    private val wantsDataSinceMs = AtomicLong(NOT_WANTING)

    fun onMediaBytes(nowMs: Long) {
        lastMediaByteAtMs.set(nowMs)
    }

    /** A continuing want keeps its original start; only a fresh one restarts the clock. */
    fun setWantsData(wants: Boolean, nowMs: Long) {
        if (wants) wantsDataSinceMs.compareAndSet(NOT_WANTING, nowMs) else wantsDataSinceMs.set(NOT_WANTING)
    }

    /** 0 while the player wants nothing; otherwise time since the later of the want and the last byte. */
    fun starvedMs(nowMs: Long): Long {
        val since = wantsDataSinceMs.get()
        if (since == NOT_WANTING) return 0L
        return (nowMs - maxOf(since, lastMediaByteAtMs.get())).coerceAtLeast(0L)
    }

    /** [Long.MIN_VALUE] until the first media byte. */
    fun lastMediaByteAtMs(): Long = lastMediaByteAtMs.get()

    private companion object {
        const val NEVER = Long.MIN_VALUE
        const val NOT_WANTING = Long.MIN_VALUE
    }
}

/**
 * A run of media load errors, from the first until media flows again. Main thread.
 *
 * Recovery is judged by a byte arriving after the last error, not by a completed
 * load: a progressive stream's one long request completes only at the end of the
 * file, so a retried load that is streaming again reports nothing at all.
 */
internal class LoadErrorStreak {
    data class Recovered(val streakMs: Long, val errors: Int)

    private var open = false
    private var startedAtMs = 0L
    private var lastErrorAtMs = 0L
    private var errors = 0

    /** True when this error opens a streak. */
    fun onError(nowMs: Long): Boolean {
        val opened = !open
        if (opened) {
            open = true
            startedAtMs = nowMs
            errors = 0
        }
        errors++
        lastErrorAtMs = nowMs
        return opened
    }

    fun closeIfRecovered(lastMediaByteAtMs: Long, nowMs: Long): Recovered? {
        if (!open || lastMediaByteAtMs <= lastErrorAtMs) return null
        open = false
        return Recovered(nowMs - startedAtMs, errors)
    }

    fun clear() {
        open = false
        errors = 0
    }
}

/**
 * Subtitle-route loads whose bytes the starvation stamp ignores while `isLoading`
 * still reports them, keyed by load task id. Main thread.
 *
 * Only a first attempt is held: a subtitle retrying on a dead link must not hide the
 * film's starvation, and a first attempt ends by itself at the connect timeout or
 * the subtitle read timeout.
 */
internal class SubtitleLoads {
    private val inFlight = HashSet<Long>()

    /** True when the set changed. */
    fun started(taskId: Long, subtitleRoute: Boolean, retryCount: Int): Boolean =
        subtitleRoute && retryCount == 0 && inFlight.add(taskId)

    /** True when the set changed. */
    fun ended(taskId: Long): Boolean = inFlight.remove(taskId)

    fun clear() = inFlight.clear()

    val isEmpty: Boolean get() = inFlight.isEmpty()
}
