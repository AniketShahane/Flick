package com.flick.sender.net

import com.flick.sender.model.PlaybackPhase
import kotlin.math.abs

/**
 * The seek decisions the session makes, as arithmetic: where a ±10s tap or a drag lands,
 * what letting go of a drag owes the TV, and what one TV `state` frame settles about a
 * seek the phone is still waiting on.
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

    private const val SCRUB_END_GUARD_MAX_MS = 10_000L
    private const val SCRUB_END_GUARD_MIN_MS = 1_000L

    /**
     * How recently a drag's last seek must have left for a release that sends nothing new
     * to wait on the TV landing it.
     *
     * While the finger is held still the TV lands that seek exactly and keeps playback
     * held; once the release marker frees it, it plays on from there, so a frame a second
     * later reports a position the held target can no longer match and the head would sit
     * frozen until [SEEK_DEADLINE_MS]. A held seek has long since landed, so the head
     * simply follows the TV.
     */
    const val RELEASE_FRESH_MS = 1_000L

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
     * How far short of the end a dragged landing stops.
     *
     * A drag that reaches the right edge would otherwise seek to the duration itself: the
     * TV plays its last second, reports ENDED, shows "Finished", and the phone drops the
     * resume point — an overshoot of the thumb ends the film. Ten seconds on a feature and
     * 5 % of a shorter clip, so the bar still covers almost all of it — but never under
     * [SCRUB_END_GUARD_MIN_MS], the margin the TV clamps every seek to: a landing the TV
     * moves would never be reported back, and the head would wait out [SEEK_DEADLINE_MS]
     * for it. Never more than the clip itself, so the furthest landing is never negative.
     * The ±10s buttons are not guarded: walking to the end on purpose is still allowed.
     */
    fun scrubEndGuardMs(durationMs: Long): Long = if (durationMs > 0L) {
        maxOf(SCRUB_END_GUARD_MIN_MS, minOf(SCRUB_END_GUARD_MAX_MS, durationMs * 5L / 100L))
            .coerceAtMost(durationMs)
    } else {
        0L
    }

    /** The furthest a dragged landing may go; only meaningful with a known duration. */
    fun scrubMaxMs(durationMs: Long): Long = (durationMs - scrubEndGuardMs(durationMs)).coerceAtLeast(0L)

    /** Where a drag at [fraction] of the bar lands. */
    fun scrubTarget(fraction: Float, durationMs: Long): Long =
        (fraction.coerceIn(0f, 1f) * durationMs).toLong().coerceIn(0L, scrubMaxMs(durationMs))

    /**
     * Where an absolute seek lands — a scrubber that reports a landing and nothing before
     * it, which is what the phone's media notification gives the platform.
     *
     * Guarded at the end exactly as a drag is. With an unknown duration only the low end
     * is clamped, as in [skipTarget]: the receiver clamps the high end against the real
     * one, so guessing here would only be wrong.
     */
    fun seekTarget(positionMs: Long, durationMs: Long): Long = if (durationMs > 0L) {
        positionMs.coerceIn(0L, scrubMaxMs(durationMs))
    } else {
        positionMs.coerceAtLeast(0L)
    }

    /**
     * The notches a drag is felt in. One per 10 s of film was ~720 ticks across a two-hour
     * bar — a continuous buzz — and matched nothing drawn; the bar's ripple marks these.
     */
    const val SCRUB_DETENTS = 36

    /** Which notch [targetMs] sits in, 0 until [SCRUB_DETENTS] − 1. */
    fun detentOf(targetMs: Long, durationMs: Long): Int {
        if (durationMs <= 0L) return 0
        return (targetMs.coerceAtLeast(0L) * SCRUB_DETENTS / durationMs).toInt().coerceIn(0, SCRUB_DETENTS - 1)
    }

    /**
     * What letting go of a drag owes the TV: whether to send the final seek, whether to
     * send the end-of-drag marker, and whether the head should then wait for the TV to
     * report the landing (rather than follow the TV straight away).
     */
    data class ScrubRelease(val seek: Boolean, val marker: Boolean, val awaitTv: Boolean) {
        companion object {
            val NONE = ScrubRelease(seek = false, marker = false, awaitTv = false)
            val SEEK_AND_MARKER = ScrubRelease(seek = true, marker = true, awaitTv = true)
            val MARKER_AWAITING = ScrubRelease(seek = false, marker = true, awaitTv = true)
            val MARKER_FOLLOWING = ScrubRelease(seek = false, marker = true, awaitTv = false)
        }
    }

    /**
     * [lastSentMs] is null when no seek left during the gesture; [lastSentAgoMs] is how
     * long ago it left, and is ignored without one. [seekOwed] is a ±10s tap run the touch
     * cancelled before its seek left: the head already shows it, so the TV is owed it.
     *
     * A touch that never moved the head owes nothing: a seek to where the TV already is
     * still costs it a decoder flush. Anything else ends with the play/pause verb the
     * viewer last chose, because that is the only end-of-drag signal the frozen protocol
     * has — the TV leaves its scrubbing mode on it instead of waiting out a quiet period.
     */
    fun scrubRelease(
        originMs: Long,
        lastSentMs: Long?,
        lastSentAgoMs: Long,
        targetMs: Long,
        seekOwed: Boolean,
    ): ScrubRelease = when {
        lastSentMs == null && targetMs == originMs && !seekOwed -> ScrubRelease.NONE
        lastSentMs == targetMs ->
            if (lastSentAgoMs < RELEASE_FRESH_MS) ScrubRelease.MARKER_AWAITING else ScrubRelease.MARKER_FOLLOWING
        else -> ScrubRelease.SEEK_AND_MARKER
    }

    /**
     * The viewer's play intent after a TV frame in [phase].
     *
     * Only a settled phase is evidence. The TV's `playing` field is `isPlaying`, false
     * while it buffers, so reading intent off it would make a drag that began during a
     * rebuffer end with `pause` and stop the film.
     */
    fun intendsPlayingAfter(phase: PlaybackPhase, intendsPlaying: Boolean): Boolean = when (phase) {
        PlaybackPhase.PLAYING -> true
        PlaybackPhase.PAUSED -> false
        else -> intendsPlaying
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
