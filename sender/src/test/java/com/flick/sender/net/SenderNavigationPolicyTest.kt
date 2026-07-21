package com.flick.sender.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SenderNavigationPolicyTest {
    @Test fun backFromNowPlayingMinimizesWithoutUsingTheCastCancellationPath() {
        assertEquals(
            BackDisposition.SHOW_LIBRARY,
            SenderNavigationPolicy.backDisposition(Route.NowPlaying, connectFromLibrary = false),
        )
        assertEquals(
            BackDisposition.CANCEL_CAST,
            SenderNavigationPolicy.backDisposition(Route.Connecting, connectFromLibrary = false),
        )
    }

    @Test fun libraryLeavesBackToTheSystemAndInFlowConnectReturnsToLibrary() {
        assertEquals(
            BackDisposition.SYSTEM,
            SenderNavigationPolicy.backDisposition(Route.Library, connectFromLibrary = false),
        )
        assertEquals(
            BackDisposition.CLOSE_PAIRING,
            SenderNavigationPolicy.backDisposition(Route.Connect, connectFromLibrary = true),
        )
        assertEquals(
            BackDisposition.SYSTEM,
            SenderNavigationPolicy.backDisposition(Route.Connect, connectFromLibrary = false),
        )
    }

    @Test fun restoreAffordanceExistsOnlyForACommittedActiveCastWithAnItem() {
        val active = CastStartState.Active("MDEyMzQ1Njc4OWFiY2RlZg")

        assertTrue(SenderNavigationPolicy.canRestoreNowPlaying(active, hasItem = true))
        assertFalse(SenderNavigationPolicy.canRestoreNowPlaying(active, hasItem = false))
        assertFalse(SenderNavigationPolicy.canRestoreNowPlaying(CastStartState.Idle, hasItem = true))
        assertFalse(
            SenderNavigationPolicy.canRestoreNowPlaying(
                CastStartState.AwaitingFirstFrame("MDEyMzQ1Njc4OWFiY2RlZg"),
                hasItem = true,
            ),
        )
    }
}
