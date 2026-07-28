package com.flick.receiver.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decisions `PairingManager.forget` makes before it writes anything.
 * `PairingManager` needs a `Context` and a real `SharedPreferences`, so the rules
 * live in pure functions and are exercised here; `PairingCommitTest` covers the
 * durable-write-first discipline they run inside.
 */
class ForgetOnePairingTest {
    private fun v2(keyId: String, label: String) = "$keyId|secret-$keyId|$label"
    private fun v3(keyId: String, at: Long, label: String) =
        encodePairingRecord(keyId, "secret-$keyId", at, label)

    @Test fun forgettingAnUnknownKeyIdRemovesNothingAndSaysSo() {
        val records = setOf(v3("keyA", 2_000L, "Alpha"), v3("keyB", 1_000L, "Beta"))
        assertNull(recordsWithout(records, "keyMissing"))
        assertNull(recordsWithout(emptySet(), "keyA"))
    }

    @Test fun forgettingRemovesExactlyOneRecord() {
        val records = setOf(v3("keyA", 2_000L, "Alpha"), v3("keyB", 1_000L, "Beta"))
        val remaining = recordsWithout(records, "keyA")
        assertEquals(setOf(v3("keyB", 1_000L, "Beta")), remaining)
    }

    @Test fun aV2RecordCanBeForgottenWithoutRePairing() {
        val records = setOf(v2("keyA", "Alpha"), v3("keyB", 1_000L, "Beta"))
        assertEquals(setOf(v3("keyB", 1_000L, "Beta")), recordsWithout(records, "keyA"))
        assertEquals(setOf(v2("keyA", "Alpha")), recordsWithout(records, "keyB"))
    }

    /** Reaching zero is what reopens the pairing surface; any other count must not. */
    @Test fun forgettingTheLastRecordEmptiesTheStore() {
        assertTrue(recordsWithout(setOf(v3("keyA", 2_000L, "Alpha")), "keyA")!!.isEmpty())
        assertTrue(recordsWithout(setOf(v2("keyA", "Alpha")), "keyA")!!.isEmpty())
    }

    @Test fun anUndecodableRecordSurvivesAForget() {
        val records = setOf("garbage", v3("keyA", 2_000L, "Alpha"))
        assertEquals(setOf("garbage"), recordsWithout(records, "keyA"))
    }

    @Test fun lastDeviceIsLeftAloneWhenItNamesAPhoneThatStays() {
        assertNull(
            lastDeviceAfterForget(
                remaining = setOf(v3("keyA", 2_000L, "Alpha")),
                forgottenKeyId = "keyB",
                forgottenLabel = "Beta",
                storedKeyId = "keyA",
                storedLabel = "Alpha",
            ),
        )
    }

    @Test fun lastDeviceNeverNamesAForgottenPhone() {
        val next = lastDeviceAfterForget(
            remaining = setOf(v3("keyA", 2_000L, "Alpha"), v3("keyC", 3_000L, "Gamma")),
            forgottenKeyId = "keyB",
            forgottenLabel = "Beta",
            storedKeyId = "keyB",
            storedLabel = "Beta",
        )
        assertEquals(LastDevice("Gamma", "keyC"), next)
    }

    @Test fun forgettingTheOnlyNamedPhoneClearsTheName() {
        assertEquals(
            LastDevice(null, null),
            lastDeviceAfterForget(
                remaining = emptySet(),
                forgottenKeyId = "keyA",
                forgottenLabel = "Alpha",
                storedKeyId = "keyA",
                storedLabel = "Alpha",
            ),
        )
    }

    /**
     * A store written before v3 recorded no last-paired key id, so the label is
     * the only signal it left.
     */
    @Test fun aLegacyStoreFallsBackToMatchingTheLabel() {
        assertEquals(
            LastDevice("Beta", "keyB"),
            lastDeviceAfterForget(
                remaining = setOf(v3("keyB", 1_000L, "Beta")),
                forgottenKeyId = "keyA",
                forgottenLabel = "Alpha",
                storedKeyId = null,
                storedLabel = "Alpha",
            ),
        )
        assertNull(
            lastDeviceAfterForget(
                remaining = setOf(v3("keyB", 1_000L, "Alpha")),
                forgottenKeyId = "keyA",
                forgottenLabel = "Beta",
                storedKeyId = null,
                storedLabel = "Alpha",
            ),
        )
    }

    /** An all-v2 remainder has no recency to promote by, so it promotes by label. */
    @Test fun anUndatedRemainderStillProducesAPhoneThatIsPaired() {
        val next = lastDeviceAfterForget(
            remaining = setOf(v2("keyZ", "Zeta"), v2("keyA", "Alpha")),
            forgottenKeyId = "keyGone",
            forgottenLabel = "Gone",
            storedKeyId = "keyGone",
            storedLabel = "Gone",
        )
        assertEquals(LastDevice("Alpha", "keyA"), next)
    }

    /**
     * A resume handshake validates its proof against the credential it cached at
     * `resumeInit`, so the record's absence alone does not stop it — the forget
     * has to reach the handshake itself.
     */
    @Test fun forgettingOnePhoneInvalidatesOnlyThatPhonesHandshake() {
        assertTrue(forgetInvalidatesResumeChallenge("keyA", "keyA"))
        assertFalse(forgetInvalidatesResumeChallenge("keyB", "keyA"))
    }

    @Test fun forgettingEveryPhoneInvalidatesEveryHandshake() {
        assertTrue(forgetInvalidatesResumeChallenge("keyA", null))
        assertTrue(forgetInvalidatesResumeChallenge("keyB", null))
    }

    /**
     * The install site's own expression: a null ticket is an authentication that
     * issued no resume challenge at all.
     */
    private fun admission(ticketRevoked: Boolean?, busy: Boolean) =
        leaseAdmission(resumeRevoked = ticketRevoked == true, busy = busy)

    @Test fun anUnrevokedIdleResumeTakesTheLease() {
        assertEquals(LeaseAdmission.INSTALL, admission(ticketRevoked = false, busy = false))
    }

    @Test fun aResumeRevokedByAForgetIsRefusedTheLease() {
        assertEquals(LeaseAdmission.FORGOTTEN, admission(ticketRevoked = true, busy = false))
    }

    /** Busy invites a retry in a moment; a forgotten phone must never be given one. */
    @Test fun aRevokedResumeIsRefusedRatherThanToldToRetry() {
        assertEquals(LeaseAdmission.FORGOTTEN, admission(ticketRevoked = true, busy = true))
        assertEquals(LeaseAdmission.BUSY, admission(ticketRevoked = false, busy = true))
    }

    /**
     * The `pair` path issues no resume challenge, so it registers no ticket — a
     * record `attemptPair` has just committed must still install.
     */
    @Test fun aFreshPairIsNeverRefusedByTheForgetGate() {
        assertEquals(LeaseAdmission.INSTALL, admission(ticketRevoked = null, busy = false))
        assertEquals(LeaseAdmission.BUSY, admission(ticketRevoked = null, busy = true))
    }
}
