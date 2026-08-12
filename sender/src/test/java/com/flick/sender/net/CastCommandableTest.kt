package com.flick.sender.net

import com.flick.sender.model.ConnectionStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one guard that decides whether a verb can reach a TV player at all — the
 * media notification arms its transport with it, and the remote's picture
 * orientation is offered under it. A control shown outside these conditions is a
 * control that silently does nothing.
 */
class CastCommandableTest {
    @Test fun anActiveCastOnAConnectedSocketIsTheOnlyStateThatCommands() {
        assertTrue(castCommandable(CastStartState.Active(CAST), CAST, ConnectionStatus.CONNECTED))
    }

    @Test fun nothingBeforeActiveCommands() {
        listOf(
            CastStartState.Idle,
            CastStartState.ConnectingControl(CAST),
            CastStartState.StartingSource(CAST),
            CastStartState.AwaitingAcceptance(CAST),
            CastStartState.AwaitingFirstFrame(CAST),
            CastStartState.Failed(CAST, "startup_timeout"),
        ).forEach { state ->
            assertFalse(state.toString(), castCommandable(state, CAST, ConnectionStatus.CONNECTED))
        }
    }

    @Test fun aDownSocketCommandsNothingHoweverActiveTheCastLooks() {
        listOf(
            ConnectionStatus.DISCONNECTED,
            ConnectionStatus.CONNECTING,
            ConnectionStatus.PAIRING,
            ConnectionStatus.CONFIRM_ON_TV,
        ).forEach { status ->
            assertFalse(status.name, castCommandable(CastStartState.Active(CAST), CAST, status))
        }
    }

    /**
     * The id is compared rather than merely present: an `Active` left over from a
     * cast this phone has superseded would otherwise answer for its successor.
     */
    @Test fun anActiveStateForAnotherCastNeverCommandsThisOne() {
        assertFalse(castCommandable(CastStartState.Active("cast-b"), CAST, ConnectionStatus.CONNECTED))
        assertFalse(castCommandable(CastStartState.Active(CAST), null, ConnectionStatus.CONNECTED))
        assertFalse(castCommandable(CastStartState.Active(CAST), "cast-b", ConnectionStatus.CONNECTED))
    }

    private companion object {
        const val CAST = "cast-a"
    }
}
