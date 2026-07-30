package com.flick.receiver.util

import com.flick.receiver.PlayerSurfaceMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the caller is expected to read a surface mode into the policy's two inputs. */
private fun keepAwakeFor(mode: PlayerSurfaceMode): Boolean = keepScreenOnWhilePresenting(
    presentingVideo = mode == PlayerSurfaceMode.VisiblePlayback,
    castHandshakeInFlight = mode == PlayerSurfaceMode.CoveredConnecting,
)

class ScreenWakePolicyTest {

    @Test fun theFilmAndTheHandshakeHoldThePanelAwakeAndNothingElseDoes() {
        assertTrue(keepScreenOnWhilePresenting(presentingVideo = true, castHandshakeInFlight = false))
        assertTrue(keepScreenOnWhilePresenting(presentingVideo = false, castHandshakeInFlight = true))
        assertTrue(keepScreenOnWhilePresenting(presentingVideo = true, castHandshakeInFlight = true))
        assertFalse(keepScreenOnWhilePresenting(presentingVideo = false, castHandshakeInFlight = false))
    }

    @Test fun theTwoLiveSurfaceModesHoldItAwakeAndTheRestingOneReleases() {
        assertTrue(keepAwakeFor(PlayerSurfaceMode.VisiblePlayback))
        // Bounded by the 18 s startup deadline, and a screensaver landing between
        // "connecting" and the first frame would read as the cast having failed.
        assertTrue(keepAwakeFor(PlayerSurfaceMode.CoveredConnecting))
        // Idle, pairing, settings and the failure card all sit here — hours at a time
        // between casts, and none of them may deny an OLED panel its dimming.
        assertFalse(keepAwakeFor(PlayerSurfaceMode.Hidden))
    }

    @Test fun everySurfaceModeIsAccountedFor() {
        // A mode added later must be classified deliberately rather than inheriting
        // whichever answer the enum ordering happens to give it.
        assertTrue(PlayerSurfaceMode.entries.size == 3)
    }
}
