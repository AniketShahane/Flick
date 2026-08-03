package com.flick.receiver.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoOrientationPolicyTest {

    // --- The correction that started this -------------------------------------

    @Test fun aLandscapeFilmFiledSidewaysIsStoodBackUp() {
        val ninety = autoRotation(film(rotationDegrees = 90))
        assertEquals(AutoRotationVerdict.LandscapeFilmFiledSideways, ninety.verdict)
        assertEquals(270, ninety.extraDegrees)

        val twoSeventy = autoRotation(film(rotationDegrees = 270))
        assertEquals(AutoRotationVerdict.LandscapeFilmFiledSideways, twoSeventy.verdict)
        assertEquals(90, twoSeventy.extraDegrees)
    }

    @Test fun theCorrectionCancelsTheContainersOwnTurn() {
        listOf(90, 270).forEach { container ->
            val correction = autoRotation(film(rotationDegrees = container)).extraDegrees
            assertEquals(0, effectiveRotationDegrees(container, correction))
        }
    }

    // --- The trap: a portrait phone clip is stored exactly the same way -------

    @Test fun aGenuinePortraitPhoneClipIsLeftAlone() {
        val clip = cameraClip(rotationDegrees = 90)
        val result = autoRotation(clip)
        assertEquals(AutoRotationVerdict.LooksLikeACameraClip, result.verdict)
        assertEquals(0, result.extraDegrees)
        // Identical video track to the film above — only the evidence differs.
        assertEquals(film(rotationDegrees = 90).video, clip.video)
    }

    @Test fun aLongPortraitPhoneClipIsStillLeftAlone() {
        val result = autoRotation(cameraClip(rotationDegrees = 90, durationMs = 45 * 60_000L))
        assertEquals(AutoRotationVerdict.LooksLikeACameraClip, result.verdict)
        assertEquals(0, result.extraDegrees)
    }

    @Test fun a4kPortraitPhoneClipIsLeftAlone() {
        val result = autoRotation(
            featureLengthClip(rotationDegrees = 270).copy(
                video = VideoTrackShape(3840, 2160, 270, 1f),
            ),
        )
        assertEquals(AutoRotationVerdict.LooksLikeACameraClip, result.verdict)
    }

    // --- Each release signal, on its own --------------------------------------

    @Test fun aSecondAudioTrackIsEvidenceOfARelease() {
        val result = autoRotation(featureLengthClip(rotationDegrees = 90).copy(audioTrackCount = 2))
        assertEquals(AutoRotationVerdict.LandscapeFilmFiledSideways, result.verdict)
    }

    @Test fun anEmbeddedSubtitleTrackIsEvidenceOfARelease() {
        val result = autoRotation(featureLengthClip(rotationDegrees = 90).copy(embeddedTextTrackCount = 1))
        assertEquals(AutoRotationVerdict.LandscapeFilmFiledSideways, result.verdict)
    }

    @Test fun sixChannelsAreEvidenceOfARelease() {
        val result = autoRotation(featureLengthClip(rotationDegrees = 90).copy(maxAudioChannelCount = 6))
        assertEquals(AutoRotationVerdict.LandscapeFilmFiledSideways, result.verdict)
    }

    @Test fun aBroadcastAudioCodecIsEvidenceEvenDeclaredAsTwoChannels() {
        listOf(
            MimeTypes.AUDIO_AC3,
            MimeTypes.AUDIO_E_AC3,
            MimeTypes.AUDIO_E_AC3_JOC,
            MimeTypes.AUDIO_AC4,
            MimeTypes.AUDIO_TRUEHD,
            MimeTypes.AUDIO_DTS,
            MimeTypes.AUDIO_DTS_HD,
            MimeTypes.AUDIO_DTS_EXPRESS,
            MimeTypes.AUDIO_DTS_X,
        ).forEach { mimeType ->
            val result = autoRotation(
                featureLengthClip(rotationDegrees = 90).copy(audioSampleMimeTypes = listOf(mimeType)),
            )
            assertEquals(mimeType, AutoRotationVerdict.LandscapeFilmFiledSideways, result.verdict)
        }
    }

    @Test fun aCaptureAudioCodecIsNotEvidence() {
        listOf(MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_OPUS, MimeTypes.AUDIO_AMR_NB).forEach { mimeType ->
            val result = autoRotation(
                featureLengthClip(rotationDegrees = 90).copy(audioSampleMimeTypes = listOf(mimeType)),
            )
            assertEquals(mimeType, AutoRotationVerdict.LooksLikeACameraClip, result.verdict)
        }
    }

    // --- Everything that must not fire ----------------------------------------

    @Test fun aContainerThatIsNotSidewaysIsNeverSecondGuessed() {
        listOf(0, 180, -180, 360).forEach { rotation ->
            val result = autoRotation(film(rotationDegrees = rotation))
            assertEquals("$rotation", AutoRotationVerdict.ContainerNotSideways, result.verdict)
            assertEquals(0, result.extraDegrees)
        }
    }

    @Test fun aRotationOffTheQuarterTurnGridIsHonoured() {
        val result = autoRotation(film(rotationDegrees = 45))
        assertEquals(AutoRotationVerdict.ContainerNotSideways, result.verdict)
    }

    @Test fun aPortraitCodedFrameIsAlreadyBeingTurnedTheRightWay() {
        val result = autoRotation(
            film(rotationDegrees = 90).copy(video = VideoTrackShape(1080, 1920, 90, 1f)),
        )
        assertEquals(AutoRotationVerdict.RotationMakesItLandscape, result.verdict)
        assertEquals(0, result.extraDegrees)
    }

    @Test fun aSquareCodedFrameIsLeftToTheContainer() {
        val result = autoRotation(
            film(rotationDegrees = 90).copy(video = VideoTrackShape(1080, 1080, 90, 1f)),
        )
        assertEquals(AutoRotationVerdict.RotationMakesItLandscape, result.verdict)
    }

    @Test fun nothingSelectedIsNothingToJudge() {
        assertEquals(
            AutoRotationVerdict.NoVideoTrack,
            autoRotation(film(rotationDegrees = 90).copy(video = null)).verdict,
        )
    }

    @Test fun aFrameWithNoDeclaredSizeIsNothingToJudge() {
        listOf(
            VideoTrackShape(0, 1080, 90, 1f),
            VideoTrackShape(1920, 0, 90, 1f),
            VideoTrackShape(Format.NO_VALUE, Format.NO_VALUE, 90, 1f),
        ).forEach { shape ->
            assertEquals(
                AutoRotationVerdict.NoVideoTrack,
                autoRotation(film(rotationDegrees = 90).copy(video = shape)).verdict,
            )
        }
    }

    @Test fun releaseEvidenceOnSomethingTooShortToBeATitleDoesNotFire() {
        val result = autoRotation(film(rotationDegrees = 90, durationMs = MIN_FEATURE_MS - 1))
        assertEquals(AutoRotationVerdict.ShorterThanAFeature, result.verdict)
        assertEquals(0, result.extraDegrees)
    }

    @Test fun theDurationFloorIsInclusiveAndAnUnknownDurationDoesNotBlock() {
        assertEquals(
            AutoRotationVerdict.LandscapeFilmFiledSideways,
            autoRotation(film(rotationDegrees = 90, durationMs = MIN_FEATURE_MS)).verdict,
        )
        assertEquals(
            AutoRotationVerdict.LandscapeFilmFiledSideways,
            autoRotation(film(rotationDegrees = 90, durationMs = null)).verdict,
        )
        // A timeline reporting 0 has not resolved one either.
        assertEquals(
            AutoRotationVerdict.LandscapeFilmFiledSideways,
            autoRotation(film(rotationDegrees = 90, durationMs = 0L)).verdict,
        )
    }

    // --- Anamorphic content ---------------------------------------------------

    @Test fun sampleAspectDecidesWhetherTheCodedFrameIsLandscape() {
        // 1440×1080 at 4:3 sample aspect is a 16:9 picture stored narrow.
        assertEquals(
            AutoRotationVerdict.LandscapeFilmFiledSideways,
            autoRotation(
                film(rotationDegrees = 90).copy(video = VideoTrackShape(1440, 1080, 90, 4f / 3f)),
            ).verdict,
        )
        // The same pixel count with a narrowing sample aspect is a portrait picture.
        assertEquals(
            AutoRotationVerdict.RotationMakesItLandscape,
            autoRotation(
                film(rotationDegrees = 90).copy(video = VideoTrackShape(1080, 1440, 90, 0.75f)),
            ).verdict,
        )
        // An unreported ratio is treated as square pixels, never as zero.
        assertEquals(
            AutoRotationVerdict.LandscapeFilmFiledSideways,
            autoRotation(
                film(rotationDegrees = 90).copy(video = VideoTrackShape(1920, 1080, 90, 0f)),
            ).verdict,
        )
    }

    // --- Composing the container's turn with Flick's --------------------------

    @Test fun theExtraTurnAddsToTheContainersOwn() {
        assertEquals(0, effectiveRotationDegrees(0, 0))
        assertEquals(90, effectiveRotationDegrees(0, 90))
        assertEquals(0, effectiveRotationDegrees(90, 270))
        assertEquals(90, effectiveRotationDegrees(270, 180))
        assertEquals(180, effectiveRotationDegrees(90, 90))
        assertEquals(270, effectiveRotationDegrees(180, 90))
    }

    @Test fun anOffGridContainerRotationIsReturnedUntouched() {
        assertEquals(45, effectiveRotationDegrees(45, 90))
        assertEquals(90, effectiveRotationDegrees(90, 45))
    }

    @Test fun negativeAndOversizedTurnsWrapRatherThanEscape() {
        assertEquals(270, effectiveRotationDegrees(0, -90))
        assertEquals(90, effectiveRotationDegrees(450, 0))
    }

    // --- The choice enum ------------------------------------------------------

    @Test fun everyExplicitChoiceIsReachableByItsDegrees() {
        assertEquals(VideoRotation.AsFiled, VideoRotation.forExtraDegrees(0))
        assertEquals(VideoRotation.Quarter, VideoRotation.forExtraDegrees(90))
        assertEquals(VideoRotation.Half, VideoRotation.forExtraDegrees(180))
        assertEquals(VideoRotation.ThreeQuarter, VideoRotation.forExtraDegrees(270))
    }

    @Test fun autoIsNotReachableByDegreesAndNeitherIsAnOffGridValue() {
        assertNull(VideoRotation.forExtraDegrees(45))
        assertNull(VideoRotation.forExtraDegrees(-90))
        assertNull(VideoRotation.forExtraDegrees(360))
        assertEquals(5, VideoRotation.ALL.size)
        assertNull(VideoRotation.Auto.extraDegrees)
    }

    // --- Reading a live Tracks ------------------------------------------------

    @Test fun theShapeComesFromTheSelectedVideoTrackAndEveryOtherTrackAroundIt() {
        val tracks = Tracks(
            listOf(
                group(videoFormat(rotationDegrees = 90), selected = false),
                group(videoFormat(rotationDegrees = 270), selected = true),
                group(audioFormat(MimeTypes.AUDIO_E_AC3, channelCount = 6)),
                group(audioFormat(MimeTypes.AUDIO_AAC, channelCount = 2)),
                group(textFormat()),
            ),
        )

        val shape = mediaShapeFrom(tracks, durationMs = 7_200_000L, sideloadedTextTracks = 0)

        assertEquals(VideoTrackShape(1920, 1080, 270, 1f), shape.video)
        assertEquals(2, shape.audioTrackCount)
        assertEquals(6, shape.maxAudioChannelCount)
        assertEquals(listOf(MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_AAC), shape.audioSampleMimeTypes)
        assertEquals(1, shape.embeddedTextTrackCount)
        assertEquals(7_200_000L, shape.durationMs)
        assertEquals(AutoRotationVerdict.LandscapeFilmFiledSideways, autoRotation(shape).verdict)
    }

    @Test fun aSideloadedSubtitleIsNotEvidenceAboutTheFile() {
        val tracks = Tracks(
            listOf(
                group(videoFormat(rotationDegrees = 90), selected = true),
                group(audioFormat(MimeTypes.AUDIO_AAC, channelCount = 2)),
                group(textFormat()),
            ),
        )

        val withPick = mediaShapeFrom(tracks, durationMs = null, sideloadedTextTracks = 1)
        assertEquals(0, withPick.embeddedTextTrackCount)
        assertEquals(AutoRotationVerdict.LooksLikeACameraClip, autoRotation(withPick).verdict)

        val withoutPick = mediaShapeFrom(tracks, durationMs = null, sideloadedTextTracks = 0)
        assertEquals(1, withoutPick.embeddedTextTrackCount)
        assertEquals(AutoRotationVerdict.LandscapeFilmFiledSideways, autoRotation(withoutPick).verdict)
    }

    @Test fun anUnselectedVideoTrackLeavesNothingToJudge() {
        val tracks = Tracks(listOf(group(videoFormat(rotationDegrees = 90), selected = false)))
        val shape = mediaShapeFrom(tracks, durationMs = null, sideloadedTextTracks = 0)
        assertNull(shape.video)
        assertEquals(AutoRotationVerdict.NoVideoTrack, autoRotation(shape).verdict)
    }

    @Test fun emptyTracksReadAsAnEmptyShape() {
        val shape = mediaShapeFrom(Tracks.EMPTY, durationMs = null, sideloadedTextTracks = 0)
        assertEquals(
            MediaShape(
                video = null,
                audioTrackCount = 0,
                maxAudioChannelCount = 0,
                audioSampleMimeTypes = emptyList(),
                embeddedTextTrackCount = 0,
                durationMs = null,
            ),
            shape,
        )
    }

    // --- Fixtures -------------------------------------------------------------

    /** A 1920×1080 frame filed sideways, with the audio a mastered title carries. */
    private fun film(rotationDegrees: Int, durationMs: Long? = 7_200_000L) = MediaShape(
        video = VideoTrackShape(1920, 1080, rotationDegrees, 1f),
        audioTrackCount = 1,
        maxAudioChannelCount = 6,
        audioSampleMimeTypes = listOf(MimeTypes.AUDIO_E_AC3),
        embeddedTextTrackCount = 0,
        durationMs = durationMs,
    )

    /**
     * A clip long enough to clear the duration floor, so a test that adds one
     * release signal is measuring that signal and not the floor.
     */
    private fun featureLengthClip(rotationDegrees: Int) =
        cameraClip(rotationDegrees, durationMs = 7_200_000L)

    /** The same frame, off a phone held upright: one stereo capture track, nothing else. */
    private fun cameraClip(rotationDegrees: Int, durationMs: Long? = 45_000L) = MediaShape(
        video = VideoTrackShape(1920, 1080, rotationDegrees, 1f),
        audioTrackCount = 1,
        maxAudioChannelCount = 2,
        audioSampleMimeTypes = listOf(MimeTypes.AUDIO_AAC),
        embeddedTextTrackCount = 0,
        durationMs = durationMs,
    )

    private fun videoFormat(rotationDegrees: Int): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_H264)
        .setWidth(1920)
        .setHeight(1080)
        .setRotationDegrees(rotationDegrees)
        .build()

    private fun audioFormat(mimeType: String, channelCount: Int): Format = Format.Builder()
        .setSampleMimeType(mimeType)
        .setChannelCount(channelCount)
        .build()

    private fun textFormat(): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
        .setCodecs(MimeTypes.APPLICATION_SUBRIP)
        .build()

    private fun group(format: Format, selected: Boolean = false): Tracks.Group = Tracks.Group(
        TrackGroup(format),
        /* adaptiveSupported = */ false,
        intArrayOf(C.FORMAT_HANDLED),
        booleanArrayOf(selected),
    )
}
