package com.flick.receiver.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstFrameGateTest {
    @Test fun delayedAFrameCannotConsumeArmedBIdentity() {
        val gate = FirstFrameGate()
        gate.arm("flick:A:1")
        gate.arm("flick:B:2")

        assertFalse(gate.consumeIfMatches("flick:A:1"))
        assertTrue(gate.consumeIfMatches("flick:B:2"))
        assertFalse(gate.consumeIfMatches("flick:B:2"))
    }
}
