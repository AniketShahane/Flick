package com.flick.sender

import org.junit.Assert.assertEquals
import org.junit.Test

/** Serving policy for the `/s/{token}` route. */
class SubtitlePolicyTest {

    @Test fun theExtensionPicksTheParserTheReceiverWillUse() {
        assertEquals("application/x-subrip", SubtitlePolicy.mimeFor("Movie.en.srt"))
        assertEquals("application/x-subrip", SubtitlePolicy.mimeFor("MOVIE.SRT"))
        assertEquals("text/vtt", SubtitlePolicy.mimeFor("Movie.vtt"))
        assertEquals("text/vtt", SubtitlePolicy.mimeFor("Movie.WebVTT"))
        // Must match androidx.media3.common.MimeTypes.TEXT_SSA exactly: the receiver
        // selects its parser from this string, and SubRip cannot read an ASS payload.
        assertEquals("text/x-ssa", SubtitlePolicy.mimeFor("Movie.ass"))
        assertEquals("text/x-ssa", SubtitlePolicy.mimeFor("Movie.SSA"))
    }

    @Test fun anythingElseFallsBackToPlainTextRatherThanGuessingAParser() {
        assertEquals("text/plain", SubtitlePolicy.mimeFor("Movie.srt.txt"))
        assertEquals("text/plain", SubtitlePolicy.mimeFor("Movie"))
        assertEquals("text/plain", SubtitlePolicy.mimeFor("Movie."))
        assertEquals("text/plain", SubtitlePolicy.mimeFor(".srt"))
        assertEquals("text/plain", SubtitlePolicy.mimeFor(""))
        assertEquals("text/plain", SubtitlePolicy.mimeFor(null))
    }

    @Test fun theCapIsSmallEnoughThatTheRouteCannotCarryBulkFiles() {
        assertEquals(5L * 1024L * 1024L, SubtitlePolicy.MAX_BYTES)
    }
}
