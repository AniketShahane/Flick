package com.flick.sender.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SilentAudioMemoryTest {

    private val film = "content://media/external/video/media/42"
    private val other = "content://media/external/video/media/43"

    @Test
    fun `a silence is remembered against its own file and no other`() {
        val memory = SilentAudioMemory()
        val marks = memory.mark(film, "audio/vnd.dts")
        assertEquals("audio/vnd.dts", marks[film])
        assertFalse(marks.containsKey(other))
    }

    /** The receiver names what it could not decode, and `unknown` when it could not. */
    @Test
    fun `the mime the receiver named is what is kept`() {
        val memory = SilentAudioMemory()
        assertEquals("unknown", memory.mark(film, "unknown")[film])
    }

    @Test
    fun `the newest report for a file replaces the last one`() {
        val memory = SilentAudioMemory()
        memory.mark(film, "unknown")
        val marks = memory.mark(film, "audio/vnd.dts")
        assertEquals(1, marks.size)
        assertEquals("audio/vnd.dts", marks[film])
    }

    // The cast that clears it is the cast that would report the silence again, so a file
    // the TV now has sound for stops wearing the chip without waiting for a relaunch.
    @Test
    fun `a fresh cast clears the file's silence`() {
        val memory = SilentAudioMemory()
        memory.mark(film, "audio/vnd.dts")
        memory.mark(other, "audio/true-hd")
        val marks = memory.clear(film)
        assertFalse(marks.containsKey(film))
        assertEquals("audio/true-hd", marks[other])
    }

    @Test
    fun `clearing a file that was never marked changes nothing`() {
        val memory = SilentAudioMemory()
        memory.mark(film, "audio/vnd.dts")
        assertEquals(mapOf(film to "audio/vnd.dts"), memory.clear(other))
    }

    // Bounded so casting a whole library at a TV missing one audio decoder cannot grow
    // this without limit.
    @Test
    fun `the oldest silence is forgotten first`() {
        val memory = SilentAudioMemory(limit = 3)
        val marks = (1..5).fold(emptyMap<String, String>()) { _, id ->
            memory.mark("content://media/external/video/media/$id", "audio/vnd.dts")
        }
        assertEquals(3, marks.size)
        assertEquals(
            listOf(
                "content://media/external/video/media/3",
                "content://media/external/video/media/4",
                "content://media/external/video/media/5",
            ),
            marks.keys.toList(),
        )
    }

    // Re-marking is a fresh report, so it also refreshes the file's place in the queue.
    @Test
    fun `a re-marked file is the youngest again`() {
        val memory = SilentAudioMemory(limit = 2)
        memory.mark("a", "audio/vnd.dts")
        memory.mark("b", "audio/vnd.dts")
        memory.mark("a", "unknown")
        val marks = memory.mark("c", "audio/vnd.dts")
        assertEquals(setOf("a", "c"), marks.keys)
    }

    @Test
    fun `the snapshot is a copy, not the live map`() {
        val memory = SilentAudioMemory()
        val before = memory.mark(film, "audio/vnd.dts")
        memory.clear(film)
        assertEquals(mapOf(film to "audio/vnd.dts"), before)
    }
}
