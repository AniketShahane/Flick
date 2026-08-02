package com.flick.sender.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportInvitationCompletionPolicyTest {
    @Test
    fun activeCurrentCastMayOfferInvitationAfterNormalCompletion() {
        assertTrue(
            supportInvitationEligibleForNormalCompletion(
                castId = "cast-1",
                currentCastId = "cast-1",
                state = CastStartState.Active("cast-1"),
            ),
        )
    }

    @Test
    fun neverOffersForStartupFailureOrStaleTerminal() {
        assertFalse(
            supportInvitationEligibleForNormalCompletion(
                castId = "cast-1",
                currentCastId = "cast-1",
                state = CastStartState.AwaitingFirstFrame("cast-1"),
            ),
        )
        assertFalse(
            supportInvitationEligibleForNormalCompletion(
                castId = "cast-1",
                currentCastId = "cast-2",
                state = CastStartState.Active("cast-1"),
            ),
        )
    }
}
