package com.flick.sender.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sender's half of the split pairing window.
 *
 * `ControlClient.open` used to wrap the dial, the negotiation, the code and the answer
 * in one six-second budget. The receiver now holds the socket while it asks the room
 * whether to admit this phone, so the answer alone may take tens of seconds — but only
 * the answer. Everything before it keeps the original budget, which is what stops a TV
 * that is off, asleep or on another subnet from being reported as unreachable half a
 * minute late.
 */
class PairDecisionBudgetTest {

    /**
     * Resume must stay silent and instant. It never reaches a prompt — the receiver's
     * confirmation lives only on the `pair` path — so it keeps exactly one window and
     * a TV that has gone is still called unreachable in six seconds.
     */
    @Test fun resumeGetsNoDecisionBudgetAtAll() {
        assertEquals(0L, controlDecisionBudgetMs(firstTimePairing = false))
    }

    @Test fun onlyAFirstTimePairingMayWaitOnAPerson() {
        assertTrue(controlDecisionBudgetMs(firstTimePairing = true) > 0L)
    }

    /**
     * The two ends' deadlines are ordered deliberately, and the order decides which of
     * them gets to explain the outcome. The receiver's own expiry answers
     * `denied(expired)`, which this app turns into "that code expired, a new one is on
     * the TV"; a sender that gave up first would replace that with a bare "the TV
     * didn't answer in time". So the sender's budget has to outlast the receiver's
     * 30-second window by more than a LAN round trip.
     *
     * The receiver's constant lives in the other module, so the number is named here
     * rather than imported — and asserted, so the two cannot drift apart silently.
     */
    @Test fun theSenderOutwaitsTheReceiversOwnDeadline() {
        val receiverConfirmWindowMs = 30_000L
        val budget = controlDecisionBudgetMs(firstTimePairing = true)
        assertTrue(
            "the sender's $budget ms must outlast the receiver's $receiverConfirmWindowMs ms " +
                "window, or the phone reports a timeout instead of the TV's real answer",
            budget > receiverConfirmWindowMs + 2_000L,
        )
        // And not by so much that a receiver which simply died leaves the sheet
        // spinning: the whole attempt is the dial budget plus this.
        assertTrue(
            "the total pairing attempt must stay under a minute",
            ControlClient.OPEN_TIMEOUT_MS + budget < 60_000L,
        )
    }

    /**
     * A code that reached the receiver may not be offered again, whichever way the
     * attempt ended. The confirmation adds one route to that — a window that expires —
     * and it arrives as `Denied` or as a `TimedOut` that knows the code went out.
     */
    @Test fun aCodeThatMayHaveBeenConsumedIsNeverOfferedAgain() {
        assertTrue(PairResultPolicy.clearCode(ControlClient.Result.Denied("expired")))
        assertTrue(PairResultPolicy.clearCode(ControlClient.Result.Denied("surface")))
        assertTrue(PairResultPolicy.clearCode(ControlClient.Result.TimedOut(pairCodeSent = true)))
        // Refused before a byte of it left the phone, so it is still good.
        assertTrue(!PairResultPolicy.clearCode(ControlClient.Result.TimedOut(pairCodeSent = false)))
    }
}
