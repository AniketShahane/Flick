package com.flick.receiver.net

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Whether a rename reaches the name the cast surfaces are drawing.
 *
 * `ControlServer` needs a `PairingManager`, which needs a `Context` and real
 * `SharedPreferences`, so the rule lives in a pure function and is exercised here —
 * the same shape `RenamePairingTest` uses for `PairingManager.rename`.
 *
 * The label published at the lease outranks the stored one for as long as a phone
 * holds it, so a rename that stopped at the store left "Flicked from …", the
 * connecting card and the receiver's own error screen naming the phone by the name
 * the viewer had just replaced, for the rest of the session.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ControlPeerRenameTest {

    @Test fun renamingThePhoneHoldingTheLeaseRepublishesItsName() {
        assertEquals(
            ControlPeer("keyA", "Kitchen phone"),
            leaseholderAfterRename(ControlPeer("keyA", "Alpha"), "keyA", "Kitchen phone"),
        )
    }

    /** Renaming phone B may not put B's new name on the cast phone A is driving. */
    @Test fun renamingAnotherPhoneLeavesTheLeaseholderExactlyAsItWas() {
        val current = ControlPeer("keyA", "Alpha")
        assertSame(current, leaseholderAfterRename(current, "keyB", "Beta"))
    }

    @Test fun aRenameWithNoLeaseEverHeldNamesNothing() {
        assertNull(leaseholderAfterRename(null, "keyA", "Alpha"))
    }

    /**
     * The identity is the key id, never the name. Two phones may honestly carry the
     * same label, and matching on it would rename whichever of them held the lease.
     */
    @Test fun twoPhonesSharingOneNameAreStillToldApart() {
        val current = ControlPeer("keyA", "iPhone")
        assertSame(current, leaseholderAfterRename(current, "keyB", "Sofa phone"))
    }

    /**
     * The lease deliberately outlives the socket — the sentences that need it most are
     * drawn after the link is gone — so a rename that lands afterwards is still about
     * the phone those sentences name.
     */
    @Test fun aPhoneRenamedAfterItsSessionEndedStillRedrawsUnderItsNewName() {
        assertEquals(
            ControlPeer("keyA", "Gamma"),
            leaseholderAfterRename(ControlPeer("keyA", "Alpha"), "keyA", "Gamma"),
        )
    }

    /** A pipe is legal in a label and reaches this value verbatim. */
    @Test fun aLabelContainingAPipeIsPublishedUnchanged() {
        assertEquals(
            ControlPeer("keyA", "12345|home"),
            leaseholderAfterRename(ControlPeer("keyA", "Alpha"), "keyA", "12345|home"),
        )
    }

    /**
     * Committing the name the phone already had must not redraw the film. The dialog
     * accepts an unchanged name as a success, so this runs on an ordinary press of
     * Save with nothing edited.
     */
    @Test fun renamingToTheSameNameEmitsNothing() = runTest {
        val flow = MutableStateFlow<ControlPeer?>(ControlPeer("keyA", "Alpha"))
        val seen = mutableListOf<ControlPeer?>()
        backgroundScope.launch { flow.collect { seen.add(it) } }
        runCurrent()

        flow.update { leaseholderAfterRename(it, "keyA", "Alpha") }
        runCurrent()

        assertEquals(listOf(ControlPeer("keyA", "Alpha")), seen)
    }

    /** And the rename that does change it reaches every collector exactly once. */
    @Test fun aRealRenameEmitsOnce() = runTest {
        val flow = MutableStateFlow<ControlPeer?>(ControlPeer("keyA", "Alpha"))
        val seen = mutableListOf<ControlPeer?>()
        backgroundScope.launch { flow.collect { seen.add(it) } }
        runCurrent()

        flow.update { leaseholderAfterRename(it, "keyA", "Gamma") }
        runCurrent()

        assertEquals(
            listOf(ControlPeer("keyA", "Alpha"), ControlPeer("keyA", "Gamma")),
            seen,
        )
    }
}
