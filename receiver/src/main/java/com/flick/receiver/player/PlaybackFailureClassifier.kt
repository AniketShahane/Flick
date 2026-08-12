package com.flick.receiver.player

import com.flick.receiver.net.CastFailureCode
import com.flick.receiver.session.ReceiverFaultDetail
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
            // A read that ran past the end of what is being served: the file shrank or was
            // replaced under a live session. Bare, with no HTTP 416 and no matched cause,
            // it is still a statement about the source — and the sender can upgrade it
            // with a source fault of its own.
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> CastFailureCode.SENDER_NOT_SERVING
            // 2000 is deliberately NOT diagnosed here. `sender_not_serving` drives the two
            // most assertive sentences either app owns — this TV's "is on the network and
            // answering" and the phone's "Flick's own server is what went down" — and an
            // unspecified IO error is by definition evidence for neither party. UNKNOWN is
            // the floor both screens already carry honest copy for.
            //
            // The DRM codes (6000-6008) and the frame-processor ones (7000/7001) fall here
            // for a different reason and are meant to: this app opens no protected media
            // and installs no video effects, so a face for either would be copy for a
            // state that cannot arise.
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

    /**
     * An unspecified IO failure earns a startup retry even though it earns no diagnosis:
     * a retry inside the startup budget costs a second and a wrong sentence costs the
     * truth, so the two decisions are allowed to read the same code differently.
     */
    fun isStartupRetryable(error: PlaybackException): Boolean = when (classify(error)) {
        CastFailureCode.MEDIA_UNREACHABLE,
        CastFailureCode.SENDER_NOT_SERVING -> true
        // Only where the walk found nothing: an HTTP rejection carried on a 2000
        // exception is still a rejection, and retrying one is retrying a refusal.
        CastFailureCode.UNKNOWN -> error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        else -> false
    }

    /** Explicit HTTP rejection is terminal in both startup and steady state. */
    fun isSteadyStateRecoveryAllowed(error: PlaybackException): Boolean =
        classify(error) != CastFailureCode.HTTP_REJECTED
}

/**
 * Local detail the wire cannot carry. [CastFailureCode.DECODER_INIT] is the honest wire
 * neighbour for three different faults; on this side of the socket the receiver still has
 * the raw exception and can tell them apart for its own screen.
 *
 * [decodeCompressedAudioLatched] is the once-per-cast rebuild having already been spent,
 * which is what makes an output refusal terminal rather than recoverable — an unlatched
 * one never reaches a terminal screen at all.
 */
fun faultDetail(error: PlaybackException, decodeCompressedAudioLatched: Boolean): ReceiverFaultDetail = when {
    error.errorCode == PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED ->
        ReceiverFaultDetail.DecoderReclaimed
    AudioOutputPolicy.isOutputRefusal(error.errorCode) && decodeCompressedAudioLatched ->
        ReceiverFaultDetail.AudioOutputRefused
    else -> ReceiverFaultDetail.None
}
