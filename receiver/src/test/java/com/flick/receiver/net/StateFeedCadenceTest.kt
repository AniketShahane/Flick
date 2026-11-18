package com.flick.receiver.net

import com.flick.receiver.net.StateFeedCadence.Companion.COLD_INTERVAL_MS
import com.flick.receiver.net.StateFeedCadence.Companion.HOT_WINDOW_MS
import com.flick.receiver.net.StateFeedCadence.Companion.NEVER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StateFeedCadenceTest {

    /**
     * Drives the cadence the way the feed does: one sample every [TICK_MS]. The player
     * fields are mutable so a test can change one between ticks; while [playing] and
     * [phase] is playing the position advances with the clock, as a real film does.
     */
    private class Feed(var nowMs: Long = START_MS) {
        val cadence = StateFeedCadence()
        var castId = "cast-a"
        var posMs = 0L
        var durationMs = 7_200_000L
        var playing = true
        var phase = "playing"
        var volume = 1.0
        var lastCommandAtMs = NEVER
        val emittedAtMs = mutableListOf<Long>()

        fun tick(): Boolean {
            val emit = cadence.onTick(nowMs, castId, posMs, durationMs, playing, phase, volume, lastCommandAtMs)
            if (emit) emittedAtMs += nowMs
            return emit
        }

        fun advance(stepMs: Long = TICK_MS) {
            nowMs += stepMs
            if (playing && phase == "playing") posMs += stepMs
        }

        /** Ticks for [forMs], returning the times emitted within it. */
        fun run(forMs: Long, stepMs: Long = TICK_MS): List<Long> {
            val from = emittedAtMs.size
            val endMs = nowMs + forMs
            while (nowMs < endMs) {
                advance(stepMs)
                tick()
            }
            return emittedAtMs.subList(from, emittedAtMs.size).toList()
        }

        /** Starts the feed and lets the opening hot window run out. */
        fun settle(): Feed {
            tick()
            run(HOT_WINDOW_MS + COLD_INTERVAL_MS)
            return this
        }
    }

    @Test
    fun `the first tick of a connection emits and starts hot`() {
        val feed = Feed()
        assertTrue(feed.tick())
        val emitted = feed.run(HOT_WINDOW_MS - TICK_MS)
        assertEquals((HOT_WINDOW_MS / TICK_MS - 1).toInt(), emitted.size)
    }

    @Test
    fun `cold emits once per second`() {
        val feed = Feed().settle()
        val emitted = feed.run(10_000L)
        assertEquals(10, emitted.size)
        emitted.zipWithNext().forEach { (a, b) -> assertEquals(COLD_INTERVAL_MS, b - a) }
    }

    @Test
    fun `a paused film is cold too`() {
        val feed = Feed()
        feed.playing = false
        feed.phase = "paused"
        feed.settle()
        assertEquals(10, feed.run(10_000L).size)
    }

    @Test
    fun `a command makes it hot for three seconds and then cold`() {
        val feed = Feed().settle()
        feed.advance()
        feed.lastCommandAtMs = feed.nowMs
        assertTrue(feed.tick())
        val hot = feed.run(HOT_WINDOW_MS - TICK_MS)
        assertEquals((HOT_WINDOW_MS / TICK_MS - 1).toInt(), hot.size)
        val cold = feed.run(5_000L)
        assertEquals(5, cold.size)
    }

    @Test
    fun `a ping never heats the feed and every other command does`() {
        assertFalse(StateFeedCadence.heats("ping"))
        assertFalse(StateFeedCadence.heats(null))
        for (verb in listOf("seek", "play", "pause", "setVolume", "setAudioDelay", "loadMedia", "stop")) {
            assertTrue(verb, StateFeedCadence.heats(verb))
        }
    }

    @Test
    fun `a feed whose commands are never stamped stays cold`() {
        val feed = Feed().settle()
        assertEquals(5, feed.run(5_000L).size)
    }

    @Test
    fun `a command stamped between ticks heats the next tick`() {
        val feed = Feed().settle()
        val lastCold = feed.emittedAtMs.last()
        feed.lastCommandAtMs = lastCold + 30L
        feed.advance()
        assertTrue(feed.tick())
    }

    @Test
    fun `a play or pause change emits on the same tick and heats`() {
        assertChangeHeats { playing = false; phase = "paused" }
    }

    @Test
    fun `a playing flag change alone emits and heats`() {
        assertChangeHeats { playing = false }
    }

    @Test
    fun `a phase change emits and heats`() {
        assertChangeHeats { phase = "ended"; playing = false }
    }

    @Test
    fun `a duration change emits and heats`() {
        assertChangeHeats { durationMs += 1L }
    }

    @Test
    fun `a volume change emits and heats`() {
        assertChangeHeats { volume = 0.5 }
    }

    @Test
    fun `a seek forward made with the remote heats`() {
        assertChangeHeats { posMs += 60_000L }
    }

    @Test
    fun `a seek backward made with the remote heats`() {
        assertChangeHeats { posMs -= 60_000L }
    }

    @Test
    fun `a seek while paused heats`() {
        val feed = Feed()
        feed.playing = false
        feed.phase = "paused"
        feed.settle()
        feed.advance()
        feed.posMs += 5_000L
        assertTrue(feed.tick())
        assertEquals((HOT_WINDOW_MS / TICK_MS - 1).toInt(), feed.run(HOT_WINDOW_MS - TICK_MS).size)
    }

    @Test
    fun `steady playback does not heat, even with sampling jitter`() {
        val feed = Feed().settle()
        val jitter = longArrayOf(0L, 180L, -90L, 250L, -200L, 40L)
        var i = 0
        val endMs = feed.nowMs + 20_000L
        val from = feed.emittedAtMs.size
        while (feed.nowMs < endMs) {
            feed.nowMs += TICK_MS
            // The sampled position lags or leads the clock by a few hundred ms, as a
            // main-thread snapshot does, but never jumps.
            feed.posMs = (feed.nowMs - START_MS) + jitter[i++ % jitter.size]
            feed.tick()
        }
        assertEquals(20, feed.emittedAtMs.size - from)
    }

    @Test
    fun `a position that freezes while playing does not keep the feed hot`() {
        val feed = Feed().settle()
        val from = feed.emittedAtMs.size
        repeat(100) {
            feed.nowMs += TICK_MS
            feed.tick()
        }
        assertEquals(10, feed.emittedAtMs.size - from)
    }

    @Test
    fun `buffering stays hot for as long as it lasts`() {
        val feed = Feed().settle()
        feed.phase = "buffering"
        val emitted = feed.run(10_000L)
        assertEquals(100, emitted.size)
        feed.phase = "playing"
        // The change out of buffering heats once more, then the window runs out.
        assertEquals((HOT_WINDOW_MS / TICK_MS).toInt(), feed.run(HOT_WINDOW_MS).size)
        assertEquals(5, feed.run(5_000L).size)
    }

    @Test
    fun `a new cast id heats`() {
        assertChangeHeats { castId = "cast-b" }
    }

    @Test
    fun `a reset makes the next tick emit and start hot`() {
        val feed = Feed().settle()
        feed.cadence.reset()
        feed.advance()
        assertTrue(feed.tick())
        assertEquals((HOT_WINDOW_MS / TICK_MS - 1).toInt(), feed.run(HOT_WINDOW_MS - TICK_MS).size)
    }

    @Test
    fun `the gap between frames never exceeds the cold interval plus one tick`() {
        val feed = Feed()
        feed.tick()
        var t = 0
        val endMs = feed.nowMs + 120_000L
        while (feed.nowMs < endMs) {
            // A tick that runs a little late each time, as delay() plus the work does.
            feed.advance(TICK_MS + (t++ % 7))
            when (t % 400) {
                50 -> feed.lastCommandAtMs = feed.nowMs
                120 -> { feed.playing = false; feed.phase = "paused" }
                200 -> { feed.playing = true; feed.phase = "playing" }
                300 -> feed.posMs += 90_000L
            }
            feed.tick()
        }
        val maxGap = feed.emittedAtMs.zipWithNext { a, b -> b - a }.max()
        assertTrue("max gap $maxGap", maxGap <= COLD_INTERVAL_MS + TICK_MS + 6)
        assertFalse(feed.emittedAtMs.isEmpty())
    }

    /** Settles cold, applies [change] between two ticks, and checks it is sent at once and heats. */
    private fun assertChangeHeats(change: Feed.() -> Unit) {
        val feed = Feed().settle()
        val lastCold = feed.emittedAtMs.last()
        feed.advance()
        feed.change()
        assertTrue("change must emit on its own tick", feed.tick())
        assertTrue(feed.nowMs - lastCold < COLD_INTERVAL_MS)
        val hot = feed.run(HOT_WINDOW_MS - TICK_MS)
        assertEquals((HOT_WINDOW_MS / TICK_MS - 1).toInt(), hot.size)
        val cold = feed.run(5_000L)
        assertEquals(5, cold.size)
    }

    private companion object {
        const val START_MS = 1_000_000L
        const val TICK_MS = 100L
    }
}
