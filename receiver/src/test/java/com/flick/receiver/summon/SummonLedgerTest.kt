package com.flick.receiver.summon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SummonLedgerTest {

    private fun ledger(strikes: Int = 0, blocked: Boolean = false) = SummonLedger(strikes, blocked)

    // --- Hold tokens ----------------------------------------------------------

    @Test fun onlyTheNewestHoldReleasesAndOnlyOnce() {
        val ledger = ledger()
        val first = ledger.takeHold()
        val second = ledger.takeHold()

        assertEquals(1L, first)
        assertFalse(ledger.releaseHold(first))
        assertTrue(ledger.releaseHold(second))
        assertFalse(ledger.releaseHold(second))
    }

    @Test fun theNoHoldTokenNeverReleases() {
        val ledger = ledger()
        assertFalse(ledger.releaseHold(0L))
        ledger.takeHold()
        assertFalse(ledger.releaseHold(0L))
    }

    @Test fun aCancelledAttemptsHoldStillReleases() {
        val ledger = ledger()
        val hold = ledger.takeHold()
        val attempt = ledger.issue(nowMs = 0L, interactive = true)

        assertTrue(ledger.abandon(attempt.id))
        assertTrue(ledger.releaseHold(hold))
    }

    // --- Attempts -------------------------------------------------------------

    @Test fun abandoningTheCurrentAttemptResolvesIt() {
        val ledger = ledger()
        val attempt = ledger.issue(nowMs = 0L, interactive = true)

        assertEquals(1L, attempt.id)
        assertTrue(ledger.abandon(attempt.id))
        assertEquals(AttemptState.Abandoned, ledger.inFlight?.state)
        assertFalse(ledger.abandon(attempt.id))
    }

    @Test fun anOlderAttemptCannotAbandonTheCurrentOne() {
        val ledger = ledger()
        val older = ledger.issue(nowMs = 0L, interactive = true)
        val current = ledger.issue(nowMs = 100L, interactive = true)

        assertFalse(ledger.abandon(older.id))
        assertEquals(current.id, ledger.inFlight?.id)
        assertEquals(AttemptState.Pending, ledger.inFlight?.state)
    }

    @Test fun openedReportsTheWaitAndResetsStrikes() {
        val ledger = ledger(strikes = 1)
        val attempt = ledger.issue(nowMs = 1_000L, interactive = true)

        assertEquals(2_500L, ledger.opened(attempt.id, nowMs = 3_500L))
        assertEquals(AttemptState.Opened, ledger.inFlight?.state)
        assertEquals(0, ledger.strikes)
        assertNull(ledger.opened(attempt.id, nowMs = 4_000L))
    }

    @Test fun aMissAfterOpenedIsANoOp() {
        val ledger = ledger()
        val attempt = ledger.issue(nowMs = 0L, interactive = true)
        ledger.opened(attempt.id, nowMs = 2_000L)

        assertNull(ledger.missed(attempt.id, nowMs = 8_000L, wakeResumed = false))
        assertFalse(ledger.missedLatch)
        assertEquals(0, ledger.strikes)
        assertEquals(AttemptState.Opened, ledger.inFlight?.state)
    }

    @Test fun aMissAfterAbandonIsANoOp() {
        val ledger = ledger()
        val attempt = ledger.issue(nowMs = 0L, interactive = true)
        ledger.abandon(attempt.id)

        assertNull(ledger.missed(attempt.id, nowMs = 8_000L, wakeResumed = false))
        assertFalse(ledger.missedLatch)
    }

    // --- Strikes --------------------------------------------------------------

    @Test fun twoInteractiveBlockedMissesDisarm() {
        val ledger = ledger()
        val first = ledger.missed(ledger.issue(0L, interactive = true).id, nowMs = 8_000L, wakeResumed = false)!!

        assertEquals(SummonMiss.Blocked, first.kind)
        assertEquals(1, first.strikes)
        assertFalse(first.blockedNow)
        assertEquals(8_000L, first.waitedMs)
        assertTrue(ledger.missedLatch)
        assertFalse(ledger.blocked)

        ledger.foreground()
        val second = ledger.missed(ledger.issue(10_000L, interactive = true).id, nowMs = 18_000L, wakeResumed = false)!!

        assertEquals(2, second.strikes)
        assertTrue(second.blockedNow)
        assertTrue(ledger.blocked)
    }

    @Test fun aBlockAlreadyInForceIsNotReportedAsNew() {
        val ledger = ledger(strikes = 2, blocked = true)
        val verdict = ledger.missed(ledger.issue(0L, interactive = true).id, nowMs = 8_000L, wakeResumed = false)!!

        assertFalse(verdict.blockedNow)
        assertTrue(ledger.blocked)
    }

    @Test fun anAsleepMissLatchesWithoutAStrike() {
        val ledger = ledger(strikes = 1)
        val verdict = ledger.missed(ledger.issue(0L, interactive = false).id, nowMs = 8_000L, wakeResumed = false)!!

        assertEquals(SummonMiss.Asleep, verdict.kind)
        assertEquals(1, verdict.strikes)
        assertFalse(verdict.blockedNow)
        assertTrue(ledger.missedLatch)
        assertEquals(AttemptState.Missed, ledger.inFlight?.state)
    }

    @Test fun aMissWhoseTrampolineResumedIsLateAndResets() {
        val ledger = ledger(strikes = 1)
        val verdict = ledger.missed(ledger.issue(0L, interactive = true).id, nowMs = 8_000L, wakeResumed = true)!!

        assertEquals(SummonMiss.Late, verdict.kind)
        assertEquals(0, verdict.strikes)
        assertTrue(ledger.missedLatch)
    }

    // --- Late correction ------------------------------------------------------

    @Test fun aBlockedMissResumedLaterLosesItsStrike() {
        val ledger = ledger()
        val attempt = ledger.issue(0L, interactive = true)
        ledger.missed(attempt.id, nowMs = 8_000L, wakeResumed = false)

        val correction = ledger.wakeResumed(attempt.id)

        assertNotNull(correction)
        assertEquals(attempt.id, correction!!.attemptId)
        assertFalse(correction.unblocked)
        assertEquals(0, ledger.strikes)
        assertFalse(ledger.blocked)
        assertNull(ledger.wakeResumed(attempt.id))
    }

    @Test fun aLateCorrectionLiftsTheBlockItsOwnStrikeSet() {
        val ledger = ledger(strikes = 1)
        val attempt = ledger.issue(0L, interactive = true)
        assertTrue(ledger.missed(attempt.id, nowMs = 8_000L, wakeResumed = false)!!.blockedNow)

        val correction = ledger.wakeResumed(attempt.id)!!

        assertTrue(correction.unblocked)
        assertFalse(ledger.blocked)
        assertEquals(0, ledger.strikes)
    }

    @Test fun aLateCorrectionNeverLiftsABlockItDidNotSet() {
        val ledger = ledger(strikes = 2, blocked = true)
        val attempt = ledger.issue(0L, interactive = true)
        ledger.missed(attempt.id, nowMs = 8_000L, wakeResumed = false)

        val correction = ledger.wakeResumed(attempt.id)!!

        assertFalse(correction.unblocked)
        assertTrue(ledger.blocked)
    }

    @Test fun aResumeForAnotherAttemptCorrectsNothing() {
        val ledger = ledger()
        val attempt = ledger.issue(0L, interactive = true)
        ledger.missed(attempt.id, nowMs = 8_000L, wakeResumed = false)

        assertNull(ledger.wakeResumed(attempt.id + 1))
        assertEquals(1, ledger.strikes)
    }

    @Test fun aResumeAfterAnAsleepMissCorrectsNothing() {
        val ledger = ledger(strikes = 1)
        val attempt = ledger.issue(0L, interactive = false)
        ledger.missed(attempt.id, nowMs = 8_000L, wakeResumed = false)

        assertNull(ledger.wakeResumed(attempt.id))
        assertEquals(1, ledger.strikes)
    }

    // --- Leaving during a cast ------------------------------------------------

    @Test fun leavingSoonAfterASummonOpenedIsABounce() {
        val ledger = ledger()
        ledger.opened(ledger.issue(nowMs = -1_000L, interactive = true).id, nowMs = 0L)

        assertEquals(LatchReason.Bounced, ledger.leftDuringCast(nowMs = 4_000L, beforeReady = true))
        assertTrue(ledger.missedLatch)
        assertEquals(LatchReason.LeftDuringCast, ledger.leftDuringCast(nowMs = 6_000L, beforeReady = true))
        assertEquals(LatchReason.LeftDuringCast, ledger.leftDuringCast(nowMs = 4_000L, beforeReady = false))
    }

    @Test fun leavingWithoutAnySummonIsLeftDuringCast() {
        val ledger = ledger()

        assertEquals(LatchReason.LeftDuringCast, ledger.leftDuringCast(nowMs = 1_000L, beforeReady = true))
        assertTrue(ledger.missedLatch)
    }

    // --- Foreground and unblock -----------------------------------------------

    @Test fun comingToTheFrontClearsTheLatch() {
        val ledger = ledger()
        ledger.leftDuringCast(nowMs = 0L, beforeReady = false)

        ledger.foreground()

        assertFalse(ledger.missedLatch)
    }

    @Test fun unblockClearsTheBlockAndTheStrikes() {
        val ledger = ledger(strikes = 2, blocked = true)

        ledger.unblock()

        assertFalse(ledger.blocked)
        assertEquals(0, ledger.strikes)
    }

    @Test fun unblockForgetsTheLastStrikeSoALateResumeCannotRecountIt() {
        val ledger = ledger(strikes = 1)
        val attempt = ledger.issue(0L, interactive = true)
        ledger.missed(attempt.id, nowMs = 8_000L, wakeResumed = false)

        ledger.unblock()

        assertNull(ledger.wakeResumed(attempt.id))
    }
}
