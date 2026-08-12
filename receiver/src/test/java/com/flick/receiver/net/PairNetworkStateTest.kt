package com.flick.receiver.net

import org.junit.Assert.assertEquals
import org.junit.Test

class PairNetworkStateTest {

    @Test fun aBoundSiteLocalAddressIsTheOnlyReadyState() {
        assertEquals(
            PairNetworkFace.READY,
            pairNetworkFace(hasSiteLocalIpv4 = true, hasAnyIpv4 = true, boundPort = 47654),
        )
    }

    @Test fun noAddressAtAllIsTheWaitingForWifiCard() {
        assertEquals(
            PairNetworkFace.NO_ADDRESS,
            pairNetworkFace(hasSiteLocalIpv4 = false, hasAnyIpv4 = false, boundPort = -1),
        )
    }

    /**
     * The TV is online and has already taken the advice the waiting card gives, so it
     * may not be given that advice again.
     */
    @Test fun anAddressFlickCannotUseIsNotTheSameAsNoAddress() {
        assertEquals(
            PairNetworkFace.NOT_SITE_LOCAL,
            pairNetworkFace(hasSiteLocalIpv4 = false, hasAnyIpv4 = true, boundPort = -1),
        )
    }

    /**
     * The regression this whole face exists for: an address the code had just resolved,
     * every bind refused, and a card telling the viewer to connect to their network.
     */
    @Test fun anAddressWithNoPortBehindItIsABindFailure() {
        assertEquals(
            PairNetworkFace.NO_BIND,
            pairNetworkFace(hasSiteLocalIpv4 = true, hasAnyIpv4 = true, boundPort = -1),
        )
    }

    @Test fun aPortOfZeroIsNoPort() {
        assertEquals(
            PairNetworkFace.NO_BIND,
            pairNetworkFace(hasSiteLocalIpv4 = true, hasAnyIpv4 = true, boundPort = 0),
        )
    }

    /** A bound port cannot rescue a TV that has no address to have bound it on. */
    @Test fun aPortWithoutAnAddressIsStillAnAddressProblem() {
        assertEquals(
            PairNetworkFace.NO_ADDRESS,
            pairNetworkFace(hasSiteLocalIpv4 = false, hasAnyIpv4 = false, boundPort = 47654),
        )
    }
}
