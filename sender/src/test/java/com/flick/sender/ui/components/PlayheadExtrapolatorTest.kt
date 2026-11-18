package com.flick.sender.ui.components

import com.flick.sender.model.PlaybackPhase
import com.flick.sender.model.PlaybackUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The TV reports a playing film's position once a second; the head runs on between those
 * frames only while nothing else owns it, and never far or backwards.
 */
class PlayheadExtrapolatorTest {

    private val head = PlayheadExtrapolator()
    private val duration = 30_000L
    private val playing = PlaybackUiState(durationMs = duration, playing = true, phase = PlaybackPhase.PLAYING)

    @Test fun onlyAPlayingFilmNobodyIsHoldingRunsOn() {
        assertTrue(PlayheadExtrapolator.extrapolates(playing))
        assertFalse(PlayheadExtrapolator.extrapolates(playing.copy(phase = PlaybackPhase.PAUSED)))
        assertFalse(PlayheadExtrapolator.extrapolates(playing.copy(phase = PlaybackPhase.BUFFERING)))
        assertFalse(PlayheadExtrapolator.extrapolates(playing.copy(playing = false)))
        assertFalse(PlayheadExtrapolator.extrapolates(playing.copy(scrubbing = true)))
        assertFalse(PlayheadExtrapolator.extrapolates(playing.copy(skipping = true)))
        assertFalse(PlayheadExtrapolator.extrapolates(playing.copy(syncing = true)))
        assertFalse(PlayheadExtrapolator.extrapolates(playing.copy(durationMs = 0L)))
    }

    @Test fun theHeadRunsAtTheMediaRateBetweenFrames() {
        assertEquals(10_000L, head.read(10_000L, nowMs = 0L, extrapolating = true, durationMs = duration))
        assertEquals(10_400L, head.read(10_000L, nowMs = 400L, extrapolating = true, durationMs = duration))
        // The next frame lands where the head had run to.
        assertEquals(11_000L, head.read(11_000L, nowMs = 1_000L, extrapolating = true, durationMs = duration))
    }

    @Test fun aFeedThatStopsCannotCarryTheHeadOff() {
        head.read(10_000L, nowMs = 0L, extrapolating = true, durationMs = duration)
        assertEquals(10_000L + PlayheadExtrapolator.MAX_LEAD_MS, head.read(10_000L, nowMs = 60_000L, extrapolating = true, durationMs = duration))
    }

    @Test fun theHeadNeverRunsPastTheEnd() {
        head.read(29_800L, nowMs = 0L, extrapolating = true, durationMs = duration)
        assertEquals(duration, head.read(29_800L, nowMs = 1_000L, extrapolating = true, durationMs = duration))
    }

    @Test fun stoppingHoldsTheDrawnHeadUntilTheNextFrame() {
        head.read(10_000L, nowMs = 0L, extrapolating = true, durationMs = duration)
        head.read(10_000L, nowMs = 800L, extrapolating = true, durationMs = duration)
        // A pause tap: no jump back to the last frame.
        assertEquals(10_800L, head.read(10_000L, nowMs = 900L, extrapolating = false, durationMs = duration))
        assertEquals(10_850L, head.read(10_850L, nowMs = 1_000L, extrapolating = false, durationMs = duration))
    }

    @Test fun aHeadNobodyRanFollowsTheSourceExactly() {
        assertEquals(5_000L, head.read(5_000L, nowMs = 0L, extrapolating = false, durationMs = duration))
        assertEquals(5_000L, head.read(5_000L, nowMs = 5_000L, extrapolating = false, durationMs = duration))
        assertEquals(20_000L, head.read(20_000L, nowMs = 5_100L, extrapolating = false, durationMs = duration))
    }

    @Test fun aLongFilmNeedsNoTickAndAShortOneTicksNoFasterThanTheOldFeed() {
        assertNull(PlayheadExtrapolator.tickIntervalMs(2 * 60 * 60_000L, widthPx = 1_080))
        assertEquals(100L, PlayheadExtrapolator.tickIntervalMs(30_000L, widthPx = 1_080))
        assertEquals(277L, PlayheadExtrapolator.tickIntervalMs(300_000L, widthPx = 1_080))
        assertNull(PlayheadExtrapolator.tickIntervalMs(0L, widthPx = 1_080))
        assertNull(PlayheadExtrapolator.tickIntervalMs(30_000L, widthPx = 0))
    }
}
