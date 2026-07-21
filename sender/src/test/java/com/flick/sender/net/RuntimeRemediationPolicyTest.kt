package com.flick.sender.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeRemediationPolicyTest {
    @Test fun cleanupSendsStopOnlyForTheCastWhoseLoadWasDispatched() {
        assertTrue(CastCleanupPolicy.shouldSendStop("cast-a", "cast-a"))
        assertFalse(CastCleanupPolicy.shouldSendStop("cast-b", "cast-a"))
        assertFalse(CastCleanupPolicy.shouldSendStop("cast-a", null))
    }

    @Test fun failedPersistenceNeverReportsSuccessAndCodeResetIsMonotonic() {
        assertFalse(PairingPersistence.commit { false })
        assertTrue(PairingPersistence.commit { true })
        val reset = PairCodeReset()
        assertEquals(1L, reset.clear())
        assertEquals(2L, reset.clear())
    }
}
