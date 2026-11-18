package com.flick.sender.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A record held across a discovery stop is a memory until a resolve issued since lands for it. */
class HeldRecordConfirmationTest {

    @Test fun nothingIsUnconfirmedBeforeTheFirstStop() {
        val confirmation = HeldRecordConfirmation()
        assertFalse(confirmation.anyUnconfirmed)
        assertTrue(confirmation.confirmed("Living Room"))
        assertEquals(emptyList<String>(), confirmation.dueForLoss(listOf("Living Room")))
    }

    @Test fun everyRecordHeldAtAStopIsUnconfirmedUntilResolved() {
        val confirmation = HeldRecordConfirmation()
        confirmation.onStopped(listOf("Living Room", "Bedroom"))
        assertTrue(confirmation.anyUnconfirmed)
        assertFalse(confirmation.confirmed("Living Room"))
        confirmation.onResolved("Living Room")
        assertTrue(confirmation.confirmed("Living Room"))
        assertFalse(confirmation.confirmed("Bedroom"))
        assertEquals(listOf("Bedroom"), confirmation.dueForLoss(listOf("Living Room", "Bedroom")))
    }

    @Test fun aNameFoundOnlyAfterTheStopWasNeverUnconfirmed() {
        val confirmation = HeldRecordConfirmation()
        confirmation.onStopped(listOf("Living Room"))
        assertTrue(confirmation.confirmed("Kitchen"))
        assertEquals(listOf("Living Room"), confirmation.dueForLoss(listOf("Kitchen", "Living Room")))
    }

    @Test fun aDroppedRecordLeavesNothingToJudge() {
        val confirmation = HeldRecordConfirmation()
        confirmation.onStopped(listOf("Living Room"))
        confirmation.onForgotten("Living Room")
        assertFalse(confirmation.anyUnconfirmed)
        assertEquals(emptyList<String>(), confirmation.dueForLoss(listOf("Living Room")))
    }

    @Test fun aSecondStopBeforeReconfirmationKeepsWhatTheFirstLeftUnconfirmed() {
        val confirmation = HeldRecordConfirmation()
        confirmation.onStopped(listOf("Living Room", "Bedroom"))
        confirmation.onResolved("Bedroom")
        confirmation.onStopped(listOf("Bedroom", "Living Room"))
        assertFalse(confirmation.confirmed("Living Room"))
        assertFalse(confirmation.confirmed("Bedroom"))
    }

    @Test fun theGraceIsTwiceTheLossGrace() {
        assertEquals(5_000L, HeldRecordConfirmation.RECONFIRM_GRACE_MS)
    }
}
