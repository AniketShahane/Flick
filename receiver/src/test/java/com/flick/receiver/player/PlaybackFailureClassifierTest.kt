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
