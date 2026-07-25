package com.flick.sender.ui.screens

import com.flick.sender.model.CastErrorKind
import com.flick.sender.model.CastFailure
import com.flick.sender.net.Route
import com.flick.sender.ui.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SenderShellPolicyTest {

    private val failure = Route.Failure(
        CastErrorKind.UNREACHABLE,
        CastFailure(code = "tv-unreachable", retryable = true),
    )

    // Route.Detail is absent from the Route-typed cases on purpose: it carries a
    // MediaItem, and android.net.Uri cannot be instantiated on the JVM.
    @Test fun everyRouteMapsToExactlyOneShellDestination() {
        assertEquals(ShellDestination.CONNECT, SenderShellPolicy.destinationOf(Route.Connect))
        assertEquals(ShellDestination.LIBRARY, SenderShellPolicy.destinationOf(Route.Library))
        assertEquals(ShellDestination.CONNECTING, SenderShellPolicy.destinationOf(Route.Connecting))
        assertEquals(ShellDestination.NOW_PLAYING, SenderShellPolicy.destinationOf(Route.NowPlaying))
        assertEquals(ShellDestination.FAILURE, SenderShellPolicy.destinationOf(failure))
    }

    @Test fun theNavFloatsOverBrowsingSurfacesAndOverNothingElse() {
        assertTrue(SenderShellPolicy.navVisible(ShellDestination.LIBRARY))
        assertTrue(SenderShellPolicy.navVisible(ShellDestination.CONNECT))
        assertFalse(SenderShellPolicy.navVisible(ShellDestination.DETAIL))
        assertFalse(SenderShellPolicy.navVisible(ShellDestination.CONNECTING))
        assertFalse(SenderShellPolicy.navVisible(ShellDestination.NOW_PLAYING))
        assertFalse(SenderShellPolicy.navVisible(ShellDestination.FAILURE))

        assertTrue(SenderShellPolicy.navVisible(Route.Library))
        assertTrue(SenderShellPolicy.navVisible(Route.Connect))
        assertFalse(SenderShellPolicy.navVisible(Route.Connecting))
        assertFalse(SenderShellPolicy.navVisible(Route.NowPlaying))
        assertFalse(SenderShellPolicy.navVisible(failure))
    }

    @Test fun detailSelectsLibraryAndTheCastFacesSelectDevices() {
        assertEquals(NavTab.LIBRARY, SenderShellPolicy.selectedTab(ShellDestination.LIBRARY))
        assertEquals(NavTab.LIBRARY, SenderShellPolicy.selectedTab(ShellDestination.DETAIL))
        assertEquals(NavTab.REMOTE, SenderShellPolicy.selectedTab(ShellDestination.NOW_PLAYING))
        assertEquals(NavTab.DEVICES, SenderShellPolicy.selectedTab(ShellDestination.CONNECT))
        assertEquals(NavTab.DEVICES, SenderShellPolicy.selectedTab(ShellDestination.CONNECTING))
        assertEquals(NavTab.DEVICES, SenderShellPolicy.selectedTab(ShellDestination.FAILURE))

        assertEquals(NavTab.LIBRARY, SenderShellPolicy.selectedTab(Route.Library))
        assertEquals(NavTab.REMOTE, SenderShellPolicy.selectedTab(Route.NowPlaying))
        assertEquals(NavTab.DEVICES, SenderShellPolicy.selectedTab(Route.Connect))
        assertEquals(NavTab.DEVICES, SenderShellPolicy.selectedTab(Route.Connecting))
        assertEquals(NavTab.DEVICES, SenderShellPolicy.selectedTab(failure))
    }

    @Test fun remoteOnlyNavigatesForACommittedCastAndOtherwiseExplainsItself() {
        assertEquals(
            RemoteTapOutcome.NAVIGATE,
            SenderShellPolicy.remoteTapOutcome(hasConnectedTv = true, hasActiveSession = true),
        )
        assertEquals(
            RemoteTapOutcome.TOAST_PICK_A_VIDEO,
            SenderShellPolicy.remoteTapOutcome(hasConnectedTv = true, hasActiveSession = false),
        )
        assertEquals(
            RemoteTapOutcome.TOAST_PICK_A_TV,
            SenderShellPolicy.remoteTapOutcome(hasConnectedTv = false, hasActiveSession = false),
        )
        // A live session outranks a dropped pairing record so the remote stays reachable.
        assertEquals(
            RemoteTapOutcome.NAVIGATE,
            SenderShellPolicy.remoteTapOutcome(hasConnectedTv = false, hasActiveSession = true),
        )
    }

    @Test fun onlyTheCinematicRoutesInvertTheSystemBarIcons() {
        assertTrue(SenderShellPolicy.darkBackdrop(ShellDestination.NOW_PLAYING))
        assertTrue(SenderShellPolicy.darkBackdrop(ShellDestination.CONNECTING))
        // Detail is a sheet over a scrimmed video frame, not over the pale canvas.
        assertTrue(SenderShellPolicy.darkBackdrop(ShellDestination.DETAIL))
        assertFalse(SenderShellPolicy.darkBackdrop(ShellDestination.LIBRARY))
        assertFalse(SenderShellPolicy.darkBackdrop(ShellDestination.CONNECT))
        assertFalse(SenderShellPolicy.darkBackdrop(ShellDestination.FAILURE))

        assertTrue(SenderShellPolicy.darkBackdrop(Route.NowPlaying))
        assertTrue(SenderShellPolicy.darkBackdrop(Route.Connecting))
        assertFalse(SenderShellPolicy.darkBackdrop(Route.Library))
        assertFalse(SenderShellPolicy.darkBackdrop(Route.Connect))
        assertFalse(SenderShellPolicy.darkBackdrop(failure))
    }

    @Test fun aDarkPaletteInvertsTheBarsOnEveryRouteIncludingTheLightSurfaces() {
        assertTrue(SenderShellPolicy.darkBackdrop(ShellDestination.LIBRARY, lightPalette = false))
        assertTrue(SenderShellPolicy.darkBackdrop(ShellDestination.CONNECT, lightPalette = false))
        assertTrue(SenderShellPolicy.darkBackdrop(ShellDestination.FAILURE, lightPalette = false))
        assertTrue(SenderShellPolicy.darkBackdrop(Route.Library, lightPalette = false))

        // A light palette falls back to the pure route rule.
        assertFalse(SenderShellPolicy.darkBackdrop(ShellDestination.LIBRARY, lightPalette = true))
        assertFalse(SenderShellPolicy.darkBackdrop(ShellDestination.CONNECT, lightPalette = true))
        assertFalse(SenderShellPolicy.darkBackdrop(ShellDestination.FAILURE, lightPalette = true))
        assertTrue(SenderShellPolicy.darkBackdrop(ShellDestination.NOW_PLAYING, lightPalette = true))
        assertTrue(SenderShellPolicy.darkBackdrop(ShellDestination.CONNECTING, lightPalette = true))
        assertTrue(SenderShellPolicy.darkBackdrop(ShellDestination.DETAIL, lightPalette = true))
        assertFalse(SenderShellPolicy.darkBackdrop(Route.Library, lightPalette = true))
        assertTrue(SenderShellPolicy.darkBackdrop(Route.NowPlaying, lightPalette = true))
    }

    @Test fun shellFormattersMatchTheSpecifiedNumericForms() {
        assertEquals("1:23:45", Format.timecode(5_025_000L))
        assertEquals("2:05", Format.timecode(125_000L))
        assertEquals("−1:23:45", Format.remaining(0L, 5_025_000L))
        assertEquals("−0:00", Format.remaining(5_025_000L, 5_025_000L))
        assertEquals("−0:00", Format.remaining(9_000_000L, 5_025_000L))
        assertEquals("61.4 Mb/s", Format.megabits(61_400_000L))
        assertEquals("18.4 GB", Format.bytes((18.4 * 1024 * 1024 * 1024).toLong()))
        assertEquals("—", Format.bytes(-1L))
        // A camera-roll clip is seconds long; whole-minute division would print "0m".
        assertEquals("45s", Format.durationHuman(45_000L))
        assertEquals("0s", Format.durationHuman(0L))
        assertEquals("1m", Format.durationHuman(60_000L))
        assertEquals("2h 5m", Format.durationHuman(7_500_000L))
    }
}
