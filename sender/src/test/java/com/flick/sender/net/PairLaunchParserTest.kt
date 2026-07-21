package com.flick.sender.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairLaunchParserTest {
    @Test fun legacyEnvelopeStillLaunchesWithNoPrefill() {
        // An un-updated TV must still be able to open the app.
        assertEquals(PairLaunchParseResult.Valid(null, null), PairLaunch.parse("flick://pair?v=2"))
        listOf(
            "flick://pair?v=1", "flick://pair?v=2&host=192.168.42.8", "flick://pair?v=2&v=2",
            "flick://pair/path?v=2", "flick://pair:9?v=2", "flick://u@pair?v=2", "flick://pair?v=2#x",
        ).forEach { assertFalse(PairLaunch.parse(it) is PairLaunchParseResult.Valid) }
        assertEquals(PairLaunchParseResult.UnsupportedVersion, PairLaunch.parse("flick://pair?v=1"))
    }

    @Test fun v3CarriesTheEndpointInAnyParameterOrder() {
        assertEquals(
            PairLaunchParseResult.Valid("192.168.42.190", 47654),
            PairLaunch.parse("flick://pair?v=3&h=192.168.42.190&p=47654"),
        )
        assertEquals(
            PairLaunchParseResult.Valid("10.0.42.7", 47655),
            PairLaunch.parse("flick://pair?p=47655&h=10.0.42.7&v=3"),
        )
    }

    @Test fun v3RejectsNonPrivateHostsMalformedPortsAndParameterTampering() {
        listOf(
            // Public / ambiguous / link-local hosts.
            "flick://pair?v=3&h=8.8.8.8&p=47654",
            "flick://pair?v=3&h=169.254.1.1&p=47654",
            "flick://pair?v=3&h=tv.lan&p=47654",
            "flick://pair?v=3&h=192.168.042.17&p=47654",
            // Malformed ports.
            "flick://pair?v=3&h=192.168.42.190&p=0",
            "flick://pair?v=3&h=192.168.42.190&p=047654",
            "flick://pair?v=3&h=192.168.42.190&p=65536",
            "flick://pair?v=3&h=192.168.42.190&p=abc",
            // Duplicate, extra and missing parameters.
            "flick://pair?v=3&h=192.168.42.190&h=192.168.42.191&p=47654",
            "flick://pair?v=3&h=192.168.42.190&p=47654&x=1",
            "flick://pair?v=3&h=192.168.42.190",
            "flick://pair?v=3&p=47654",
            "flick://pair?v=3",
            // Version and payload must agree.
            "flick://pair?v=2&h=192.168.42.190&p=47654",
        ).forEach { assertEquals(it, PairLaunchParseResult.Invalid, PairLaunch.parse(it)) }
    }

    @Test fun typedValuesRejectAmbiguousAndPublicAddresses() {
        assertTrue(PairLaunch.isCanonicalIpv4("192.168.42.17"))
        listOf("192.168.042.17", "192.168.42", "192.168.42.256", "localhost", "127.0.0.1", "169.254.1.1", " 192.168.1.2").forEach {
            assertFalse(PairLaunch.isCanonicalIpv4(it))
        }
        assertTrue(PairLaunch.isCanonicalPort("42421")); assertFalse(PairLaunch.isCanonicalPort("042421")); assertFalse(PairLaunch.isCanonicalPort("0"))
        assertTrue(PairLaunch.isCode("0007")); assertFalse(PairLaunch.isCode("7")); assertFalse(PairLaunch.isCode("00a7"))
        assertTrue(PairLaunch.isCanonicalPort(PairLaunch.DEFAULT_CONTROL_PORT.toString()))
    }
}
