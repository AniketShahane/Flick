package com.flick.receiver.session

import com.flick.receiver.net.CastFailureCode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalPhaseTest {
    @Test fun backgroundTerminalKeepsThePriorActivePhase() {
        assertTrue(TerminalPhase.beforeReady(MediaStage.Preparing("A", 1L)))
        assertFalse(TerminalPhase.beforeReady(MediaStage.Active("A", 1L)))
        assertTrue(TerminalPhase.beforeReady(MediaStage.Error("A", CastFailureCode.UNKNOWN, 1L)))
    }
}
