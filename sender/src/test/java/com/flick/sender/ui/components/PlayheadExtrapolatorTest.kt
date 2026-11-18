package com.flick.sender.ui.components

import com.flick.sender.model.PlaybackPhase
import com.flick.sender.model.PlaybackUiState
import com.flick.sender.net.StateFrameStamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The TV reports a playing film's position about once a second; the head runs on between
 * those frames only while nothing else owns it and the TV's position is provably moving,
 * and never far ahead or backwards.
 */
class PlayheadExtrapolatorTest {

    private val head = PlayheadExtrapolator()
    private val duration = 30_000L
    private val playing = PlaybackUiState(durationMs = duration, playing = true, phase = PlaybackPhase.PLAYING)

    /** One surface fed the way the UI feeds it: a state, a frame stamp, a read per draw. */
    private class Surface(var state: PlaybackUiState) {
        val extrapolator = PlayheadExtrapolator()
        private var stamp = StateFrameStamp()

        /**
         * A frame reporting [posMs] reached the phone at [atMs] and was drawn; [idle] is the
         * session following it with the head.
         */
        fun frame(posMs: Long, atMs: Long, phase: PlaybackPhase = state.phase, idle: Boolean = true) {
            stamp = stamp.next(posMs, atMs)
            state = state.copy(confirmedMs = posMs, phase = phase, targetMs = if (idle) posMs else state.targetMs)
            draw(atMs)
        }

        fun draw(nowMs: Long): Long {
            val source = state.targetMs
            return extrapolator.read(source, nowMs, PlayheadExtrapolator.runs(state, stamp, source), state.durationMs)
        }
    }

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

    @Test fun motionIsAPositionThatAdvancedByAboutTheTimeBetweenFrames() {
        fun moves(fromPos: Long, fromAt: Long, toPos: Long, toAt: Long) =
            PlayheadExtrapolator.provesMotion(StateFrameStamp().next(fromPos, fromAt).next(toPos, toAt))
        assertTrue("slow rhythm", moves(10_000L, 5_000L, 11_000L, 6_010L))
        assertTrue("fast rhythm", moves(10_000L, 5_000L, 10_100L, 5_100L))
        assertTrue("a frame 400 ms late", moves(10_000L, 5_000L, 11_000L, 6_400L))
        assertTrue("the frame after it, early", moves(11_000L, 6_400L, 12_000L, 7_000L))
        assertTrue("two fast frames bunched", moves(10_000L, 5_000L, 10_100L, 5_003L))
        assertFalse("frozen", moves(10_000L, 5_000L, 10_000L, 6_000L))
        assertFalse("barely moving", moves(10_000L, 5_000L, 10_010L, 5_100L))
        assertFalse("starting up", moves(0L, 5_000L, 300L, 6_000L))
        assertFalse("a skip forward", moves(10_000L, 5_000L, 20_000L, 5_100L))
        assertFalse("a seek back", moves(10_000L, 5_000L, 7_000L, 6_000L))
        assertFalse("a first frame", PlayheadExtrapolator.provesMotion(StateFrameStamp().next(10_000L, 5_000L)))
        assertFalse("no frame", PlayheadExtrapolator.provesMotion(StateFrameStamp()))
    }

    @Test fun itRunsOnlyFromTheFrameThatProvedMotion() {
        val moving = StateFrameStamp().next(9_000L, 1_000L).next(10_000L, 2_000L)
        assertTrue(PlayheadExtrapolator.runs(playing, moving, 10_000L))
        assertFalse(PlayheadExtrapolator.runs(playing, moving, 20_000L))
        assertFalse(PlayheadExtrapolator.runs(playing.copy(playing = false), moving, 10_000L))
    }

    @Test fun theHeadRunsAtTheMediaRateBetweenFrames() {
        assertEquals(10_000L, head.read(10_000L, nowMs = 0L, runs = true, durationMs = duration))
        assertEquals(10_400L, head.read(10_000L, nowMs = 400L, runs = true, durationMs = duration))
        // The next frame lands where the head had run to.
        assertEquals(11_000L, head.read(11_000L, nowMs = 1_000L, runs = true, durationMs = duration))
    }

    @Test fun aFeedThatStopsCannotCarryTheHeadOff() {
        head.read(10_000L, nowMs = 0L, runs = true, durationMs = duration)
        assertEquals(10_000L + PlayheadExtrapolator.MAX_LEAD_MS, head.read(10_000L, nowMs = 60_000L, runs = true, durationMs = duration))
    }

    @Test fun theHeadNeverRunsPastTheEnd() {
        head.read(29_800L, nowMs = 0L, runs = true, durationMs = duration)
        assertEquals(duration, head.read(29_800L, nowMs = 1_000L, runs = true, durationMs = duration))
    }

    @Test fun stoppingHoldsTheDrawnHeadUntilTheNextFrame() {
        head.read(10_000L, nowMs = 0L, runs = true, durationMs = duration)
        head.read(10_000L, nowMs = 800L, runs = true, durationMs = duration)
        // A pause tap: no jump back to the last frame.
        assertEquals(10_800L, head.read(10_000L, nowMs = 900L, runs = false, durationMs = duration))
        assertEquals(10_850L, head.read(10_850L, nowMs = 1_000L, runs = false, durationMs = duration))
    }

    @Test fun aHeadNobodyRanFollowsTheSourceExactly() {
        assertEquals(5_000L, head.read(5_000L, nowMs = 0L, runs = false, durationMs = duration))
        assertEquals(5_000L, head.read(5_000L, nowMs = 5_000L, runs = false, durationMs = duration))
        assertEquals(20_000L, head.read(20_000L, nowMs = 5_100L, runs = false, durationMs = duration))
    }

    /** Starting to run again counts the lead from that moment, not from when the frame landed. */
    @Test fun aRestartCountsTheLeadFromZero() {
        head.read(10_000L, nowMs = 0L, runs = false, durationMs = duration)
        assertEquals(10_000L, head.read(10_000L, nowMs = 1_200L, runs = true, durationMs = duration))
        assertEquals(10_100L, head.read(10_000L, nowMs = 1_300L, runs = true, durationMs = duration))
    }

    /**
     * Finding 1a. Right after a cast starts, the TV reports PLAYING with its position frozen
     * for the picture hold. The head waits on the frozen position and starts only once the
     * position moves — never ahead of the TV, never back.
     */
    @Test fun aCastStartsPictureHoldDoesNotRunTheHead() {
        val surface = Surface(playing.copy(phase = PlaybackPhase.BUFFERING))
        val holdEndsAt = 2_500L
        val latency = 30L
        fun truth(t: Long) = (t - holdEndsAt).coerceAtLeast(0L)
        var nextFrameSample = 0L
        var t = 0L
        var lastDrawn = 0L
        while (t <= 6_000L) {
            // Hot at the cast's start, then slow once its window closes.
            if (t == nextFrameSample + latency) {
                surface.frame(truth(nextFrameSample), t, PlaybackPhase.PLAYING)
                nextFrameSample += if (nextFrameSample < 3_000L) 100L else 1_000L
            }
            val drawn = surface.draw(t)
            if (t < holdEndsAt + latency) assertEquals("during the hold at $t", 0L, drawn)
            assertTrue("ahead of the TV at $t: $drawn", drawn <= truth(t))
            assertTrue("stepped back at $t: $drawn < $lastDrawn", drawn >= lastDrawn)
            lastDrawn = drawn
            t += 1L
        }
        assertTrue("never ran", lastDrawn > 3_000L)
    }

    /** Finding 1a on the slow rhythm: frozen frames a second apart keep the head still. */
    @Test fun frozenFramesASecondApartKeepTheHeadStill() {
        val surface = Surface(playing)
        for (i in 0..4) {
            surface.frame(0L, i * 1_000L)
            for (dt in 0L until 1_000L step 16L) assertEquals(0L, surface.draw(i * 1_000L + dt))
        }
    }

    /**
     * Finding 1b. A ±10 s run commits: the head sits on the target, not running from it,
     * through the seek's trip and the rebuffer the TV reports at exactly that target.
     */
    @Test fun aCommittedSkipRunWaitsOnTheTargetThroughTheRebuffer() {
        val surface = Surface(playing.copy(durationMs = 120_000L))
        // Steady play, on the slow rhythm.
        surface.frame(9_000L, 1L)
        surface.frame(10_000L, 1_000L)
        assertEquals(10_500L, surface.draw(1_500L))
        // Two taps forward: the head is the user's.
        surface.state = surface.state.copy(targetMs = 30_000L, skipping = true)
        assertEquals(30_000L, surface.draw(1_600L))
        surface.frame(11_000L, 2_000L, idle = false)
        assertEquals(30_000L, surface.draw(2_100L))
        // The quiet window lapses: skipping clears, the seek leaves, no frame yet.
        surface.state = surface.state.copy(skipping = false)
        for (t in 2_400L..2_750L) assertEquals("before the TV answers, at $t", 30_000L, surface.draw(t))
        // The TV answers with a rebuffer at the target, for two seconds.
        var t = 2_750L
        while (t < 4_800L) {
            surface.frame(30_000L, t, PlaybackPhase.BUFFERING)
            for (dt in 0L until 100L) assertEquals("rebuffering, at ${t + dt}", 30_000L, surface.draw(t + dt))
            t += 100L
        }
        // Playing again: the first frame shows only a restart, the next proves motion.
        surface.frame(30_000L, t, PlaybackPhase.PLAYING)
        assertEquals(30_000L, surface.draw(t + 50L))
        surface.frame(30_100L, t + 100L)
        assertEquals(30_100L, surface.draw(t + 100L))
        assertEquals(30_150L, surface.draw(t + 150L))
    }

    /**
     * Finding 1c. The thumb held still while the TV plays on from it, then released: the
     * head stays on the release point until the TV is seen moving from there.
     */
    @Test fun aScrubReleasedAfterHoldingStillWaitsOnTheTv() {
        val surface = Surface(playing)
        surface.frame(5_000L, 1L)
        surface.frame(6_000L, 1_000L)
        surface.state = surface.state.copy(scrubbing = true, targetMs = 12_000L)
        // The TV seeked to the thumb and plays on from it while the thumb stays put.
        var t = 1_100L
        var tvPos = 12_000L
        while (t < 2_500L) {
            surface.frame(tvPos, t, idle = false)
            assertEquals(12_000L, surface.draw(t))
            tvPos += 100L
            t += 100L
        }
        // Release: the session sends the seek again and waits on it.
        surface.state = surface.state.copy(scrubbing = false)
        for (dt in 0L..400L) assertEquals("after release, at ${t + dt}", 12_000L, surface.draw(t + dt))
        // The TV jumps back to the thumb: a discontinuity, not motion.
        surface.frame(12_000L, t + 400L)
        assertEquals(12_000L, surface.draw(t + 450L))
        surface.frame(12_100L, t + 500L)
        assertEquals(12_150L, surface.draw(t + 550L))
    }

    /**
     * Finding 3. A slow-rhythm frame 400 ms late lands behind where the head has run to;
     * the head waits for it rather than stepping back.
     */
    @Test fun aLateFrameIsWaitedForNotSnappedTo() {
        val surface = Surface(playing)
        surface.frame(9_000L, 1L)
        surface.frame(10_000L, 1_000L)
        var lastDrawn = 0L
        fun drawAll(range: LongRange) {
            for (t in range) {
                val drawn = surface.draw(t)
                assertTrue("stepped back at $t: $drawn < $lastDrawn", drawn >= lastDrawn)
                lastDrawn = drawn
            }
        }
        drawAll(1_000L until 2_400L)
        assertEquals(11_399L, lastDrawn)
        surface.frame(11_000L, 2_400L)
        drawAll(2_400L until 2_800L)
        assertEquals(11_399L, lastDrawn)
        drawAll(2_800L until 3_000L)
        assertEquals(11_599L, lastDrawn)
        surface.frame(12_000L, 3_000L)
        drawAll(3_000L until 4_000L)
        assertEquals(12_999L, lastDrawn)
    }

    /** A real discontinuity still snaps: a seek back from the TV's own remote. */
    @Test fun aSeekBackFromTheTvSnaps() {
        val surface = Surface(playing)
        surface.frame(9_000L, 1L)
        surface.frame(10_000L, 1_000L)
        assertEquals(10_800L, surface.draw(1_800L))
        surface.frame(7_000L, 2_000L)
        assertEquals(7_000L, surface.draw(2_000L))
        assertEquals(7_000L, surface.draw(2_500L))
    }

    /** A frame repeating the last position while "playing" freezes the head where it was drawn. */
    @Test fun aRepeatedPositionFreezesTheHead() {
        val surface = Surface(playing)
        surface.frame(9_000L, 1L)
        surface.frame(10_000L, 1_000L)
        assertEquals(10_900L, surface.draw(1_900L))
        surface.frame(10_000L, 2_000L)
        assertEquals(10_900L, surface.draw(2_000L))
        assertEquals(10_900L, surface.draw(2_900L))
    }

    @Test fun aLongFilmNeedsNoPixelTickAndAShortOneTicksNoFasterThanTheOldFeed() {
        assertNull(PlayheadExtrapolator.tickIntervalMs(2 * 60 * 60_000L, widthPx = 1_080))
        assertEquals(100L, PlayheadExtrapolator.tickIntervalMs(30_000L, widthPx = 1_080))
        assertEquals(277L, PlayheadExtrapolator.tickIntervalMs(300_000L, widthPx = 1_080))
        assertNull(PlayheadExtrapolator.tickIntervalMs(0L, widthPx = 1_080))
        assertNull(PlayheadExtrapolator.tickIntervalMs(30_000L, widthPx = 0))
    }

    @Test fun theNextWakeIsTheSoonerOfAPixelAndASecond() {
        head.read(10_300L, nowMs = 0L, runs = true, durationMs = 7_200_000L)
        assertEquals(700L, head.nextWakeMs(0L, pixelMs = null, labels = true))
        assertEquals(277L, head.nextWakeMs(0L, pixelMs = 277L, labels = true))
        assertEquals(277L, head.nextWakeMs(0L, pixelMs = 277L, labels = false))
        assertNull(head.nextWakeMs(0L, pixelMs = null, labels = false))
        // The next second lies past the lead cap: nothing to wake for until a frame.
        head.read(10_300L, nowMs = 700L, runs = true, durationMs = 7_200_000L)
        assertNull(head.nextWakeMs(700L, pixelMs = null, labels = true))
        // A stopped head never wakes.
        head.read(10_300L, nowMs = 800L, runs = false, durationMs = 7_200_000L)
        assertNull(head.nextWakeMs(800L, pixelMs = 100L, labels = true))
    }

    @Test fun aHeldHeadWakesWhenItMovesAgain() {
        head.read(10_000L, nowMs = 0L, runs = true, durationMs = duration)
        head.read(10_000L, nowMs = 1_300L, runs = true, durationMs = duration)
        // A frame 300 ms late: the head holds at 11_300 for 300 ms.
        head.read(11_000L, nowMs = 1_300L, runs = true, durationMs = duration)
        assertEquals(400L, head.nextWakeMs(1_300L, pixelMs = 100L, labels = false))
        assertEquals(1_000L, head.nextWakeMs(1_300L, pixelMs = null, labels = true))
    }

    /**
     * Finding 2. A two-hour film on the slow rhythm, frames 1,005–1,020 ms apart with
     * jittery latency, waking only where [PlayheadExtrapolator.nextWakeMs] asks: the time
     * label steps by exactly one second each time, flips no later than the TV's position
     * crosses the second plus the frame's trip, and costs about one wake a second.
     */
    @Test fun theTimeLabelFlipsOnTimeAndOneSecondAtATimeOnALongFilm() {
        val film = 2L * 60L * 60_000L
        val surface = Surface(playing.copy(durationMs = film))
        val random = Random(7)
        val start = 600_000L
        fun truth(t: Long) = start + t
        val arrivals = ArrayDeque<Pair<Long, Long>>() // arrival time to reported position
        var sample = 0L
        while (sample < 200_000L) {
            val latency = 20L + random.nextLong(0L, 61L)
            arrivals.addLast(sample + latency to truth(sample))
            sample += 1_005L + random.nextLong(0L, 16L)
        }
        var wakeAt: Long? = null
        var label = -1L
        var wakes = 0
        var t = 0L
        val maxLatency = 80L
        while (t < 200_000L) {
            var event = false
            while (arrivals.isNotEmpty() && arrivals.first().first == t) {
                surface.frame(arrivals.removeFirst().second, t)
                event = true
            }
            if (wakeAt == t) {
                wakes++
                event = true
            }
            if (event) {
                val shown = surface.draw(t) / 1_000L
                if (label >= 0L) {
                    assertTrue("label went from $label to $shown at $t", shown == label || shown == label + 1L)
                }
                label = shown
                wakeAt = surface.extrapolator.nextWakeMs(t, pixelMs = null, labels = true)?.let { t + it }
            }
            // The label already shows the second the TV crossed at least maxLatency ago.
            if (t >= 3_000L) {
                assertTrue("label $label lags at $t", label >= (truth(t) - maxLatency) / 1_000L)
            }
            t += 1L
        }
        assertTrue("$wakes wakes in 200 s", wakes in 150..210)
    }
}
