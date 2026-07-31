package com.flick.sender.ui.screens

import com.flick.sender.media.MediaAccess
import com.flick.sender.model.CastErrorKind
import com.flick.sender.model.CastFailure
import com.flick.sender.net.BackDisposition
import com.flick.sender.net.Route
import com.flick.sender.net.SenderNavigationPolicy
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
        assertEquals(ShellDestination.SETTINGS, SenderShellPolicy.destinationOf(Route.Settings))
        assertEquals(ShellDestination.CONNECTING, SenderShellPolicy.destinationOf(Route.Connecting))
        assertEquals(ShellDestination.NOW_PLAYING, SenderShellPolicy.destinationOf(Route.NowPlaying))
        assertEquals(ShellDestination.FAILURE, SenderShellPolicy.destinationOf(failure))
    }

    @Test fun theNavFloatsOverItsOwnSeatsAndOverNothingElse() {
        assertTrue(SenderShellPolicy.navVisible(ShellDestination.LIBRARY))
        assertTrue(SenderShellPolicy.navVisible(ShellDestination.CONNECT))
        assertTrue(SenderShellPolicy.navVisible(ShellDestination.SETTINGS))
        assertFalse(SenderShellPolicy.navVisible(ShellDestination.DETAIL))
        assertFalse(SenderShellPolicy.navVisible(ShellDestination.CONNECTING))
        assertFalse(SenderShellPolicy.navVisible(ShellDestination.NOW_PLAYING))
        assertFalse(SenderShellPolicy.navVisible(ShellDestination.FAILURE))

        assertTrue(SenderShellPolicy.navVisible(Route.Library))
        assertTrue(SenderShellPolicy.navVisible(Route.Connect))
        assertTrue(SenderShellPolicy.navVisible(Route.Settings))
        assertFalse(SenderShellPolicy.navVisible(Route.Connecting))
        assertFalse(SenderShellPolicy.navVisible(Route.NowPlaying))
        assertFalse(SenderShellPolicy.navVisible(failure))
    }

    // The seat order is load-bearing: FlickApp reads it back as the lateral direction the
    // route slides in, so a reorder here silently inverts the screen against the pill.
    @Test fun theNavHasExactlyThreeSeatsAndTheRemoteIsNotOneOfThem() {
        assertEquals(listOf(NavTab.LIBRARY, NavTab.DEVICES, NavTab.SETTINGS), NavTab.entries.toList())
    }

    // Settings is a peer of the library, never a launch destination — so unlike Connect
    // it has no arm that hands Back to the system.
    @Test fun backFromSettingsReturnsToTheLibraryRatherThanLeavingTheApp() {
        assertEquals(
            BackDisposition.SHOW_LIBRARY,
            SenderNavigationPolicy.backDisposition(Route.Settings, connectFromLibrary = false),
        )
        assertEquals(
            BackDisposition.SHOW_LIBRARY,
            SenderNavigationPolicy.backDisposition(Route.Settings, connectFromLibrary = true),
        )
    }

    // Every surface over the library answers LIBRARY: back out of the detail sheet or
    // the remote and the library is what the nav has to be showing again.
    @Test fun detailAndTheRemoteSelectLibraryAndTheCastFacesSelectDevices() {
        assertEquals(NavTab.LIBRARY, SenderShellPolicy.selectedTab(ShellDestination.LIBRARY))
        assertEquals(NavTab.LIBRARY, SenderShellPolicy.selectedTab(ShellDestination.DETAIL))
        assertEquals(NavTab.LIBRARY, SenderShellPolicy.selectedTab(ShellDestination.NOW_PLAYING))
        assertEquals(NavTab.DEVICES, SenderShellPolicy.selectedTab(ShellDestination.CONNECT))
        assertEquals(NavTab.DEVICES, SenderShellPolicy.selectedTab(ShellDestination.CONNECTING))
        assertEquals(NavTab.DEVICES, SenderShellPolicy.selectedTab(ShellDestination.FAILURE))
        assertEquals(NavTab.SETTINGS, SenderShellPolicy.selectedTab(ShellDestination.SETTINGS))

        assertEquals(NavTab.LIBRARY, SenderShellPolicy.selectedTab(Route.Library))
        assertEquals(NavTab.LIBRARY, SenderShellPolicy.selectedTab(Route.NowPlaying))
        assertEquals(NavTab.DEVICES, SenderShellPolicy.selectedTab(Route.Connect))
        assertEquals(NavTab.DEVICES, SenderShellPolicy.selectedTab(Route.Connecting))
        assertEquals(NavTab.DEVICES, SenderShellPolicy.selectedTab(failure))
        assertEquals(NavTab.SETTINGS, SenderShellPolicy.selectedTab(Route.Settings))
    }

    // The dock is the only door to the remote now, so it has to be on every surface the
    // nav floats over — never on one it does not.
    @Test fun theDockRidesExactlyWhereTheNavDoes() {
        ShellDestination.entries.forEach { destination ->
            assertEquals(
                SenderShellPolicy.navVisible(destination),
                SenderShellPolicy.dockVisible(destination),
            )
        }
        assertTrue(SenderShellPolicy.dockVisible(Route.Library))
        assertTrue(SenderShellPolicy.dockVisible(Route.Connect))
        assertTrue(SenderShellPolicy.dockVisible(Route.Settings))
        assertFalse(SenderShellPolicy.dockVisible(Route.NowPlaying))
        assertFalse(SenderShellPolicy.dockVisible(Route.Connecting))
        assertFalse(SenderShellPolicy.dockVisible(failure))
    }

    @Test fun theDockMorphsAcrossEveryPairThatJoinsItToTheRemoteAndNoOther() {
        assertTrue(SenderShellPolicy.dockMorph(ShellDestination.LIBRARY, ShellDestination.NOW_PLAYING))
        assertTrue(SenderShellPolicy.dockMorph(ShellDestination.CONNECT, ShellDestination.NOW_PLAYING))
        assertTrue(SenderShellPolicy.dockMorph(ShellDestination.SETTINGS, ShellDestination.NOW_PLAYING))
        // Symmetric: minimizing is the same geometry read backwards.
        assertTrue(SenderShellPolicy.dockMorph(ShellDestination.NOW_PLAYING, ShellDestination.LIBRARY))
        assertTrue(SenderShellPolicy.dockMorph(ShellDestination.NOW_PLAYING, ShellDestination.CONNECT))
        assertTrue(SenderShellPolicy.dockMorph(ShellDestination.NOW_PLAYING, ShellDestination.SETTINGS))

        // No dock on the other side, so nothing to grow out of or shrink back into.
        assertFalse(SenderShellPolicy.dockMorph(ShellDestination.CONNECTING, ShellDestination.NOW_PLAYING))
        assertFalse(SenderShellPolicy.dockMorph(ShellDestination.NOW_PLAYING, ShellDestination.FAILURE))
        assertFalse(SenderShellPolicy.dockMorph(ShellDestination.DETAIL, ShellDestination.NOW_PLAYING))
        // Neither end is the remote.
        assertFalse(SenderShellPolicy.dockMorph(ShellDestination.LIBRARY, ShellDestination.CONNECT))
        assertFalse(SenderShellPolicy.dockMorph(ShellDestination.LIBRARY, ShellDestination.DETAIL))
    }

    @Test fun onlyTheReturnOutOfDetailHoldsTheChromeBackForTheFrame() {
        assertTrue(SenderShellPolicy.heroReturn(ShellDestination.DETAIL, ShellDestination.LIBRARY))
        // Going in, the chrome is the thing leaving, and it leaves before the frame grows.
        assertFalse(SenderShellPolicy.heroReturn(ShellDestination.LIBRARY, ShellDestination.DETAIL))
        // The app's other shared-frame pair carries no chrome at either end.
        assertFalse(
            SenderShellPolicy.heroReturn(ShellDestination.CONNECTING, ShellDestination.NOW_PLAYING),
        )
        assertFalse(SenderShellPolicy.heroReturn(ShellDestination.DETAIL, ShellDestination.CONNECTING))
        assertFalse(SenderShellPolicy.heroReturn(ShellDestination.NOW_PLAYING, ShellDestination.LIBRARY))
        assertFalse(SenderShellPolicy.heroReturn(ShellDestination.FAILURE, ShellDestination.LIBRARY))
        assertFalse(SenderShellPolicy.heroReturn(ShellDestination.LIBRARY, ShellDestination.CONNECT))

        // Both arms hang off one latch in the shell, so no pair may claim both of them.
        ShellDestination.entries.forEach { initial ->
            ShellDestination.entries.forEach { target ->
                assertFalse(
                    "$initial -> $target",
                    SenderShellPolicy.heroReturn(initial, target) &&
                        SenderShellPolicy.dockMorph(initial, target),
                )
            }
        }
    }

    @Test fun onlyTheCinematicRoutesInvertTheSystemBarIcons() {
        assertTrue(SenderShellPolicy.darkBackdrop(ShellDestination.NOW_PLAYING))
        assertTrue(SenderShellPolicy.darkBackdrop(ShellDestination.CONNECTING))
        // Detail is a sheet over a scrimmed video frame, not over the pale canvas.
        assertTrue(SenderShellPolicy.darkBackdrop(ShellDestination.DETAIL))
        assertFalse(SenderShellPolicy.darkBackdrop(ShellDestination.LIBRARY))
        assertFalse(SenderShellPolicy.darkBackdrop(ShellDestination.CONNECT))
        // Settings is a pale canvas surface like the other two browsing seats.
        assertFalse(SenderShellPolicy.darkBackdrop(ShellDestination.SETTINGS))
        assertFalse(SenderShellPolicy.darkBackdrop(ShellDestination.FAILURE))

        assertTrue(SenderShellPolicy.darkBackdrop(Route.NowPlaying))
        assertTrue(SenderShellPolicy.darkBackdrop(Route.Connecting))
        assertFalse(SenderShellPolicy.darkBackdrop(Route.Library))
        assertFalse(SenderShellPolicy.darkBackdrop(Route.Connect))
        assertFalse(SenderShellPolicy.darkBackdrop(Route.Settings))
        assertFalse(SenderShellPolicy.darkBackdrop(failure))
    }

    @Test fun aDarkPaletteInvertsTheBarsOnEveryRouteIncludingTheLightSurfaces() {
        assertTrue(SenderShellPolicy.darkBackdrop(ShellDestination.LIBRARY, lightPalette = false))
        assertTrue(SenderShellPolicy.darkBackdrop(ShellDestination.CONNECT, lightPalette = false))
        assertTrue(SenderShellPolicy.darkBackdrop(ShellDestination.SETTINGS, lightPalette = false))
        assertTrue(SenderShellPolicy.darkBackdrop(ShellDestination.FAILURE, lightPalette = false))
        assertTrue(SenderShellPolicy.darkBackdrop(Route.Library, lightPalette = false))

        // A light palette falls back to the pure route rule.
        assertFalse(SenderShellPolicy.darkBackdrop(ShellDestination.LIBRARY, lightPalette = true))
        assertFalse(SenderShellPolicy.darkBackdrop(ShellDestination.CONNECT, lightPalette = true))
        assertFalse(SenderShellPolicy.darkBackdrop(ShellDestination.SETTINGS, lightPalette = true))
        assertFalse(SenderShellPolicy.darkBackdrop(ShellDestination.FAILURE, lightPalette = true))
        assertTrue(SenderShellPolicy.darkBackdrop(ShellDestination.NOW_PLAYING, lightPalette = true))
        assertTrue(SenderShellPolicy.darkBackdrop(ShellDestination.CONNECTING, lightPalette = true))
        assertTrue(SenderShellPolicy.darkBackdrop(ShellDestination.DETAIL, lightPalette = true))
        assertFalse(SenderShellPolicy.darkBackdrop(Route.Library, lightPalette = true))
        assertTrue(SenderShellPolicy.darkBackdrop(Route.NowPlaying, lightPalette = true))
    }

    // The empty state's own Refresh raises `loading` on a library that is already empty.
    // Falling through to the grid there flashes its entire chrome — header, link pill,
    // the library controls — and snaps back the moment the query returns nothing new.
    @Test fun theEmptyLibraryHoldsItsGroundWhileARequeryIsInFlight() {
        assertTrue(libraryEmptyShown(MediaAccess.FULL, itemCount = 0, loading = false, showing = false))
        assertTrue(libraryEmptyShown(MediaAccess.FULL, itemCount = 0, loading = true, showing = true))
        // Re-picking from PARTIAL keeps the previous (empty) list until the query lands.
        assertTrue(libraryEmptyShown(MediaAccess.PARTIAL, itemCount = 0, loading = true, showing = true))
        // A first load has no answer to hold, so the grid shows its loading note instead.
        assertFalse(libraryEmptyShown(MediaAccess.FULL, itemCount = 0, loading = true, showing = false))
        // A query that found something ends the empty state whatever it was showing.
        assertFalse(libraryEmptyShown(MediaAccess.FULL, itemCount = 4, loading = false, showing = true))
        assertFalse(libraryEmptyShown(MediaAccess.PARTIAL, itemCount = 1, loading = false, showing = true))
    }

    // Denied access is answered by the permission rather than by MediaStore: no query is
    // run, so nothing about the loading flag may keep the grid up over it.
    @Test fun deniedAccessAlwaysShowsTheEmptyState() {
        assertTrue(libraryEmptyShown(MediaAccess.NONE, itemCount = 0, loading = false, showing = false))
        assertTrue(libraryEmptyShown(MediaAccess.NONE, itemCount = 0, loading = true, showing = false))
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
