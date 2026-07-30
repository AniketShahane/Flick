package com.flick.receiver.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import com.flick.receiver.net.CastFailureCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class VideoTrackSupportPolicyTest {

    @Test fun aSelectedVideoTrackIsNeverAShortfall() {
        assertNull(
            videoTrackShortfall(
                trackSupports = listOf(C.FORMAT_HANDLED),
                anyVideoSelected = true,
                videoMimeType = MimeTypes.VIDEO_DOLBY_VISION,
            ),
        )
        // Even an unsupported-looking group: if something was selected, it plays.
        assertNull(
            videoTrackShortfall(
                trackSupports = listOf(C.FORMAT_UNSUPPORTED_SUBTYPE, C.FORMAT_HANDLED),
                anyVideoSelected = true,
                videoMimeType = MimeTypes.VIDEO_H265,
            ),
        )
    }

    @Test fun noVideoGroupAtAllIsNotAShortfall() {
        // Before tracks are known, and for an audio-only file: nothing to judge.
        assertNull(videoTrackShortfall(emptyList(), anyVideoSelected = false, videoMimeType = null))
    }

    @Test fun aHandledFormatLeftUnselectedIsNotBlamedOnCapability() {
        // Something other than capability declined to select it. Refusing here would
        // refuse a film this TV can actually decode.
        assertNull(
            videoTrackShortfall(
                trackSupports = listOf(C.FORMAT_HANDLED),
                anyVideoSelected = false,
                videoMimeType = MimeTypes.VIDEO_H265,
            ),
        )
    }

    @Test fun anEmptyHardwareOnlyCodecListReadsAsNoDecoderForTheCodec() {
        // This is the non-MediaTek Android 8-9 TV case: the selector returned nothing,
        // so the track selector marked the video FORMAT_UNSUPPORTED_SUBTYPE.
        assertEquals(
            VideoTrackShortfall.NoDecoderForCodec,
            videoTrackShortfall(
                trackSupports = listOf(C.FORMAT_UNSUPPORTED_SUBTYPE),
                anyVideoSelected = false,
                videoMimeType = MimeTypes.VIDEO_H265,
            ),
        )
        assertEquals(
            VideoTrackShortfall.NoDecoderForCodec,
            videoTrackShortfall(
                trackSupports = listOf(C.FORMAT_UNSUPPORTED_TYPE),
                anyVideoSelected = false,
                videoMimeType = MimeTypes.VIDEO_H264,
            ),
        )
    }

    @Test fun aDolbyVisionFileWithNoDvDecoderIsAnHdrProfileFaultNotAGuess() {
        // video/dolby-vision as an unsupported subtype means precisely that this TV has
        // no DV pipeline — which has its own, better wording in the wire vocabulary.
        assertEquals(
            VideoTrackShortfall.NoDolbyVisionDecoder,
            videoTrackShortfall(
                trackSupports = listOf(C.FORMAT_UNSUPPORTED_SUBTYPE),
                anyVideoSelected = false,
                videoMimeType = MimeTypes.VIDEO_DOLBY_VISION,
            ),
        )
    }

    @Test fun exceedingCapabilitiesStaysGenericEvenForAnHdrFile() {
        // A 4K DV file rejected by a 1080p decoder is a RESOLUTION fault. Blaming HDR
        // because the file happens to be HDR would be inventing a cause.
        assertEquals(
            VideoTrackShortfall.ExceedsDecoderCapabilities,
            videoTrackShortfall(
                trackSupports = listOf(C.FORMAT_EXCEEDS_CAPABILITIES),
                anyVideoSelected = false,
                videoMimeType = MimeTypes.VIDEO_DOLBY_VISION,
            ),
        )
    }

    @Test fun theBestSupportLevelInTheGroupDecidesTheReason() {
        // C.FORMAT_* is ordered HANDLED(4) > EXCEEDS(3) > DRM(2) > SUBTYPE(1) > TYPE(0).
        assertEquals(
            VideoTrackShortfall.ExceedsDecoderCapabilities,
            videoTrackShortfall(
                trackSupports = listOf(C.FORMAT_UNSUPPORTED_TYPE, C.FORMAT_EXCEEDS_CAPABILITIES, C.FORMAT_UNSUPPORTED_SUBTYPE),
                anyVideoSelected = false,
                videoMimeType = MimeTypes.VIDEO_H265,
            ),
        )
        assertEquals(
            VideoTrackShortfall.DrmUnsupported,
            videoTrackShortfall(
                trackSupports = listOf(C.FORMAT_UNSUPPORTED_TYPE, C.FORMAT_UNSUPPORTED_DRM),
                anyVideoSelected = false,
                videoMimeType = MimeTypes.VIDEO_H265,
            ),
        )
    }

    // ── What the phone and the TV screen are told ────────────────────────────

    @Test fun theTwoPreviouslyDeadWireCodesAreNowEmitted() {
        assertEquals(
            CastFailureCode.UNSUPPORTED_VIDEO_CODEC,
            PlaybackFailureClassifier.classifyVideoTrackShortfall(VideoTrackShortfall.NoDecoderForCodec),
        )
        assertEquals(
            CastFailureCode.UNSUPPORTED_HDR_PROFILE,
            PlaybackFailureClassifier.classifyVideoTrackShortfall(VideoTrackShortfall.NoDolbyVisionDecoder),
        )
        assertEquals(
            CastFailureCode.UNSUPPORTED_VIDEO_FORMAT,
            PlaybackFailureClassifier.classifyVideoTrackShortfall(VideoTrackShortfall.ExceedsDecoderCapabilities),
        )
        assertEquals(
            CastFailureCode.UNSUPPORTED_VIDEO_FORMAT,
            PlaybackFailureClassifier.classifyVideoTrackShortfall(VideoTrackShortfall.DrmUnsupported),
        )
    }

    @Test fun theShortfallReachesTheClassifierThroughTheCauseChain() {
        for (shortfall in VideoTrackShortfall.entries) {
            val error = PlaybackException(
                "video track unplayable",
                UnplayableVideoTrackException(shortfall),
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            )
            assertEquals(
                shortfall.name,
                PlaybackFailureClassifier.classifyVideoTrackShortfall(shortfall),
                PlaybackFailureClassifier.classify(error),
            )
        }
    }

    @Test fun anUnplayableFormatIsNeverOfferedAsRetryable() {
        // The bug this replaces: an 18 s STARTUP_TIMEOUT with retryable = true, offering
        // a Retry button that can never succeed, forever.
        for (shortfall in VideoTrackShortfall.entries) {
            val error = PlaybackException(
                "video track unplayable",
                UnplayableVideoTrackException(shortfall),
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            )
            assertFalse(shortfall.name, PlaybackFailureClassifier.isStartupRetryable(error))
        }
    }

    @Test fun theRenderPathAndBitstreamCodesNoLongerCollapseToUnknown() {
        fun codeFor(errorCode: Int) =
            PlaybackFailureClassifier.classify(PlaybackException("e", null, errorCode))

        assertEquals(CastFailureCode.DECODER_INIT, codeFor(PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED))
        assertEquals(CastFailureCode.DECODER_INIT, codeFor(PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED))
        assertEquals(CastFailureCode.DECODER_INIT, codeFor(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED))
        assertEquals(CastFailureCode.DECODER_INIT, codeFor(PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED))
        assertEquals(CastFailureCode.DECODER_INIT, codeFor(PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED))
        assertEquals(CastFailureCode.DECODER_INIT, codeFor(PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED))
        assertEquals(CastFailureCode.MALFORMED_MEDIA, codeFor(PlaybackException.ERROR_CODE_DECODING_FAILED))
        // Unchanged, and still the right answers.
        assertEquals(CastFailureCode.DECODER_INIT, codeFor(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        assertEquals(
            CastFailureCode.UNSUPPORTED_VIDEO_FORMAT,
            codeFor(PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES),
        )
    }
}
