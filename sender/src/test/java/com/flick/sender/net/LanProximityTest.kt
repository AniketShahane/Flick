package com.flick.sender.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Only RFC-1918 literals appear here; this repo is public. */
class LanProximityTest {

    @Test
    fun `two addresses in one 24 share a subnet`() {
        assertTrue(LanProximity.sameSubnet("192.168.42.17", "192.168.42.99"))
        assertTrue(LanProximity.sameSubnet("10.0.0.5", "10.0.0.200"))
    }

    @Test
    fun `a different third octet is a different subnet`() {
        assertFalse(LanProximity.sameSubnet("192.168.42.17", "192.168.43.17"))
        assertFalse(LanProximity.sameSubnet("192.168.42.17", "10.0.0.5"))
    }

    @Test
    fun `an unknown address on either side proves nothing`() {
        assertFalse(LanProximity.sameSubnet(null, "192.168.42.17"))
        assertFalse(LanProximity.sameSubnet("192.168.42.17", null))
        assertFalse(LanProximity.sameSubnet(null, null))
    }

    // A value this app would never dial is not a value it may reason about either: the
    // same canonical test the pairing path uses gates this one.
    @Test
    fun `a non-canonical address proves nothing`() {
        assertFalse(LanProximity.sameSubnet("192.168.042.17", "192.168.42.99"))
        assertFalse(LanProximity.sameSubnet("192.168.42.17", "not-an-address"))
        assertFalse(LanProximity.sameSubnet("8.8.8.8", "8.8.8.9"))
        assertFalse(LanProximity.sameSubnet("", ""))
    }

    @Test
    fun `an address shares a subnet with itself`() {
        assertTrue(LanProximity.sameSubnet("192.168.42.17", "192.168.42.17"))
    }
}
