package com.flick.sender.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingRecordRulesTest {
    private fun record(keyId: String = "AQIDBAUGBwgJCgsMDQ4PEA", host: String = "192.168.42.88", repair: Boolean = false) =
        PairingRecordRules.Record("ABEiM0RVZneImaq7zN3u_w", keyId, "Demo TV", host, 42421, "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", repair)

    @Test fun replacementTombstonesOnlySupersededKeyAndLegacyHost() {
        val prior = record()
        val replacement = record("ERITFBUWFxgZGhscHR4fIA")
        val result = PairingRecordRules.replacement(prior, replacement)
        assertEquals(prior.keyId, result.tombstoneKeyId)
        assertEquals(prior.host, result.removeLegacyHost)
        assertNull(PairingRecordRules.replacement(prior, prior).tombstoneKeyId)
    }

    @Test fun repairAndTombstonesSuppressAutomaticResumeWithoutDeletingKeyMaterial() {
        assertTrue(PairingRecordRules.mayAutoResume(record(), tombstoned = false))
        assertFalse(PairingRecordRules.mayAutoResume(record(repair = true), tombstoned = false))
        assertFalse(PairingRecordRules.mayAutoResume(record(), tombstoned = true))
    }

    @Test fun legacyMigrationNeverSpraysProofToChangedHost() {
        assertTrue(PairingRecordRules.legacyMigrationAllowed("192.168.42.88", "192.168.42.88"))
        assertFalse(PairingRecordRules.legacyMigrationAllowed("192.168.42.88", "192.168.42.89"))
        assertFalse(PairingRecordRules.legacyMigrationAllowed("tv.local", "tv.local"))
    }
}
