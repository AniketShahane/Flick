package com.flick.receiver.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlOwnershipTest {
    @Test fun staleLeaseCannotClearSupersedingIdleController() {
        val owner = ControlOwnership()
        val first = Any(); val second = Any()
        assertNull(owner.adoptIdleConnection(first, 1L)?.displaced)
        assertTrue(owner.adoptIdleConnection(second, 2L)?.displaced === first)
        assertFalse(owner.release(first, 1L))
        assertTrue(owner.adoptCast(second, 2L, "B") == ControlOwnership.CastAdoption.NEW)
        assertFalse(owner.isCurrent(first, 1L, "B"))
        assertTrue(owner.isCurrent(second, 2L, "B"))
    }

    @Test fun activeCastMakesSecondAuthenticationBusyWithoutDisplacement() {
        val owner = ControlOwnership(); val first=Any(); val second=Any()
        owner.adoptIdleConnection(first, 1L)
        owner.adoptCast(first, 1L, "A")
        assertNull(owner.adoptIdleConnection(second, 2L))
        assertTrue(owner.isCurrent(first, 1L, "A"))
    }

    @Test fun terminalStopClearsOnlyItsCurrentCastAndIsIdempotent() {
        val owner = ControlOwnership(); val socket = Any()
        owner.adoptIdleConnection(socket, 7L)
        owner.adoptCast(socket, 7L, "cast")
        assertTrue(owner.clearCast(socket, 7L, "cast"))
        assertFalse(owner.clearCast(socket, 7L, "cast"))
        assertTrue(owner.adoptIdleConnection(Any(), 8L) != null)
    }
}
