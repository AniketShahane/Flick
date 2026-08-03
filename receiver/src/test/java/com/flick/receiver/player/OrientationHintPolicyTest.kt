package com.flick.receiver.player

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrientationHintPolicyTest {

    // --- Case 1: Flick turned the picture -------------------------------------

    @Test fun aCorrectedFilmSaysSoAndSaysWhereToUndoIt() {
        listOf(90, 270).forEach { container ->
            val shape = film(rotationDegrees = container)
            val auto = autoRotation(shape)
            assertEquals(AutoRotationVerdict.LandscapeFilmFiledSideways, auto.verdict)
            assertEquals(
                OrientationHint.TurnedUpright,
                orientationHintFor(shape, auto, VideoRotation.Auto),
            )
        }
    }

    // --- Case 2: a tall strip Flick did NOT touch ------------------------------

    /**
     * The case the control was invisible for. Nothing in the video track separates
     * this from the film above, so the correction deliberately does not fire — and
     * the viewer is left looking at a sideways picture with no way to find the row
     * that fixes it.
     */
    @Test fun aSidewaysPictureFlickWasTooConservativeToTurnPointsAtThePanel() {
        val shape = featureLengthClip(rotationDegrees = 90)
        val auto = autoRotation(shape)
        assertEquals(AutoRotationVerdict.LooksLikeACameraClip, auto.verdict)
        assertEquals(
            OrientationHint.ShownAsFiled,
            orientationHintFor(shape, auto, VideoRotation.Auto),
        )
    }

    @Test fun aShortSidewaysClipPointsAtThePanelToo() {
        // Assembled, but under the duration floor — the other way to be left alone.
        val shape = film(rotationDegrees = 270, durationMs = 60_000L)
        val auto = autoRotation(shape)
        assertEquals(AutoRotationVerdict.ShorterThanAFeature, auto.verdict)
        assertEquals(
            OrientationHint.ShownAsFiled,
            orientationHintFor(shape, auto, VideoRotation.Auto),
        )
    }

    /**
     * A portrait coded frame with no rotation matrix at all is not detectable as an
     * error, so `autoRotation` never looks at it twice. It is still a tall strip on
     * a landscape panel, which is the only thing the hint is reading.
     */
    @Test fun aPortraitRecordingWithNoRotationMatrixStillPointsAtThePanel() {
        val shape = cameraClip(rotationDegrees = 0).copy(
            video = VideoTrackShape(1080, 1920, 0, 1f),
        )
        val auto = autoRotation(shape)
        assertEquals(AutoRotationVerdict.ContainerNotSideways, auto.verdict)
        assertEquals(
            OrientationHint.ShownAsFiled,
            orientationHintFor(shape, auto, VideoRotation.Auto),
        )
    }

    @Test fun anAnamorphicFrameIsJudgedOnItsDisplayedWidth() {
        // 1440x1080 at 1.333 is a 16:9 picture; the container's turn makes it tall.
        val shape = featureLengthClip(rotationDegrees = 90).copy(
            video = VideoTrackShape(1440, 1080, 90, 1.3333f),
        )
        assertEquals(
            OrientationHint.ShownAsFiled,
            orientationHintFor(shape, autoRotation(shape), VideoRotation.Auto),
        )
    }

    // --- Everything else says nothing -----------------------------------------

    @Test fun anOrdinaryLandscapeFilmSaysNothing() {
        val shape = film(rotationDegrees = 0)
        val auto = autoRotation(shape)
        assertEquals(AutoRotationVerdict.ContainerNotSideways, auto.verdict)
        assertNull(orientationHintFor(shape, auto, VideoRotation.Auto))
    }

    @Test fun aPortraitFrameTheContainersTurnMakesLandscapeSaysNothing() {
        val shape = cameraClip(rotationDegrees = 90).copy(
            video = VideoTrackShape(1080, 1920, 90, 1f),
        )
        val auto = autoRotation(shape)
        assertEquals(AutoRotationVerdict.RotationMakesItLandscape, auto.verdict)
        assertNull(orientationHintFor(shape, auto, VideoRotation.Auto))
    }

    @Test fun noVideoTrackSaysNothing() {
        val shape = film(rotationDegrees = 90).copy(video = null)
        val auto = autoRotation(shape)
        assertEquals(AutoRotationVerdict.NoVideoTrack, auto.verdict)
        assertNull(orientationHintFor(shape, auto, VideoRotation.Auto))
    }

    @Test fun aSquarePictureSaysNothing() {
        val shape = cameraClip(rotationDegrees = 90).copy(
            video = VideoTrackShape(1080, 1080, 90, 1f),
        )
        assertNull(orientationHintFor(shape, autoRotation(shape), VideoRotation.Auto))
    }

    @Test fun aTurnOffTheQuarterGridSaysNothing() {
        val shape = cameraClip(rotationDegrees = 45)
        assertNull(orientationHintFor(shape, autoRotation(shape), VideoRotation.Auto))
    }

    // --- A rotation the viewer chose is not Flick's to explain -----------------

    @Test fun aViewerWhoAlreadyTurnedThePictureIsToldNothing() {
        // Every explicit cell, over both films that would otherwise say something.
        listOf(film(rotationDegrees = 90), featureLengthClip(rotationDegrees = 90)).forEach { shape ->
            val auto = autoRotation(shape)
            VideoRotation.ALL.filter { it != VideoRotation.Auto }.forEach { choice ->
                assertNull(orientationHintFor(shape, auto, choice))
            }
        }
    }

    @Test fun handingTheReadingBackToAutoOffersItAgain() {
        // The phone's Auto cell and the panel's both land on VideoRotation.Auto, so
        // the reading is offered again; the once-per-cast latch is what stops it.
        val shape = featureLengthClip(rotationDegrees = 90)
        val auto = autoRotation(shape)
        assertNull(orientationHintFor(shape, auto, VideoRotation.Quarter))
        assertEquals(
            OrientationHint.ShownAsFiled,
            orientationHintFor(shape, auto, VideoRotation.Auto),
        )
    }

    // --- The presented picture, straight from the container --------------------

    @Test fun aQuarterTurnPresentsTheCodedFrameTheOtherWayRound() {
        val landscape = VideoTrackShape(1920, 1080, 0, 1f)
        assertEquals(PictureShape.Landscape, presentedShape(landscape, 0))
        assertEquals(PictureShape.Portrait, presentedShape(landscape.copy(rotationDegrees = 90), 0))
        assertEquals(PictureShape.Portrait, presentedShape(landscape.copy(rotationDegrees = 270), 0))
        assertEquals(PictureShape.Landscape, presentedShape(landscape.copy(rotationDegrees = 180), 0))
    }

    @Test fun flicksOwnTurnIsCountedOnTopOfTheContainers() {
        val sideways = VideoTrackShape(1920, 1080, 90, 1f)
        assertEquals(PictureShape.Portrait, presentedShape(sideways, 0))
        // 90 + 270 comes back to 0, which is what the correction does.
        assertEquals(PictureShape.Landscape, presentedShape(sideways, 270))
        assertEquals(PictureShape.Portrait, presentedShape(sideways, 180))
    }

    @Test fun anUnreadablePictureIsNeither() {
        assertEquals(PictureShape.Neither, presentedShape(null, 0))
        assertEquals(PictureShape.Neither, presentedShape(VideoTrackShape(0, 0, 0, 1f), 0))
        assertEquals(PictureShape.Neither, presentedShape(VideoTrackShape(1920, 1080, 45, 1f), 0))
        assertEquals(PictureShape.Neither, presentedShape(VideoTrackShape(1080, 1080, 90, 1f), 0))
    }

    // --- One life per cast -----------------------------------------------------

    @Test fun nothingToSayMeansNothingToWaitFor() {
        assertEquals(
            OrientationHintPhase.Waiting,
            orientationHintPhase(
                null,
                filmVisible = true,
                qualityShowing = false,
                panelOpen = false,
                alreadyShown = false,
            ),
        )
    }

    @Test fun theReadingWaitsForTheFilmToBeOnScreen() {
        assertEquals(
            OrientationHintPhase.Waiting,
            orientationHintPhase(
                OrientationHint.TurnedUpright,
                filmVisible = false,
                qualityShowing = false,
                panelOpen = false,
                alreadyShown = false,
            ),
        )
        assertEquals(
            OrientationHintPhase.Showing,
            orientationHintPhase(
                OrientationHint.TurnedUpright,
                filmVisible = true,
                qualityShowing = false,
                panelOpen = false,
                alreadyShown = false,
            ),
        )
    }

    /** Both live in the band under the top pill row, and that card is full-bleed. */
    @Test fun theQualityFlourishGetsTheBandFirst() {
        assertEquals(
            OrientationHintPhase.Waiting,
            orientationHintPhase(
                OrientationHint.ShownAsFiled,
                filmVisible = true,
                qualityShowing = true,
                panelOpen = false,
                alreadyShown = false,
            ),
        )
        assertEquals(
            OrientationHintPhase.Showing,
            orientationHintPhase(
                OrientationHint.ShownAsFiled,
                filmVisible = true,
                qualityShowing = false,
                panelOpen = false,
                alreadyShown = false,
            ),
        )
    }

    /** Waiting on the flourish must not outrank reaching the panel. */
    @Test fun thePanelStillSpendsItWhileTheFlourishHoldsTheBand() {
        assertEquals(
            OrientationHintPhase.Spent,
            orientationHintPhase(
                OrientationHint.ShownAsFiled,
                filmVisible = true,
                qualityShowing = true,
                panelOpen = true,
                alreadyShown = false,
            ),
        )
    }

    @Test fun theOpenPanelIsTheDoorThisPointsAtSoItSpendsTheHint() {
        listOf(true, false).forEach { filmVisible ->
            assertEquals(
                OrientationHintPhase.Spent,
                orientationHintPhase(
                    OrientationHint.ShownAsFiled,
                    filmVisible = filmVisible,
                    qualityShowing = false,
                    panelOpen = true,
                    alreadyShown = false,
                ),
            )
        }
    }

    @Test fun anOpenPanelWithNothingToSayHasNothingToSpend() {
        assertEquals(
            OrientationHintPhase.Waiting,
            orientationHintPhase(
                null,
                filmVisible = true,
                qualityShowing = false,
                panelOpen = true,
                alreadyShown = false,
            ),
        )
    }

    @Test fun onceGivenItNeverComesBack() {
        OrientationHint.values().forEach { hint ->
            assertEquals(
                OrientationHintPhase.Spent,
                orientationHintPhase(
                    hint,
                    filmVisible = true,
                    qualityShowing = false,
                    panelOpen = false,
                    alreadyShown = true,
                ),
            )
        }
    }

    @Test fun itIsLongEnoughToOutliveTheChromeThatFallsAwayUnderIt() {
        // The chrome auto-hide is 4 s and the quality flourish holds 4.5 s.
        assertTrue(ORIENTATION_HINT_MS > 4_500L)
    }

    // --- Fixtures --------------------------------------------------------------

    /** A released title: assembled, feature length, filed with a display matrix. */
    private fun film(rotationDegrees: Int, durationMs: Long? = 7_200_000L) = MediaShape(
        video = VideoTrackShape(1920, 1080, rotationDegrees, 1f),
        audioTrackCount = 1,
        maxAudioChannelCount = 6,
        audioSampleMimeTypes = listOf(MimeTypes.AUDIO_E_AC3),
        embeddedTextTrackCount = 0,
        durationMs = durationMs,
    )

    /** The same frame off a phone held upright: one stereo capture track, nothing else. */
    private fun cameraClip(rotationDegrees: Int, durationMs: Long? = 45_000L) = MediaShape(
        video = VideoTrackShape(1920, 1080, rotationDegrees, 1f),
        audioTrackCount = 1,
        maxAudioChannelCount = 2,
        audioSampleMimeTypes = listOf(MimeTypes.AUDIO_AAC),
        embeddedTextTrackCount = 0,
        durationMs = durationMs,
    )

    /** Long enough that a test measures the evidence rule rather than the duration floor. */
    private fun featureLengthClip(rotationDegrees: Int) =
        cameraClip(rotationDegrees, durationMs = 7_200_000L)
}
