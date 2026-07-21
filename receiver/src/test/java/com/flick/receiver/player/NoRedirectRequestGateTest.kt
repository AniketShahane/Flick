package com.flick.receiver.player

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class NoRedirectRequestGateTest {
    @Test fun everyRedirectIsRejectedBeforeASecondRequest() {
        for (status in listOf(301, 302, 303, 307, 308)) {
            val gate = NoRedirectRequestGate()
            try {
                gate.verifyResponse(status)
                fail("$status must be rejected")
            } catch (_: RedirectRejectedException) {
                assertEquals("redirect $status must not trigger a follow-up request", 1, gate.requestCount)
            }
        }
    }
}
