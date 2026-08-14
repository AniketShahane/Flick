package com.flick.sender.net

import com.flick.sender.model.CastErrorKind
import com.flick.sender.model.CastFailure
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a route the viewer chose ends the window that is waiting a router block out.
 *
 * The window's idle test is taken before the dial, and the dial that follows it is not
 * quick: a candidate sweep spends seconds against a block and tens of them where candidates
 * run their dial bound out. A tap that lands inside one used to be undone by it — the sweep
 * failing pulled the viewer back onto the error face they had just left, and the sweep
 * landing started the film on the TV after they had chosen to stop waiting.
 */
class BlockWaitStandsDownTest {

    private val failure = Route.Failure(
        CastErrorKind.UNREACHABLE,
        CastFailure(code = "control_no_route", retryable = true),
    )

    /** The regression: leaving the face the window lives behind is the viewer answering. */
    @Test fun leavingTheErrorFaceStandsTheWindowDown() {
        assertTrue(blockWaitStandsDown(failure, Route.Library, waiting = true))
        assertTrue(blockWaitStandsDown(failure, Route.Connect, waiting = true))
        assertTrue(blockWaitStandsDown(failure, Route.Settings, waiting = true))
    }

    /**
     * A second terminal is not a navigation. The window re-arms across faults of its own —
     * every tick that keeps waiting publishes one — and reading those as the viewer moving
     * would end the wait at its first sweep.
     */
    @Test fun oneErrorFaceReplacingAnotherIsNotTheViewerMoving() {
        assertFalse(
            blockWaitStandsDown(
                failure,
                Route.Failure(CastErrorKind.GENERIC, CastFailure(code = "control_refused", retryable = true)),
                waiting = true,
            ),
        )
    }

    /**
     * With no window open there is no dial of this app's own behind the face, so ordinary
     * navigation may not reach the pairing gate and cancel whatever else is on it.
     */
    @Test fun navigationWithNoWindowOpenCancelsNothing() {
        assertFalse(blockWaitStandsDown(failure, Route.Library, waiting = false))
    }

    /** Nothing that never started on the error face is the window's to stand down. */
    @Test fun navigationThatNeverLeftTheFaceIsNotTheWindows() {
        assertFalse(blockWaitStandsDown(Route.Library, Route.Settings, waiting = true))
        assertFalse(blockWaitStandsDown(Route.Connecting, Route.NowPlaying, waiting = true))
        assertFalse(blockWaitStandsDown(Route.Library, failure, waiting = true))
    }
}
