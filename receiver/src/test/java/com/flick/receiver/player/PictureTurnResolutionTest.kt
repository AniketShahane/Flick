package com.flick.receiver.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a film's picture turn gets resolved at all, and what it resolves to.
 *
 * The bug this pins is not in either policy function — both were already right —
 * but in the composition of them. [autoRotation] answers how much Flick should
 * add to the container's turn; [pictureTurnFor] answers who can carry the TOTAL.
 * They part company exactly where the user's films live: a phone-shot portrait
 * clip is a container declaring 90 that Auto correctly leaves at 0, so a
 * resolution triggered by Auto's ANSWER moving never fires for it, the picture is
 * never moved onto a surface that can turn it, and the decoder is configured with
 * a turn the verified TV's display pipeline drops. Every such clip then plays as a
 * sideways landscape picture from the first frame, with no key pressed.
 *
 * The counterweight is the other test in each pair: an ordinary film must resolve
 * to no work whatsoever, because a re-prepare spent on a cast that needs none is
 * the anti-buffering thesis being spent for nothing.
 */
class PictureTurnResolutionTest {

    // --- The film the user actually casts -------------------------------------

    /** 1920x1080 sensor readout, 90° display matrix, one stereo AAC track. */
    private fun phonePortraitClip(durationMs: Long = 47_000L) = MediaShape(
        video = VideoTrackShape(
            widthPx = 1920,
            heightPx = 1080,
            rotationDegrees = 90,
            pixelWidthHeightRatio = 1f,
            sampleMimeType = MimeTypes.VIDEO_H264,
            colorTransfer = C.COLOR_TRANSFER_SDR,
        ),
        audioTrackCount = 1,
        maxAudioChannelCount = 2,
        audioSampleMimeTypes = listOf(MimeTypes.AUDIO_AAC),
        embeddedTextTrackCount = 0,
        durationMs = durationMs,
    )

    /** An ordinary landscape release: nothing to turn, and nothing to spend. */
    private fun ordinaryFilm(
        sampleMimeType: String = MimeTypes.VIDEO_H265,
        colorTransfer: Int = C.COLOR_TRANSFER_SDR,
    ) = MediaShape(
        video = VideoTrackShape(
            widthPx = 3840,
            heightPx = 2160,
            rotationDegrees = 0,
            pixelWidthHeightRatio = 1f,
            sampleMimeType = sampleMimeType,
            colorTransfer = colorTransfer,
        ),
        audioTrackCount = 1,
        maxAudioChannelCount = 6,
        audioSampleMimeTypes = listOf(MimeTypes.AUDIO_E_AC3),
        embeddedTextTrackCount = 2,
        durationMs = 7_200_000L,
    )

    private fun turnFor(shape: MediaShape, extraDegrees: Int) = pictureTurnFor(
        containerDegrees = shape.video!!.rotationDegrees,
        extraDegrees = extraDegrees,
        colour = pictureColourOf(shape.video.sampleMimeType, shape.video.colorTransfer),
        turnUnavailable = false,
    )

    // --- The defect -----------------------------------------------------------

    /**
     * Auto's answer for this clip is 0, which is the value a new film already
     * starts at — so there is no change for a resolution to key on, and that is
     * the whole failure.
     */
    @Test fun theClipsAutoVerdictNeverMovesOffTheValueAFilmStartsAt() {
        val auto = autoRotation(phonePortraitClip())
        assertEquals(0, auto.extraDegrees)
        assertEquals(AutoRotationVerdict.LooksLikeACameraClip, auto.verdict)
    }

    /**
     * And the total nobody was asking about is a quarter turn this TV can only
     * show on a surface it can transform.
     */
    @Test fun theClipsTotalTurnNeedsTheViewMechanism() {
        val turn = turnFor(phonePortraitClip(), extraDegrees = 0)
        assertEquals(TurnMechanism.View, turn.mechanism)
        assertEquals(90, turn.viewDegrees)
        // Zero, not the container's own 90: two mechanisms both applying the turn
        // would land 180 out on a TV whose decoder transform does work.
        assertEquals(0, turn.decoderDegrees)
        assertNull(turn.note)
    }

    /** The two facts above, joined: the first delivery has to resolve regardless. */
    @Test fun theFirstDeliveryResolvesEvenThoughAutoDidNotMove() {
        assertTrue(
            resolvesPictureTurn(
                alreadyResolved = false,
                autoChanged = false,
                choiceIsAuto = true,
            ),
        )
    }

    // --- The cast that must stay byte-identical --------------------------------

    @Test fun anOrdinaryFilmResolvesToNoViewTurnAndNoTurnAtAll() {
        val turn = turnFor(ordinaryFilm(), extraDegrees = 0)
        assertEquals(TurnMechanism.Decoder, turn.mechanism)
        assertEquals(0, turn.viewDegrees)
        assertEquals(0, turn.decoderDegrees)
        assertNull(turn.note)
    }

    /** Including the one this app exists for, which a turn would cost its colour. */
    @Test fun a4kDolbyVisionCastResolvesToNoViewTurnEither() {
        val film = ordinaryFilm(
            sampleMimeType = MimeTypes.VIDEO_DOLBY_VISION,
            colorTransfer = C.COLOR_TRANSFER_ST2084,
        )
        val turn = turnFor(film, extraDegrees = 0)
        assertEquals(TurnMechanism.Decoder, turn.mechanism)
        assertEquals(0, turn.viewDegrees)
        assertNull(turn.note)
    }

    /**
     * The other half of "no work": the resolution reaches
     * `VideoRotationOverride` with an extra turn of 0 on a film that has commanded
     * nothing, and is owed no re-prepare — before the decoder has read a format
     * and after it has.
     */
    @Test fun anOrdinaryFilmIsOwedNoRePrepareBeforeOrAfterItsFirstFormat() {
        val override = VideoRotationOverride()
        override.reset()
        override.commandTurn(0, viaView = false)
        assertFalse(override.needsDecoderReconfigure())
        assertEquals(0, override.takeForDecoder(0).correctedDegrees)
        assertFalse(override.needsDecoderReconfigure())
    }

    /**
     * The saving the view turn buys, and the one a viewer feels: asserting a turn
     * on an ordinary film hands the surface a 90 and leaves the codec at the 0 it
     * already had, so no re-prepare and no re-buffer is owed at all.
     */
    @Test fun turningAnOrdinaryFilmOwesTheDecoderNothing() {
        val override = VideoRotationOverride()
        override.reset()
        assertEquals(0, override.takeForDecoder(0).correctedDegrees)
        override.commandTurn(90, viaView = true)
        assertFalse(override.needsDecoderReconfigure())
    }

    /**
     * And the case that does cost one: the phone clip, whose codec was configured
     * with the container's own 90 and has to be taken back to 0 so the turn
     * happens exactly once.
     */
    @Test fun turningAContainerRotatedFilmOwesTheDecoderOneRePrepare() {
        val override = VideoRotationOverride()
        override.reset()
        assertEquals(90, override.takeForDecoder(90).correctedDegrees)
        override.commandTurn(0, viaView = true)
        assertTrue(override.needsDecoderReconfigure())
    }

    // --- Once per film, not once per delivery ----------------------------------

    /**
     * `onTracksChanged` fires again for every text-track change and for the
     * panel's 2 Hz re-read. A resolution can cost a re-prepare, so a settled film
     * must not ask for one on each delivery.
     */
    @Test fun laterDeliveriesOfTheSameSettledFilmResolveNothing() {
        repeat(5) {
            assertFalse(
                resolvesPictureTurn(
                    alreadyResolved = true,
                    autoChanged = false,
                    choiceIsAuto = true,
                ),
            )
        }
    }

    /**
     * The duration arrives after the tracks do, so Auto genuinely can change its
     * mind once — a sideways-filed release read as a feature and then as too short
     * to be one. While Auto is still the choice, that answer has to reach the
     * picture.
     */
    @Test fun anAutoVerdictThatChangesLaterStillResolves() {
        val filedSideways = MediaShape(
            video = VideoTrackShape(1920, 1080, 90, 1f, MimeTypes.VIDEO_H264, C.COLOR_TRANSFER_SDR),
            audioTrackCount = 1,
            maxAudioChannelCount = 6,
            audioSampleMimeTypes = listOf(MimeTypes.AUDIO_E_AC3),
            embeddedTextTrackCount = 1,
            durationMs = null,
        )
        assertEquals(270, autoRotation(filedSideways).extraDegrees)
        assertEquals(
            AutoRotationVerdict.ShorterThanAFeature,
            autoRotation(filedSideways.copy(durationMs = 42_000L)).verdict,
        )
        assertTrue(
            resolvesPictureTurn(
                alreadyResolved = true,
                autoChanged = true,
                choiceIsAuto = true,
            ),
        )
    }

    /** A viewer who chose a turn themselves does not have Auto change it back. */
    @Test fun anAutoChangeUnderAnExplicitChoiceResolvesNothing() {
        assertFalse(
            resolvesPictureTurn(
                alreadyResolved = true,
                autoChanged = true,
                choiceIsAuto = false,
            ),
        )
    }

    /** A film the turn already failed on is never given another one. */
    @Test fun aFilmThatLostItsTurnIsShownAsFiledAndSaysSo() {
        val shape = phonePortraitClip()
        val turn = pictureTurnFor(
            containerDegrees = shape.video!!.rotationDegrees,
            extraDegrees = 0,
            colour = PictureColour.Sdr,
            turnUnavailable = true,
        )
        assertEquals(TurnMechanism.Decoder, turn.mechanism)
        assertEquals(90, turn.decoderDegrees)
        assertEquals(TurnNote.NotOnThisTv, turn.note)
    }

    /** A clip whose container is off the quarter-turn grid is left exactly as filed. */
    @Test fun aContainerOffTheGridIsNeverGivenAViewTurn() {
        val shape = phonePortraitClip().let {
            it.copy(video = it.video!!.copy(rotationDegrees = 45))
        }
        val turn = turnFor(shape, extraDegrees = 0)
        assertEquals(TurnMechanism.Decoder, turn.mechanism)
        assertEquals(45, turn.decoderDegrees)
        assertNull(turn.note)
    }
}
