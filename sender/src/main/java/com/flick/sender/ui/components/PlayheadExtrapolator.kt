package com.flick.sender.ui.components

import com.flick.sender.model.PlaybackPhase
import com.flick.sender.model.PlaybackUiState
import com.flick.sender.net.StateFrameStamp
import kotlin.math.abs

/**
 * The playing head between the TV's `state` frames.
 *
 * The receiver reports position every 100 ms only around a change or a command and about
 * once a second while a film simply plays, so a head drawn from frames alone steps once a
 * second: invisible on a two-hour film, a jump of several millimetres on a thirty-second
 * clip. So while the head [runs], it moves on from the last frame at the media rate.
 *
 * Invariants, which every viewer-visible promise rests on:
 * - Never ahead of the TV's last report by more than [MAX_LEAD_MS], so a feed that stops
 *   cannot carry it off; and the lead is counted from when this head first saw the anchor
 *   (or started running from it), never from before, so it trails the TV rather than leads.
 * - Never moving while the TV's position is not provably advancing: it runs only from a
 *   frame whose position moved on from the one before it by about the time between them
 *   ([provesMotion]), and only while that frame's position is the one being drawn. A
 *   picture hold, a rebuffer at a seek target or a thumb held still is a frozen position,
 *   and a frozen position never runs.
 * - Never backwards during continuous play: a frame that lands slightly behind where the
 *   head has run to (a late frame, or one too late or too stale to prove motion, while
 *   the film still plays) is waited for rather than snapped to. Anything that
 *   stops the run — a pause, a seek, a skip, a scrub, a phase change — puts the head on
 *   the reported position exactly, as frames alone always did, except that a stop with
 *   no new position holds the head where it was drawn instead of jumping back.
 *
 * One instance per surface, read on the main thread.
 */
internal class PlayheadExtrapolator {

    private var anchorMs = Long.MIN_VALUE
    private var anchorAtMs = 0L
    private var shownMs = 0L
    private var running = false
    private var durationMs = 0L

    /**
     * The head to draw for a reported [sourceMs] at [nowMs]; [runs] as [Companion.runs]
     * answers it, [extrapolates] as [Companion.extrapolates] does.
     */
    fun read(sourceMs: Long, nowMs: Long, runs: Boolean, durationMs: Long, extrapolates: Boolean = runs): Long {
        this.durationMs = durationMs
        val moved = sourceMs != anchorMs
        if (!runs) {
            if (moved) {
                // A playing film's frame whose motion went unproven only through a late
                // arrival or a stale position lands a little behind the drawn head: wait
                // for it, frozen, rather than step back.
                val lead = shownMs - sourceMs
                val holds = extrapolates && sourceMs > anchorMs && lead > 0L && lead <= MAX_LEAD_MS
                anchorMs = sourceMs
                anchorAtMs = nowMs
                if (!holds) shownMs = sourceMs
            }
            running = false
            return shownMs
        }
        if (moved || !running) {
            anchorMs = sourceMs
            anchorAtMs = nowMs
        }
        running = true
        val ran = ranAt(nowMs)
        val behind = shownMs - ran
        if (behind <= 0L || behind > MAX_LEAD_MS) shownMs = ran
        return shownMs
    }

    /**
     * How long after [nowMs] the head next moves far enough to be worth a redraw, or null
     * when it will not move again before the next frame. A move is a pixel every
     * [pixelMs] when that is given, and the next flip of the position or remaining-time
     * label when [labels].
     * Valid after a [read] at [nowMs].
     */
    fun nextWakeMs(nowMs: Long, pixelMs: Long?, labels: Boolean): Long? {
        if (!running) return null
        val lead = (nowMs - anchorAtMs).coerceAtLeast(0L)
        val ran = ranAt(nowMs)
        val room = minOf(MAX_LEAD_MS - lead, endOf() - ran)
        if (room <= 0L) return null
        val held = (shownMs - ran).coerceAtLeast(0L)
        val toPixel = pixelMs?.let { held + it }
        val toSecond = if (labels) {
            val head = maxOf(shownMs, ran)
            val nextSecond = (head / 1_000L + 1L) * 1_000L
            // The remaining label floors durationMs − head, so it flips one past each
            // position ≡ durationMs (mod 1 s).
            val nextRemaining = if (durationMs > 0L) {
                val flip = Math.floorMod(durationMs + 1L, 1_000L)
                (head - Math.floorMod(head - flip, 1_000L) + 1_000L).takeIf { it <= durationMs }
            } else {
                null
            }
            // On a whole-second duration the two flips fall 1 ms apart: one wake serves both.
            val remainingAt = nextRemaining ?: Long.MAX_VALUE
            val next = if (remainingAt == nextSecond + 1L) remainingAt else minOf(nextSecond, remainingAt)
            next - ran
        } else {
            null
        }
        val wake = listOfNotNull(toPixel, toSecond).minOrNull() ?: return null
        return when {
            wake <= room -> wake.coerceAtLeast(1L)
            // The last stretch to the cap or the end still moves pixels; it reaches no new second.
            toPixel != null -> room
            else -> null
        }
    }

    private fun endOf(): Long = if (durationMs > 0L) maxOf(durationMs, anchorMs) else Long.MAX_VALUE

    private fun ranAt(nowMs: Long): Long {
        val lead = (nowMs - anchorAtMs).coerceIn(0L, MAX_LEAD_MS)
        return minOf(anchorMs + lead, endOf())
    }

    companion object {
        /** The TV's slow rhythm plus jitter; a frame later than this is a stall, not a gap. */
        const val MAX_LEAD_MS = 1_500L

        /** Today's fast-rhythm update rate, and no faster. */
        const val MIN_TICK_MS = 100L

        /** Under a pixel a second the head needs nothing but the frames and the second ticks. */
        const val FRAMES_SUFFICE_MS_PER_PX = 1_000L

        /**
         * How far a frame's advance may differ from the time between two frames' arrivals
         * and still read as playing: a frame 300–400 ms late on the slow rhythm, or two
         * fast frames bunched by Wi-Fi power save, must not stop the head; a seek of a
         * second or more must.
         */
        const val MOTION_SLACK_MS = 500L

        fun extrapolates(state: PlaybackUiState): Boolean =
            state.phase == PlaybackPhase.PLAYING && state.playing && !state.scrubbing && !state.skipping &&
                !state.syncing && state.durationMs > 0L

        /**
         * Whether [frame]'s position moved on from the frame before it by about the time
         * between their arrivals: forward, at least half that time, and within
         * [MOTION_SLACK_MS] of it.
         */
        fun provesMotion(frame: StateFrameStamp): Boolean {
            if (frame.atMs <= 0L || frame.previousAtMs <= 0L) return false
            val advancedMs = frame.posMs - frame.previousPosMs
            val elapsedMs = frame.atMs - frame.previousAtMs
            return advancedMs > 0L && 2L * advancedMs >= elapsedMs && abs(advancedMs - elapsedMs) <= MOTION_SLACK_MS
        }

        /** Whether a head drawing [sourceMs] runs on from it. */
        fun runs(state: PlaybackUiState, frame: StateFrameStamp, sourceMs: Long): Boolean =
            extrapolates(state) && frame.posMs == sourceMs && provesMotion(frame)

        /**
         * The redraw interval that moves the head about a pixel at a time across a surface
         * [widthPx] wide, or null where the head moves under a pixel a second.
         */
        fun tickIntervalMs(durationMs: Long, widthPx: Int): Long? {
            if (durationMs <= 0L || widthPx <= 0) return null
            val msPerPx = durationMs / widthPx
            if (msPerPx >= FRAMES_SUFFICE_MS_PER_PX) return null
            return msPerPx.coerceAtLeast(MIN_TICK_MS)
        }
    }
}
