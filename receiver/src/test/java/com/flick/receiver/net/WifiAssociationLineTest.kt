package com.flick.receiver.net

import com.flick.receiver.util.WifiTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape of an association line.
 *
 * These lines are written to be read off `adb logcat` months after the incident
 * they explain, by someone holding a wall-clock time and nothing else. So the
 * format is part of the contract, not decoration: the same keys at both edges, a
 * monotonic stamp that survives a clock change, and a key set that cannot quietly
 * grow an identifier.
 */
class WifiAssociationLineTest {

    private val link = WifiTelemetry.Link(
        band = "5 GHz",
        frequencyMhz = 5180,
        linkSpeedMbps = 433,
        rssiDbm = -52,
    )

    private fun fields(line: String): Map<String, String> {
        assertTrue("line is not greppable as a wifi edge: $line", line.startsWith("wifi-assoc edge="))
        return line.removePrefix("wifi-assoc ")
            .split(" ")
            .associate { it.substringBefore('=') to it.substringAfter('=') }
    }

    /**
     * The redaction contract as something a test can fail on. SSID and BSSID
     * identify a household and would need a location permission this app does not
     * hold; the epoch is the non-identifying stand-in, and this is what stops a
     * later edit from adding "just the network name" to the line.
     */
    @Test fun theLineCarriesExactlyTheseKeysAndNoIdentifier() {
        val keys = fields(wifiAssociationLine(WIFI_EDGE_AVAILABLE, epoch = 4, atMs = 98_765_432L, link = link)).keys

        assertEquals(
            setOf("edge", "epoch", "atMs", "freqMhz", "linkMbps", "rssiDbm"),
            keys,
        )
    }

    /**
     * The anchor and the stamp key are shared with the sender on purpose: the question
     * these lines exist for is answered by laying the phone's ring beside the TV's and
     * subtracting, and two spellings would make that a manual reconciliation every time.
     */
    @Test fun theLineUsesTheSameAnchorAndStampKeyAsTheSender() {
        val line = wifiAssociationLine(WIFI_EDGE_AVAILABLE, 4, 98_765_432L, link)

        assertTrue(line, line.startsWith("wifi-assoc "))
        assertTrue(line, line.contains(" atMs=98765432 "))
    }

    /**
     * A monitor's first arrival is the association already in place at least as often as
     * it is a new one, and nothing inside the process can tell them apart. Only a later
     * epoch is evidence that the radio moved, so only a later epoch may say "available".
     */
    @Test fun theFirstArrivalIsNotCalledAnAssociation() {
        assertEquals(WIFI_EDGE_FIRST, wifiAssociationEdge(WIFI_FIRST_EPOCH))
        assertEquals(WIFI_EDGE_AVAILABLE, wifiAssociationEdge(WIFI_FIRST_EPOCH + 1))
        assertEquals(WIFI_EDGE_AVAILABLE, wifiAssociationEdge(7))
    }

    /** Both edges are the same table, so a grep of the two reads as one column set. */
    @Test fun bothEdgesCarryTheSameKeys() {
        val available = fields(wifiAssociationLine(WIFI_EDGE_AVAILABLE, 4, 98_765_432L, link))
        val lost = fields(wifiAssociationLine(WIFI_EDGE_LOST, 3, 98_700_000L, null))

        assertEquals(available.keys, lost.keys)
        assertEquals(WIFI_EDGE_AVAILABLE, available["edge"])
        assertEquals(WIFI_EDGE_LOST, lost["edge"])
    }

    /** The radio reading is passed through verbatim; a rounded log is a useless one. */
    @Test fun theRadioReadingIsPrintedAsItWasSampled() {
        val line = fields(wifiAssociationLine(WIFI_EDGE_AVAILABLE, 4, 98_765_432L, link))

        assertEquals("5180", line["freqMhz"])
        assertEquals("433", line["linkMbps"])
        assertEquals("-52", line["rssiDbm"])
    }

    /**
     * The radio is usually already gone by the time the loss arrives, so this is the
     * ordinary case at that edge rather than an error path. An RSSI printed as 0
     * would be a reading — and a plausible one — where there was none at all.
     */
    @Test fun aRadioThatCouldNotBeReadSaysSoRatherThanPrintingZero() {
        val line = fields(wifiAssociationLine(WIFI_EDGE_LOST, 3, 98_700_000L, null))

        assertEquals(WIFI_LINK_UNREAD, line["freqMhz"])
        assertEquals(WIFI_LINK_UNREAD, line["linkMbps"])
        assertEquals(WIFI_LINK_UNREAD, line["rssiDbm"])
        assertEquals("3", line["epoch"])
    }

    /**
     * The stamp is the monotonic one the caller sampled. The logcat stamp beside it
     * is already the wall clock an incident is matched against; this is the one two
     * edges can be subtracted across, so it must reach the line unaltered — no
     * seconds, no rounding, no locale.
     */
    @Test fun theMonotonicStampReachesTheLineUnaltered() {
        val line = fields(wifiAssociationLine(WIFI_EDGE_AVAILABLE, 2, 4_294_967_296L, link))

        assertEquals("4294967296", line["atMs"])
    }
}
