package com.flick.receiver.player

import com.flick.receiver.net.CastFailureCode
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Conservative mapping: precision is allowed only when supplied evidence proves it. */
object PlaybackFailureClassifier {
    fun classify(networkUnreachable: Boolean = false, refused: Boolean = false, httpStatus: Int? = null,
                 decoderInitialization: Boolean = false, supportedCodecEvidence: Boolean = false,
                 supportedHdrEvidence: Boolean = false): CastFailureCode = when {
        networkUnreachable -> CastFailureCode.MEDIA_UNREACHABLE
        refused -> CastFailureCode.SENDER_NOT_SERVING
        httpStatus != null -> CastFailureCode.HTTP_REJECTED
        decoderInitialization -> CastFailureCode.DECODER_INIT
        supportedCodecEvidence || supportedHdrEvidence -> CastFailureCode.UNSUPPORTED_VIDEO_FORMAT
        else -> CastFailureCode.UNKNOWN
    }

    /** Maps a real player exception without exposing its message or endpoint. */
    fun classify(error: PlaybackException): CastFailureCode {
        var cause: Throwable? = error.cause
        while (cause != null) {
            when (cause) {
                is RedirectRejectedException,
                is PlaybackHttpStatusException -> return CastFailureCode.HTTP_REJECTED
                is ConnectException -> return CastFailureCode.SENDER_NOT_SERVING
                is SocketTimeoutException,
                is NoRouteToHostException,
                is UnknownHostException -> return CastFailureCode.MEDIA_UNREACHABLE
                is ParserException -> return classifyParserFailure(
                    cause.javaClass.name == "androidx.media3.exoplayer.source.UnrecognizedInputFormatException",
                )
            }
            cause = cause.cause
        }
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> CastFailureCode.DECODER_INIT
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> CastFailureCode.UNSUPPORTED_VIDEO_FORMAT
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_TIMEOUT -> CastFailureCode.MEDIA_UNREACHABLE
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> CastFailureCode.SENDER_NOT_SERVING
            else -> CastFailureCode.UNKNOWN
        }
    }

    /** A known extractor/container rejection is more precise than malformed bytes. */
    fun classifyParserFailure(knownUnsupportedContainer: Boolean): CastFailureCode =
        if (knownUnsupportedContainer) CastFailureCode.UNSUPPORTED_CONTAINER else CastFailureCode.MALFORMED_MEDIA

    fun isStartupRetryable(error: PlaybackException): Boolean = when (classify(error)) {
        CastFailureCode.MEDIA_UNREACHABLE,
        CastFailureCode.SENDER_NOT_SERVING -> true
        else -> false
    }

    /** Explicit HTTP rejection is terminal in both startup and steady state. */
    fun isSteadyStateRecoveryAllowed(error: PlaybackException): Boolean =
        classify(error) != CastFailureCode.HTTP_REJECTED
}
