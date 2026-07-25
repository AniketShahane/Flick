package com.flick.sender.ui.screens

import com.flick.sender.net.Route

/** The three destinations the floating nav can select. */
internal enum class NavTab { LIBRARY, REMOTE, DEVICES }

/** A [Route] reduced to shell identity, dropping the payloads Detail and Failure carry. */
internal enum class ShellDestination { CONNECT, LIBRARY, DETAIL, CONNECTING, NOW_PLAYING, FAILURE }

/** What a Remote tap resolves to. The shell never navigates to a session that isn't live. */
internal enum class RemoteTapOutcome { NAVIGATE, TOAST_PICK_A_TV, TOAST_PICK_A_VIDEO }

/**
 * Pure policy for the floating navigation. The rules live on the [ShellDestination]
 * overloads because `Route.Detail` wraps a `MediaItem` whose `Uri` cannot be built off
 * device, which would otherwise leave that arm unreachable from a JVM test.
 */
internal object SenderShellPolicy {

    fun destinationOf(route: Route): ShellDestination = when (route) {
        Route.Connect -> ShellDestination.CONNECT
        Route.Library -> ShellDestination.LIBRARY
        is Route.Detail -> ShellDestination.DETAIL
        Route.Connecting -> ShellDestination.CONNECTING
        Route.NowPlaying -> ShellDestination.NOW_PLAYING
        is Route.Failure -> ShellDestination.FAILURE
    }

    fun navVisible(route: Route): Boolean = navVisible(destinationOf(route))

    /** Only the two browsing surfaces reserve room for it; the rest are full-bleed. */
    fun navVisible(destination: ShellDestination): Boolean =
        destination == ShellDestination.LIBRARY || destination == ShellDestination.CONNECT

    fun selectedTab(route: Route): NavTab = selectedTab(destinationOf(route))

    fun selectedTab(destination: ShellDestination): NavTab = when (destination) {
        ShellDestination.LIBRARY, ShellDestination.DETAIL -> NavTab.LIBRARY
        ShellDestination.NOW_PLAYING -> NavTab.REMOTE
        ShellDestination.CONNECT, ShellDestination.CONNECTING, ShellDestination.FAILURE -> NavTab.DEVICES
    }

    fun darkBackdrop(route: Route): Boolean = darkBackdrop(destinationOf(route))

    /**
     * Which routes force a dark backdrop under the system bars regardless of the
     * resolved palette. Detail counts: it is a sheet over a scrimmed video frame, not
     * over the pale canvas.
     */
    fun darkBackdrop(destination: ShellDestination): Boolean = when (destination) {
        ShellDestination.CONNECTING, ShellDestination.NOW_PLAYING, ShellDestination.DETAIL -> true
        ShellDestination.CONNECT, ShellDestination.LIBRARY, ShellDestination.FAILURE -> false
    }

    fun darkBackdrop(route: Route, lightPalette: Boolean): Boolean =
        darkBackdrop(destinationOf(route), lightPalette)

    /**
     * What the system-bar icons actually have to contend with. The route rule alone is
     * not enough: in system dark mode every route resolves to the cinematic palette, so
     * the pale-canvas routes paint near-black too and dark icons would vanish on them.
     */
    fun darkBackdrop(destination: ShellDestination, lightPalette: Boolean): Boolean =
        !lightPalette || darkBackdrop(destination)

    /**
     * [hasActiveSession] is `SenderNavigationPolicy.canRestoreNowPlaying`: `restoreNowPlaying()`
     * silently no-ops without a committed cast, so the shell has to say why nothing moved.
     */
    fun remoteTapOutcome(hasConnectedTv: Boolean, hasActiveSession: Boolean): RemoteTapOutcome = when {
        hasActiveSession -> RemoteTapOutcome.NAVIGATE
        hasConnectedTv -> RemoteTapOutcome.TOAST_PICK_A_VIDEO
        else -> RemoteTapOutcome.TOAST_PICK_A_TV
    }
}
