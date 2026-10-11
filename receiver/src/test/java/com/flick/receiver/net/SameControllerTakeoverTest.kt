package com.flick.receiver.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * A phone whose own Wi-Fi blipped re-dials at once, while this TV still holds its old
 * socket half-open. That phone must get its film back; every other phone must still be
 * told the TV is busy.
 */
class SameControllerTakeoverTest {

    @Test fun theSamePhoneTakesItsOwnCastOver() {
        assertEquals(LeaseAdmission.TAKEOVER, leaseAdmission(resumeRevoked = false, busy = true, sameController = true))
    }

    @Test fun anotherPhoneIsStillBusy() {
        assertEquals(LeaseAdmission.BUSY, leaseAdmission(resumeRevoked = false, busy = true, sameController = false))
    }

    /** An idle TV installs as it always did; the same key changes nothing there. */
    @Test fun anIdleLeaseIsAnOrdinaryInstallForAnyPhone() {
        assertEquals(LeaseAdmission.INSTALL, leaseAdmission(resumeRevoked = false, busy = false, sameController = true))
        assertEquals(LeaseAdmission.INSTALL, leaseAdmission(resumeRevoked = false, busy = false, sameController = false))
    }

    /** A Forget outranks the takeover exactly as it outranks busy. */
    @Test fun aForgottenPhoneNeverTakesOver() {
        assertEquals(LeaseAdmission.FORGOTTEN, leaseAdmission(resumeRevoked = true, busy = true, sameController = true))
    }

    @Test fun theCastMovesWithTheLease() {
        val owner = ControlOwnership()
        val old = Any(); val new = Any()
        owner.adoptIdleConnection(old, 1L)
        owner.adoptCast(old, 1L, "A")

        assertTrue(owner.takeOverController(new, 2L).displaced === old)

        assertTrue(owner.isCurrent(new, 2L, "A"))
        assertEquals("A", owner.currentCast(new, 2L))
        assertEquals(ControlOwnership.CastAdoption.DUPLICATE, owner.adoptCast(new, 2L, "A"))
    }

    /** The displaced socket's close and late commands must not reach the moved cast. */
    @Test fun theDisplacedSocketCanNoLongerTouchTheCast() {
        val owner = ControlOwnership()
        val old = Any(); val new = Any()
        owner.adoptIdleConnection(old, 1L)
        owner.adoptCast(old, 1L, "A")
        owner.takeOverController(new, 2L)

        assertFalse(owner.clearCast(old, 1L, "A"))
        assertFalse(owner.release(old, 1L))
        assertNull(owner.currentCast(old, 1L))
        assertEquals(ControlOwnership.CastAdoption.STALE_LEASE, owner.adoptCast(old, 1L, "B"))
        assertTrue(owner.isCurrent(new, 2L, "A"))
    }

    /** A takeover leaves the TV exactly as busy as before, for everyone else. */
    @Test fun theTvStaysBusyThroughATakeover() {
        val owner = ControlOwnership()
        val old = Any(); val new = Any()
        owner.adoptIdleConnection(old, 1L)
        owner.adoptCast(old, 1L, "A")
        owner.takeOverController(new, 2L)

        assertTrue(owner.isBusy())
        assertNull(owner.adoptIdleConnection(Any(), 3L))
    }

    /**
     * The phone reads the frame after `resumed` as its busy disposition, so a socket
     * that took a cast over says nothing of its own until the phone's first
     * `loadMedia` has spent the inherited cast.
     */
    @Test fun aTakeoverSocketIsSilentUntilItsFirstLoadMedia() {
        val owner = ControlOwnership()
        val old = Any(); val new = Any()
        owner.adoptIdleConnection(old, 1L)
        owner.adoptCast(old, 1L, "A")
        owner.takeOverController(new, 2L)
        val inherited = AtomicReference<String?>(owner.currentCast(new, 2L))

        assertEquals("A", inherited.get())
        assertFalse(speaksUnprompted(inherited.get()))

        inherited.getAndSet(null)
        assertTrue(speaksUnprompted(inherited.get()))
    }

    @Test fun anOrdinaryInstallSpeaksAtOnce() {
        assertTrue(speaksUnprompted(null))
    }
}
