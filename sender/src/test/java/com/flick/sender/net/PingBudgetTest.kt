package com.flick.sender.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * This phone's ceiling on authenticated pings, held under the receiver's.
 *
 * The receiver's `PingGate` answers a sixth ping inside its ten-second window by closing
 * the control socket — the one outcome a liveness check exists to prevent — so the phone
 * keeps a stricter budget of its own and simply does not ask once it is spent.
 */
class PingBudgetTest {

    @Test fun theBudgetIsSpentAfterThreeInsideTheWindow() {
        val budget = PingBudget()
        assertTrue(budget.tryAcquire(1_000L))
        assertTrue(budget.tryAcquire(2_000L))
        assertTrue(budget.tryAcquire(3_000L))
        assertFalse(budget.available(4_000L))
        assertFalse(budget.tryAcquire(4_000L))
    }

    /** A refused acquire records nothing, so asking while spent never pushes the window out. */
    @Test fun aRefusedPingIsNotCounted() {
        val budget = PingBudget()
        repeat(3) { budget.tryAcquire(1_000L) }
        repeat(20) { assertFalse(budget.tryAcquire(5_000L)) }
        assertTrue(budget.tryAcquire(11_000L))
    }

    /** Rolling, not tumbling: a ping frees its slot exactly one window after it was sent. */
    @Test fun eachPingFreesItsSlotOneWindowLater() {
        val budget = PingBudget()
        assertTrue(budget.tryAcquire(1_000L))
        assertTrue(budget.tryAcquire(6_000L))
        assertTrue(budget.tryAcquire(9_000L))
        assertFalse(budget.tryAcquire(10_999L))
        assertTrue(budget.tryAcquire(11_000L))
        assertFalse(budget.tryAcquire(15_999L))
        assertTrue(budget.tryAcquire(16_000L))
    }

    /** The phone's own ceiling is strictly under the receiver's, over windows of the same length. */
    @Test fun thePhoneCeilingIsUnderTheReceivers() {
        assertTrue(ControlLiveness.PINGS_PER_WINDOW < ControlLiveness.RECEIVER_PINGS_PER_WINDOW)
        assertTrue(ControlLiveness.PING_WINDOW_MS >= ControlLiveness.RECEIVER_PING_WINDOW_MS)
    }

    /**
     * The receiver counts in fixed windows that open wherever its last one ended, and this
     * phone counts in a rolling one. Hammering the budget every millisecond, no interval
     * the length of the receiver's window — at any alignment — may hold more sends than
     * this phone's own ceiling, and so none may approach the receiver's.
     */
    @Test fun noReceiverWindowAtAnyAlignmentSeesMoreThanTheCeiling() {
        val budget = PingBudget()
        val sent = (0L..60_000L).filter(budget::tryAcquire)
        assertTrue(sent.size > ControlLiveness.PINGS_PER_WINDOW)
        for (start in 0L..60_000L step 25L) {
            val inside = sent.count { it >= start && it < start + ControlLiveness.RECEIVER_PING_WINDOW_MS }
            assertTrue("window at $start held $inside", inside <= ControlLiveness.PINGS_PER_WINDOW)
        }
    }

    /**
     * Sends are not arrivals. Pings are strictly sequential, so each one reaches the TV
     * before the next leaves the phone, however late; bunching every arrival as late as
     * that allows still leaves any receiver window under its limit.
     */
    @Test fun sequentialArrivalsBunchedAsLateAsPossibleStayUnderTheReceiversLimit() {
        val budget = PingBudget()
        val sent = (0L..60_000L).filter(budget::tryAcquire)
        // The latest each could arrive: an instant before the next one was sent.
        val arrivals = sent.zipWithNext { _, next -> next - 1 }
        for (start in 0L..60_000L step 25L) {
            val inside = arrivals.count { it >= start && it < start + ControlLiveness.RECEIVER_PING_WINDOW_MS }
            assertTrue("window at $start held $inside", inside < ControlLiveness.RECEIVER_PINGS_PER_WINDOW)
        }
    }
}
