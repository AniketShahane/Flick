package com.flick.receiver.net

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlWireSerializationTest {

    @Test fun negotiatedCapabilitiesSerializeAsAnArrayOnAndroid() {
        val payload = controlFrameJson(
            negotiatedFrameFields(
                clientNonce = "ERITFBUWFxgZGhscHR4fIA",
                serverNonce = "ISIjJCUmJygpKissLS4vMA",
                tvId = "ABEiM0RVZneImaq7zN3u_w",
                capabilities = listOf("cast-ack", "first-frame-ready", "structured-errors", "resume-hmac"),
            ),
        )

        val frame = JSONObject(payload)
        assertTrue(frame.get("cap") is JSONArray)
        assertEquals(
            listOf("cast-ack", "first-frame-ready", "structured-errors", "resume-hmac"),
            frame.getJSONArray("cap").let { array ->
                List(array.length()) { index -> array.getString(index) }
            },
        )
    }
}
