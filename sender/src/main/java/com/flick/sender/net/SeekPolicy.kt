package com.flick.sender.net

import com.flick.sender.model.PlaybackPhase
import kotlin.math.abs

/**
 * The two seek decisions the session makes, as arithmetic: where a ±10s tap lands, and
 * what one TV `state` frame settles about a seek the phone is still waiting on.
 *
 * They live here rather than inline in [PlaybackSession] because that class reads
 * `SystemClock` and builds `org.json` frames, neither of which exists in a plain JVM
 * unit test — a decision left inside it cannot be tested at all. So elapsed time is a
 * parameter here, never a reading.
 */
internal object SeekPolicy {

    /**
     * How long the ±10s buttons wait for the tapping to stop before one seek goes out.
     *
     * A run of taps is a single intent ("back thirty seconds"), and every seek costs the
     * TV a decoder flush and a refill from a new byte offset — so one seek per tap makes
     * the user pay for three 4K buffer fills to move thirty seconds, which is the whole
     * complaint. This is the quiet period that ends a run: comfortably past the ~250 ms
     * cadence of deliberate repeated tapping, so an ordinary burst is never split in two,
     * and a small fraction of the 1–4 s a 4K remux seek costs on the verified hardware, so
     * the wait a *single* tap pays is invisible next to the refill it triggers. Nothing
     * about the wait is silent: the head and the timecode move on the tap itself.
     */
    const val QUIET_WINDOW_MS = 350L

    /** How near the ghost has to get to the head to count as having arrived. */
    const val RECONCILE_MS = 400L

    /**
     * How long a seek may be outstanding before the head stops waiting and adopts whatever
     * position the TV is reporting.
     *
     * Three seconds — the previous figure — is inside the ordinary cost of a 4K seek over
     * home Wi-Fi, so it fired on healthy casts and dragged the head BACKWARDS to where the
     * TV still was, which reads exactly like the taps having been thrown away. This is a
     * deadline for a TV that is not coming, not a budget for one that is slow.
     */
    const val SEEK_DEADLINE_MS = 12_000L

    /**
     * The ceiling that gives up regardless of phase. [SEEK_DEADLINE_MS] is not spent while
     * the TV reports BUFFERING, because that report is evidence the seek is being honoured
     * — but a receiver wedged in that phase would otherwise hold the head at a position it
     * never reaches for the rest of the cast, so the head cannot be left there forever.
     */
    const val SEEK_ABANDON_MS = 45_000L

    /**
     * Where a ±10s tap lands, and — applied once per tap — where a whole run of them does.
     *
     * With an unknown duration ([durationMs] == 0: MediaStore had none and no state frame
     * with dur>0 has arrived yet) only the low end is clamped; the receiver clamps the high
     * end to the real duration. Without that, +10s would `coerceIn(0, 0)` → seek 0 and
     * restart playback from the start of the film.
     */
    fun skipTarget(targetMs: Long, deltaMs: Long, durationMs: Long): Long = if (durationMs > 0L) {
        (targetMs + deltaMs).coerceIn(0L, durationMs)
    } else {
        (targetMs + deltaMs).coerceAtLeast(0L)
    }

    /**
     * Where an absolute seek lands — a scrubber that reports a landing and nothing before
     * it, which is what the phone's media notification gives the platform.
     *
     * Clamped on the same terms as [skipTarget]: with an unknown duration the receiver
     * clamps the high end against the real one, so guessing here would only be wrong.
     */
    fun seekTarget(positionMs: Long, durationMs: Long): Long = if (durationMs > 0L) {
        positionMs.coerceIn(0L, durationMs)
    } else {
        positionMs.coerceAtLeast(0L)
    }

    /** What a `state` frame settles about an outstanding seek. */
    enum class Pending { WAITING, ARRIVED, ABANDONED }

    /**
     * [reportedMs] is only authoritative once the TV has stopped saying it is still
     * filling toward the requested position: a BUFFERING frame carries the position the
     * player is seeking FROM, so adopting it is how the head ends up behind the seek that
     * is currently succeeding.
     */
    fun pending(
        targetMs: Long,
        reportedMs: Long,
        phase: PlaybackPhase,
        outstandingMs: Long,
    ): Pending = when {
        abs(targetMs - reportedMs) <= RECONCILE_MS -> Pending.ARRIVED
        outstandingMs > SEEK_ABANDON_MS -> Pending.ABANDONED
        outstandingMs > SEEK_DEADLINE_MS && phase != PlaybackPhase.BUFFERING -> Pending.ABANDONED
        else -> Pending.WAITING
    }
}
