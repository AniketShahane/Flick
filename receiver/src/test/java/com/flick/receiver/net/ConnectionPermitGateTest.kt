package com.flick.receiver.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionPermitGateTest {
    @Test fun unauthenticatedConnectionsAreCappedWithoutBlocking() {
        val gate = ConnectionPermitGate(2)
        assertTrue(gate.tryAcquire())
        assertTrue(gate.tryAcquire())
        assertFalse(gate.tryAcquire())
        gate.release()
        assertTrue(gate.tryAcquire())
    }
}
