package com.flick.receiver.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The budget the startup deadline gives back for a rotation re-prepare. Pure,
 * because the cases that decide a cast — a grant already spent, a deadline that
 * has already passed, a correction on a cast with no startup outstanding — are
 * exactly the ones a running TV cannot be asked to reproduce.
 */
class StartupDeadlinePolicyTest {

    /** The value the cast transaction arms; duplicated because it is private there. */
    private val startupDeadlineMs = 18_000L

    @Test fun aRotationInsideTheBudgetBuysTheRemainderPlusTheGrant() {
        val deadlineMs = startupDeadlineMs
        val nowMs = 2_000L

        val budget = StartupDeadlinePolicy.budgetAfterRotationRePrepare(deadlineMs, false, nowMs)

        assertEquals(16_000L + StartupDeadlinePolicy.ROTATION_EXTENSION_MS, budget)
        // What the caller re-arms from: the ORIGINAL deadline moved by the grant,
        // never a fresh full budget restarted from the correction.
        assertEquals(deadlineMs + StartupDeadlinePolicy.ROTATION_EXTENSION_MS, nowMs + budget!!)
    }

    @Test fun aSecondRotationBuysNothingHoweverEarlyItLands() {
        assertNull(StartupDeadlinePolicy.budgetAfterRotationRePrepare(startupDeadlineMs, true, 0L))
        assertNull(StartupDeadlinePolicy.budgetAfterRotationRePrepare(startupDeadlineMs, true, 500L))
        assertNull(
            StartupDeadlinePolicy.budgetAfterRotationRePrepare(
                startupDeadlineMs + StartupDeadlinePolicy.ROTATION_EXTENSION_MS,
                true,
                3_000L,
            ),
        )
    }

    /** Cleared with the rest of the startup transaction, so zero means there is nothing to extend. */
    @Test fun aCastWithNoStartupOutstandingIsNeverExtended() {
        assertNull(StartupDeadlinePolicy.budgetAfterRotationRePrepare(0L, false, 0L))
        assertNull(StartupDeadlinePolicy.budgetAfterRotationRePrepare(-1L, false, 0L))
    }

    /** A verdict landing after the budget expired must not revive a cast that is already gone. */
    @Test fun aSpentBudgetIsNotRevived() {
        assertNull(StartupDeadlinePolicy.budgetAfterRotationRePrepare(startupDeadlineMs, false, 18_000L))
        assertNull(StartupDeadlinePolicy.budgetAfterRotationRePrepare(startupDeadlineMs, false, 19_500L))
    }

    @Test fun theGrantIsBoundedAndNowhereNearDoublingTheWait() {
        val worstCaseMs = startupDeadlineMs + StartupDeadlinePolicy.ROTATION_EXTENSION_MS

        assertEquals(24_000L, worstCaseMs)
        assertTrue(worstCaseMs < startupDeadlineMs * 3 / 2)
        // Every grant is the same fixed size, whenever the correction fires.
        for (nowMs in longArrayOf(0L, 1_000L, 9_000L, 17_999L)) {
            val budget = StartupDeadlinePolicy.budgetAfterRotationRePrepare(startupDeadlineMs, false, nowMs)
            assertEquals(worstCaseMs, nowMs + budget!!)
        }
    }
}
