package com.flick.sender.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualPairAttemptLedgerTest {

    @Test
    fun terminalRevisionChangesEvenWhenTheVisiblePairErrorWouldRepeat() {
        val ledger = ManualPairAttemptLedger()
        val generation = ledger.begin()
        // The UI may observe UNREACHABLE both before and after this attempt, but this event
        // remains distinct and lets it consume the new terminal outcome exactly once.
        assertTrue(ledger.complete(generation))

        assertEquals(generation, ledger.event.startedGeneration)
        assertEquals(generation, ledger.event.terminalGeneration)
    }

    @Test
    fun cancelledOlderAttemptCannotPublishOverANewerManualSubmission() {
        val ledger = ManualPairAttemptLedger()
        val old = ledger.begin()
        val current = ledger.begin()

        assertFalse(ledger.complete(old))
        assertEquals(current, ledger.event.startedGeneration)
        assertEquals(null, ledger.event.terminalGeneration)
        assertTrue(ledger.complete(current))
    }
}
