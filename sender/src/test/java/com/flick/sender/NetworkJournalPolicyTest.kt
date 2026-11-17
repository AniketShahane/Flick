package com.flick.sender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One journal line per change worth a line, never one per callback. */
class NetworkJournalPolicyTest {

    @Test fun aVpnIsNamedOverTheTransportUnderIt() {
        assertEquals("vpn", defaultNetworkTransport(wifi = true, vpn = true, cellular = false))
        assertEquals("vpn", defaultNetworkTransport(wifi = false, vpn = true, cellular = true))
        assertEquals("wifi", defaultNetworkTransport(wifi = true, vpn = false, cellular = false))
        assertEquals("cellular", defaultNetworkTransport(wifi = false, vpn = false, cellular = true))
        assertEquals("other", defaultNetworkTransport(wifi = false, vpn = false, cellular = false))
    }

    /** A frequency change is a roam hint; the first reading and a repeat are not. */
    @Test fun aFrequencyMovesOnlyFromAKnownReading() {
        val latch = ChangeLatch<Int>()
        assertFalse(latch.moved(5180))
        assertFalse(latch.moved(5180))
        assertTrue(latch.moved(5745))
        assertFalse(latch.moved(null))
        assertFalse(latch.moved(5745))
        assertTrue(latch.moved(2437))
    }

    @Test fun aNewAssociationResetsTheBaseline() {
        val latch = ChangeLatch<Int>()
        latch.moved(5180)
        latch.reset(2437)
        assertFalse(latch.moved(2437))
        latch.reset(null)
        assertFalse(latch.moved(5180))
    }

    @Test fun theFirstTransportIsAChange() {
        val latch = ChangeLatch<String>()
        assertTrue(latch.changed("wifi"))
        assertFalse(latch.changed("wifi"))
        assertTrue(latch.changed("vpn"))
    }
}
