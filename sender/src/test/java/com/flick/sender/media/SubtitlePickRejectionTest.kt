package com.flick.sender.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The four outcomes a picked document can have, and the one that means "attach it". */
class SubtitlePickRejectionTest {

    @Test
    fun `a named subtitle of a readable size is accepted`() {
        assertNull(subtitlePickRejection("Film.en.srt", 40_000L))
        assertNull(subtitlePickRejection("Film.vtt", 0L))
        assertNull(subtitlePickRejection("Film.ass", SubtitleFiles.MaxSubtitleBytes))
    }

    @Test
    fun `a provider that gave no name at all is its own outcome`() {
        assertEquals(PickRejection.UNNAMED, subtitlePickRejection(null, 40_000L))
    }

    // extensionOf answers null here, and the four accepted extensions are not an
    // instruction anyone can act on for a file that has none.
    @Test
    fun `a name with no extension is not a wrong kind of file`() {
        assertEquals(PickRejection.UNNAMED, subtitlePickRejection("document", 40_000L))
        assertEquals(PickRejection.UNNAMED, subtitlePickRejection("", 40_000L))
    }

    @Test
    fun `an extension Flick does not take is the wrong kind`() {
        assertEquals(PickRejection.WRONG_KIND, subtitlePickRejection("Film.sub", 40_000L))
        assertEquals(PickRejection.WRONG_KIND, subtitlePickRejection("Film.mkv", 40_000L))
        assertEquals(PickRejection.WRONG_KIND, subtitlePickRejection("Film.txt", 40_000L))
    }

    /**
     * The pick path and the serving path have to agree: `MediaHttpServer` refuses an
     * unmeasurable subtitle with a 404, so accepting one here would attach a track the TV
     * is never allowed to fetch.
     */
    @Test
    fun `a size the provider will not report is refused, and is not oversize`() {
        assertEquals(PickRejection.UNMEASURABLE, subtitlePickRejection("Film.srt", -1L))
    }

    @Test
    fun `a file past the ceiling is oversize`() {
        assertEquals(
            PickRejection.TOO_LARGE,
            subtitlePickRejection("Film.srt", SubtitleFiles.MaxSubtitleBytes + 1L),
        )
    }

    // The name is judged before the size, so a picked film is never reported as an
    // oversized subtitle.
    @Test
    fun `the kind is decided before the size`() {
        assertEquals(
            PickRejection.WRONG_KIND,
            subtitlePickRejection("Film.mkv", 8L * 1024L * 1024L * 1024L),
        )
        assertEquals(PickRejection.UNNAMED, subtitlePickRejection(null, -1L))
    }
}
