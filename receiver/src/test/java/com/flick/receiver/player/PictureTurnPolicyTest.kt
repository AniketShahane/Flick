package com.flick.receiver.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which mechanism turns the picture, and what that costs.
 *
 * The whole product thesis is at stake in one of these answers: an ordinary cast
 * must reach the decoder on the same path it would take if this feature did not
 * exist, on the same `SurfaceView`, because that path is the one proven to
 * direct-play 4K Dolby Vision with no stalls. Everything else here is about being
 * honest when the cheap mechanism cannot deliver — the verified Google TV
 * Streamer's display pipeline drops the codec's rotation transform, so a turn on
 * that TV is either free and invisible or effective and paid for.
 */
class PictureTurnPolicyTest {

    private fun turn(
        container: Int,
        extra: Int,
        colour: PictureColour = PictureColour.Sdr,
        turnUnavailable: Boolean = false,
    ) = pictureTurnFor(container, extra, colour, turnUnavailable)

    // --- The path an ordinary cast takes -------------------------------------

    /**
     * The one that matters most. A film nobody has turned reaches the decoder
     * with the container's own value and NO view turn — which is what keeps a 4K
     * Dolby Vision cast on the `SurfaceView`, the hardware overlay and the
     * tunneling path it would have had without this feature.
     */
    @Test fun aFilmNobodyHasTurnedNeverLeavesTheOrdinarySurface() {
        for (colour in PictureColour.values()) {
            val t = turn(container = 0, extra = 0, colour = colour)
            assertEquals(TurnMechanism.Decoder, t.mechanism)
            assertEquals(0, t.decoderDegrees)
            assertEquals(0, t.viewDegrees)
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
        assertEquals(0, t.viewDegrees)
        assertNull(t.note)
    }

    // --- A turn that has to actually happen ----------------------------------

    /**
     * The reported bug. Container 0, viewer presses 90: the view takes the whole
     * turn and the decoder is given ZERO rather than 90 — both because the two
     * mechanisms applying it would land 180 out on hardware where the codec's
     * transform does work, and because a zero keeps media3's reported `VideoSize`
     * equal to the coded size, which is the shape the frames arrive in and the
     * shape the surface transform is computed from.
     */
    @Test fun anAssertedTurnGoesToTheViewAndTheDecoderGetsZero() {
        val t = turn(container = 0, extra = 90)
        assertEquals(TurnMechanism.View, t.mechanism)
        assertEquals(0, t.decoderDegrees)
        assertEquals(90, t.viewDegrees)
        assertNull(t.note)
    }

    /** The container's own turn is the view's to apply too, not the codec's. */
    @Test fun theViewCarriesTheContainersTurnAsWellAsFlicks() {
        val t = turn(container = 90, extra = 90)
        assertEquals(TurnMechanism.View, t.mechanism)
        assertEquals(180, t.viewDegrees)
        assertEquals(0, t.decoderDegrees)
    }

    /** Past a full turn wraps rather than asking for something no API accepts. */
    @Test fun aTotalPastAFullTurnWraps() {
        assertEquals(90, turn(container = 270, extra = 180).viewDegrees)
    }

    /**
     * `Format.rotationDegrees` is clockwise and so is `Matrix.postRotate` on a
     * screen whose y axis points down, so the number handed to the view is the
     * total exactly as resolved. The effects graph this replaced took the
     * opposite sense and needed a conversion; getting that wrong turned 90 into
     * 270 and looked like a working feature installed upside down.
     */
    @Test fun theViewIsGivenTheTurnInTheSameSenseTheContainerStatesIt() {
        assertEquals(90, turn(container = 0, extra = 90).viewDegrees)
        assertEquals(180, turn(container = 0, extra = 180).viewDegrees)
        assertEquals(270, turn(container = 0, extra = 270).viewDegrees)
    }

    // --- What a turn is allowed to cost --------------------------------------

    /**
     * Dolby Vision is never turned. The RPU is metadata the display applies to a
     * video layer, and a turned film has none — the base layer arriving
     * uninterpreted would cost the very thing the product exists to deliver.
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
     * HDR10 or HLG is turned, and the grade is the price — on every TV, not only
     * on the verified one. An HDR transfer is applied to a video layer, and the
     * turned film's frames are a texture composited into the app's own window, so
     * there is no panel capability that can buy the grade back. The note is the
     * only thing that stops the loss being silent.
     */
    @Test fun hdrIsTurnedAndSaysWhatItCost() {
        val t = turn(container = 0, extra = 90, colour = PictureColour.Hdr)
        assertEquals(TurnMechanism.View, t.mechanism)
        assertEquals(90, t.viewDegrees)
        assertEquals(TurnNote.ShownInSdr, t.note)
    }

    /** An HDR film nobody turned keeps its grade and is told nothing. */
    @Test fun hdrThatWasNeverTurnedIsUntouchedAndSilent() {
        val t = turn(container = 0, extra = 0, colour = PictureColour.Hdr)
        assertEquals(TurnMechanism.Decoder, t.mechanism)
        assertNull(t.note)
    }

    /**
     * A film the turn already failed on is never sent back to it — the
     * alternative is a rotation key that can end the cast on a diagnosis screen,
     * once per press.
     */
    @Test fun aFilmTheTurnFailedOnIsNeverSentBackToIt() {
        val t = turn(container = 0, extra = 90, turnUnavailable = true)
        assertEquals(TurnMechanism.Decoder, t.mechanism)
        assertEquals(90, t.decoderDegrees)
        assertEquals(TurnNote.NotOnThisTv, t.note)
    }

    // --- Reading the colour off a track --------------------------------------

    /**
     * The MIME type is read before the transfer, and that ordering is the whole
     * point: Dolby Vision profile 8.1 carries an ordinary HDR10-compatible
     * `ColorInfo` for its base layer, so a rule written on the transfer alone
     * would call it plain HDR10 and turn a film that must not be.
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

    // --- SDR, HDR, Dolby Vision, side by side ---------------------------------

    /**
     * The whole selection table on one film's worth of turn, because the three
     * answers are the product decision and not an implementation detail: SDR is
     * turned for free, HDR is turned and says what it cost, and Dolby Vision is
     * refused rather than shown wrong.
     */
    @Test fun theMechanismFollowsTheColourAndNothingElse() {
        val sdr = turn(container = 0, extra = 90, colour = PictureColour.Sdr)
        assertEquals(TurnMechanism.View, sdr.mechanism)
        assertNull(sdr.note)

        val hdr = turn(container = 0, extra = 90, colour = PictureColour.Hdr)
        assertEquals(TurnMechanism.View, hdr.mechanism)
        assertEquals(TurnNote.ShownInSdr, hdr.note)

        val dv = turn(container = 0, extra = 90, colour = PictureColour.DolbyVision)
        assertEquals(TurnMechanism.Decoder, dv.mechanism)
        assertEquals(TurnNote.NotOnThisTv, dv.note)
    }
}
