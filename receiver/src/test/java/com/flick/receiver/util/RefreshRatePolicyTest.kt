package com.flick.receiver.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hint must be a function of the current stage, never a latch.
 *
 * The regression this guards is measured, not hypothetical: a stale 24 Hz hint
 * left over from a finished film pinned the whole Compose UI — pairing, idle,
 * settings — to a 41.67 ms vsync period, which is what the viewer reported as
 * choppy animation on a screen with no video on it at all.
 */
class RefreshRatePolicyTest {

    @Test fun theReleaseSentinelIsThePlatformDefault() {
        assertEquals(0f, SYSTEM_DEFAULT_REFRESH_RATE, 0f)
    }

    @Test fun aPresentingFilmPinsItsOwnCadence() {
        assertEquals(
            23.976f,
            preferredWindowRefreshRate(presentingVideo = true, contentFrameRate = 23.976f),
            0f,
        )
        assertEquals(
            50f,
            preferredWindowRefreshRate(presentingVideo = true, contentFrameRate = 50f),
            0f,
        )
    }

    @Test fun noVideoReleasesTheHint() {
        assertEquals(
            SYSTEM_DEFAULT_REFRESH_RATE,
            preferredWindowRefreshRate(presentingVideo = false, contentFrameRate = 0f),
            0f,
        )
    }

    @Test fun aStaleFrameRateAfterPlaybackStillReleases() {
        // The diagnostics snapshot is sampled at ~2 Hz and can still carry the
        // finished film's frame rate for a tick after the surface is gone.
        assertEquals(
            SYSTEM_DEFAULT_REFRESH_RATE,
            preferredWindowRefreshRate(presentingVideo = false, contentFrameRate = 23.976f),
            0f,
        )
    }

    @Test fun aFrameRateNotYetKnownReleases() {
        assertEquals(
            SYSTEM_DEFAULT_REFRESH_RATE,
            preferredWindowRefreshRate(presentingVideo = true, contentFrameRate = 0f),
            0f,
        )
    }

    @Test fun aCastHandshakeDefersTheRelease() {
        // Active(24) -> Checking/Preparing -> Active(24) is one cast replacing
        // another, or a subtitle attached mid-watch. The hint must not be dropped
        // and re-taken across it: that is two visible HDMI resyncs for a cadence
        // that never changed.
        assertEquals(
            CAST_HANDSHAKE_SETTLE_MS,
            refreshRateHintDelayMs(
                requestedRate = SYSTEM_DEFAULT_REFRESH_RATE,
                castHandshakeInFlight = true,
            ),
        )
    }

    @Test fun playbackEndingReleasesImmediately() {
        // The measured complaint: idle and pairing must get the panel's own rate
        // back the moment there is no film, not after a settle. Only a stop straight
        // off a presenting film waits, and only for the handshake's settle.
        assertEquals(
            0L,
            refreshRateHintDelayMs(
                requestedRate = SYSTEM_DEFAULT_REFRESH_RATE,
                castHandshakeInFlight = false,
            ),
        )
        assertEquals(
            0L,
            refreshRateHintDelayMs(
                requestedRate = SYSTEM_DEFAULT_REFRESH_RATE,
                castHandshakeInFlight = false,
                filmJustStopped = false,
            ),
        )
    }

    @Test fun aStopBetweenTwoFilmsDefersTheRelease() {
        // The phone re-casts as stop then loadMedia, so Active -> None -> Checking.
        // Released at the None, the panel resyncs there and again at the re-pin.
        assertEquals(
            CAST_HANDSHAKE_SETTLE_MS,
            refreshRateHintDelayMs(
                requestedRate = SYSTEM_DEFAULT_REFRESH_RATE,
                castHandshakeInFlight = false,
                filmJustStopped = true,
            ),
        )
    }

    @Test fun aStopNeverDefersAPin() {
        assertEquals(
            0L,
            refreshRateHintDelayMs(
                requestedRate = 23.976f,
                castHandshakeInFlight = false,
                filmJustStopped = true,
            ),
        )
    }

    @Test fun aStopSettlesNoLongerThanAHandshake() {
        // A stop with no cast behind it is the idle screen at a film's cadence for
        // the whole settle; it may not outlast the handshake's.
        assertEquals(
            refreshRateHintDelayMs(SYSTEM_DEFAULT_REFRESH_RATE, castHandshakeInFlight = true),
            refreshRateHintDelayMs(SYSTEM_DEFAULT_REFRESH_RATE, castHandshakeInFlight = false, filmJustStopped = true),
        )
    }

    @Test fun pinningIsNeverDeferred() {
        assertEquals(
            0L,
            refreshRateHintDelayMs(requestedRate = 23.976f, castHandshakeInFlight = true),
        )
        assertEquals(
            0L,
            refreshRateHintDelayMs(requestedRate = 23.976f, castHandshakeInFlight = false),
        )
    }

    @Test fun theSettleStaysFarInsideTheStartupDeadline() {
        // A stalled handshake is only failed at the session's 18 s adoption
        // deadline. Holding a finished film's cadence for anything like that long
        // would be the latch this policy exists to prevent, so the settle covers a
        // LAN probe-and-prepare and nothing more.
        assertTrue(CAST_HANDSHAKE_SETTLE_MS in 1L..5_000L)
    }

    @Test fun nonsenseFrameRatesRelease() {
        assertEquals(
            SYSTEM_DEFAULT_REFRESH_RATE,
            preferredWindowRefreshRate(presentingVideo = true, contentFrameRate = Float.NaN),
            0f,
        )
        assertEquals(
            SYSTEM_DEFAULT_REFRESH_RATE,
            preferredWindowRefreshRate(
                presentingVideo = true,
                contentFrameRate = Float.POSITIVE_INFINITY,
            ),
            0f,
        )
        assertEquals(
            SYSTEM_DEFAULT_REFRESH_RATE,
            preferredWindowRefreshRate(presentingVideo = true, contentFrameRate = -1f),
            0f,
        )
    }
}
