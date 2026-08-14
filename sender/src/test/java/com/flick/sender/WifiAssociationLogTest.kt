package com.flick.sender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The association history the router-block question is answered from.
 *
 * A new Network object inside half a minute of a block clearing means the block was ended
 * by this phone re-associating, and no app-side action could ever have shortened it. None
 * means every station-inactivity-timer explanation dies. So the counter has to be right
 * about which association each edge belongs to, and the line has to be greppable enough to
 * pull the whole history out of a pasted log in one filter.
 */
class WifiAssociationLogTest {

    @Test fun eachNewNetworkOpensTheNextEpoch() {
        val ledger = WifiAssociationLedger()
        assertEquals(1, ledger.available(0x1111L))
        assertEquals(2, ledger.available(0x2222L))
        assertEquals(3, ledger.available(0x3333L))
    }

    /** A repeat for a network already recorded is not a re-association. */
    @Test fun theSameNetworkNeverOpensASecondEpoch() {
        val ledger = WifiAssociationLedger()
        assertEquals(1, ledger.available(0x1111L))
        assertNull(ledger.available(0x1111L))
        assertEquals(2, ledger.available(0x2222L))
    }

    /**
     * A handover reports the new network's arrival before the old one's departure, so a
     * loss that read the counter live would be filed under the association that replaced it
     * — and the log would show a network leaving after it arrived.
     */
    @Test fun aLossCarriesTheEpochItCloses() {
        val ledger = WifiAssociationLedger()
        ledger.available(0x1111L)
        ledger.available(0x2222L)
        assertEquals(1, ledger.lost(0x1111L))
        assertEquals(2, ledger.lost(0x2222L))
    }

    @Test fun aLossOfANetworkNeverSeenArriveIsNotRecorded() {
        assertNull(WifiAssociationLedger().lost(0x4444L))
    }

    /** It is fed by a callback that outlives every screen, so it may not grow forever. */
    @Test fun theLedgerIsBounded() {
        val ledger = WifiAssociationLedger(limit = 2)
        ledger.available(0x1111L)
        ledger.available(0x2222L)
        ledger.available(0x3333L)
        assertNull(ledger.lost(0x1111L))
        assertEquals(3, ledger.lost(0x3333L))
    }

    @Test fun anEdgeCarriesTheReadingsTheQuestionIsAskedWith() {
        val line = wifiAssociationLine(
            edge = "available",
            epoch = 4,
            atMs = 987_654L,
            link = WifiLinkInfo(WifiBand.GHZ_5, frequencyMhz = 5180, linkSpeedMbps = 433, rssiDbm = -52),
        )
        assertEquals(
            "wifi-assoc edge=available epoch=4 atMs=987654 band=ghz_5 freqMhz=5180 linkMbps=433 rssiDbm=-52",
            line,
        )
    }

    /** At a loss edge there is often no link left to read, and zeros would average in. */
    @Test fun anUnreadableLinkIsStatedAsAnAbsence() {
        assertEquals(
            "wifi-assoc edge=lost epoch=4 atMs=987654 link=none",
            wifiAssociationLine("lost", epoch = 4, atMs = 987_654L, link = null),
        )
    }

    /**
     * The redaction contract, held by construction: this line has no parameter an SSID or a
     * BSSID could arrive in, and the epoch that replaces them is derived here and names
     * nothing outside this phone.
     */
    /**
     * Observed on the real phone: three app restarts inside four minutes each logged an
     * identical `epoch=1`, and not one of them was the radio doing anything. A callback
     * replays `onAvailable` for a network already up the moment it registers, so a
     * monitor's first arrival cannot be told from an association it simply walked in on —
     * and counting it would make the ±30 s correlation agree with whatever it was asked.
     */
    @Test fun theFirstArrivalIsNotReportedAsAnAssociation() {
        assertEquals(WIFI_EDGE_FIRST, wifiAssociationEdge(WIFI_FIRST_EPOCH))
        assertEquals(WIFI_EDGE_AVAILABLE, wifiAssociationEdge(WIFI_FIRST_EPOCH + 1))
        assertEquals(WIFI_EDGE_AVAILABLE, wifiAssociationEdge(9))
    }

    @Test fun everyEdgeIsGreppableAndCarriesNoNetworkIdentity() {
        val link = WifiLinkInfo(WifiBand.GHZ_24, frequencyMhz = 2437, linkSpeedMbps = 72, rssiDbm = -70)
        for (edge in listOf(WIFI_EDGE_FIRST, WIFI_EDGE_AVAILABLE, WIFI_EDGE_LOST)) {
            for (reading in listOf(link, null)) {
                val line = wifiAssociationLine(edge, epoch = 1, atMs = 1L, link = reading)
                assertTrue(line, line.startsWith("wifi-assoc "))
                assertTrue(line, line.contains(" epoch=1 "))
                assertTrue(line, !line.contains(":"))
            }
        }
    }
}
