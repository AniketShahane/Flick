package com.flick.sender.ui.components

import com.flick.sender.model.TvAvailability
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a device row on the Connect screen claims about its TV.
 *
 * The receiver keeps its control socket open when Flick leaves the TV's screen, so a live
 * link on its own is not a TV ready to cast to. The row used to let the link overrule the
 * TV's own `sleeping` advertisement and went on calling it Connected.
 */
class DeviceRowFaceTest {

    @Test fun aConnectedTvAdvertisingSleepIsClosedNotConnected() {
        assertEquals(DeviceRowFace.CLOSED_ON_TV, deviceRowFace(TvAvailability.SLEEPING, connected = true))
    }

    @Test fun aConnectedTvThatIsAwakeOrUnadvertisedIsLive() {
        assertEquals(DeviceRowFace.LIVE, deviceRowFace(TvAvailability.READY, connected = true))
        // The row drawn from the pairing record when no advertisement accounts for the link.
        assertEquals(DeviceRowFace.LIVE, deviceRowFace(TvAvailability.UNKNOWN, connected = true))
    }

    @Test fun rowsWithoutALinkAreUnchanged() {
        assertEquals(DeviceRowFace.ASLEEP, deviceRowFace(TvAvailability.SLEEPING, connected = false))
        assertEquals(DeviceRowFace.AVAILABLE, deviceRowFace(TvAvailability.READY, connected = false))
        assertEquals(DeviceRowFace.AVAILABLE, deviceRowFace(TvAvailability.UNKNOWN, connected = false))
    }
}
