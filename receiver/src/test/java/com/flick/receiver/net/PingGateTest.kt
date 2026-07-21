package com.flick.receiver.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PingGateTest {
    @Test fun boundsPingWorkAndRecoversInNextWindow() {
        var now=0L; val gate=PingGate { now }
        repeat(PingGate.MAX_PINGS) { assertTrue(gate.tryAcquire()) }
        assertFalse(gate.tryAcquire())
        now += PingGate.WINDOW_MS
        assertTrue(gate.tryAcquire())
    }
}
