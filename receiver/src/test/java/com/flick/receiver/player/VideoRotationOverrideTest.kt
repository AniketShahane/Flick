package com.flick.receiver.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one piece of the rotation path that is decidable without a decoder: whether
 * a commanded turn still has to be carried to one by a re-prepare.
 *
 * Every answer here is worth a re-buffer of the film, in one direction or the
 * other. Saying "yes" too readily costs the viewer seconds of frozen 4K for a
 * turn that is already on its way, that provably cannot arrive, or that the
 * decoder was never going to be asked to do; saying "no" too readily strands the
 * picture on a turn the panel no longer draws, with no way back. The states the
 * sequences below pin apart — configured, in flight, commanded but never carried
 * — are what separates those.
 *
 * NOT covered, and not coverable here: the threading. The class is exercised on
 * one thread by these tests, so the volatile publication between the main and
 * playback threads, the single-read guarantee inside [VideoRotationOverride.takeForDecoder],
 * and the claim that the only harmful interleaving cannot occur are reasoned
 * about in that class's KDoc and verified by reading, not by this file.
 */
class VideoRotationOverrideTest {

    /**
     * What `PlayerController.applyVideoRotation` does for a turn it can act on:
     * commit the command, then mark it carried because a re-prepare went out.
     */
    private fun VideoRotationOverride.commandAndRePrepare(degrees: Int, viaView: Boolean = false) {
        commandTurn(degrees, viaView)
        markCarried()
    }

    // --- What the decoder is given -------------------------------------------

    @Test fun aFreshOverrideHonoursTheContainerExactly() {
        val override = VideoRotationOverride()
        assertEquals(0, override.commandedDegrees)
        assertEquals(0, override.takeForDecoder(0).correctedDegrees)
        assertEquals(90, override.takeForDecoder(90).correctedDegrees)
        assertEquals(270, override.takeForDecoder(270).correctedDegrees)
    }

    @Test fun theCommandedTurnAddsToTheContainersOwn() {
        val override = VideoRotationOverride()
        override.commandAndRePrepare(90)
        assertEquals(90, override.takeForDecoder(0).correctedDegrees)
        assertEquals(180, override.takeForDecoder(90).correctedDegrees)
        // Past a full turn wraps rather than running off the KEY_ROTATION grid.
        assertEquals(0, override.takeForDecoder(270).correctedDegrees)
    }

    @Test fun theReadingReportsTheCommandItConsumed() {
        val override = VideoRotationOverride()
        override.commandAndRePrepare(90)
        val turn = override.takeForDecoder(90)
        assertEquals(180, turn.correctedDegrees)
        // The command as read once, so a turn committed on the main thread while
        // this was in progress cannot be the one that gets logged against it.
        assertEquals(90, turn.commandedDegrees)
        assertEquals(90, turn.appliedDegrees)
    }

    // --- What the decoder has ------------------------------------------------

    @Test fun theDecodersTurnIsUnknownUntilItIsGivenAFormat() {
        val override = VideoRotationOverride()
        assertNull(override.decoderExtraDegrees)
        override.takeForDecoder(0)
        assertEquals(0, override.decoderExtraDegrees)
    }

    @Test fun theDecoderRecordsTheEXTRATurnNotTheContainersTotal() {
        val override = VideoRotationOverride()
        override.commandAndRePrepare(90)
        assertEquals(180, override.takeForDecoder(90).correctedDegrees)
        assertEquals(90, override.decoderExtraDegrees)
    }

    @Test fun aNewFilmInheritsNoTurnAndNoDecoder() {
        val override = VideoRotationOverride()
        override.commandAndRePrepare(90)
        override.takeForDecoder(0)
        override.reset()
        assertEquals(0, override.commandedDegrees)
        assertNull(override.decoderExtraDegrees)
        // And it is not owed a re-prepare to reach the turn its first codec is
        // about to be configured with anyway.
        assertFalse(override.needsDecoderReconfigure())
        override.commandTurn(90, viaView = false)
        assertFalse(override.needsDecoderReconfigure())
    }

    // --- The container that cannot be turned ---------------------------------

    /**
     * `MediaFormat.KEY_ROTATION` accepts quarter turns only, so a container
     * declaring anything else is played as filed and no command can move it.
     *
     * The trap this pins is that such a file's decoder is configured with the
     * container's own value whatever is commanded, so a rule written on the
     * commanded degrees would find the decoder "behind" forever — and every
     * re-prepare it demanded would land on the same value again. A permanent
     * re-buffer loop over a picture that never changes.
     */
    @Test fun aContainerOffTheQuarterTurnGridNeverAsksForASecondRePrepare() {
        val override = VideoRotationOverride()
        override.takeForDecoder(45)
        override.commandTurn(90, viaView = false)
        // The command asks for the container's own 45, because that is what
        // `effectiveRotationDegrees` resolves an off-grid file to.
        assertFalse(override.needsDecoderReconfigure())
        val turn = override.takeForDecoder(45)
        assertEquals(45, turn.correctedDegrees)
        // Claiming otherwise would report a turn nobody can see as applied.
        assertEquals(0, turn.appliedDegrees)
        assertEquals(0, override.decoderExtraDegrees)
        assertFalse(override.needsDecoderReconfigure())
    }

    // --- Whether a re-prepare is owed ----------------------------------------

    @Test fun aDecoderAlreadyPresentingTheTurnIsLeftAlone() {
        val override = VideoRotationOverride()
        override.commandAndRePrepare(90)
        override.takeForDecoder(0)
        assertFalse(override.needsDecoderReconfigure())
    }

    @Test fun aChangedTurnIsOwedAReconfigure() {
        val override = VideoRotationOverride()
        override.takeForDecoder(0)
        for (degrees in listOf(90, 180, 270)) {
            override.commandTurn(degrees, viaView = false)
            assertTrue(override.needsDecoderReconfigure())
        }
    }

    @Test fun afterAReconfigureTheSameChoiceStopsAskingForOne() {
        val override = VideoRotationOverride()
        override.takeForDecoder(0)
        override.commandTurn(270, viaView = false)
        assertTrue(override.needsDecoderReconfigure())
        override.markCarried()
        override.takeForDecoder(0)
        assertFalse(override.needsDecoderReconfigure())
    }

    /**
     * Startup: the renderer has not read a format yet, so the decoder's
     * configuration is unknown — and it is about to be taken from the commanded
     * value anyway. Treating unknown as wrong would spend a re-buffer to reach the
     * turn the first codec was already going to be given.
     */
    @Test fun aDecoderThatHasNotReportedYetIsNotOwedAReconfigure() {
        val override = VideoRotationOverride()
        assertFalse(override.needsDecoderReconfigure())
        override.commandTurn(90, viaView = false)
        assertFalse(override.needsDecoderReconfigure())
        override.commandTurn(180, viaView = false)
        assertFalse(override.needsDecoderReconfigure())
    }

    // --- In flight versus never carried --------------------------------------

    /**
     * The blocker this class exists to stop. A re-prepare at 4K over Wi-Fi is a
     * re-buffer of seconds, and its only visible symptom is a picture that has
     * not turned — which is exactly what makes a viewer press the same key again.
     * Every repeat must be absorbed, or each one restarts the re-buffer and the
     * film never comes back.
     */
    @Test fun everyRepeatDuringOneRePrepareIsAbsorbed() {
        val override = VideoRotationOverride()
        override.takeForDecoder(0)
        override.commandTurn(90, viaView = false)
        assertTrue(override.needsDecoderReconfigure())
        override.markCarried()
        // Still re-buffering: the decoder has read nothing for this command yet.
        repeat(5) {
            override.commandTurn(90, viaView = false)
            assertFalse(override.needsDecoderReconfigure())
        }
        // And once it lands, the same answer for the same reason.
        override.takeForDecoder(0)
        repeat(5) {
            override.commandTurn(90, viaView = false)
            assertFalse(override.needsDecoderReconfigure())
        }
    }

    /** A different turn is a different request, and re-buffering for it is right. */
    @Test fun aDifferentTurnDuringARePrepareIsStillOwedOne() {
        val override = VideoRotationOverride()
        override.takeForDecoder(0)
        override.commandAndRePrepare(90)
        override.commandTurn(180, viaView = false)
        assertTrue(override.needsDecoderReconfigure())
    }

    /**
     * The repair case, and the reason a re-assert is not simply deduped away: a
     * command was recorded but no re-prepare ever carried it, so the decoder is
     * still on the previous turn under a choice the panel already draws as
     * selected. Nothing else in the receiver can notice, so re-asserting the same
     * choice has to be able to reach it.
     */
    @Test fun aCommandNoRePrepareCarriedIsRepairedByAReAssert() {
        val override = VideoRotationOverride()
        override.takeForDecoder(0)
        override.commandTurn(90, viaView = false)
        assertTrue(override.needsDecoderReconfigure())
        // Re-asserting the identical choice keeps the same request alive rather
        // than minting a new one, and still finds it unpaid.
        override.commandTurn(90, viaView = false)
        assertTrue(override.needsDecoderReconfigure())
        // And the repair is one re-prepare, not one per re-assert: once it is
        // carrying the turn, the next press waits for it like any other.
        override.markCarried()
        assertFalse(override.needsDecoderReconfigure())
    }

    // --- When the video surface is carrying the turn --------------------------

    /**
     * The codec is configured at ZERO under a view turn, and not at the
     * container's own value. Two mechanisms both applying the turn would land 180
     * out on any device whose codec transform does work, and a non-zero here
     * would also make media3 transpose the reported `VideoSize` away from the
     * shape of the frames that actually arrive.
     */
    @Test fun aTurnCarriedByTheViewLeavesTheDecoderAtZero() {
        val override = VideoRotationOverride()
        override.commandAndRePrepare(90, viaView = true)
        assertEquals(0, override.takeForDecoder(0).correctedDegrees)
        // Including the container's own turn, which the view has taken over too.
        assertEquals(0, override.takeForDecoder(90).correctedDegrees)
        assertEquals(0, override.takeForDecoder(45).correctedDegrees)
    }

    /** The decoder applied nothing, and the reading says which mechanism did. */
    @Test fun theReadingNamesTheMechanismThatCarriedIt() {
        val override = VideoRotationOverride()
        override.commandTurn(90, viaView = true)
        val viaView = override.takeForDecoder(0)
        assertEquals(90, viaView.commandedDegrees)
        assertEquals(0, viaView.appliedDegrees)
        assertTrue(viaView.viaView)
        assertEquals(true, override.decoderReadViaView)
        assertTrue(override.commandedViaView)

        override.commandTurn(90, viaView = false)
        val viaDecoder = override.takeForDecoder(0)
        assertEquals(90, viaDecoder.appliedDegrees)
        assertFalse(viaDecoder.viaView)
        assertEquals(false, override.decoderReadViaView)
        assertFalse(override.commandedViaView)
    }

    /**
     * The saving that made the view turn worth building, stated as the rule that
     * produces it: handing a turn from the codec to the surface on a film whose
     * container declares nothing leaves the codec configured exactly as it was, so
     * the viewer's key press costs no re-buffer at all.
     */
    @Test fun handingTheTurnToTheViewOwesNothingWhenTheCodecEndsUpTheSame() {
        val override = VideoRotationOverride()
        override.takeForDecoder(0)
        override.commandTurn(90, viaView = true)
        assertFalse(override.needsDecoderReconfigure())
    }

    /**
     * And the mirror of it: the same hand-over on a container-rotated film DOES
     * change the codec's configuration — from the container's 90 to 0 — so it is
     * owed the one re-prepare that makes the turn happen exactly once.
     */
    @Test fun handingTheTurnToTheViewOwesOneWhenTheCodecWasCarryingSomething() {
        val override = VideoRotationOverride()
        override.takeForDecoder(90)
        override.commandTurn(0, viaView = true)
        assertTrue(override.needsDecoderReconfigure())
    }

    /** A new film goes back to the free path with nothing carried over. */
    @Test fun aNewFilmForgetsThatTheViewWasCarryingTheTurn() {
        val override = VideoRotationOverride()
        override.commandTurn(90, viaView = true)
        override.takeForDecoder(0)
        override.reset()
        assertFalse(override.commandedViaView)
        assertNull(override.decoderReadViaView)
        assertEquals(90, override.takeForDecoder(90).correctedDegrees)
    }
}
