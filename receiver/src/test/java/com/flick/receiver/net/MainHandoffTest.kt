package com.flick.receiver.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class MainHandoffTest {
    @Test fun completedWorkIsReturned() {
        val handoff = MainHandoff<String>(abandonOnTimeout = true)
        handoff.run({ true }) { "accepted" }
        assertEquals(MainHandoff.Outcome.Done("accepted"), handoff.await(10))
    }

    @Test fun workIsRefusedWhenOwnershipChangedBeforeTheMainThreadReachedIt() {
        val handoff = MainHandoff<String>(abandonOnTimeout = true)
        var ran = false
        handoff.run({ false }) { ran = true; "accepted" }
        assertEquals(MainHandoff.Outcome.Refused, handoff.await(10))
        assertFalse(ran)
    }

    @Test fun throwingWorkCompletesWithNoValueRatherThanTimingOut() {
        val handoff = MainHandoff<String>(abandonOnTimeout = true)
        handoff.run({ true }) { error("boom") }
        assertEquals(MainHandoff.Outcome.Done<String>(null), handoff.await(10))
    }

    @Test fun abandonedWorkNeverRunsLate() {
        val handoff = MainHandoff<String>(abandonOnTimeout = true)
        assertEquals(MainHandoff.Outcome.TimedOut, handoff.await(1))
        val ran = AtomicInteger()
        handoff.run({ true }) { ran.incrementAndGet(); "late" }
        assertEquals(0, ran.get())
    }

    @Test fun unabandonedWorkStillRunsAfterTheWaiterGaveUp() {
        val handoff = MainHandoff<String>(abandonOnTimeout = false)
        assertEquals(MainHandoff.Outcome.TimedOut, handoff.await(1))
        val ran = AtomicInteger()
        handoff.run({ true }) { ran.incrementAndGet(); "late" }
        assertEquals(1, ran.get())
    }

    @Test fun workAlreadyRunningAtTheDeadlineIsWaitedForNotReportedAsTimedOut() {
        val handoff = MainHandoff<String>(abandonOnTimeout = true)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val main = thread {
            handoff.run({ true }) {
                started.countDown()
                release.await()
                "accepted"
            }
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val seen = AtomicReference<MainHandoff.Outcome<String>>()
        val waiter = thread { seen.set(handoff.await(1)) }
        Thread.sleep(50)
        release.countDown()
        waiter.join(5_000); main.join(5_000)
        assertEquals(MainHandoff.Outcome.Done("accepted"), seen.get())
    }

    @Test fun onlyAnAdoptionAtOrPastHalfASecondIsSlow() {
        assertFalse(mainAdoptionSlow(2))
        assertFalse(mainAdoptionSlow(499))
        assertTrue(mainAdoptionSlow(500))
        assertTrue(mainAdoptionSlow(MAIN_ADOPTION_TIMEOUT_MS))
    }

    @Test fun adoptionBudgetOutlastsATransientStallButEndsInsideThePhonesWait() {
        // The phone's `loadAccepted` wait; the receiver has no view of the sender's constant.
        val phoneAcceptanceBudgetMs = 10_000L
        assertTrue(MAIN_ADOPTION_TIMEOUT_MS >= 3_000L)
        assertTrue(MAIN_ADOPTION_TIMEOUT_MS * 2 <= phoneAcceptanceBudgetMs)
        assertTrue(SLOW_MAIN_ADOPTION_MS < MAIN_ADOPTION_TIMEOUT_MS)
    }
}
