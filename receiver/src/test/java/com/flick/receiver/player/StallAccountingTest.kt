package com.flick.receiver.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StallAccountingTest {

    private fun opens(
        playbackStarted: Boolean = true,
        stallOpen: Boolean = false,
        seekFillOpen: Boolean = false,
        reloadFillOpen: Boolean = false,
    ) = StallAccounting.opensStall(playbackStarted, stallOpen, seekFillOpen, reloadFillOpen)

    @Test
    fun `buffering with nothing else in flight is the stall this app is judged on`() {
        assertTrue(opens())
    }

    @Test
    fun `the startup fill is not a stall, because nothing has played yet`() {
        assertFalse(opens(playbackStarted = false))
    }

    @Test
    fun `buffering that flutters inside an open episode is still one stall`() {
        assertFalse(opens(stallOpen = true))
    }

    @Test
    fun `a seek's refill is the viewer moving the playhead, not a stall`() {
        assertFalse(opens(seekFillOpen = true))
    }

    /**
     * The regression this file exists for. Measured on a Google TV Streamer: three
     * subtitle toggles produced three "stalls" of 1091/1257/1203 ms while the network
     * stalled zero times, which put amber "3 stalls" on the overlay, pinned the
     * diagnostics status at WARN, and made the advertised `-s FlickTV:W` stall filter
     * fire on a feature working exactly as designed.
     */
    @Test
    fun `an in-place reload's refill is the viewer attaching a subtitle, not a stall`() {
        assertFalse(opens(reloadFillOpen = true))
    }

    @Test
    fun `a reload still shields the count when a seek settles across it`() {
        assertFalse(opens(seekFillOpen = true, reloadFillOpen = true))
    }

    /**
     * The exclusions are about WHY the player is buffering, so none of them may
     * outrank the precondition that something was playing to be interrupted.
     */
    @Test
    fun `no exclusion turns the startup fill into something countable`() {
        assertFalse(opens(playbackStarted = false, seekFillOpen = true))
        assertFalse(opens(playbackStarted = false, reloadFillOpen = true))
        assertFalse(opens(playbackStarted = false, stallOpen = true))
    }
}
