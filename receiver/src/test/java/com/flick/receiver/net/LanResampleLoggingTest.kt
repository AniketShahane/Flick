package com.flick.receiver.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which re-samples are worth a line in a 200-entry ring that is the only record this TV keeps
 * of a cast.
 *
 * These are not tests about a boolean. Each one is a question the diagnostics screen can still
 * answer after the TV has been sitting still for an hour.
 */
class LanResampleLoggingTest {

    /**
     * The regression this exists for.
     *
     * `onCapabilitiesChanged` fires for RSSI, link speed, validation and NOT_SUSPENDED on a
     * link whose address never moved — observed on the verified TV every three to ten seconds
     * while completely idle. At a line each that is the whole ring inside twenty minutes, and
     * a five-hour-old process was found holding nothing else: no pairing, no loadMedia, no
     * playback, only this one line repeated.
     */
    @Test fun anIdleLinkSayingNothingChangedIsSilent() {
        assertFalse(logsLanResample("capabilities", changed = false))
    }

    /** Whatever the callback was called, agreement with the last answer describes nothing. */
    @Test fun anyCallbackThatChangedNothingIsSilent() {
        listOf("capabilities", "available", "lost").forEach {
            assertFalse(it, logsLanResample(it, changed = false))
        }
    }

    /**
     * And the event the ring exists for still lands. A DHCP move is what makes the server
     * rebind, and it is the line a reader correlates a dead cast against.
     */
    @Test fun anAddressThatMovedIsAlwaysWorthALine() {
        listOf("capabilities", "available", "lost", LAN_CALLBACK_START).forEach {
            assertTrue(it, logsLanResample(it, changed = true))
        }
    }

    /**
     * The first sample speaks even when it agrees, because it agrees with nothing: the field
     * it is compared against starts null, so a TV that genuinely has no address would
     * otherwise open its log with silence and look identical to a monitor that never ran.
     */
    @Test fun theFirstSampleSpeaksEvenWhenItFoundNothing() {
        assertTrue(logsLanResample(LAN_CALLBACK_START, changed = false))
    }
}
