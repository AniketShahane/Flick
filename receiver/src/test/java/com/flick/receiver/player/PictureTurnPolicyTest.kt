package com.flick.receiver.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which mechanism turns the picture, and what that costs.
 *
 * The whole product thesis is at stake in one of these answers: an ordinary cast
 * must reach the decoder on the same path it would take if this feature did not
 * exist, because that path is the one proven to direct-play 4K Dolby Vision with
 * no stalls. Everything else here is about being honest when the cheap mechanism
 * cannot deliver — the verified Google TV Streamer's display pipeline drops the
 * codec's rotation transform, and its EGL has neither BT.2020 colour space, so a
 * turn on that TV is either free and invisible or effective and paid for.
 */
class PictureTurnPolicyTest {

    private fun turn(
        container: Int,
        extra: Int,
        colour: PictureColour = PictureColour.Sdr,
        hdrSurvives: Boolean = false,
        framesUnavailable: Boolean = false,
    ) = pictureTurnFor(container, extra, colour, hdrSurvives, framesUnavailable)

    // --- The path an ordinary cast takes -------------------------------------

    /**
     * The one that matters most. A film nobody has turned reaches the decoder
     * with the container's own value and NO graph — which is what makes a 4K
     * Dolby Vision cast byte-identical to having no rotation feature at all.
     */
    @Test fun aFilmNobodyHasTurnedNeverBuildsAGraph() {
        for (colour in PictureColour.values()) {
            val t = turn(container = 0, extra = 0, colour = colour)
            assertEquals(TurnMechanism.Decoder, t.mechanism)
            assertEquals(0, t.decoderDegrees)
            assertEquals(0, t.frameDegrees)
            assertNull(t.note)
        }
    }

    /**
     * Auto's own correction is free too, and that is not a coincidence: standing
     * a sideways film up means cancelling the container's turn, and a total of 0
     * is not a transform at all — no display pipeline can fail to honour it.
     */
    @Test fun autoStandingASidewaysFilmUpCostsNothing() {
        val t = turn(container = 90, extra = 270)
        assertEquals(TurnMechanism.Decoder, t.mechanism)
        assertEquals(0, t.decoderDegrees)
        assertNull(t.note)
    }

    /** A container off the quarter-turn grid is handed on untouched, as before. */
    @Test fun aContainerOffTheGridIsLeftToTheDecoder() {
        val t = turn(container = 45, extra = 90)
        assertEquals(TurnMechanism.Decoder, t.mechanism)
        assertEquals(45, t.decoderDegrees)
        assertNull(t.note)
    }

    // --- A turn that has to actually happen ----------------------------------

    /**
     * The reported bug. Container 0, viewer presses 90: the graph takes the whole
     * turn and the decoder is given ZERO rather than 90 — both because the two
     * mechanisms applying it would land 180 out on hardware where the codec's
     * transform does work, and because a zero keeps media3's reported `VideoSize`
     * equal to the coded size, which is the size of the frames that arrive.
     */
    @Test fun anAssertedTurnGoesToTheGraphAndTheDecoderGetsZero() {
        val t = turn(container = 0, extra = 90)
        assertEquals(TurnMechanism.Frames, t.mechanism)
        assertEquals(0, t.decoderDegrees)
        assertEquals(90, t.frameDegrees)
        assertNull(t.note)
    }

    /** The container's own turn is the graph's to apply too, not the codec's. */
    @Test fun theGraphCarriesTheContainersTurnAsWellAsFlicks() {
        val t = turn(container = 90, extra = 90)
        assertEquals(TurnMechanism.Frames, t.mechanism)
        assertEquals(180, t.frameDegrees)
        assertEquals(0, t.decoderDegrees)
    }

    /** Past a full turn wraps rather than asking for something no API accepts. */
    @Test fun aTotalPastAFullTurnWraps() {
        assertEquals(90, turn(container = 270, extra = 180).frameDegrees)
    }

    /**
     * `Format.rotationDegrees` is clockwise; `ScaleAndRotateTransformation` is
     * counterclockwise. Getting this backwards turns 90 into 270 and looks like a
     * working feature installed upside down.
     */
    @Test fun theGraphIsGivenTheOppositeSenseOfTheSameTurn() {
        assertEquals(270, turn(container = 0, extra = 90).frameDegreesCounterClockwise)
        assertEquals(180, turn(container = 0, extra = 180).frameDegreesCounterClockwise)
        assertEquals(90, turn(container = 0, extra = 270).frameDegreesCounterClockwise)
        // And a turn of nothing is a turn of nothing in either sense.
        assertEquals(0, turn(container = 0, extra = 0).frameDegreesCounterClockwise)
    }

    // --- What a turn is allowed to cost --------------------------------------

    /**
     * Dolby Vision never enters the graph. `GlUtil.createEglSurface` accepts SDR,
     * BT.2020 PQ and BT.2020 HLG and nothing else, so there is no surface a DV
     * RPU can be presented through — the turn would cost the very thing the
     * product exists to deliver.
     */
    @Test fun dolbyVisionKeepsItsPictureAndSaysSo() {
        val t = turn(container = 0, extra = 90, colour = PictureColour.DolbyVision)
        assertEquals(TurnMechanism.Decoder, t.mechanism)
        assertEquals(90, t.decoderDegrees)
        assertEquals(TurnNote.NotOnThisTv, t.note)
    }

    /** And a DV film nobody turned is not owed a note about a turn it never asked for. */
    @Test fun dolbyVisionIsToldNothingWhenNothingWasAsked() {
        assertNull(turn(container = 0, extra = 0, colour = PictureColour.DolbyVision).note)
    }

    /**
     * HDR10 or HLG on a panel whose EGL cannot present BT.2020 — the verified
     * hardware — is turned, and the grade is the price. Media3 does not fail
     * there, it quietly tone maps, so the note is the only thing that stops the
     * loss being silent.
     */
    @Test fun hdrIsTurnedAndSaysWhatItCost() {
        val t = turn(container = 0, extra = 90, colour = PictureColour.Hdr, hdrSurvives = false)
        assertEquals(TurnMechanism.Frames, t.mechanism)
        assertEquals(90, t.frameDegrees)
        assertEquals(TurnNote.ShownInSdr, t.note)
    }

    /** On a panel that can present it, the same turn costs nothing and says nothing. */
    @Test fun hdrOnACapablePanelKeepsItsGrade() {
        val t = turn(container = 0, extra = 90, colour = PictureColour.Hdr, hdrSurvives = true)
        assertEquals(TurnMechanism.Frames, t.mechanism)
        assertNull(t.note)
    }

    /**
     * A graph that already failed on this film is never built for it again — the
     * alternative is a rotation key that can end the cast on a diagnosis screen,
     * once per press.
     */
    @Test fun aFilmTheGraphFailedOnIsNeverSentBackToIt() {
        val t = turn(container = 0, extra = 90, framesUnavailable = true)
        assertEquals(TurnMechanism.Decoder, t.mechanism)
        assertEquals(90, t.decoderDegrees)
        assertEquals(TurnNote.NotOnThisTv, t.note)
    }

    // --- Reading the colour off a track --------------------------------------

    /**
     * The MIME type is read before the transfer, and that ordering is the whole
     * point: Dolby Vision profile 8.1 carries an ordinary HDR10-compatible
     * `ColorInfo` for its base layer, so a rule written on the transfer alone
     * would call it plain HDR10 and send it into a pipeline that cannot carry its
     * RPU.
     */
    @Test fun dolbyVisionIsRecognisedByItsMimeTypeNotItsTransfer() {
        assertEquals(
            PictureColour.DolbyVision,
            pictureColourOf(MimeTypes.VIDEO_DOLBY_VISION, C.COLOR_TRANSFER_ST2084),
        )
        assertEquals(
            PictureColour.DolbyVision,
            pictureColourOf(MimeTypes.VIDEO_DOLBY_VISION, Format.NO_VALUE),
        )
    }

    @Test fun theHdrTransfersAreTheOnlyOnesThatCostAnything() {
        assertEquals(PictureColour.Hdr, pictureColourOf(MimeTypes.VIDEO_H265, C.COLOR_TRANSFER_ST2084))
        assertEquals(PictureColour.Hdr, pictureColourOf(MimeTypes.VIDEO_H265, C.COLOR_TRANSFER_HLG))
        assertEquals(PictureColour.Sdr, pictureColourOf(MimeTypes.VIDEO_H265, C.COLOR_TRANSFER_SDR))
        assertEquals(PictureColour.Sdr, pictureColourOf(MimeTypes.VIDEO_H264, Format.NO_VALUE))
        // A track with no MIME at all is not a reason to refuse a turn.
        assertEquals(PictureColour.Sdr, pictureColourOf(null, Format.NO_VALUE))
    }

    // --- Whether HDR survives a GL pass --------------------------------------

    /**
     * Reproduces `PlaybackVideoGraphWrapper.registerInput` and
     * `GlUtil.isColorTransferSupported` from media3 1.10.1, because the answer
     * decides whether a turn silently costs the viewer the grade.
     *
     * The verified Google TV Streamer is the last row: OpenGL ES 3.2 and
     * `GL_EXT_YUV_target`, so it can READ HDR, and neither BT.2020 EGL colour
     * space, so it cannot PRESENT it.
     */
    @Test fun hdrOutputFollowsTheEglColourSpaces() {
        // HDR10 needs the PQ extension, full stop.
        assertTrue(hdrSurvivesFrameProcessing(C.COLOR_TRANSFER_ST2084, true, false, 34))
        assertFalse(hdrSurvivesFrameProcessing(C.COLOR_TRANSFER_ST2084, false, true, 34))
        // HLG output landed a release after PQ output, so below API 34 media3
        // converts HLG to PQ where PQ is available.
        assertTrue(hdrSurvivesFrameProcessing(C.COLOR_TRANSFER_HLG, false, true, 34))
        assertTrue(hdrSurvivesFrameProcessing(C.COLOR_TRANSFER_HLG, true, false, 33))
        assertFalse(hdrSurvivesFrameProcessing(C.COLOR_TRANSFER_HLG, true, false, 34))
        // SDR asks nothing of the panel.
        assertTrue(hdrSurvivesFrameProcessing(C.COLOR_TRANSFER_SDR, false, false, 34))
        // The verified hardware.
        assertFalse(hdrSurvivesFrameProcessing(C.COLOR_TRANSFER_ST2084, false, false, 34))
        assertFalse(hdrSurvivesFrameProcessing(C.COLOR_TRANSFER_HLG, false, false, 34))
    }
}
