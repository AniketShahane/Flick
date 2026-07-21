package com.flick.receiver.net

import io.ktor.http.HttpMethod
import io.ktor.http.RequestConnectionPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for the pre-auth gate. `remoteHost` performs a blocking reverse-DNS
 * lookup and returns a NAME on any LAN whose router answers PTR, which failed the
 * private-IPv4 gate and closed every legitimate phone's socket before a single
 * protocol frame was read. The gate must be fed `remoteAddress`, which never
 * resolves.
 */
class PeerIdentityTest {

    private class FakeConnectionPoint(
        override val remoteAddress: String,
        override val remoteHost: String,
    ) : RequestConnectionPoint {
        override val scheme: String = "http"
        override val version: String = "HTTP/1.1"
        @Deprecated("Use localPort or serverPort instead")
        override val port: Int = 47654
        override val localPort: Int = 47654
        override val serverPort: Int = 47654
        @Deprecated("Use localHost or serverHost instead")
        override val host: String = "10.0.0.2"
        override val localHost: String = "10.0.0.2"
        override val serverHost: String = "10.0.0.2"
        override val localAddress: String = "10.0.0.2"
        override val uri: String = "/control"
        override val method: HttpMethod = HttpMethod.Get
        override val remotePort: Int = 41234
    }

    @Test fun `a router-supplied hostname never reaches the gate`() {
        val point = FakeConnectionPoint(remoteAddress = "10.0.0.7", remoteHost = "some-phone.lan")
        val peer = peerIdentity(point)
        assertEquals("10.0.0.7", peer)
        assertTrue(MediaUrlValidator.isPrivateIpv4(peer))
        // The value the old code used would have been rejected pre-auth.
        assertFalse(MediaUrlValidator.isPrivateIpv4(point.remoteHost))
    }

    @Test fun `ipv4-mapped ipv6 literals are unwrapped`() {
        val peer = peerIdentity(FakeConnectionPoint("::ffff:192.168.7.42", "gateway.lan"))
        assertEquals("192.168.7.42", peer)
        assertTrue(MediaUrlValidator.isPrivateIpv4(peer))
    }

    @Test fun `an unresolvable socket address fails closed`() {
        // Ktor returns the literal "unknown" when the socket address is null.
        assertFalse(MediaUrlValidator.isPrivateIpv4(peerIdentity(FakeConnectionPoint("unknown", "unknown"))))
    }
}
