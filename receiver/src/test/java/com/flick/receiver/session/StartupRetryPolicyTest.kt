package com.flick.receiver.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StartupRetryPolicyTest {
    @Test fun onlyTwoShortTransientStartupRetriesFitTheDeadline() {
        assertEquals(250L, StartupRetryPolicy.delayForRetry(0, true, 0L, 18_000L))
        assertEquals(500L, StartupRetryPolicy.delayForRetry(1, true, 0L, 18_000L))
        assertNull(StartupRetryPolicy.delayForRetry(2, true, 0L, 18_000L))
        assertNull(StartupRetryPolicy.delayForRetry(0, false, 0L, 18_000L))
        assertNull(StartupRetryPolicy.delayForRetry(1, true, 17_700L, 18_000L))
    }
}
