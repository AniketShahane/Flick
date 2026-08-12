package com.flick.sender

import java.io.FileNotFoundException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The phone-side codes and the one rule that decides whether they outrank the receiver.
 * None of them is on the wire — see [SourceFault] — so the only thing to pin is that
 * they stay distinct from each other and that the preference is narrow.
 */
class SourceFaultTest {

    @Test
    fun `the phone-side codes are distinct`() {
        val codes = setOf(SourceFault.BIND_FAILED, SourceFault.NO_LAN_ADDRESS, SourceFault.SOURCE_LOST)
        assertEquals(3, codes.size)
    }

    // Deliberately not inspecting the throwable: from the far end of the LAN a revoked
    // grant, a deleted row and a dead provider are one fact.
    @Test
    fun `every mid-stream throwable resolves to one fact`() {
        assertEquals(SourceFault.SOURCE_LOST, SourceFault.midStream(IOException("x")))
        assertEquals(SourceFault.SOURCE_LOST, SourceFault.midStream(FileNotFoundException("x")))
        assertEquals(SourceFault.SOURCE_LOST, SourceFault.midStream(SecurityException("x")))
        assertEquals(SourceFault.SOURCE_LOST, SourceFault.midStream(IllegalStateException("x")))
    }

    @Test
    fun `a recorded fault outranks the receiver's guess about the body`() {
        assertEquals("source_lost", preferredTerminalCode("sender_not_serving", "source_lost"))
        assertEquals("source_lost", preferredTerminalCode("media_unreachable", "source_lost"))
        assertEquals("source_lost", preferredTerminalCode("http_rejected", "source_lost"))
    }

    // Every other receiver verdict was reached with the file in front of it, which is
    // better evidence than anything this phone holds about its own socket.
    @Test
    fun `a recorded fault never overrides a verdict about the file`() {
        for (code in listOf(
            "unsupported_container",
            "unsupported_video_codec",
            "unsupported_hdr_profile",
            "malformed_media",
            "decoder_init",
            "startup_timeout",
            "tv_backgrounded",
            "unknown",
        )) {
            assertEquals(code, preferredTerminalCode(code, "source_lost"))
        }
    }

    @Test
    fun `with nothing recorded the reported code stands`() {
        assertEquals("sender_not_serving", preferredTerminalCode("sender_not_serving", null))
        assertEquals("media_unreachable", preferredTerminalCode("media_unreachable", null))
        assertEquals("unknown", preferredTerminalCode("unknown", null))
    }
}
