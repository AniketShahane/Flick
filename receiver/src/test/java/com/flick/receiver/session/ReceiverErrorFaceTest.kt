package com.flick.receiver.session

import com.flick.receiver.net.CastFailureCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The face is the sentence the TV puts on screen, so every code and every local detail
 * has to reach the one it earns. `ErrorKind` was exhaustively tested and its only
 * consumer reduced it to a Boolean, which is why the rendered copy is asserted too —
 * see `ErrorScreenFocusTest`.
 */
class ReceiverErrorFaceTest {

    private fun faceOf(code: CastFailureCode) = receiverErrorFace(code, ReceiverFaultDetail.None)

    @Test fun aMissingCodecIsNamedAsOne() {
        assertEquals(
            ReceiverErrorFace.VIDEO_CODEC_UNSUPPORTED,
            faceOf(CastFailureCode.UNSUPPORTED_VIDEO_CODEC),
        )
    }

    @Test fun aRefusedVideoFormatIsNotCalledAMissingCodec() {
        assertEquals(
            ReceiverErrorFace.VIDEO_FORMAT_UNSUPPORTED,
            faceOf(CastFailureCode.UNSUPPORTED_VIDEO_FORMAT),
        )
    }

    @Test fun dolbyVisionGetsItsOwnFace() {
        assertEquals(
            ReceiverErrorFace.HDR_PROFILE_UNSUPPORTED,
            faceOf(CastFailureCode.UNSUPPORTED_HDR_PROFILE),
        )
    }

    @Test fun anUnreadableContainerIsAboutTheWrapperAndNotTheDecoder() {
        assertEquals(
            ReceiverErrorFace.CONTAINER_UNSUPPORTED,
            faceOf(CastFailureCode.UNSUPPORTED_CONTAINER),
        )
    }

    @Test fun malformedBytesAreAboutTheCopyOfTheFilm() {
        assertEquals(ReceiverErrorFace.MEDIA_MALFORMED, faceOf(CastFailureCode.MALFORMED_MEDIA))
    }

    @Test fun aDecoderThatWouldNotOpenIsThisTvsOwnFault() {
        assertEquals(ReceiverErrorFace.DECODER_UNAVAILABLE, faceOf(CastFailureCode.DECODER_INIT))
    }

    @Test fun aStartupTimeoutIsNotDressedAsThePhoneStoppingServing() {
        assertEquals(ReceiverErrorFace.STARTUP_TIMEOUT, faceOf(CastFailureCode.STARTUP_TIMEOUT))
    }

    @Test fun anHttpRejectionNamesThePhoneRefusingTheFile() {
        assertEquals(ReceiverErrorFace.SENDER_REFUSED, faceOf(CastFailureCode.HTTP_REJECTED))
    }

    @Test fun theSendersOwnStopKeepsItsShippedFace() {
        assertEquals(
            ReceiverErrorFace.SENDER_NOT_SERVING,
            faceOf(CastFailureCode.SENDER_NOT_SERVING),
        )
    }

    @Test fun anUnansweredFileServerIsThePhoneBeingUnreachable() {
        assertEquals(ReceiverErrorFace.PHONE_UNREACHABLE, faceOf(CastFailureCode.MEDIA_UNREACHABLE))
    }

    @Test fun aDeadControlSocketIsItsOwnFaceAndNotAnUnreachableFileServer() {
        assertEquals(ReceiverErrorFace.LINK_LOST, faceOf(CastFailureCode.CONTROL_DISCONNECTED))
        assertNotEquals(
            faceOf(CastFailureCode.MEDIA_UNREACHABLE),
            faceOf(CastFailureCode.CONTROL_DISCONNECTED),
        )
    }

    /** The one code whose meaning depends on which device raised it. */
    @Test fun theTvLosingItsAddressIsAboutThisTv() {
        assertEquals(
            ReceiverErrorFace.TV_NETWORK_CHANGED,
            faceOf(CastFailureCode.NO_COMPATIBLE_LAN),
        )
    }

    @Test fun everyUnmappedCodeFallsToTheOneFaceThatExplainsNothing() {
        for (code in listOf(
            CastFailureCode.UNKNOWN,
            CastFailureCode.PROTOCOL_ERROR,
            CastFailureCode.ACTIVE_CAST_BUSY,
            CastFailureCode.TV_BACKGROUNDED,
            CastFailureCode.MEDIA_BIND_FAILED,
            CastFailureCode.HOST_MISMATCH,
        )) {
            assertEquals(code.wire, ReceiverErrorFace.PLAYBACK_STOPPED, faceOf(code))
        }
    }

    @Test fun everyWireCodeResolvesToSomething() {
        for (code in CastFailureCode.entries) {
            assertEquals(code.wire, receiverErrorFace(code, ReceiverFaultDetail.None), faceOf(code))
        }
    }

    // --- The local detail, which the wire cannot carry ---------------------------

    @Test fun aRefusedAudioOutputIsNotReportedAsAMissingDecoder() {
        assertEquals(
            ReceiverErrorFace.AUDIO_OUTPUT_REFUSED,
            receiverErrorFace(CastFailureCode.DECODER_INIT, ReceiverFaultDetail.AudioOutputRefused),
        )
    }

    @Test fun aReclaimedDecoderNamesTheAppThatTookIt() {
        assertEquals(
            ReceiverErrorFace.DECODER_TAKEN,
            receiverErrorFace(CastFailureCode.DECODER_INIT, ReceiverFaultDetail.DecoderReclaimed),
        )
    }

    @Test fun aStoppedPictureIsItsOwnFaceOverTheUnknownCodeItTravelsUnder() {
        assertEquals(
            ReceiverErrorFace.PICTURE_STOPPED,
            receiverErrorFace(CastFailureCode.UNKNOWN, ReceiverFaultDetail.PictureStopped),
        )
    }

    /**
     * The detail is strictly more evidence than the code — it was read from the raw
     * exception on this side of the socket — so it wins wherever the two disagree.
     */
    @Test fun aDetailAlwaysOutranksTheCode() {
        for (code in CastFailureCode.entries) {
            assertEquals(
                code.wire,
                ReceiverErrorFace.AUDIO_OUTPUT_REFUSED,
                receiverErrorFace(code, ReceiverFaultDetail.AudioOutputRefused),
            )
            assertEquals(
                code.wire,
                ReceiverErrorFace.DECODER_TAKEN,
                receiverErrorFace(code, ReceiverFaultDetail.DecoderReclaimed),
            )
            assertEquals(
                code.wire,
                ReceiverErrorFace.PICTURE_STOPPED,
                receiverErrorFace(code, ReceiverFaultDetail.PictureStopped),
            )
        }
    }
}
