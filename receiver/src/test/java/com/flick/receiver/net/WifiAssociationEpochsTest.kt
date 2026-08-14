package com.flick.receiver.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which network callbacks are a genuinely new Wi-Fi association.
 *
 * The whole telemetry rests on this one question. It exists to answer whether a
 * re-association is what ends a router-side peer block, so a counter that inflates
 * — that calls a re-announcement of an unchanged link a new association — would
 * find a re-association within ±30 s of anything at all, and would answer yes to a
 * question nobody asked.
 */
class WifiAssociationEpochsTest {

    @Test fun theFirstAssociationIsNewAndIsCalledOne() {
        val epochs = WifiAssociationEpochs<String>()

        assertEquals(1, epochs.onAvailable("net-a"))
    }

    /**
     * The regression this exists for.
     *
     * A network callback replays `onAvailable` for every already-up network the
     * moment it is registered, and this receiver registers per composition. Every
     * screensaver, Home press and system dialog therefore re-announces the
     * association the TV never lost.
     */
    @Test fun theSameNetworkAnnouncedAgainIsNotANewAssociation() {
        val epochs = WifiAssociationEpochs<String>()
        epochs.onAvailable("net-a")

        assertNull(epochs.onAvailable("net-a"))
        assertNull(epochs.onAvailable("net-a"))
    }

    /** A loss names the association it ends, so the two edges pair up in the log. */
    @Test fun aLossReportsTheEpochItsAssociationWasGiven() {
        val epochs = WifiAssociationEpochs<String>()
        epochs.onAvailable("net-a")
        epochs.onLost("net-a")
        val second = epochs.onAvailable("net-b")

        assertEquals(2, second)
        assertEquals(2, epochs.onLost("net-b"))
    }

    /** Nothing to say about a link that was never up here. */
    @Test fun aLossForANetworkNeverHeldSaysNothing() {
        val epochs = WifiAssociationEpochs<String>()

        assertNull(epochs.onLost("net-a"))
    }

    /** And a loss is not repeatable: the second one is for an association already ended. */
    @Test fun theSameLossIsNotReportedTwice() {
        val epochs = WifiAssociationEpochs<String>()
        epochs.onAvailable("net-a")

        assertEquals(1, epochs.onLost("net-a"))
        assertNull(epochs.onLost("net-a"))
    }

    /**
     * Network ids are handed out by the framework and are eventually reissued. An
     * id that comes back AFTER its own loss is a different association wearing an
     * old name, and it is exactly the event this telemetry is looking for.
     */
    @Test fun anIdThatReturnsAfterItsLossIsANewAssociation() {
        val epochs = WifiAssociationEpochs<String>()
        epochs.onAvailable("net-a")
        epochs.onLost("net-a")

        assertEquals(2, epochs.onAvailable("net-a"))
    }

    /**
     * A make-before-break roam: the new link is up before the old one goes away, so
     * the edges interleave. Both associations are counted, and the loss still names
     * the older of the two — which is what makes an `available` printed before the
     * `lost` beneath it readable as a link that never actually dropped.
     */
    @Test fun aRoamThatNeverDropsTheLinkCountsBothAssociations() {
        val epochs = WifiAssociationEpochs<String>()

        assertEquals(1, epochs.onAvailable("net-a"))
        assertEquals(2, epochs.onAvailable("net-b"))
        assertEquals(1, epochs.onLost("net-a"))
    }

    /** Two live networks are still told apart while both are up. */
    @Test fun aSecondLiveNetworkDoesNotMaskTheFirst() {
        val epochs = WifiAssociationEpochs<String>()
        epochs.onAvailable("net-a")
        epochs.onAvailable("net-b")

        assertNull(epochs.onAvailable("net-a"))
        assertNull(epochs.onAvailable("net-b"))
    }

    /** Epochs are ordinals, never reused, so any two lines can be ordered by eye. */
    @Test fun epochsOnlyEverCountUpwards() {
        val epochs = WifiAssociationEpochs<String>()
        val seen = (1..20).map {
            val epoch = epochs.onAvailable("net-$it")
            epochs.onLost("net-$it")
            epoch
        }

        assertEquals((1..20).toList(), seen)
    }

    /**
     * A network lost while the monitor is unregistered is never withdrawn, because
     * the bookkeeping deliberately outlives the registration. The map is bounded so
     * those dead keys cannot accumulate for the life of the process.
     */
    @Test fun theEldestLiveKeyIsDroppedOnceTheMapIsFull() {
        val epochs = WifiAssociationEpochs<String>(capacity = 2)
        epochs.onAvailable("net-a")
        epochs.onAvailable("net-b")
        epochs.onAvailable("net-c")

        assertNull("net-a was evicted", epochs.onLost("net-a"))
        assertEquals(2, epochs.onLost("net-b"))
        assertEquals(3, epochs.onLost("net-c"))
    }

    /**
     * Eviction costs a suppression, not a miscount: a key dropped from the map is
     * treated as new when it is next announced. At the real depth only long-dead
     * keys are ever reached, so this errs towards over-reporting rather than towards
     * silence.
     */
    @Test fun anEvictedKeyIsNewAgainRatherThanLost() {
        val epochs = WifiAssociationEpochs<String>(capacity = 1)
        epochs.onAvailable("net-a")
        epochs.onAvailable("net-b")

        assertEquals(3, epochs.onAvailable("net-a"))
    }
}
