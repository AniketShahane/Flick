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
 * resolution triggered by Auto's ANSWER moving never fires for it, no graph is
 * ever built, and the decoder is configured with a turn the verified TV's display
 * pipeline drops. Every such clip then plays as a sideways landscape picture from
 * the first frame, with no key pressed.
 *
 * The counterweight is the other test in each pair: an ordinary film must resolve
 * to no work whatsoever, because a rebuild or a re-prepare spent on a cast that
 * needs neither is the anti-buffering thesis being spent for nothing.
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
        hdrSurvivesFrames = false,
        framesUnavailable = false,
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
     * show through the effects graph.
     */
    @Test fun theClipsTotalTurnNeedsTheFramesMechanism() {
        val turn = turnFor(phonePortraitClip(), extraDegrees = 0)
        assertEquals(TurnMechanism.Frames, turn.mechanism)
        assertEquals(90, turn.frameDegrees)
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

    @Test fun anOrdinaryFilmResolvesToNoGraphAndNoTurnAtAll() {
        val turn = turnFor(ordinaryFilm(), extraDegrees = 0)
        assertEquals(TurnMechanism.Decoder, turn.mechanism)
        assertEquals(0, turn.frameDegrees)
        assertEquals(0, turn.decoderDegrees)
        assertNull(turn.note)
    }

    /** Including the one this app exists for, whose GL pass would cost the grade. */
    @Test fun a4kDolbyVisionCastResolvesToNoGraphEither() {
        val film = ordinaryFilm(
            sampleMimeType = MimeTypes.VIDEO_DOLBY_VISION,
            colorTransfer = C.COLOR_TRANSFER_ST2084,
        )
        val turn = turnFor(film, extraDegrees = 0)
        assertEquals(TurnMechanism.Decoder, turn.mechanism)
        assertEquals(0, turn.frameDegrees)
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
        assertFalse(override.needsDecoderReconfigure(0, viaFrames = false))
        assertEquals(0, override.takeForDecoder(0).correctedDegrees)
        assertFalse(override.needsDecoderReconfigure(0, viaFrames = false))
    }

    // --- Once per film, not once per delivery ----------------------------------

    /**
     * `onTracksChanged` fires again for every text-track change and for the
     * panel's 2 Hz re-read. A resolution can cost a player rebuild, so a settled
     * film must not ask for one on each delivery.
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

    /** A film the graph already failed on is never given another one. */
    @Test fun aFilmThatLostItsGraphIsShownAsFiledAndSaysSo() {
        val shape = phonePortraitClip()
        val turn = pictureTurnFor(
            containerDegrees = shape.video!!.rotationDegrees,
            extraDegrees = 0,
            colour = PictureColour.Sdr,
            hdrSurvivesFrames = false,
            framesUnavailable = true,
        )
        assertEquals(TurnMechanism.Decoder, turn.mechanism)
        assertEquals(90, turn.decoderDegrees)
        assertEquals(TurnNote.NotOnThisTv, turn.note)
    }

    /** A clip whose container is off the quarter-turn grid is left exactly as filed. */
    @Test fun aContainerOffTheGridIsNeverGivenAGraph() {
        val shape = phonePortraitClip().let {
            it.copy(video = it.video!!.copy(rotationDegrees = 45))
        }
        val turn = turnFor(shape, extraDegrees = 0)
        assertEquals(TurnMechanism.Decoder, turn.mechanism)
        assertEquals(45, turn.decoderDegrees)
        assertNull(turn.note)
    }
}
