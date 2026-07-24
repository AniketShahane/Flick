package com.flick.receiver

import com.flick.receiver.net.CastFailureCode
import com.flick.receiver.session.MediaStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSurfaceModeTest {
    @Test fun preparingKeepsTheSurfaceBoundBehindConnectingButActiveRevealsIt() {
        assertEquals(PlayerSurfaceMode.CoveredConnecting, playerSurfaceMode(MediaStage.Checking("cast", 1L)))
        assertEquals(PlayerSurfaceMode.CoveredConnecting, playerSurfaceMode(MediaStage.Preparing("cast", 1L)))
        assertEquals(PlayerSurfaceMode.VisiblePlayback, playerSurfaceMode(MediaStage.Active("cast", 1L)))
        assertEquals(PlayerSurfaceMode.Hidden, playerSurfaceMode(MediaStage.Error("cast", CastFailureCode.UNKNOWN, 1L)))
    }

    @Test fun rootCatcherHoldsFocusOnEveryStageThatDrawsNoFocusableOfItsOwn() {
        // The handshake is informational and the remote policy declines to consume
        // keys before playback is active: with no catcher there is no focus owner.
        assertTrue(rootFocusCatcherEnabled(MediaStage.Checking("cast", 1L), chromeVisible = true))
        assertTrue(rootFocusCatcherEnabled(MediaStage.Preparing("cast", 1L), chromeVisible = false))
        assertTrue(rootFocusCatcherEnabled(MediaStage.Active("cast", 1L), chromeVisible = false))
        // Visible chrome owns focus; idle/pair/settings and error carry their own.
        assertFalse(rootFocusCatcherEnabled(MediaStage.Active("cast", 1L), chromeVisible = true))
        assertFalse(rootFocusCatcherEnabled(MediaStage.None, chromeVisible = false))
        assertFalse(
            rootFocusCatcherEnabled(
                MediaStage.Error("cast", CastFailureCode.UNKNOWN, 1L),
                chromeVisible = false,
            ),
        )
    }
}
