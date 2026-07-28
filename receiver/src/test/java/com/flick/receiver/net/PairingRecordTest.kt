package com.flick.receiver.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The record codec and the ordering it feeds. The user has live pairings written
 * in v2, so every case here is about the upgrade taking their credentials with it.
 */
class PairingRecordTest {
    private fun v2(keyId: String, key: String, label: String) = "$keyId|$key|$label"

    @Test fun v2RecordMigratesInPlaceWithAnUnknownDate() {
        val decoded = decodePairingRecord(v2("keyIdA", "secretA", "Pixel 9 Pro"))
        assertEquals(PairingRecord("keyIdA", "secretA", "Pixel 9 Pro", null), decoded)
        assertNull("a v2 record must not invent a date", decoded?.pairedAtMs)
    }

    @Test fun v3RecordRoundTrips() {
        val encoded = encodePairingRecord("keyIdA", "secretA", 1_700_000_000_000L, "Pixel 9 Pro")
        assertEquals(
            PairingRecord("keyIdA", "secretA", "Pixel 9 Pro", 1_700_000_000_000L),
            decodePairingRecord(encoded),
        )
    }

    @Test fun labelKeepsItsPipesInBothShapes() {
        assertEquals("A|B|C", decodePairingRecord(v2("keyIdA", "secretA", "A|B|C"))?.label)
        val v3 = encodePairingRecord("keyIdA", "secretA", 42L, "A|B|C")
        assertEquals("A|B|C", decodePairingRecord(v3)?.label)
        assertEquals(42L, decodePairingRecord(v3)?.pairedAtMs)
    }

    /**
     * The whole reason a v3 record carries a version sentinel: this v2 label
     * splits into four fields whose third parses as a number, and a migration
     * that trusted the field count would read a phone called "home" paired in
     * 1970 while losing half the name.
     */
    @Test fun v2LabelThatLooksLikeADateIsNotMisreadAsV3() {
        val decoded = decodePairingRecord(v2("keyIdA", "secretA", "12345|home"))
        assertEquals("12345|home", decoded?.label)
        assertNull(decoded?.pairedAtMs)
    }

    @Test fun aV3RecordWithACorruptDateKeepsItsCredential() {
        val decoded = decodePairingRecord("v3|keyIdA|secretA|not-a-number|Pixel")
        assertEquals("keyIdA", decoded?.keyId)
        assertEquals("secretA", decoded?.key)
        assertEquals("Pixel", decoded?.label)
        assertNull(decoded?.pairedAtMs)
    }

    @Test fun malformedRecordsDecodeToNull() {
        assertNull(decodePairingRecord(""))
        assertNull(decodePairingRecord("keyIdA|secretA"))
        assertNull(decodePairingRecord("v3|keyIdA|secretA|42"))
    }

    @Test fun datedRecordsSortNewestFirstAndUndatedOnesLast() {
        val records = listOf(
            v2("keyZ", "secretZ", "Zeta"),
            encodePairingRecord("keyOld", "secretOld", 1_000L, "Older"),
            v2("keyA", "secretA", "Alpha"),
            encodePairingRecord("keyNew", "secretNew", 9_000L, "Newer"),
        )
        assertEquals(
            listOf("Newer", "Older", "Alpha", "Zeta"),
            pairedPhonesOf(records).map { it.label },
        )
    }

    @Test fun orderIsStableAcrossAnUnorderedStore() {
        val records = listOf(
            encodePairingRecord("keyB", "secretB", 5_000L, "Same"),
            encodePairingRecord("keyA", "secretA", 5_000L, "Same"),
            v2("keyD", "secretD", "Same"),
            v2("keyC", "secretC", "Same"),
        )
        val forward = pairedPhonesOf(records).map { it.keyId }
        val reversed = pairedPhonesOf(records.reversed()).map { it.keyId }
        val shuffled = pairedPhonesOf(records.toSet())
        assertEquals(listOf("keyA", "keyB", "keyC", "keyD"), forward)
        assertEquals(forward, reversed)
        assertEquals(forward, shuffled.map { it.keyId })
    }

    @Test fun undecodableRecordsAreNotListed() {
        val phones = pairedPhonesOf(listOf("garbage", v2("keyA", "secretA", "Alpha")))
        assertEquals(listOf("Alpha"), phones.map { it.label })
    }

    /** The pairing key must be unreachable from anything the UI is handed. */
    @Test fun pairedPhoneCarriesNoKeyMaterial() {
        val phone = pairedPhonesOf(listOf(v2("keyIdA", "secretA", "Pixel"))).single()
        assertTrue(
            "PairedPhone must never render the pairing key",
            !phone.toString().contains("secretA"),
        )
        assertEquals("keyIdA", phone.keyId)
    }
}
