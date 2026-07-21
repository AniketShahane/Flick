package com.flick.receiver.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeniedReasonTest {

    @Test fun `every pair outcome maps to a declared reason`() {
        assertEquals(DENIED_CODE, deniedReasonFor(PairAttemptResult.InvalidCode))
        assertEquals(DENIED_EXPIRED, deniedReasonFor(PairAttemptResult.Expired))
        assertEquals(DENIED_SURFACE, deniedReasonFor(PairAttemptResult.SurfaceClosed))
        assertEquals(DENIED_LOCKED, deniedReasonFor(PairAttemptResult.LockedOut(0L)))
        assertEquals(DENIED_STORAGE, deniedReasonFor(PairAttemptResult.PersistenceFailed))
        assertEquals(DENIED_BUSY, deniedReasonFor(null))
    }

    @Test fun `only code and expired are derived from what the user typed`() {
        // Anything else would make the frame an enumeration oracle.
        val codeDerived = setOf(DENIED_CODE, DENIED_EXPIRED)
        val fromOutcomes = listOf<PairAttemptResult?>(
            PairAttemptResult.SurfaceClosed,
            PairAttemptResult.LockedOut(0L),
            PairAttemptResult.PersistenceFailed,
            null,
        ).map(::deniedReasonFor)
        assertTrue(fromOutcomes.none { it in codeDerived })
    }

    @Test fun `frame carries exactly three keys and a declared reason`() {
        val fields = deniedFrameFields(DENIED_PROOF)
        assertEquals(listOf("t", "v", "reason"), fields.keys.toList())
        assertEquals("denied", fields["t"])
        assertEquals(2, fields["v"])
        assertEquals(DENIED_PROOF, fields["reason"])
    }

    @Test fun `an undeclared reason degrades to unknown`() {
        assertEquals(DENIED_UNKNOWN, deniedFrameFields("something-else")["reason"])
    }

    @Test fun `the vocabulary is exactly the contract set`() {
        assertEquals(
            setOf("code", "expired", "surface", "locked", "busy", "storage", "proof", "unknown"),
            DENIED_REASONS,
        )
    }
}
