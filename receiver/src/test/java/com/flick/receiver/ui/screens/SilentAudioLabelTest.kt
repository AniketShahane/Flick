package com.flick.receiver.ui.screens

import com.flick.receiver.player.SILENT_AUDIO_MIME_UNKNOWN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The notice names a format or it names none, and there is no third outcome: a
 * null here selects the sentence that says nothing about which format it was, so
 * a MIME that fell through can never leave a gap where a name should be.
 */
class SilentAudioLabelTest {

    @Test fun theFormatWithNoDecoderOnThisTvIsNamed() {
        assertEquals("DTS", audioSilentFormatLabel("audio/vnd.dts"))
        assertEquals("DTS-HD", audioSilentFormatLabel("audio/vnd.dts.hd"))
    }

    /** Containers are not consistent about case, and neither is this test's TV. */
    @Test fun theNameIsFoundWhateverCaseTheContainerDeclaredIt() {
        assertEquals("DTS", audioSilentFormatLabel("AUDIO/VND.DTS"))
        assertEquals("DTS-HD", audioSilentFormatLabel("Audio/Vnd.Dts.Hd"))
    }

    /**
     * The unknown form, which is a complete sentence on its own. `dts.uhd` is the
     * live example: a true name nobody wants to read from ten feet.
     */
    @Test fun everythingElseTakesTheSentenceThatNamesNoFormat() {
        listOf(
            null,
            SILENT_AUDIO_MIME_UNKNOWN,
            "audio/vnd.dts.uhd;profile=p2",
            "audio/vnd.dts.hd;profile=lbr",
            "audio/true-hd",
            "",
        ).forEach { mimeType ->
            assertNull(mimeType, audioSilentFormatLabel(mimeType))
        }
    }
}
