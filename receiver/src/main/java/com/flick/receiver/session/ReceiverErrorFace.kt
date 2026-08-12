package com.flick.receiver.session

import com.flick.receiver.net.CastFailureCode

/**
 * What this TV is allowed to say happened, at the resolution the TV actually has.
 *
 * [ErrorKind] was the right idea at the wrong grain: it is a three-value summary and the
 * screen consumed it as a Boolean. A face is the sentence, so it is driven by the code
 * plus whatever local detail the receiver still holds — the wire vocabulary is frozen
 * (`cap` is exact-match and the sender validates inbound codes against an allow-list), so
 * nothing here may grow it.
 */
enum class ReceiverErrorFace {
    VIDEO_CODEC_UNSUPPORTED, VIDEO_FORMAT_UNSUPPORTED, HDR_PROFILE_UNSUPPORTED,
    CONTAINER_UNSUPPORTED, MEDIA_MALFORMED, DECODER_UNAVAILABLE, DECODER_TAKEN,
    AUDIO_OUTPUT_REFUSED, STARTUP_TIMEOUT, SENDER_REFUSED, SENDER_NOT_SERVING,
    PHONE_UNREACHABLE, LINK_LOST, TV_NETWORK_CHANGED, PICTURE_STOPPED, PLAYBACK_STOPPED,
}

/**
 * Local detail the wire code cannot carry. Every value is derived from the
 * `PlaybackException` and player state the receiver still holds at `recordPlaybackError`,
 * so none of it costs a matched release.
 */
enum class ReceiverFaultDetail { None, AudioOutputRefused, DecoderReclaimed, PictureStopped }

fun receiverErrorFace(code: CastFailureCode, detail: ReceiverFaultDetail): ReceiverErrorFace = when {
    detail == ReceiverFaultDetail.AudioOutputRefused -> ReceiverErrorFace.AUDIO_OUTPUT_REFUSED
    detail == ReceiverFaultDetail.DecoderReclaimed -> ReceiverErrorFace.DECODER_TAKEN
    detail == ReceiverFaultDetail.PictureStopped -> ReceiverErrorFace.PICTURE_STOPPED
    code == CastFailureCode.UNSUPPORTED_VIDEO_CODEC -> ReceiverErrorFace.VIDEO_CODEC_UNSUPPORTED
    code == CastFailureCode.UNSUPPORTED_VIDEO_FORMAT -> ReceiverErrorFace.VIDEO_FORMAT_UNSUPPORTED
    code == CastFailureCode.UNSUPPORTED_HDR_PROFILE -> ReceiverErrorFace.HDR_PROFILE_UNSUPPORTED
    code == CastFailureCode.UNSUPPORTED_CONTAINER -> ReceiverErrorFace.CONTAINER_UNSUPPORTED
    code == CastFailureCode.MALFORMED_MEDIA -> ReceiverErrorFace.MEDIA_MALFORMED
    code == CastFailureCode.DECODER_INIT -> ReceiverErrorFace.DECODER_UNAVAILABLE
    code == CastFailureCode.STARTUP_TIMEOUT -> ReceiverErrorFace.STARTUP_TIMEOUT
    code == CastFailureCode.HTTP_REJECTED -> ReceiverErrorFace.SENDER_REFUSED
    code == CastFailureCode.SENDER_NOT_SERVING -> ReceiverErrorFace.SENDER_NOT_SERVING
    code == CastFailureCode.MEDIA_UNREACHABLE -> ReceiverErrorFace.PHONE_UNREACHABLE
    code == CastFailureCode.CONTROL_DISCONNECTED -> ReceiverErrorFace.LINK_LOST
    code == CastFailureCode.NO_COMPATIBLE_LAN -> ReceiverErrorFace.TV_NETWORK_CHANGED
    else -> ReceiverErrorFace.PLAYBACK_STOPPED
}
