package com.flick.sender.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairLaunchParserTest {
    @Test fun canonicalEnvelopeIsTheOnlyAcceptedLaunch() {
        assertEquals(PairLaunchParseResult.Valid, PairLaunch.parse("flick://pair?v=2"))
        listOf(
            "flick://pair?v=1", "flick://pair?v=2&host=192.168.1.8", "flick://pair?v=2&v=2",
            "flick://pair/path?v=2", "flick://pair:9?v=2", "flick://u@pair?v=2", "flick://pair?v=2#x",
        ).forEach { assertFalse(PairLaunch.parse(it) == PairLaunchParseResult.Valid) }
    }

    @Test fun typedValuesRejectAmbiguousAndPublicAddresses() {
        assertTrue(PairLaunch.isCanonicalIpv4("192.168.42.17"))
        listOf("192.168.042.17", "192.168.42", "192.168.42.256", "localhost", "127.0.0.1", "169.254.1.1", " 192.168.1.2").forEach {
            assertFalse(PairLaunch.isCanonicalIpv4(it))
        }
        assertTrue(PairLaunch.isCanonicalPort("42421")); assertFalse(PairLaunch.isCanonicalPort("042421")); assertFalse(PairLaunch.isCanonicalPort("0"))
        assertTrue(PairLaunch.isCode("0007")); assertFalse(PairLaunch.isCode("7")); assertFalse(PairLaunch.isCode("00a7"))
    }
}
