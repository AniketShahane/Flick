package com.flick.sender.support

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportPromptPolicyTest {
    @Test fun successesReachTheThresholdAndThenSaturate() {
        var state = SupportPromptState()

        repeat(100) { state = SupportPromptPolicy.recordSuccess(state) }

        assertEquals(SupportPromptPolicy.SUCCESS_THRESHOLD, state.successfulCastCount)
        assertFalse(state.promptConsumed)
    }

    @Test fun promptCannotBeConsumedBeforeThreeSuccessfulCasts() {
        val state = SupportPromptState(successfulCastCount = 2)

        assertNull(SupportPromptPolicy.consumeIfEligible(state))
    }

    @Test fun thirdSuccessMakesThePromptConsumableExactlyOnce() {
        val eligible = SupportPromptPolicy.recordSuccess(SupportPromptState(successfulCastCount = 2))

        val consumed = SupportPromptPolicy.consumeIfEligible(eligible)!!

        assertEquals(3, consumed.successfulCastCount)
        assertTrue(consumed.promptConsumed)
        assertNull(SupportPromptPolicy.consumeIfEligible(consumed))
    }

    @Test fun recordingMoreSuccessesNeverClearsTheConsumedMarker() {
        val consumed = SupportPromptState(successfulCastCount = 3, promptConsumed = true)

        val next = SupportPromptPolicy.recordSuccess(consumed)

        assertEquals(3, next.successfulCastCount)
        assertTrue(next.promptConsumed)
    }

    @Test fun restoredCountsAreClampedWithoutOverflow() {
        assertEquals(0, SupportPromptPolicy.restore(Int.MIN_VALUE, false).successfulCastCount)
        assertEquals(3, SupportPromptPolicy.restore(Int.MAX_VALUE, false).successfulCastCount)
        assertEquals(
            3,
            SupportPromptPolicy.recordSuccess(SupportPromptState(Int.MAX_VALUE)).successfulCastCount,
        )
    }
}
