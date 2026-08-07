package com.flick.receiver.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioOutputPolicyTest {

    /**
     * The four the platform raises when the output will not take what it was
     * handed. The observed one is INIT_FAILED — AudioFlinger refusing an AC-3
     * bitstream on a PCM-only Bluetooth route — but a route that changes mid-film
     * lands on a write failure instead and wants the same answer.
     */
    @Test
    fun `every way the output can refuse the track counts`() {
        for (code in listOf(
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED,
        )) {
            assertTrue(code.toString(), AudioOutputPolicy.isOutputRefusal(code))
        }
    }

    /**
     * Nothing else may take this path. A decoder that would not start, a format
     * the TV cannot handle and a network that fell over are all different faults
     * with their own diagnoses, and rebuilding the audio sink fixes none of them.
     */
    @Test
    fun `no other failure is an output refusal`() {
        for (code in listOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_UNSPECIFIED,
        )) {
            assertFalse(code.toString(), AudioOutputPolicy.isOutputRefusal(code))
        }
    }

    @Test
    fun `a first refusal asks for the sink to be rebuilt`() {
        assertTrue(
            AudioOutputPolicy.shouldDecodeInstead(
                PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
                alreadyDecoding = false,
            ),
        )
    }

    /**
     * The bound that stops a loop. Once the sink is already decoding, a further
     * refusal is not about passthrough, and rebuilding again would only produce
     * the same sink and the same failure.
     */
    @Test
    fun `a refusal that survives the rebuild is not rebuilt again`() {
        assertFalse(
            AudioOutputPolicy.shouldDecodeInstead(
                PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
                alreadyDecoding = true,
            ),
        )
    }

    @Test
    fun `an unrelated failure is never rebuilt, decoding or not`() {
        for (decoding in listOf(false, true)) {
            assertFalse(
                decoding.toString(),
                AudioOutputPolicy.shouldDecodeInstead(
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    alreadyDecoding = decoding,
                ),
            )
        }
    }
}
