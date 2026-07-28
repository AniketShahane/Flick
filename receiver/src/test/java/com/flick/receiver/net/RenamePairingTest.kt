package com.flick.receiver.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decisions `PairingManager.rename` makes before it writes anything.
 * `PairingManager` needs a `Context` and a real `SharedPreferences`, so the rules
 * live in pure functions and are exercised here; `PairingCommitTest` covers the
 * durable-write-first discipline they run inside.
 *
 * The whole contract under test is that a rename changes the NAME and nothing
 * else. Every credential field is asserted explicitly rather than by comparing
 * whole records, because a rename that quietly re-minted a key would unpair the
 * phone it was meant to relabel and the user would be told it worked.
 */
class RenamePairingTest {
    private fun v2(keyId: String, label: String) = "$keyId|secret-$keyId|$label"
    private fun v3(keyId: String, at: Long, label: String) =
        encodePairingRecord(keyId, "secret-$keyId", at, label)

    private fun decoded(records: Set<String>?, keyId: String): PairingRecord? =
        records?.mapNotNull(PairingRecord::decode)?.firstOrNull { it.keyId == keyId }

    @Test fun renamingChangesTheLabelAndLeavesTheCredentialUntouched() {
        val records = setOf(v3("keyA", 2_000L, "Alpha"), v3("keyB", 1_000L, "Beta"))
        val renamed = recordsRenamed(records, "keyA", "Kitchen phone")
        val record = decoded(renamed, "keyA")
        assertNotNull(record)
        assertEquals("Kitchen phone", record!!.label)
        assertEquals("keyA", record.keyId)
        assertEquals("secret-keyA", record.key)
        assertEquals(2_000L, record.pairedAtMs)
    }

    /** Renaming one phone must not touch, reorder or re-encode any other. */
    @Test fun renamingOnePhoneLeavesEveryOtherRecordByteIdentical() {
        val untouched = v3("keyB", 1_000L, "Beta")
        val renamed = recordsRenamed(setOf(v3("keyA", 2_000L, "Alpha"), untouched), "keyA", "Gamma")
        assertTrue(renamed!!.contains(untouched))
        assertEquals(2, renamed.size)
    }

    @Test fun renamingAnUnknownKeyIdChangesNothingAndSaysSo() {
        val records = setOf(v3("keyA", 2_000L, "Alpha"))
        assertNull(recordsRenamed(records, "keyMissing", "Gamma"))
        assertNull(recordsRenamed(emptySet(), "keyA", "Gamma"))
    }

    /**
     * A v2 record has no date, and a rename may not invent one — the label is the
     * only field it is allowed to author. It re-encodes as v3 with an empty date,
     * which decodes back to "unknown" rather than to an epoch.
     */
    @Test fun renamingAnUndatedRecordKeepsTheDateUnknown() {
        val renamed = recordsRenamed(setOf(v2("keyA", "Alpha")), "keyA", "Study phone")
        val record = decoded(renamed, "keyA")
        assertNotNull(record)
        assertEquals("Study phone", record!!.label)
        assertEquals("secret-keyA", record.key)
        assertNull(record.pairedAtMs)
    }

    @Test fun anEmptyDateFieldRoundTripsAsAnUnknownDate() {
        val encoded = encodePairingRecord("keyA", "secret-keyA", null, "Alpha")
        val record = PairingRecord.decode(encoded)
        assertNotNull(record)
        assertNull(record!!.pairedAtMs)
        assertEquals("keyA", record.keyId)
        assertEquals("secret-keyA", record.key)
        assertEquals("Alpha", record.label)
    }

    /** v2 puts the label last precisely so it may contain `|`; a rename must too. */
    @Test fun aLabelContainingAPipeSurvivesARename() {
        val renamed = recordsRenamed(setOf(v3("keyA", 2_000L, "Alpha")), "keyA", "12345|home")
        val record = decoded(renamed, "keyA")
        assertEquals("12345|home", record!!.label)
        assertEquals("secret-keyA", record.key)
    }

    @Test fun anUndecodableRecordSurvivesARename() {
        val renamed = recordsRenamed(setOf("garbage", v3("keyA", 2_000L, "Alpha")), "keyA", "Gamma")
        assertTrue(renamed!!.contains("garbage"))
    }

    @Test fun lastDeviceIsLeftAloneWhenItNamesAPhoneThatWasNotRenamed() {
        assertNull(
            lastDeviceAfterRename(
                renamedKeyId = "keyB",
                oldLabel = "Beta",
                newLabel = "Gamma",
                storedKeyId = "keyA",
                storedLabel = "Alpha",
            ),
        )
    }

    /** Idle renders `last_device` as "Paired with …"; it may not keep the old name. */
    @Test fun lastDeviceFollowsThePhoneItNames() {
        assertEquals(
            LastDevice("Gamma", "keyB"),
            lastDeviceAfterRename(
                renamedKeyId = "keyB",
                oldLabel = "Beta",
                newLabel = "Gamma",
                storedKeyId = "keyB",
                storedLabel = "Beta",
            ),
        )
    }

    /** A store written before v3 recorded no key id, so the old label is the only signal. */
    @Test fun aLegacyStoreFallsBackToMatchingTheLabel() {
        assertEquals(
            LastDevice("Gamma", "keyB"),
            lastDeviceAfterRename(
                renamedKeyId = "keyB",
                oldLabel = "Beta",
                newLabel = "Gamma",
                storedKeyId = null,
                storedLabel = "Beta",
            ),
        )
        assertNull(
            lastDeviceAfterRename(
                renamedKeyId = "keyB",
                oldLabel = "Beta",
                newLabel = "Gamma",
                storedKeyId = null,
                storedLabel = "Alpha",
            ),
        )
        assertNull(
            lastDeviceAfterRename(
                renamedKeyId = "keyB",
                oldLabel = "Beta",
                newLabel = "Gamma",
                storedKeyId = null,
                storedLabel = null,
            ),
        )
    }
}
