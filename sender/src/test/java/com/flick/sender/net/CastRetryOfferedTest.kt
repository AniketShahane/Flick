package com.flick.sender.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastRetryOfferedTest {
    @Test
    fun aRetryableCodeWithNoCastRecordLeftIsNotOffered() {
        // The dead button: the failure said retryable, the error face showed "Try again"
        // as its only control, and the retry had nothing to re-cast.
        assertFalse(castRetryOffered(retryable = true, hasCastRecord = false))
        assertTrue(castRetryOffered(retryable = true, hasCastRecord = true))
    }

    @Test
    fun aRecordAloneNeverMakesAFatalCodeRetryable() {
        assertFalse(castRetryOffered(retryable = false, hasCastRecord = true))
        assertFalse(castRetryOffered(retryable = false, hasCastRecord = false))
    }
}
