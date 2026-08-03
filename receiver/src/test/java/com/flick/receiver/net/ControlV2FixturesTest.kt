package com.flick.receiver.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Fixed frozen control-v2 proof vector; protects transcript ordering and length prefixes. */
class ControlV2FixturesTest {
    @Test fun negotiatedCapabilitiesRemainAJsonArrayInput() {
        val fields = negotiatedFrameFields(
            clientNonce = "ERITFBUWFxgZGhscHR4fIA",
            serverNonce = "ISIjJCUmJygpKissLS4vMA",
            tvId = "ABEiM0RVZneImaq7zN3u_w",
            capabilities = CAPABILITIES,
        )

        assertEquals(
            listOf("t", "v", "clientNonce", "serverNonce", "tvId", "cap"),
            fields.keys.toList(),
        )
        assertTrue(fields["cap"] is List<*>)
        assertEquals(CAPABILITIES, fields["cap"])
    }

    @Test fun proofsMatchTheFrozenFixture() {
        assertEquals(272, transcriptFor("client").size)
        assertEquals("ebPf_v2pHAw6ex1ij0_NA3f7YiwKU8gcd_hHBOQAu7I", proofFor("client"))
        assertEquals("0R0MDBC27xcqAY9bT0BuPg9Y3gOFYHlWOlPljyCPoCs", proofFor("server"))
    }

    private fun transcriptFor(role: String): ByteArray {
        val fields = listOf(
            "Flick-Control-Resume-V2", role, "2", "ABEiM0RVZneImaq7zN3u_w",
            "AQIDBAUGBwgJCgsMDQ4PEA", "ERITFBUWFxgZGhscHR4fIA", "ISIjJCUmJygpKissLS4vMA",
            "192.168.42.17", "192.168.42.88", "42421", "Demo TV",
            CAPABILITIES.joinToString(","),
        )
        return fields.fold(ByteArray(0)) { bytes, field ->
            bytes + java.nio.ByteBuffer.allocate(4).putInt(field.toByteArray().size).array() + field.toByteArray()
        }
    }

    private fun proofFor(role: String): String {
        val key = java.util.Base64.getUrlDecoder().decode("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8")
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(transcriptFor(role)))
    }

    private companion object {
        // Field 12 of the HMAC transcript, so this order is signed rather than
        // merely compared: one entry out of place and every proof changes.
        val CAPABILITIES = listOf("cast-ack", "first-frame-ready", "structured-errors", "resume-hmac", "audio-delay")
    }
}
