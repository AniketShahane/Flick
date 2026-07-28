package com.flick.sender.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    /**
     * The gate the whole v4 design rests on. `flick://pair` is a BROWSABLE deep link on an
     * exported activity, so any installed app can bind a server on this phone's own LAN
     * address and fire a URI naming it — and a one-tap card over that would pair with it.
     * A code therefore survives only the ingress that proves a QR was in the room.
     */
    @Test fun onlyTheScannerMayKeepTheCodeAV4PayloadCarries() {
        val payload = "flick://pair?v=4&h=192.168.42.190&p=47654&c=0007"
        val endpoint = PairLaunchParseResult.Valid("192.168.42.190", 47654)

        // In-process camera: the code is kept, and pairing is one confirmation away.
        assertEquals(ScannedPairLaunch(endpoint, "0007"), PairLaunch.parseScanned(payload))
        // Anything an Intent can deliver resolves to a type with no field a code could
        // occupy: the endpoint prefills the form and the four digits are still typed.
        assertEquals(endpoint, PairLaunch.parse(payload))
        // Parameter order is not the gate; provenance is.
        assertEquals(
            ScannedPairLaunch(endpoint, "0007"),
            PairLaunch.parseScanned("flick://pair?c=0007&p=47654&h=192.168.42.190&v=4"),
        )
    }

    @Test fun aScannedCodeIsNeverPrinted() {
        val held = PairLaunch.parseScanned("flick://pair?v=4&h=192.168.42.190&p=47654&c=0007")
        assertFalse(held.toString().contains("0007"))
        assertTrue(held.toString().contains("held"))
        assertEquals("0007", held.code)
    }

    @Test fun v4IsRejectedWholeWheneverAnyFieldIsWrong() {
        listOf(
            // The code is exactly four ASCII digits, canonical and unencoded.
            "flick://pair?v=4&h=192.168.42.190&p=47654&c=007",
            "flick://pair?v=4&h=192.168.42.190&p=47654&c=00007",
            "flick://pair?v=4&h=192.168.42.190&p=47654&c=00a7",
            "flick://pair?v=4&h=192.168.42.190&p=47654&c=",
            "flick://pair?v=4&h=192.168.42.190&p=47654&c=%30%30%30%37",
            "flick://pair?v=4&h=192.168.42.190&p=47654&c=+007",
            // The endpoint keeps every rejection v3 already had.
            "flick://pair?v=4&h=8.8.8.8&p=47654&c=0007",
            "flick://pair?v=4&h=169.254.1.1&p=47654&c=0007",
            "flick://pair?v=4&h=192.168.042.17&p=47654&c=0007",
            "flick://pair?v=4&h=tv.lan&p=47654&c=0007",
            "flick://pair?v=4&h=192.168.42.190&p=0&c=0007",
            "flick://pair?v=4&h=192.168.42.190&p=047654&c=0007",
            "flick://pair?v=4&h=192.168.42.190&p=65536&c=0007",
            // Duplicate, extra, and missing parameters.
            "flick://pair?v=4&h=192.168.42.190&p=47654&c=0007&c=0008",
            "flick://pair?v=4&h=192.168.42.190&p=47654&c=0007&x=1",
            "flick://pair?v=4&h=192.168.42.190&p=47654",
            "flick://pair?v=4&h=192.168.42.190&c=0007",
            // A payload malformed anywhere is not salvaged into a v3-shaped prefill.
            "flick://pair?v=3&h=192.168.42.190&p=47654&c=0007",
            "flick://pair?v=5&h=192.168.42.190&p=47654&c=0007",
            // The URI's own shape is still checked before any of its fields are.
            "flick://pair:9?v=4&h=192.168.42.190&p=47654&c=0007",
            "flick://u@pair?v=4&h=192.168.42.190&p=47654&c=0007",
            "flick://pair/x?v=4&h=192.168.42.190&p=47654&c=0007",
            "flick://pair?v=4&h=192.168.42.190&p=47654&c=0007#x",
        ).forEach {
            assertEquals(it, PairLaunchParseResult.Invalid, PairLaunch.parse(it))
            assertEquals(it, ScannedPairLaunch(PairLaunchParseResult.Invalid, null), PairLaunch.parseScanned(it))
        }
    }

    @Test fun anOlderQrScannedInAppStillOnlyPrefills() {
        // The scanner's own ingress invents no code for a payload that had none: a TV
        // that has not been updated is still paired by typing what it shows.
        listOf("flick://pair?v=2", "flick://pair?v=3&h=192.168.42.190&p=47654").forEach {
            assertNull(it, PairLaunch.parseScanned(it).code)
        }
        assertEquals(
            ScannedPairLaunch(PairLaunchParseResult.Valid("192.168.42.190", 47654), null),
            PairLaunch.parseScanned("flick://pair?v=3&h=192.168.42.190&p=47654"),
        )
        assertEquals(
            ScannedPairLaunch(PairLaunchParseResult.UnsupportedVersion, null),
            PairLaunch.parseScanned("flick://pair?v=1"),
        )
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
