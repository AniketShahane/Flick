package com.flick.receiver.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which outstanding main-thread work a canceller is allowed to collect.
 *
 * The defect this pins had nothing to do with what either piece of work does and
 * everything to do with where it was kept. The bounded auto-recovery and the
 * picture-turn hand-back shared one slot, so `scheduleRecovery` re-arming itself
 * — or a subtitle rollback, or a rotation re-prepare — removed whichever runnable
 * was in it. A `PlaybackException` already queued when the turn was condemned
 * therefore ran first and took the hand-back with it, and the film was left
 * latched as un-turnable with the turn still in force and nothing on the way to
 * remove it. The two are correlated in practice: a twelve-second frame drought
 * and an HTTP read timeout have the same cause.
 */
class PendingWorkTest {

    /** A Looper's ordering and nothing else: due time first, then post order. */
    private class FakeMainThread(private val obeysCancel: Boolean = true) {

        private class Entry(val work: Runnable, val dueMs: Long, val sequence: Long)

        private val queue = mutableListOf<Entry>()
        private var sequence = 0L

        fun schedule(work: Runnable, delayMs: Long) {
            queue += Entry(work, delayMs, sequence++)
            queue.sortWith(compareBy({ it.dueMs }, { it.sequence }))
        }

        fun unschedule(work: Runnable) {
            if (!obeysCancel) return
            queue.removeAll { it.work === work }
        }

        val queued: Int get() = queue.size

        fun runAll() {
            while (queue.isNotEmpty()) queue.removeAt(0).work.run()
        }
    }

    private fun slotOn(main: FakeMainThread) = PendingWork(main::schedule, main::unschedule)

    // --- The race ------------------------------------------------------------

    /**
     * The exact sequence: the watchdog condemns the turn and queues the
     * hand-back; the error that was already in flight runs first and takes the
     * transient branch, which cancels the recovery slot and schedules its own
     * re-prepare of the player still presenting the condemned turn. The hand-back
     * must survive that, run first because it was posted first with no delay, and
     * cancel the re-prepare on its way through — which is what
     * `rePrepareForRotation` does.
     */
    @Test fun anErrorQueuedBehindTheHandBackCannotCollectIt() {
        val main = FakeMainThread()
        val recovery = slotOn(main)
        val fallback = slotOn(main)
        var handedBack = false
        var rePreparedUnderTheGraph = false

        recovery.cancel()
        fallback.post {
            handedBack = true
            recovery.cancel()
        }

        recovery.post(2_000L) { rePreparedUnderTheGraph = true }
        assertTrue(fallback.isPending)

        main.runAll()
        assertTrue(handedBack)
        assertFalse(rePreparedUnderTheGraph)
    }

    @Test fun cancellingOneSlotLeavesTheOtherUntouched() {
        val main = FakeMainThread()
        val recovery = slotOn(main)
        val fallback = slotOn(main)
        var handedBack = false

        fallback.post { handedBack = true }
        recovery.post(2_000L) { }
        recovery.cancel()

        assertTrue(fallback.isPending)
        assertEquals(1, main.queued)
        main.runAll()
        assertTrue(handedBack)
    }

    // --- The slot itself ------------------------------------------------------

    @Test fun aFreshSlotHoldsNothing() {
        val main = FakeMainThread()
        assertFalse(slotOn(main).isPending)
        assertEquals(0, main.queued)
    }

    @Test fun postingReplacesWhateverTheSlotHeld() {
        val main = FakeMainThread()
        val slot = slotOn(main)
        var ran = ""
        slot.post { ran += "first" }
        slot.post { ran += "second" }
        assertEquals(1, main.queued)
        main.runAll()
        assertEquals("second", ran)
    }

    /**
     * Work that cancels the other slot, or re-posts itself, has to see the state
     * it is about to create rather than the one it replaced.
     */
    @Test fun theSlotIsClearedBeforeItsWorkRuns() {
        val main = FakeMainThread()
        val slot = slotOn(main)
        var pendingInsideAction = true
        slot.post { pendingInsideAction = slot.isPending }
        main.runAll()
        assertFalse(pendingInsideAction)
        assertFalse(slot.isPending)
    }

    @Test fun workCanRePostItself() {
        val main = FakeMainThread()
        val slot = slotOn(main)
        var runs = 0
        fun rearm() {
            slot.post(1_000L) {
                runs++
                if (runs < 3) rearm()
            }
        }
        rearm()
        main.runAll()
        assertEquals(3, runs)
        assertFalse(slot.isPending)
    }

    @Test fun cancelledWorkNeverRuns() {
        val main = FakeMainThread()
        val slot = slotOn(main)
        var ran = false
        slot.post { ran = true }
        slot.cancel()
        assertFalse(slot.isPending)
        main.runAll()
        assertFalse(ran)
    }

    /**
     * The slot is the authority on what is outstanding, not the queue: a callback
     * a queue ran after being asked to drop it must not reach the caller's action.
     */
    @Test fun aQueueThatRunsCancelledWorkAnywayIsIgnored() {
        val main = FakeMainThread(obeysCancel = false)
        val slot = slotOn(main)
        var ran = false
        slot.post { ran = true }
        slot.cancel()
        main.runAll()
        assertFalse(ran)
    }
}
