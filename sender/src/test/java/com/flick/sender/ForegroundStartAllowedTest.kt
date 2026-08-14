package com.flick.sender

import android.app.ActivityManager.RunningAppProcessInfo
import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether this process may start its foreground service right now.
 *
 * The window that waits a router block out dials minutes after a cast ended, when the app
 * may well be in someone's pocket — and from API 31 the platform answers a background
 * foreground-service start with a throw that kills the process rather than the cast. The
 * `deliverToRunningService` catch already exists because that happened on a real phone; this
 * is the same fault on the start path, asked before it is risked.
 */
class ForegroundStartAllowedTest {

    /** Before API 31 there was no restriction to answer, and no throw to survive. */
    @Test fun theRestrictionOnlyExistsFromApi31() {
        assertTrue(
            foregroundStartAllowed(
                Build.VERSION_CODES.R,
                RunningAppProcessInfo.IMPORTANCE_CACHED,
            ),
        )
    }

    @Test fun aVisibleAppMayStartServing() {
        assertTrue(
            foregroundStartAllowed(
                Build.VERSION_CODES.S,
                RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
            ),
        )
        assertTrue(
            foregroundStartAllowed(
                Build.VERSION_CODES.S,
                RunningAppProcessInfo.IMPORTANCE_VISIBLE,
            ),
        )
    }

    /** A cast already running holds one, which is why an ordinary re-target never asks. */
    @Test fun aRunningForegroundServiceMayStartAnother() {
        assertTrue(
            foregroundStartAllowed(
                Build.VERSION_CODES.S,
                RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE,
            ),
        )
    }

    /** The state the window spends most of its twenty minutes in. */
    @Test fun aBackgroundedAppMayNot() {
        assertFalse(
            foregroundStartAllowed(
                Build.VERSION_CODES.TIRAMISU,
                RunningAppProcessInfo.IMPORTANCE_SERVICE,
            ),
        )
        assertFalse(
            foregroundStartAllowed(
                Build.VERSION_CODES.TIRAMISU,
                RunningAppProcessInfo.IMPORTANCE_CACHED,
            ),
        )
    }
}
