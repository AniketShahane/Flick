package com.flick.sender.net

import com.flick.sender.model.DiscoveredTv
import com.flick.sender.model.TvAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvNameRefreshGateTest {
    private val tvId = "ABEiM0RVZneImaq7zN3u_w"
    private val otherTvId = "ERITFBUWFxgZGhscHR4fIA"

    @Test fun anAdvertisedNameThatDiffersFromTheShownOneIsWorthOneResume() {
        val gate = TvNameRefreshGate()
        assertTrue(gate.refreshes(tvId, "Living Room", idle = true, discovered = listOf(tv("Lounge"))))
    }

    // A resume tears the control session down and builds a new one. A cosmetic name is
    // never worth the film the viewer is watching.
    @Test fun aCastInFlightOutranksTheName() {
        val gate = TvNameRefreshGate()
        assertFalse(gate.refreshes(tvId, "Living Room", idle = false, discovered = listOf(tv("Lounge"))))
    }

    // Deferred, not dropped: the rename is still true once the cast ends.
    @Test fun aHintMetWhileBusyIsNotSpent() {
        val gate = TvNameRefreshGate()
        gate.refreshes(tvId, "Living Room", idle = false, discovered = listOf(tv("Lounge")))
        assertTrue(gate.refreshes(tvId, "Living Room", idle = true, discovered = listOf(tv("Lounge"))))
    }

    @Test fun anAdvertisementAgreeingWithTheShownNameIsNoHintAtAll() {
        val gate = TvNameRefreshGate()
        assertFalse(gate.refreshes(tvId, "Living Room", idle = true, discovered = listOf(tv("Living Room"))))
    }

    @Test fun anotherTvsAdvertisementSaysNothingAboutThisPairing() {
        val gate = TvNameRefreshGate()
        assertFalse(
            gate.refreshes(tvId, "Living Room", idle = true, discovered = listOf(tv("Lounge", otherTvId))),
        )
    }

    // mDNS records carry no id until they resolve, and one that names no TV names no
    // pairing either.
    @Test fun anUnidentifiedAdvertisementIsNotAHint() {
        val gate = TvNameRefreshGate()
        assertFalse(
            gate.refreshes(tvId, "Living Room", idle = true, discovered = listOf(tv("Lounge", null))),
        )
    }

    @Test fun theSameHintIsActedOnOnce() {
        val gate = TvNameRefreshGate()
        val advertised = listOf(tv("Lounge"))
        assertTrue(gate.refreshes(tvId, "Living Room", idle = true, discovered = advertised))
        assertFalse(gate.refreshes(tvId, "Living Room", idle = true, discovered = advertised))
    }

    // The resume that followed failed, so the shown name is unchanged and the
    // advertisement is unchanged. Re-reading it as a fresh hint is the loop this exists to
    // stop — the caller reports no outcome here precisely so that it cannot.
    @Test fun aHintIsNotRetriedAfterTheResumeThatFollowedItFailed() {
        val gate = TvNameRefreshGate()
        val advertised = listOf(tv("Lounge"))
        assertTrue(gate.refreshes(tvId, "Living Room", idle = true, discovered = advertised))
        repeat(5) {
            assertFalse(gate.refreshes(tvId, "Living Room", idle = true, discovered = advertised))
        }
    }

    // The authenticated name is allowed to disagree with the advertised one for ever — the
    // platform renames a colliding service, and a rogue advertiser can claim anything. The
    // spent hint is what keeps that a single re-handshake rather than one per advertisement.
    @Test fun aNameThatNeverConvergesCostsOneResumeAndNoMore() {
        val gate = TvNameRefreshGate()
        assertTrue(gate.refreshes(tvId, "Living Room", idle = true, discovered = listOf(tv("Living Room (2)"))))
        assertFalse(gate.refreshes(tvId, "Living Room", idle = true, discovered = listOf(tv("Living Room (2)"))))
    }

    @Test fun aSecondRenameIsStillPickedUp() {
        val gate = TvNameRefreshGate()
        assertTrue(gate.refreshes(tvId, "Living Room", idle = true, discovered = listOf(tv("Lounge"))))
        assertTrue(gate.refreshes(tvId, "Lounge", idle = true, discovered = listOf(tv("Den"))))
    }

    // Renaming back to a name used earlier is an ordinary thing to do, and the phone owes
    // it the same refresh as any other. Only a repeat of the hint just acted on is a loop.
    @Test fun aRenameBackToAnEarlierNameIsNotMistakenForALoop() {
        val gate = TvNameRefreshGate()
        assertTrue(gate.refreshes(tvId, "Living Room", idle = true, discovered = listOf(tv("Lounge"))))
        assertTrue(gate.refreshes(tvId, "Lounge", idle = true, discovered = listOf(tv("Living Room"))))
        assertTrue(gate.refreshes(tvId, "Living Room", idle = true, discovered = listOf(tv("Lounge"))))
    }

    // An advertiser this phone cannot authenticate decides how often the names it publishes
    // change, so distinct names alone may not be allowed to fund re-dials.
    @Test fun anAdvertiserCyclingNamesRunsOutOfBudget() {
        val gate = TvNameRefreshGate(budget = 3)
        val acted = (1..10).count { attempt ->
            gate.refreshes(tvId, "Living Room", idle = true, discovered = listOf(tv("Lounge $attempt")))
        }
        assertEquals(3, acted)
    }

    // A rename the phone has already picked up, advertised alongside the old name by a
    // second host claiming the same id, must still reach the handshake.
    @Test fun anAdvertiserHoldingTheOldNameCannotMaskARename() {
        val gate = TvNameRefreshGate()
        assertTrue(
            gate.refreshes(
                tvId,
                "Living Room",
                idle = true,
                discovered = listOf(tv("Living Room"), tv("Lounge")),
            ),
        )
    }

    private fun tv(name: String, id: String? = tvId) =
        DiscoveredTv(name, "192.168.42.88", 42421, id, 2, null, TvAvailability.READY)
}
