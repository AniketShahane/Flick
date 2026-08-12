package com.flick.receiver.player

import org.junit.Assert.assertEquals
import org.junit.Test

class BufferingPlatePolicyTest {

    @Test fun theFirstSecondsOfAStallStillHoldTheQuality() {
        assertEquals(
            BufferingPlate.TOPPING_UP,
            bufferingPlate(stallMs = 1_500L, protectionSeconds = 15, recoveryAttempts = 0),
        )
    }

    @Test fun oneMillisecondShortOfTheRideOutIsStillToppingUp() {
        assertEquals(
            BufferingPlate.TOPPING_UP,
            bufferingPlate(stallMs = 14_999L, protectionSeconds = 15, recoveryAttempts = 0),
        )
    }

    @Test fun aStallThatOutlastsTheBufferStopsClaimingTheQualityIsHeld() {
        assertEquals(
            BufferingPlate.STALLED,
            bufferingPlate(stallMs = 15_000L, protectionSeconds = 15, recoveryAttempts = 0),
        )
    }

    /**
     * The threshold is MEASURED and not a constant, so a device whose heap forced a
     * smaller budget escalates sooner — which is exactly when its buffer really is gone.
     */
    @Test fun aSmallerBudgetEscalatesSooner() {
        assertEquals(
            BufferingPlate.STALLED,
            bufferingPlate(stallMs = 5_000L, protectionSeconds = 4, recoveryAttempts = 0),
        )
        assertEquals(
            BufferingPlate.TOPPING_UP,
            bufferingPlate(stallMs = 5_000L, protectionSeconds = 40, recoveryAttempts = 0),
        )
    }

    /**
     * The silence of the FIRST recovery is the anti-buffering thesis working. What is
     * not defensible is the second, third and fourth looking identical to it.
     */
    @Test fun anyRecoveryAttemptAtAllHasAlreadySpentTheBuffer() {
        assertEquals(
            BufferingPlate.STALLED,
            bufferingPlate(stallMs = 0L, protectionSeconds = 180, recoveryAttempts = 1),
        )
    }

    /** A budget of nothing cannot be described as holding anything. */
    @Test fun aDeviceThatBoughtNoRideOutIsStalledFromTheStart() {
        assertEquals(
            BufferingPlate.STALLED,
            bufferingPlate(stallMs = 0L, protectionSeconds = 0, recoveryAttempts = 0),
        )
    }
}
