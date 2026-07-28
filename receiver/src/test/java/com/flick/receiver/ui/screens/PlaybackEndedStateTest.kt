package com.flick.receiver.ui.screens

import com.flick.receiver.player.PlaybackPhase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The finished state is honest everywhere or it is not honest at all.
 *
 * `PlaybackPhase.Ended` ships a deeper dim, a "Finished" chip and a FINISHED
 * eyebrow, and the chrome stays up. The one thing on that screen a viewer can
 * press is the primary transport key, whose default action is a resume — and a
 * resume past the end of a film only sets `playWhenReady` on a player that has
 * nothing left to play. Offering it anyway is the single lie this state must not
 * tell, so the key is a control only when the screen was handed a real restart.
 */
class PlaybackEndedStateTest {

    private val restart: () -> Unit = {}

    @Test fun everyPhaseShortOfEndedKeepsALivePrimaryKey() {
        for (phase in PlaybackPhase.entries.filter { it != PlaybackPhase.Ended }) {
            assertTrue(phase.wire, primaryTransportLive(phase, onReplay = null))
            assertTrue(phase.wire, primaryTransportLive(phase, onReplay = restart))
        }
    }

    @Test fun endedWithNoRestartWiredHasNoPrimaryKey() {
        assertFalse(primaryTransportLive(PlaybackPhase.Ended, onReplay = null))
    }

    @Test fun endedWithARestartWiredKeepsTheKeyAndRelabelsIt() {
        assertTrue(primaryTransportLive(PlaybackPhase.Ended, onReplay = restart))
    }
}
