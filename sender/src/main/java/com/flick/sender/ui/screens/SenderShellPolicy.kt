package com.flick.sender.ui.screens

import com.flick.sender.net.Route

/**
 * The three destinations the floating nav can select. There is no Remote seat: the
 * remote only exists while something is casting, and the dock that rides above this bar
 * is the door to it.
 */
internal enum class NavTab { LIBRARY, DEVICES, SETTINGS }

/** A [Route] reduced to shell identity, dropping the payloads Detail and Failure carry. */
internal enum class ShellDestination { CONNECT, LIBRARY, SETTINGS, DETAIL, CONNECTING, NOW_PLAYING, FAILURE }

/**
 * Pure policy for the floating navigation. The rules live on the [ShellDestination]
 * overloads because `Route.Detail` wraps a `MediaItem` whose `Uri` cannot be built off
 * device, which would otherwise leave that arm unreachable from a JVM test.
 */
internal object SenderShellPolicy {

    fun destinationOf(route: Route): ShellDestination = when (route) {
        Route.Connect -> ShellDestination.CONNECT
        Route.Library -> ShellDestination.LIBRARY
        Route.Settings -> ShellDestination.SETTINGS
        is Route.Detail -> ShellDestination.DETAIL
        Route.Connecting -> ShellDestination.CONNECTING
        Route.NowPlaying -> ShellDestination.NOW_PLAYING
        is Route.Failure -> ShellDestination.FAILURE
    }

    fun navVisible(route: Route): Boolean = navVisible(destinationOf(route))

    /** Only the seats the bar itself offers reserve room for it; the rest are full-bleed. */
    fun navVisible(destination: ShellDestination): Boolean = when (destination) {
        ShellDestination.LIBRARY, ShellDestination.CONNECT, ShellDestination.SETTINGS -> true
        ShellDestination.DETAIL, ShellDestination.CONNECTING, ShellDestination.NOW_PLAYING,
        ShellDestination.FAILURE,
        -> false
    }

    fun dockVisible(route: Route): Boolean = dockVisible(destinationOf(route))

    /**
     * The dock docks with the nav, not with a route — and with the Remote seat gone it
     * is the only door to the remote, so it has to be reachable from every surface the
     * nav floats over rather than from the library alone. Deliberately defined as
     * [navVisible] so the two cannot drift apart.
     */
    fun dockVisible(destination: ShellDestination): Boolean = navVisible(destination)

    /**
     * The one route pair the dock's bounds travel across: the bar grows into the remote
     * and the remote shrinks back into the bar. Symmetric by construction, so minimizing
     * is the same geometry read backwards.
     */
    fun dockMorph(initial: ShellDestination, target: ShellDestination): Boolean =
        (target == ShellDestination.NOW_PLAYING && dockVisible(initial)) ||
            (initial == ShellDestination.NOW_PLAYING && dockVisible(target))

    fun selectedTab(route: Route): NavTab = selectedTab(destinationOf(route))

    /**
     * NOW_PLAYING answers LIBRARY, not a seat of its own: the nav is not drawn over the
     * remote at all, and every way out of the remote — minimize, back, a stop — lands on
     * the library. Its seat is therefore the one the nav has to be showing when it comes
     * back, and holding it still is also what keeps the travelling pill from moving
     * under the card that is swallowing it.
     */
    fun selectedTab(destination: ShellDestination): NavTab = when (destination) {
        ShellDestination.LIBRARY, ShellDestination.DETAIL, ShellDestination.NOW_PLAYING ->
            NavTab.LIBRARY
        ShellDestination.CONNECT, ShellDestination.CONNECTING, ShellDestination.FAILURE ->
            NavTab.DEVICES
        ShellDestination.SETTINGS -> NavTab.SETTINGS
    }

    fun darkBackdrop(route: Route): Boolean = darkBackdrop(destinationOf(route))

    /**
     * Which routes force a dark backdrop under the system bars regardless of the
     * resolved palette. Detail counts: it is a sheet over a scrimmed video frame, not
     * over the pale canvas.
     */
    fun darkBackdrop(destination: ShellDestination): Boolean = when (destination) {
        ShellDestination.CONNECTING, ShellDestination.NOW_PLAYING, ShellDestination.DETAIL -> true
        ShellDestination.CONNECT, ShellDestination.LIBRARY, ShellDestination.SETTINGS,
        ShellDestination.FAILURE,
        -> false
    }

    fun darkBackdrop(route: Route, lightPalette: Boolean): Boolean =
        darkBackdrop(destinationOf(route), lightPalette)

    /**
     * What the system-bar icons actually have to contend with. The route rule alone is
     * not enough: on a dark resolution — the appearance preference, or the platform under
     * Match system — every route lands on the cinematic palette, so the pale-canvas routes
     * paint near-black too and dark icons would vanish on them.
     */
    fun darkBackdrop(destination: ShellDestination, lightPalette: Boolean): Boolean =
        !lightPalette || darkBackdrop(destination)
}
