package com.flick.receiver.net

import com.flick.receiver.session.CastReattachPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A missing address is a blip until the window says otherwise. */
class LanBlipPolicyTest {

    @Test fun anEmptySampleInsideTheWindowWaits() {
        assertEquals(LanBlipVerdict.WAIT, LanBlipPolicy.decide(HOST, null, waitedMs = 0L))
        assertEquals(LanBlipVerdict.WAIT, LanBlipPolicy.decide(HOST, null, LanBlipPolicy.LAN_BLIP_GRACE_MS - 1L))
    }

    @Test fun theSameAddressComingBackKeepsTheBinding() {
        assertEquals(LanBlipVerdict.KEEP, LanBlipPolicy.decide(HOST, HOST, waitedMs = 1_500L))
    }

    /** Even at the very end of the window: the address is what decides, not the clock. */
    @Test fun theSameAddressIsKeptEvenLate() {
        assertEquals(LanBlipVerdict.KEEP, LanBlipPolicy.decide(HOST, HOST, LanBlipPolicy.LAN_BLIP_GRACE_MS + 5_000L))
    }

    /** A different address is a real move and is never waited on. */
    @Test fun aDifferentAddressIsAChangeAtOnce() {
        assertEquals(LanBlipVerdict.CHANGED, LanBlipPolicy.decide(HOST, OTHER, waitedMs = 0L))
        assertEquals(LanBlipVerdict.CHANGED, LanBlipPolicy.decide(HOST, OTHER, waitedMs = 3_000L))
    }

    @Test fun nothingByTheEndOfTheWindowReleases() {
        assertEquals(LanBlipVerdict.RELEASE, LanBlipPolicy.decide(HOST, null, LanBlipPolicy.LAN_BLIP_GRACE_MS))
    }

    /** The TV is found again while a held film is still waiting for its phone. */
    @Test fun theWindowEndsInsideTheCastHold() {
        assertTrue(LanBlipPolicy.LAN_BLIP_GRACE_MS < CastReattachPolicy.ORPHAN_GRACE_MS)
    }

    private companion object {
        const val HOST = "192.168.42.20"
        const val OTHER = "192.168.42.21"
    }
}
