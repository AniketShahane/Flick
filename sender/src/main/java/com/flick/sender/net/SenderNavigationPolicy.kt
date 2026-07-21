package com.flick.sender.net

internal enum class BackDisposition { SYSTEM, CANCEL_CAST, CLOSE_PAIRING, SHOW_LIBRARY }

/** Pure route policy so Back never conflates minimizing an active cast with stopping it. */
internal object SenderNavigationPolicy {
    fun backDisposition(route: Route, connectFromLibrary: Boolean): BackDisposition = when (route) {
        Route.Library -> BackDisposition.SYSTEM
        Route.Connecting -> BackDisposition.CANCEL_CAST
        Route.Connect -> if (connectFromLibrary) BackDisposition.CLOSE_PAIRING else BackDisposition.SYSTEM
        Route.NowPlaying, is Route.Detail, is Route.Failure -> BackDisposition.SHOW_LIBRARY
    }

    fun canRestoreNowPlaying(state: CastStartState, hasItem: Boolean): Boolean =
        state is CastStartState.Active && hasItem
}
