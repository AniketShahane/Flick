package com.flick.receiver.net

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The phone reads this frame against an exact-key allowlist, so its shape is the
 * contract rather than a detail of it: a fifth key, a renamed one, or a null
 * `mime` is a refused frame, and a refused frame closes the control socket of a
 * film that is still playing perfectly well.
 */
class AudioSilentFrameSchemaTest {

    private val castId = "MDEyMzQ1Njc4OWFiY2RlZg"

    @Test fun audioSilentIsExactlyFourKeysAndCastCorrelated() {
        assertEquals(
            linkedMapOf<String, Any?>(
                "t" to "audio_silent",
                "v" to 2,
                "castId" to castId,
                "mime" to "audio/vnd.dts",
            ),
            audioSilentFrameFields(castId, "audio/vnd.dts"),
        )
    }

    /** A container that named no audio format still produces a string. */
    @Test fun theUnknownFormatIsAValueAndNeverAMissingField() {
        val fields = audioSilentFrameFields(castId, "unknown")
        assertEquals(setOf("t", "v", "castId", "mime"), fields.keys)
        assertEquals("unknown", fields["mime"])
    }
}
