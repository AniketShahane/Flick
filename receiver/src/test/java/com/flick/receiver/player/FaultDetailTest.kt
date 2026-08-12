package com.flick.receiver.player

import androidx.media3.common.PlaybackException
import com.flick.receiver.session.ReceiverFaultDetail
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `DECODER_INIT` is the honest wire neighbour for three different faults, and the wire
 * is frozen. This is what separates them for the screen, on evidence that never leaves
 * this device.
 */
class FaultDetailTest {

    private fun error(code: Int) = PlaybackException("test", null, code)

    @Test fun `a reclaimed codec names the app that took it`() {
        assertEquals(
            ReceiverFaultDetail.DecoderReclaimed,
            faultDetail(
                error(PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED),
                decodeCompressedAudioLatched = false,
            ),
        )
    }

    /**
     * The first refusal is answered by the sink rebuild and never reaches a screen, so
     * only a refusal AFTER the latch is a terminal audio-output fault.
     */
    @Test fun `an output refusal is only a diagnosis once the rebuild has been spent`() {
        for (code in listOf(
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED,
        )) {
            assertEquals(
                code.toString(),
                ReceiverFaultDetail.AudioOutputRefused,
                faultDetail(error(code), decodeCompressedAudioLatched = true),
            )
            assertEquals(
                code.toString(),
                ReceiverFaultDetail.None,
                faultDetail(error(code), decodeCompressedAudioLatched = false),
            )
        }
    }

    /** A reclaimed codec outranks the audio arm, so the order of the two is asserted. */
    @Test fun `a reclaimed codec is not re-read as an audio refusal`() {
        assertEquals(
            ReceiverFaultDetail.DecoderReclaimed,
            faultDetail(
                error(PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED),
                decodeCompressedAudioLatched = true,
            ),
        )
    }

    @Test fun `everything else carries no local detail at all`() {
        for (code in listOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_UNSPECIFIED,
        )) {
            assertEquals(
                code.toString(),
                ReceiverFaultDetail.None,
                faultDetail(error(code), decodeCompressedAudioLatched = true),
            )
        }
    }
}
