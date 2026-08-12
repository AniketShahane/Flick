package com.flick.sender.media

import org.junit.Assert.assertEquals
import org.junit.Test

/** All eight inputs. The BLOCKED arm is the one the locked empty state had no name for. */
class MediaPermissionStateTest {

    @Test
    fun `a granted permission draws no locked state at all`() {
        for (rationale in listOf(false, true)) {
            for (requested in listOf(false, true)) {
                assertEquals(
                    "rationale=$rationale requested=$requested",
                    MediaPermissionState.UNREQUESTED,
                    mediaPermissionState(granted = true, showRationale = rationale, requested = requested),
                )
            }
        }
    }

    @Test
    fun `a rationale means the prompt can still be raised`() {
        assertEquals(
            MediaPermissionState.DENIED,
            mediaPermissionState(granted = false, showRationale = true, requested = true),
        )
        assertEquals(
            MediaPermissionState.DENIED,
            mediaPermissionState(granted = false, showRationale = true, requested = false),
        )
    }

    // The platform reports no rationale both before the first prompt and after a second
    // refusal; the latch is the only thing separating them.
    @Test
    fun `no rationale after a request is a permanent block`() {
        assertEquals(
            MediaPermissionState.BLOCKED,
            mediaPermissionState(granted = false, showRationale = false, requested = true),
        )
    }

    @Test
    fun `no rationale before any request is simply unasked`() {
        assertEquals(
            MediaPermissionState.UNREQUESTED,
            mediaPermissionState(granted = false, showRationale = false, requested = false),
        )
    }
}
