package com.flick.receiver.ui.screens

import com.flick.receiver.player.DiagnosticsSnapshot
import com.flick.receiver.ui.theme.FlickColor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The dev HUD is where a tuner goes to find a bad link. Its NET row had been
 * hardcoded to [FlickColor.Live], so an unmeasured throughput, a 2.4 GHz radio and
 * a healthy 5 GHz link all reported as healthy. Hardcoding the tint again fails
 * here.
 */
class MetricsOverlayTintTest {

    private fun link(bitrateBps: Long, band: String?, rssiDbm: Int) =
        DiagnosticsSnapshot.EMPTY.copy(
            bitrateEstimateBps = bitrateBps,
            wifiBand = band,
            wifiRssiDbm = rssiDbm,
        )

    @Test fun nothingMeasuredMakesNoHealthClaim() {
        assertEquals(FlickColor.OnChrome, netTint(link(0L, "5 GHz", -40)))
        assertEquals(FlickColor.OnChrome, netTint(link(0L, null, 0)))
    }

    @Test fun theBandThatCannotCarry4kIsFlaggedAtAnySignalStrength() {
        assertEquals(FlickColor.Caution, netTint(link(30_000_000L, "2.4 GHz", -35)))
        assertEquals(FlickColor.Caution, netTint(link(30_000_000L, "2.4 GHz", -80)))
    }

    @Test fun aWeakLinkIsFlaggedBeforeTheFilmStallsOnIt() {
        assertEquals(FlickColor.Caution, netTint(link(30_000_000L, "5 GHz", -82)))
        assertEquals(FlickColor.Caution, netTint(link(30_000_000L, "5 GHz", -75)))
        assertEquals(FlickColor.Live, netTint(link(30_000_000L, "5 GHz", -74)))
    }

    @Test fun anUnreadableRssiIsNotAWeakOne() {
        // 0 dBm is the "unavailable" value, and Ethernet reports no band at all;
        // neither may be read as the bottom of the signal scale.
        assertEquals(FlickColor.Live, netTint(link(30_000_000L, "5 GHz", 0)))
        assertEquals(FlickColor.Live, netTint(link(30_000_000L, null, 0)))
    }
}
