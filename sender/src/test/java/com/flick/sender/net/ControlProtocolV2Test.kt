package com.flick.sender.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ControlProtocolV2Test {
    private val key = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    private val tvId = "ABEiM0RVZneImaq7zN3u_w"
    private val keyId = "AQIDBAUGBwgJCgsMDQ4PEA"
    private val client = "ERITFBUWFxgZGhscHR4fIA"
    private val server = "ISIjJCUmJygpKissLS4vMA"

    @Test fun fixedResumeVectorMatchesFrozenFixture() {
        val transcript = ControlProtocolV2.transcript("client", tvId, keyId, client, server, "192.168.42.17", "192.168.42.88", 42421, "Demo TV")
        assertEquals(260, transcript.size)
        assertEquals("bqJZ6nUWl-KhUfA49f3Y9TWZ39boGj2P01YsmwTs53E", ControlProtocolV2.proof(key, "client", tvId, keyId, client, server, "192.168.42.17", "192.168.42.88", 42421, "Demo TV"))
        assertEquals("Z2JExw8mDA1QzUJGIira1xeQE3YvZiUbgl3jW9XK-Sk", ControlProtocolV2.proof(key, "server", tvId, keyId, client, server, "192.168.42.17", "192.168.42.88", 42421, "Demo TV"))
    }

    @Test fun anyTranscriptChangeFailsProofComparison() {
        val good = ControlProtocolV2.proof(key, "client", tvId, keyId, client, server, "192.168.42.17", "192.168.42.88", 42421, "Demo TV")
        val changed = ControlProtocolV2.proof(key, "client", tvId, keyId, client, server, "192.168.42.18", "192.168.42.88", 42421, "Demo TV")
        assertFalse(ControlProtocolV2.constantTimeEquals(good, changed))
    }

    @Test fun legacyKeyIdIsStableAndDoesNotReuseThePairingKey() {
        val legacyId = ControlProtocolV2.legacyKeyId(key)
        assertEquals(22, legacyId.length)
        assertFalse(legacyId == key.take(22))
        assertEquals(legacyId, ControlProtocolV2.legacyKeyId(key))
    }

    @Test fun outboundLabelsMatchTheControlBoundaryCanonicalization() {
        assertEquals("Pixel 9 Pro", ControlProtocolV2.normalizedLabel("\nPixel\t9  Pro\u200e", 80))
        assertEquals("A B", ControlProtocolV2.normalizedLabel("A\u00a0\u200f B", 80))
        assertEquals(null, ControlProtocolV2.normalizedLabel("\n\t\u200e", 80))
        assertEquals("😀😀", ControlProtocolV2.normalizedLabel("😀😀😀", 2))
        assertEquals("x", ControlProtocolV2.normalizedLabel("  x  ", 1))
    }
}
