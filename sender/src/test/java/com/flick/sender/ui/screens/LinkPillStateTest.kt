package com.flick.sender.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkPillStateTest {
    @Test
    fun aStoredPairingIsNotCalledReadyWhileThisPhoneHasNoWifiLink() {
        // The observed lie: Wi-Fi off, mobile data only, and the pill still read
        // "Ready · <TV>". A pairing is a credential and survives the network it needs.
        assertEquals(
            LinkPillState.OFFLINE,
            linkPillState(paired = true, casting = false, wifiLinkUp = false),
        )
        assertEquals(
            LinkPillState.PAIRED,
            linkPillState(paired = true, casting = false, wifiLinkUp = true),
        )
    }

    @Test
    fun aCastInFlightOutranksTheLinkReading() {
        // Bytes moving are better evidence than a band this phone could not read, so a
        // live cast is never demoted to the offline face by a momentary null link.
        assertEquals(
            LinkPillState.CASTING,
            linkPillState(paired = true, casting = true, wifiLinkUp = false),
        )
        assertEquals(
            LinkPillState.CASTING,
            linkPillState(paired = true, casting = true, wifiLinkUp = true),
        )
    }

    @Test
    fun nothingPairedStaysTheInviteWhateverTheNetworkIsDoing() {
        for (wifi in listOf(true, false)) {
            assertEquals(
                LinkPillState.UNPAIRED,
                linkPillState(paired = false, casting = false, wifiLinkUp = wifi),
            )
            // A cast cannot exist without a pairing, but the face must not depend on
            // that invariant holding somewhere else.
            assertEquals(
                LinkPillState.UNPAIRED,
                linkPillState(paired = false, casting = true, wifiLinkUp = wifi),
            )
        }
    }
}
