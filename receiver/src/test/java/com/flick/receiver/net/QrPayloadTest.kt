package com.flick.receiver.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one string a phone camera has to read. `PairingManager.qrPayload` delegates
 * straight to [pairingQrPayload], which is pure so the grammar can be pinned
 * without a `Context`.
 *
 * The sender parses this by field, so the version and the field set are a
 * contract between two apps: a change here that is not matched on the phone
 * breaks pairing on a device that has already been updated.
 */
class QrPayloadTest {

    @Test fun theCanonicalPayloadCarriesVersionHostPortAndCode() {
        assertEquals(
            "flick://pair?v=4&h=192.168.1.42&p=47654&c=0731",
            pairingQrPayload("192.168.1.42", 47654, "0731"),
        )
    }

    /** The phone matches on `v`; bumping it without bumping the sender breaks pairing. */
    @Test fun theVersionIsFour() {
        assertEquals(4, PairingManager.QR_VERSION)
        assertTrue(pairingQrPayload("10.0.0.2", 8080, "0000")!!.startsWith("flick://pair?v=4&"))
    }

    /** A leading-zero code is a real code, and its zeros are significant. */
    @Test fun aLeadingZeroCodeIsCarriedVerbatim() {
        assertTrue(pairingQrPayload("10.0.0.2", 8080, "0007")!!.endsWith("&c=0007"))
    }

    @Test fun noBindingDrawsNoSymbol() {
        assertNull(pairingQrPayload("", 47654, "0731"))
        assertNull(pairingQrPayload("   ", 47654, "0731"))
    }

    @Test fun anImpossiblePortDrawsNoSymbol() {
        assertNull(pairingQrPayload("192.168.1.42", 0, "0731"))
        assertNull(pairingQrPayload("192.168.1.42", -1, "0731"))
        assertNull(pairingQrPayload("192.168.1.42", 65_536, "0731"))
        assertEquals(
            "flick://pair?v=4&h=192.168.1.42&p=65535&c=0731",
            pairingQrPayload("192.168.1.42", 65_535, "0731"),
        )
    }

    /**
     * The pair screen renders "—" whenever no code is live — locked out, or the
     * surface standing by. Encoding it would draw a symbol whose scan fails at
     * `attemptPair`, which is worse than drawing none.
     */
    @Test fun aPlaceholderCodeDrawsNoSymbol() {
        assertNull(pairingQrPayload("192.168.1.42", 47654, "—"))
        assertNull(pairingQrPayload("192.168.1.42", 47654, ""))
    }

    @Test fun onlyExactlyFourDigitsIsACode() {
        assertTrue(isPairingCode("0000"))
        assertTrue(isPairingCode("9999"))
        assertFalse(isPairingCode("073"))
        assertFalse(isPairingCode("07311"))
        assertFalse(isPairingCode("07a1"))
        assertFalse(isPairingCode(" 731"))
        assertFalse(isPairingCode("−731"))
        assertFalse(isPairingCode(""))
    }

    /**
     * Non-ASCII digits carry `Character.isDigit`, so a check written that way
     * would admit them — and they are not what `attemptPair` compares against.
     */
    @Test fun nonAsciiDigitsAreNotACode() {
        assertFalse(isPairingCode("٠١٢٣"))
        assertFalse(isPairingCode("１２３４"))
        assertNull(pairingQrPayload("192.168.1.42", 47654, "１２３４"))
    }
}
