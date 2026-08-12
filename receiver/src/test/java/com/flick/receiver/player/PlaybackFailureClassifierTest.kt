package com.flick.receiver.player

import androidx.media3.common.PlaybackException
import com.flick.receiver.net.CastFailureCode
import java.net.ConnectException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackFailureClassifierTest {
    @Test fun onlyEvidenceBackedCategoriesArePrecise() {
        assertEquals(CastFailureCode.UNKNOWN, PlaybackFailureClassifier.classify())
        assertEquals(CastFailureCode.HTTP_REJECTED, PlaybackFailureClassifier.classify(httpStatus = 302))
        assertEquals(CastFailureCode.DECODER_INIT, PlaybackFailureClassifier.classify(decoderInitialization = true))
        assertEquals(CastFailureCode.UNSUPPORTED_VIDEO_FORMAT, PlaybackFailureClassifier.classify(supportedCodecEvidence = true))
    }

    @Test fun realPlaybackExceptionsUseTheSafeStartupTaxonomy() {
        val refused = PlaybackException(
            "connection refused",
            ConnectException(),
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        )
        val unsupported = PlaybackException(
            "format unsupported",
            null,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        )
        assertEquals(CastFailureCode.SENDER_NOT_SERVING, PlaybackFailureClassifier.classify(refused))
        assertEquals(CastFailureCode.UNSUPPORTED_VIDEO_FORMAT, PlaybackFailureClassifier.classify(unsupported))
        assertTrue(PlaybackFailureClassifier.isStartupRetryable(refused))
        assertFalse(PlaybackFailureClassifier.isStartupRetryable(unsupported))
    }

    @Test fun knownContainerRejectionPrecedesGenericParserFailure() {
        assertEquals(CastFailureCode.UNSUPPORTED_CONTAINER, PlaybackFailureClassifier.classifyParserFailure(true))
        assertEquals(CastFailureCode.MALFORMED_MEDIA, PlaybackFailureClassifier.classifyParserFailure(false))
    }

    /**
     * `sender_not_serving` drives the most assertive sentences either app owns — this
     * TV's "is on the network and answering" and the phone's "Flick's own server is what
     * went down" — and an unspecified IO error is evidence for neither party.
     */
    @Test fun anUnspecifiedIoFailureEarnsNoDiagnosis() {
        val unspecified = PlaybackException("io", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        assertEquals(CastFailureCode.UNKNOWN, PlaybackFailureClassifier.classify(unspecified))
    }

    /** It still earns a retry: a second inside the startup budget is cheaper than a wrong sentence. */
    @Test fun anUnspecifiedIoFailureIsStillRetriedDuringStartup() {
        val unspecified = PlaybackException("io", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        assertTrue(PlaybackFailureClassifier.isStartupRetryable(unspecified))
    }

    /** A rejection carried on a 2000 exception is a rejection, and retrying one retries a refusal. */
    @Test fun aRejectionWearingTheUnspecifiedCodeIsNotRetried() {
        val rejected = PlaybackException(
            "status",
            PlaybackHttpStatusException(404),
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        )
        assertEquals(CastFailureCode.HTTP_REJECTED, PlaybackFailureClassifier.classify(rejected))
        assertFalse(PlaybackFailureClassifier.isStartupRetryable(rejected))
    }

    /** A read past the end of what is served is a statement about the source. */
    @Test fun aReadPastTheEndOfTheFileIndictsTheSource() {
        val pastEnd = PlaybackException(
            "eof",
            null,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        )
        assertEquals(CastFailureCode.SENDER_NOT_SERVING, PlaybackFailureClassifier.classify(pastEnd))
    }

    /** Deliberately generic: this app opens no protected media and installs no effects. */
    @Test fun drmAndFrameProcessorFailuresStayGeneric() {
        for (code in listOf(
            PlaybackException.ERROR_CODE_DRM_UNSPECIFIED,
            PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED,
            PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED,
            PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED,
        )) {
            val error = PlaybackException("x", null, code)
            assertEquals("code=$code", CastFailureCode.UNKNOWN, PlaybackFailureClassifier.classify(error))
        }
    }

    @Test fun explicitHttpRejectionVetoesSteadyStateRecovery() {
        val redirect = PlaybackException(
            "redirect",
            RedirectRejectedException(302),
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        )
        val response = PlaybackException(
            "status",
            PlaybackHttpStatusException(503),
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        )
        assertFalse(PlaybackFailureClassifier.isSteadyStateRecoveryAllowed(redirect))
        assertFalse(PlaybackFailureClassifier.isSteadyStateRecoveryAllowed(response))
    }
}
