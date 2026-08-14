package com.flick.sender.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a failed dial may state where this phone and the TV sit relative to each other.
 *
 * The face this gates names the router, and naming it takes two facts rather than one: the
 * kernel's EHOSTUNREACH, and a TV that is demonstrably there to be kept apart from. The
 * platform's NSD stack answers a resolve out of its own cache, so a TV switched off minutes
 * ago is still advertised — and the shipped face convicted the router for it.
 */
class DialPlacesTvTest {

    @Test fun aFreshlyAdvertisedTvIsOneTheRouterCanBeKeepingApart() {
        assertTrue(dialPlacesTv(DialFault.NO_ROUTE, freshlyAdvertised = true))
    }

    /** No answer to a resolve in this pass is no evidence the TV is on the network at all. */
    @Test fun aTvThatAnsweredNoResolveIsNotPlaced() {
        assertFalse(dialPlacesTv(DialFault.NO_ROUTE, freshlyAdvertised = false))
    }

    /**
     * An RST proves the path forwards, so a refusal can never reach the blocking face and
     * must never be hedged as though it might: its own copy is about a receiver that is not
     * listening, and that sentence is true whatever mDNS said.
     */
    @Test fun anRstIsNeverGatedOnAnAdvertisement() {
        assertTrue(dialPlacesTv(DialFault.REFUSED, freshlyAdvertised = false))
    }

    /** Every other fault keeps the copy it shipped with, advertised or not. */
    @Test fun onlyTheRouterFaultIsGated() {
        for (fault in DialFault.entries.filter { it != DialFault.NO_ROUTE }) {
            assertTrue("$fault was gated", dialPlacesTv(fault, freshlyAdvertised = false))
            assertTrue("$fault was gated", dialPlacesTv(fault, freshlyAdvertised = true))
        }
    }
}
