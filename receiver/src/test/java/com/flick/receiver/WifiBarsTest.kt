package com.flick.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The quality card is up for 4.5 s at the start of every cast and its whole job is
 * to be believed. `wifiRssiDbm` is 0 on Ethernet AND on a failed read, so the
 * unguarded `>= -55` this replaced lit four green bars for a radio nobody
 * measured. Restoring any arm that maps a non-negative reading to a bar count
 * fails here rather than on a viewer's television.
 */
class WifiBarsTest {

    @Test fun aReadingTheTvNeverTookIsNoReadingAtAll() {
        assertNull(wifiBars(0))
        assertNull(wifiBars(12))
    }

    @Test fun everyMeasuredBandGetsItsOwnCount() {
        assertEquals(4, wifiBars(-30))
        assertEquals(4, wifiBars(-55))
        assertEquals(3, wifiBars(-56))
        assertEquals(3, wifiBars(-65))
        assertEquals(2, wifiBars(-66))
        assertEquals(2, wifiBars(-75))
        assertEquals(1, wifiBars(-76))
        assertEquals(1, wifiBars(-92))
    }

    @Test fun theWeakestMeasuredLinkStillShowsOneBar() {
        // A measured link is never drawn as nothing: nothing is what an unmeasured
        // one has to look like, and the two may not collide.
        assertEquals(1, wifiBars(Int.MIN_VALUE))
    }
}
