package com.flick.receiver.net

import org.junit.Assert.assertEquals
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Fixed frozen control-v2 proof vector; protects transcript ordering and length prefixes. */
class ControlV2FixturesTest {
    @Test fun clientProofMatchesFrozenFixture() {
        val fields = listOf(
            "Flick-Control-Resume-V2", "client", "2", "ABEiM0RVZneImaq7zN3u_w",
            "AQIDBAUGBwgJCgsMDQ4PEA", "ERITFBUWFxgZGhscHR4fIA", "ISIjJCUmJygpKissLS4vMA",
            "192.168.42.17", "192.168.42.88", "42421", "Demo TV",
            "cast-ack,first-frame-ready,structured-errors,resume-hmac",
        )
        val transcript = fields.fold(ByteArray(0)) { bytes, field ->
            bytes + java.nio.ByteBuffer.allocate(4).putInt(field.toByteArray().size).array() + field.toByteArray()
        }
        assertEquals(260, transcript.size)
        val key = java.util.Base64.getUrlDecoder().decode("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8")
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        assertEquals("bqJZ6nUWl-KhUfA49f3Y9TWZ39boGj2P01YsmwTs53E", java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(transcript)))
    }
}
