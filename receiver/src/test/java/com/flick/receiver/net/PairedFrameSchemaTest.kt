package com.flick.receiver.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PairedFrameSchemaTest {
    @Test fun pairedHasTheFrozenInitialPairingSchemaWithoutNegotiationNonces() {
        val fields = pairedFrameFields(
            key = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
            keyId = "AQIDBAUGBwgJCgsMDQ4PEA",
            tv = "Demo TV",
            tvId = "ABEiM0RVZneImaq7zN3u_w",
            peerIp = "192.168.42.17",
            serverHost = "192.168.42.88",
            serverPort = 42421,
            capabilities = listOf("cast-ack", "first-frame-ready", "structured-errors", "resume-hmac"),
        )

        assertEquals(
            listOf("t", "v", "key", "keyId", "tv", "tvId", "peerIp", "serverHost", "serverPort", "cap"),
            fields.keys.toList(),
        )
        assertFalse(fields.containsKey("clientNonce"))
        assertFalse(fields.containsKey("serverNonce"))
        assertFalse(fields.containsKey("proof"))
    }
}
