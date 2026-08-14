package com.flick.sender.net

import com.flick.sender.model.CastErrorKind
import com.flick.sender.model.CastFailure
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which routes a re-handshake nobody asked for may run under.
 *
 * The name refresh is cued by an advertisement, so its timing belongs to a LAN this phone
 * does not control — and it reaches the pairing gate like every deliberate dial does. That
 * makes it the one background caller able to end things it knows nothing about.
 */
class NameRefreshRouteTest {

    private val failure = Route.Failure(
        CastErrorKind.UNREACHABLE,
        CastFailure(code = "control_no_route", retryable = true),
    )

    /**
     * The regression. The error face is where the block-wait window lives: a record
     * advertising a renamed TV arrives while the block runs — multicast crosses it, which is
     * the measured half of the fault — and a refresh let through here stands that window
     * down milliseconds after it armed, then dials into the block and fails silently by
     * design, re-arming nothing. The viewer is left with a face that has stopped promising
     * anything and a film they must restart by hand.
     */
    @Test fun theFaceTheBlockWindowLivesBehindIsNeverIdle() {
        assertFalse(routeIdleForNameRefresh(failure))
    }

    /** A dial nobody asked for arrives on the Connect screen as a pairing that is. */
    @Test fun theScreensThatReadTheControlLinkAreNeverIdle() {
        assertFalse(routeIdleForNameRefresh(Route.Connect))
        assertFalse(routeIdleForNameRefresh(Route.Connecting))
    }

    /**
     * And the rest still refresh, or a TV renamed on the TV keeps the old name on every
     * surface for the life of the process.
     *
     * Route.Detail is absent on purpose: it carries a MediaItem, and android.net.Uri cannot
     * be instantiated on the JVM.
     */
    @Test fun theRoutesWithNothingInFlightStillRefresh() {
        assertTrue(routeIdleForNameRefresh(Route.Library))
        assertTrue(routeIdleForNameRefresh(Route.Settings))
        assertTrue(routeIdleForNameRefresh(Route.NowPlaying))
    }
}
