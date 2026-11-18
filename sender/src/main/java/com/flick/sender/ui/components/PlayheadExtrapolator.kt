package com.flick.sender.ui.components

import com.flick.sender.model.PlaybackPhase
import com.flick.sender.model.PlaybackUiState

/**
 * The playing head between the TV's `state` frames.
 *
 * The receiver reports position every 100 ms only around a change and every second while
 * a film simply plays, so a head drawn from frames alone steps once a second: invisible on
 * a two-hour film, a jump of several millimetres on a thirty-second clip. While the film
 * plays and nothing else owns the head (no drag, no tap run, no seek in flight), the head
 * runs on from the last frame at the media rate, by no more than [MAX_LEAD_MS] so a feed
 * that stops cannot carry it off. When it stops running before a new frame lands — a
 * pause tap, a grab — it holds where it was drawn rather than jumping back to the last
 * frame, and the next frame puts it right.
 *
 * One instance per surface; read only from that surface's draw or layout phase.
 */
internal class PlayheadExtrapolator {

    private var anchorMs = Long.MIN_VALUE
    private var anchorAtMs = 0L
    private var shownMs = 0L
    private var holding = false

    /** The head to draw for a frame-reported [sourceMs] at [nowMs]. */
    fun read(sourceMs: Long, nowMs: Long, extrapolating: Boolean, durationMs: Long): Long {
        if (sourceMs != anchorMs) {
            anchorMs = sourceMs
            anchorAtMs = nowMs
            holding = false
        }
        shownMs = when {
            extrapolating -> {
                holding = true
                val lead = (nowMs - anchorAtMs).coerceIn(0L, MAX_LEAD_MS)
                val ran = anchorMs + lead
                if (durationMs > 0L) ran.coerceAtMost(maxOf(durationMs, anchorMs)) else ran
            }
            holding -> shownMs
            else -> anchorMs
        }
        return shownMs
    }

    companion object {
        /** The TV's slow rhythm plus jitter; a frame later than this is a stall, not a gap. */
        const val MAX_LEAD_MS = 1_500L

        /** Today's fast-rhythm update rate, and no faster. */
        const val MIN_TICK_MS = 100L

        /** Under a pixel a second the head needs nothing but the frames. */
        const val FRAMES_SUFFICE_MS_PER_PX = 1_000L

        fun extrapolates(state: PlaybackUiState): Boolean =
            state.phase == PlaybackPhase.PLAYING && state.playing && !state.scrubbing && !state.skipping &&
                !state.syncing && state.durationMs > 0L

        /**
         * How often a surface [widthPx] wide must redraw for the head to move about a pixel
         * at a time, or null where once a second (the frames) already does.
         */
        fun tickIntervalMs(durationMs: Long, widthPx: Int): Long? {
            if (durationMs <= 0L || widthPx <= 0) return null
            val msPerPx = durationMs / widthPx
            if (msPerPx >= FRAMES_SUFFICE_MS_PER_PX) return null
            return msPerPx.coerceAtLeast(MIN_TICK_MS)
        }
    }
}
