package com.flick.sender.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastTransactionTest {
    @Test fun lateCastACallbackCannotCommitCastB() {
        val gate = CastGenerationGate()
        val a = gate.begin("MDEyMzQ1Njc4OWFiY2RlZg")
        val b = gate.begin("QUJDREVGR0hJSktMTU5PUA")
        assertFalse(gate.isCurrent("MDEyMzQ1Njc4OWFiY2RlZg", a))
        assertTrue(gate.isCurrent("QUJDREVGR0hJSktMTU5PUA", b))
        assertFalse(gate.invalidate("MDEyMzQ1Njc4OWFiY2RlZg"))
        assertTrue(gate.isCurrent("QUJDREVGR0hJSktMTU5PUA", b))
    }
}
