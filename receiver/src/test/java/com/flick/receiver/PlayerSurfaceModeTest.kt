package com.flick.receiver

import com.flick.receiver.net.CastFailureCode
import com.flick.receiver.session.MediaStage
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerSurfaceModeTest {
    @Test fun preparingKeepsTheSurfaceBoundBehindConnectingButActiveRevealsIt() {
        assertEquals(PlayerSurfaceMode.CoveredConnecting, playerSurfaceMode(MediaStage.Checking("cast", 1L)))
        assertEquals(PlayerSurfaceMode.CoveredConnecting, playerSurfaceMode(MediaStage.Preparing("cast", 1L)))
        assertEquals(PlayerSurfaceMode.VisiblePlayback, playerSurfaceMode(MediaStage.Active("cast", 1L)))
        assertEquals(PlayerSurfaceMode.Hidden, playerSurfaceMode(MediaStage.Error("cast", CastFailureCode.UNKNOWN, 1L)))
    }
}
