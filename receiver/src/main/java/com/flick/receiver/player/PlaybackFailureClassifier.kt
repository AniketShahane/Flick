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
                is UnplayableVideoTrackException -> return classifyVideoTrackShortfall(cause.shortfall)
            }
            cause = cause.cause
        }
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            // Decoder enumeration itself failed, and a codec reclaimed under memory
            // pressure is the same class of fault: the render path could not be stood up.
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
            // The wire vocabulary has no audio-specific code, and adding one would need a
            // matched release of both apps (`cap` is exact-match). DECODER_INIT is the
            // honest neighbour: an AudioTrack this TV will not open or accept output for
            // is a render-path failure, and it is terminal rather than transient.
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED -> CastFailureCode.DECODER_INIT
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> CastFailureCode.UNSUPPORTED_VIDEO_FORMAT
            // A decoder that accepted the format and then failed on samples is reporting
            // the bitstream, not the TV. "Malformed" is the truthful half of that.
            PlaybackException.ERROR_CODE_DECODING_FAILED -> CastFailureCode.MALFORMED_MEDIA
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_TIMEOUT -> CastFailureCode.MEDIA_UNREACHABLE
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> CastFailureCode.SENDER_NOT_SERVING
            else -> CastFailureCode.UNKNOWN
        }
    }

    /**
     * The two most specific codes in the v2 vocabulary — `unsupported_video_codec` and
     * `unsupported_hdr_profile` — already carry good sender-side copy and were never
     * emitted by anything. This is what emits them. No wire change: both are already in
     * the vocabulary the `cap` handshake agreed.
     */
    fun classifyVideoTrackShortfall(shortfall: VideoTrackShortfall): CastFailureCode = when (shortfall) {
        VideoTrackShortfall.NoDecoderForCodec -> CastFailureCode.UNSUPPORTED_VIDEO_CODEC
        VideoTrackShortfall.NoDolbyVisionDecoder -> CastFailureCode.UNSUPPORTED_HDR_PROFILE
        VideoTrackShortfall.ExceedsDecoderCapabilities,
        VideoTrackShortfall.DrmUnsupported -> CastFailureCode.UNSUPPORTED_VIDEO_FORMAT
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
