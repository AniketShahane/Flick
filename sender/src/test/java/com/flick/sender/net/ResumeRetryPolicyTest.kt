package com.flick.sender.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A resume the person is waiting on gets exactly one more sweep, and only on Wi-Fi. */
class ResumeRetryPolicyTest {

    @Test fun aFailedFirstSweepTheUserStartedIsRepeatedOnce() {
        assertTrue(ResumeRetryPolicy.sweepsAgain(sweep = 1, userDial = true, onWifi = true))
        assertFalse(ResumeRetryPolicy.sweepsAgain(sweep = 2, userDial = true, onWifi = true))
    }

    @Test fun recoveryDialsKeepTheirOwnWindows() {
        assertFalse(ResumeRetryPolicy.sweepsAgain(sweep = 1, userDial = false, onWifi = true))
    }

    @Test fun aPhoneOffWifiIsNotMadeToWait() {
        assertFalse(ResumeRetryPolicy.sweepsAgain(sweep = 1, userDial = true, onWifi = false))
    }
}
