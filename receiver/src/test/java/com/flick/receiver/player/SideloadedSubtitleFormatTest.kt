package com.flick.receiver.player

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Test

class SideloadedSubtitleFormatTest {
    @Test fun aVttLabelDeclaresVtt() {
        assertEquals(MimeTypes.TEXT_VTT, sideloadedSubtitleMimeType("Interstellar.en.vtt"))
        assertEquals(MimeTypes.TEXT_VTT, sideloadedSubtitleMimeType("INTERSTELLAR.EN.VTT"))
        assertEquals(MimeTypes.TEXT_VTT, sideloadedSubtitleMimeType("subs.vtt "))
        // The sender declares these as text/x-ssa; handing an ASS payload to the SubRip
        // parser attaches a track that draws nothing.
        assertEquals(MimeTypes.TEXT_SSA, sideloadedSubtitleMimeType("Interstellar.ass"))
        assertEquals(MimeTypes.TEXT_SSA, sideloadedSubtitleMimeType("INTERSTELLAR.SSA"))
    }

    @Test fun everythingElseDeclaresSubRip() {
        assertEquals(MimeTypes.APPLICATION_SUBRIP, sideloadedSubtitleMimeType("Interstellar.en.srt"))
        assertEquals(MimeTypes.APPLICATION_SUBRIP, sideloadedSubtitleMimeType("English"))
        assertEquals(MimeTypes.APPLICATION_SUBRIP, sideloadedSubtitleMimeType("vtt"))
        assertEquals(MimeTypes.APPLICATION_SUBRIP, sideloadedSubtitleMimeType("film.vtt.srt"))
        assertEquals(MimeTypes.APPLICATION_SUBRIP, sideloadedSubtitleMimeType(null))
    }
}
