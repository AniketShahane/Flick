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
 * turn that is already on its way or that provably cannot arrive; saying "no" too
 * readily strands the picture on a turn the panel no longer draws, with no way
 * back. The three states the sequences below pin apart — consumed, in flight,
 * commanded but never carried — are what separates those.
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
     * commit the command, then mark it as carried because a re-prepare went out.
     */
    private fun VideoRotationOverride.commandAndRePrepare(degrees: Int) {
        commandExtraDegrees(degrees)
        markRePrepareIssued()
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
        assertFalse(override.needsDecoderReconfigure(0))
        assertTrue(override.needsDecoderReconfigure(90))
    }

    // --- The container that cannot be turned ---------------------------------

    /**
     * `MediaFormat.KEY_ROTATION` accepts quarter turns only, so a container
     * declaring anything else is played as filed and no command can move it.
     *
     * The trap this pins is that the decoder's reading then records a turn of 0
     * whatever was commanded, so a rule written on degrees alone would find the
     * decoder "behind" forever — and every re-prepare it demanded would re-record
     * the same 0. A permanent re-buffer loop over a picture that never changes.
     */
    @Test fun aContainerOffTheQuarterTurnGridNeverAsksForASecondRePrepare() {
        val override = VideoRotationOverride()
        override.takeForDecoder(45)
        assertTrue(override.needsDecoderReconfigure(90))
        override.commandAndRePrepare(90)
        val turn = override.takeForDecoder(45)
        assertEquals(45, turn.correctedDegrees)
        // Claiming otherwise would report a turn nobody can see as applied.
        assertEquals(0, turn.appliedDegrees)
        assertEquals(0, override.decoderExtraDegrees)
        // The decoder has consumed the command; that it achieved nothing is a
        // fact about the file, not a re-prepare still owed.
        assertFalse(override.needsDecoderReconfigure(90))
    }

    // --- Whether a re-prepare is owed ----------------------------------------

    @Test fun aDecoderAlreadyPresentingTheTurnIsLeftAlone() {
        val override = VideoRotationOverride()
        override.commandAndRePrepare(90)
        override.takeForDecoder(0)
        assertFalse(override.needsDecoderReconfigure(90))
    }

    @Test fun aChangedTurnIsOwedAReconfigure() {
        val override = VideoRotationOverride()
        override.takeForDecoder(0)
        assertTrue(override.needsDecoderReconfigure(90))
        assertTrue(override.needsDecoderReconfigure(180))
        assertTrue(override.needsDecoderReconfigure(270))
    }

    @Test fun afterAReconfigureTheSameChoiceStopsAskingForOne() {
        val override = VideoRotationOverride()
        override.takeForDecoder(0)
        assertTrue(override.needsDecoderReconfigure(270))
        override.commandAndRePrepare(270)
        override.takeForDecoder(0)
        assertFalse(override.needsDecoderReconfigure(270))
    }

    /**
     * Startup: the renderer has not read a format yet, so the decoder's turn is
     * unknown — and it is about to be configured from the commanded value anyway.
     * Treating unknown as wrong would spend a re-buffer to reach the turn the
     * first codec was already going to be given.
     */
    @Test fun aDecoderThatHasNotReportedYetIsNotOwedAReconfigure() {
        val override = VideoRotationOverride()
        assertFalse(override.needsDecoderReconfigure(0))
        override.commandAndRePrepare(90)
        assertFalse(override.needsDecoderReconfigure(90))
        // A turn that is neither commanded nor known-applied still is owed one.
        assertTrue(override.needsDecoderReconfigure(180))
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
        assertTrue(override.needsDecoderReconfigure(90))
        override.commandAndRePrepare(90)
        // Still re-buffering: the decoder has read nothing for this command yet.
        repeat(5) { assertFalse(override.needsDecoderReconfigure(90)) }
        // And once it lands, the same answer for the same reason.
        override.takeForDecoder(0)
        repeat(5) { assertFalse(override.needsDecoderReconfigure(90)) }
    }

    /** A different turn is a different request, and re-buffering for it is right. */
    @Test fun aDifferentTurnDuringARePrepareIsStillOwedOne() {
        val override = VideoRotationOverride()
        override.takeForDecoder(0)
        override.commandAndRePrepare(90)
        assertTrue(override.needsDecoderReconfigure(180))
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
        override.commandExtraDegrees(90)
        assertTrue(override.needsDecoderReconfigure(90))
        // And the repair is one re-prepare, not one per re-assert: once it is
        // carrying the turn, the next press waits for it like any other.
        override.markRePrepareIssued()
        assertFalse(override.needsDecoderReconfigure(90))
    }

    // --- When the effects graph is carrying the turn --------------------------

    /**
     * The codec is configured at ZERO under the graph, and not at the container's
     * own value. Two mechanisms both applying the turn would land 180 out on any
     * device whose codec transform does work, and a non-zero here would also make
     * media3 transpose the reported `VideoSize` away from the size of the frames
     * that actually arrive.
     */
    @Test fun aTurnCarriedByFramesLeavesTheDecoderAtZero() {
        val override = VideoRotationOverride()
        override.commandTurn(90, viaFrames = true)
        override.markRePrepareIssued()
        assertEquals(0, override.takeForDecoder(0).correctedDegrees)
        // Including the container's own turn, which the graph has taken over too.
        assertEquals(0, override.takeForDecoder(90).correctedDegrees)
        assertEquals(0, override.takeForDecoder(45).correctedDegrees)
    }

    /** The decoder applied nothing, and the reading says which mechanism did. */
    @Test fun theReadingNamesTheMechanismThatCarriedIt() {
        val override = VideoRotationOverride()
        override.commandTurn(90, viaFrames = true)
        val viaFrames = override.takeForDecoder(0)
        assertEquals(90, viaFrames.commandedDegrees)
        assertEquals(0, viaFrames.appliedDegrees)
        assertTrue(viaFrames.viaFrames)
        assertEquals(true, override.decoderReadViaFrames)
        assertTrue(override.commandedViaFrames)

        override.commandExtraDegrees(90)
        val viaDecoder = override.takeForDecoder(0)
        assertEquals(90, viaDecoder.appliedDegrees)
        assertFalse(viaDecoder.viaFrames)
        assertEquals(false, override.decoderReadViaFrames)
        assertFalse(override.commandedViaFrames)
    }

    /**
     * A change of MECHANISM on unchanged degrees still owes the codec a
     * reconfigure, because what the codec is given changes with it — 90 carried
     * by the decoder is a codec configured at 90, and 90 carried by frames is a
     * codec configured at 0.
     */
    @Test fun swappingMechanismOnTheSameDegreesIsOwedAReconfigure() {
        val override = VideoRotationOverride()
        override.commandExtraDegrees(90)
        override.markRePrepareIssued()
        override.takeForDecoder(0)
        assertFalse(override.needsDecoderReconfigure(90, viaFrames = false))
        assertTrue(override.needsDecoderReconfigure(90, viaFrames = true))
        override.commandTurn(90, viaFrames = true)
        override.markRePrepareIssued()
        override.takeForDecoder(0)
        assertFalse(override.needsDecoderReconfigure(90, viaFrames = true))
        assertTrue(override.needsDecoderReconfigure(90, viaFrames = false))
    }

    /** A new film goes back to the free path with nothing carried over. */
    @Test fun aNewFilmForgetsThatFramesWereCarryingTheTurn() {
        val override = VideoRotationOverride()
        override.commandTurn(90, viaFrames = true)
        override.takeForDecoder(0)
        override.reset()
        assertFalse(override.commandedViaFrames)
        assertNull(override.decoderReadViaFrames)
        assertEquals(90, override.takeForDecoder(90).correctedDegrees)
    }
}
