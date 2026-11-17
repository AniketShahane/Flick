package com.flick.sender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Four transfers stranded by Wi-Fi blips used to hold every slot, and the TV's next
 * request was answered 503. A stranded transfer has to give way; a live one never does.
 */
class TransferSlotsTest {

    private val cancelled = mutableListOf<Int>()

    private fun TransferSlots.take(nowMs: Long, id: Int): TransferSlots.Admission.Admitted =
        acquire(nowMs) { cancelled += id } as TransferSlots.Admission.Admitted

    @Test fun admitsUpToCapacityWithoutEvicting() {
        val slots = TransferSlots(4)
        for (id in 1..4) assertNull(slots.take(0L, id).evictedIdleMs)
        assertEquals(4, slots.active)
        assertTrue(cancelled.isEmpty())
    }

    @Test fun aFullPoolOfLiveTransfersRefuses() {
        val slots = TransferSlots(4)
        val leases = (1..4).map { slots.take(0L, it).lease }
        leases.forEach { it.progress(1_000L) }
        val refused = slots.acquire(1_000L + TransferSlots.EVICT_IDLE_MS - 1) { cancelled += 99 }
        assertTrue(refused is TransferSlots.Admission.Refused)
        assertEquals(4, (refused as TransferSlots.Admission.Refused).active)
        assertTrue(cancelled.isEmpty())
    }

    @Test fun theOldestStillTransferGivesWayAtTheThreshold() {
        val slots = TransferSlots(4)
        val leases = (1..4).map { slots.take(0L, it).lease }
        leases[0].progress(3_000L)
        leases[1].progress(1_000L) // oldest progress
        leases[2].progress(2_000L)
        leases[3].progress(4_000L)
        val admitted = slots.take(1_000L + TransferSlots.EVICT_IDLE_MS, 5)
        assertEquals(TransferSlots.EVICT_IDLE_MS, admitted.evictedIdleMs)
        assertEquals(listOf(2), cancelled)
        assertTrue(leases[1].evicted)
        assertFalse(leases[0].evicted)
        assertEquals(4, admitted.active)
        assertEquals(4, slots.active)
    }

    /** The evicted thread may unwind long after; its release must not free a slot it no longer holds. */
    @Test fun anEvictedTransfersLateReleaseIsANoOp() {
        val slots = TransferSlots(2)
        val stale = slots.take(0L, 1).lease
        val live = slots.take(0L, 2).lease
        live.progress(10_000L)
        val newcomer = slots.take(10_000L, 3).lease
        assertTrue(stale.evicted)
        slots.release(stale)
        assertEquals(2, slots.active)
        assertTrue(slots.acquire(10_000L) {} is TransferSlots.Admission.Refused)
        slots.release(newcomer)
        assertEquals(1, slots.active)
        assertSame(TransferSlots.Admission.Admitted::class.java, slots.acquire(10_000L) {}.javaClass)
    }

    @Test fun releaseFreesTheSlotOnce() {
        val slots = TransferSlots(1)
        val lease = slots.take(0L, 1).lease
        slots.release(lease)
        slots.release(lease)
        assertEquals(0, slots.active)
        slots.take(0L, 2)
        assertEquals(1, slots.active)
    }

    @Test fun aThrowingCancelStillAdmits() {
        val slots = TransferSlots(1)
        slots.acquire(0L) { throw IllegalStateException("boom") }
        val admission = slots.acquire(TransferSlots.EVICT_IDLE_MS) {}
        assertTrue(admission is TransferSlots.Admission.Admitted)
    }
}
