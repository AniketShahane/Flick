package com.flick.receiver.session

import com.flick.receiver.net.CastFailureCode
import org.junit.Assert.assertEquals
import org.junit.Test

class ErrorKindTest {

    @Test fun aDecodeFaultNoLongerBlamesThePhoneForStoppingServing() {
        // The regression this replaces: every non-MEDIA_UNREACHABLE code collapsed to
        // NotServing, so the TV told the viewer "Your phone stopped serving… battery
        // saver paused it" while the phone was serving perfectly.
        for (code in listOf(
            CastFailureCode.UNSUPPORTED_VIDEO_CODEC,
            CastFailureCode.UNSUPPORTED_HDR_PROFILE,
            CastFailureCode.UNSUPPORTED_VIDEO_FORMAT,
            CastFailureCode.UNSUPPORTED_CONTAINER,
            CastFailureCode.MALFORMED_MEDIA,
            CastFailureCode.DECODER_INIT,
        )) {
            assertEquals(code.wire, ErrorKind.Unplayable, errorKindFor(code))
        }
    }

    @Test fun theSendersOwnFaultsStillReadAsNotServing() {
        for (code in listOf(
            CastFailureCode.SENDER_NOT_SERVING,
            CastFailureCode.HTTP_REJECTED,
            CastFailureCode.CONTROL_DISCONNECTED,
            CastFailureCode.STARTUP_TIMEOUT,
            CastFailureCode.PROTOCOL_ERROR,
            CastFailureCode.UNKNOWN,
        )) {
            assertEquals(code.wire, ErrorKind.NotServing, errorKindFor(code))
        }
    }

    @Test fun onlyLeavingTheNetworkIsUnreachable() {
        assertEquals(ErrorKind.Unreachable, errorKindFor(CastFailureCode.MEDIA_UNREACHABLE))
    }

    @Test fun everyWireCodeIsClassifiedAndTheStageAgreesWithTheFunction() {
        for (code in CastFailureCode.entries) {
            val kind = errorKindFor(code)
            assertEquals(
                code.wire,
                kind,
                MediaStage.Error(castId = "cast", code = code, controlLeaseGeneration = 1L).kind,
            )
        }
    }
}
