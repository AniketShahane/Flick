package com.flick.sender.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnplayableMemoryTest {

    private val film = "content://media/external/video/media/42"
    private val other = "content://media/external/video/media/43"

    @Test
    fun `only a fault that names the file may mark it`() {
        for (code in listOf(
            "unsupported_container",
            "unsupported_video_codec",
            "unsupported_video_format",
            "unsupported_hdr_profile",
            "malformed_media",
        )) {
            assertTrue(code, marksFileUnplayable(code))
        }
    }

    // The whole point of the split: a bad network minute must not libel a file, and one
    // decoder the TV failed to start is the TV's state, not the container's contents.
    // `decoder_init` is in this list on its FIRST arrival only — see the repeat tests
    // below, which are the case that made the sheet promise forever.
    @Test
    fun `a link or session fault never marks the file`() {
        for (code in listOf(
            "decoder_init", "media_unreachable", "sender_not_serving", "http_rejected",
            "control_disconnected", "control_unreachable", "startup_timeout", "no_compatible_lan",
            "host_mismatch", "media_bind_failed", "tv_backgrounded", "active_cast_busy",
            "protocol_error", "update_required", "unknown", "a_code_from_a_newer_receiver",
        )) {
            assertFalse(code, marksFileUnplayable(code))
        }
    }

    /**
     * The badge and its TalkBack label both say a TV refused these bytes, so a code no TV
     * can send must never raise one. These three appear in `ControlFrameSchema` but have
     * no constant in the receiver's `CastFailureCode`: the phone raises each for itself,
     * `source_unavailable` before the source server is even asked to bind.
     */
    @Test
    fun `a fault this phone raised for itself never marks the file`() {
        for (code in listOf("update_required", "control_unreachable", "source_unavailable")) {
            assertFalse(code, marksFileUnplayable(code))
        }
    }

    @Test
    fun `a decoder fault marks the file only once it has happened twice`() {
        assertFalse(marksFileUnplayable(DecoderFaultCode, repeatedDecoderFault = false))
        assertTrue(marksFileUnplayable(DecoderFaultCode, repeatedDecoderFault = true))
    }

    /**
     * The repeat flag is about `decoder_init` and nothing else. A second network failure
     * is still a network failure, and promoting one to a verdict on the file would undo
     * the whole split this class exists to keep.
     */
    @Test
    fun `repetition promotes no other code`() {
        for (code in listOf(
            "media_unreachable", "sender_not_serving", "http_rejected", "startup_timeout",
            "control_disconnected", "active_cast_busy", "unknown",
        )) {
            assertFalse(code, marksFileUnplayable(code, repeatedDecoderFault = true))
        }
    }

    @Test
    fun `the ledger calls the second fault on a file and not the first`() {
        val ledger = DecoderFaultLedger()
        assertFalse(ledger.record(film))
        assertTrue(ledger.record(film))
    }

    @Test
    fun `two films each get their own first chance`() {
        val ledger = DecoderFaultLedger()
        assertFalse(ledger.record(film))
        assertFalse(ledger.record(other))
        assertTrue(ledger.record(other))
    }

    /** A first frame proves the decoder is reachable, so the count starts over. */
    @Test
    fun `a film that finally played gets its first chance back`() {
        val ledger = DecoderFaultLedger()
        assertFalse(ledger.record(film))
        ledger.forget(film)
        assertFalse(ledger.record(film))
    }

    @Test
    fun `a marked film staying marked survives further faults`() {
        val ledger = DecoderFaultLedger()
        ledger.record(film)
        assertTrue(ledger.record(film))
        assertTrue(ledger.record(film))
    }

    @Test
    fun `the ledger forgets its oldest suspect rather than growing`() {
        val ledger = DecoderFaultLedger(limit = 2)
        ledger.record("a")
        ledger.record("b")
        ledger.record("c")
        assertEquals(setOf("b", "c"), ledger.suspects())
        // "a" was evicted, so its next fault is a first one again rather than a verdict.
        assertFalse(ledger.record("a"))
    }

    @Test
    fun `suspects names every film waiting on a second look`() {
        val ledger = DecoderFaultLedger()
        assertEquals(emptySet<String>(), ledger.suspects())
        ledger.record(film)
        assertEquals(setOf(film), ledger.suspects())
        ledger.forget(film)
        assertEquals(emptySet<String>(), ledger.suspects())
    }

    @Test
    fun `a mark is remembered against its own file and no other`() {
        val memory = UnplayableMemory()
        val marks = memory.mark(film, "unsupported_container")
        assertEquals("unsupported_container", marks[film])
        assertFalse(marks.containsKey(other))
    }

    @Test
    fun `the newest diagnosis for a file replaces the last one`() {
        val memory = UnplayableMemory()
        memory.mark(film, "malformed_media")
        val marks = memory.mark(film, "unsupported_container")
        assertEquals(1, marks.size)
        assertEquals("unsupported_container", marks[film])
    }

    @Test
    fun `a first frame clears the file's verdict`() {
        val memory = UnplayableMemory()
        memory.mark(film, "unsupported_container")
        memory.mark(other, "malformed_media")
        val marks = memory.clear(film)
        assertFalse(marks.containsKey(film))
        assertEquals("malformed_media", marks[other])
    }

    @Test
    fun `clearing a file that was never marked changes nothing`() {
        val memory = UnplayableMemory()
        memory.mark(film, "unsupported_container")
        assertEquals(mapOf(film to "unsupported_container"), memory.clear(other))
    }

    // Bounded so casting a whole library at a broken TV cannot grow this without limit.
    @Test
    fun `the oldest verdict is forgotten first`() {
        val memory = UnplayableMemory(limit = 3)
        val marks = (1..5).fold(emptyMap<String, String>()) { _, id ->
            memory.mark("content://media/external/video/media/$id", "unsupported_container")
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

    // Re-marking is a fresh verdict, so it also refreshes the file's place in the queue.
    @Test
    fun `a re-marked file is the youngest again`() {
        val memory = UnplayableMemory(limit = 2)
        memory.mark("a", "malformed_media")
        memory.mark("b", "malformed_media")
        memory.mark("a", "unsupported_container")
        val marks = memory.mark("c", "malformed_media")
        assertEquals(setOf("a", "c"), marks.keys)
    }

    @Test
    fun `the snapshot is a copy, not the live map`() {
        val memory = UnplayableMemory()
        val before = memory.mark(film, "unsupported_container")
        memory.clear(film)
        assertEquals(mapOf(film to "unsupported_container"), before)
    }
}
