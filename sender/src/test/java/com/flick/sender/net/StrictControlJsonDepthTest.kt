package com.flick.sender.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The nesting ceiling on the frames the phone accepts from a TV.
 *
 * These call [StrictControlJson.hasUniqueTopLevelObject] rather than `parse`
 * deliberately: it returns a Boolean and catches nothing, so a parser that
 * recursed until the stack gave out would fail these tests by throwing rather
 * than quietly answering "malformed". That is the whole point — the ceiling has
 * to be the thing that rejects the frame, not the stack.
 */
class StrictControlJsonDepthTest {

    /** `{"a":[[…]]}` — one object plus [arrays] nested arrays. */
    private fun nested(arrays: Int) = "{\"a\":" + "[".repeat(arrays) + "]".repeat(arrays) + "}"

    @Test fun acceptsTheDeepestFrameInsideTheCeiling() {
        // 31 arrays under the top-level object is exactly 32 levels.
        assertTrue(StrictControlJson.hasUniqueTopLevelObject(nested(31)))
    }

    @Test fun rejectsOneLevelPastTheCeiling() {
        assertFalse(StrictControlJson.hasUniqueTopLevelObject(nested(32)))
    }

    @Test fun rejectsAWireSizedNestingBombBeforeOrgJsonEverSeesIt() {
        // 16 KB of openers: the lexical pass runs before JSONObject is constructed,
        // so refusing here is what keeps the platform parser off this input.
        assertFalse(StrictControlJson.hasUniqueTopLevelObject("{\"a\":" + "[".repeat(16 * 1024)))
        assertFalse(StrictControlJson.hasUniqueTopLevelObject(nested(8_000)))
        assertTrue(StrictControlJson.parse(nested(8_000)) is StrictControlJson.Result.Malformed)
    }

    @Test fun theDeepestRealReceiverFrameStillParses() {
        // `paired` carries `cap`, an array of capability strings: two levels, and
        // nothing in the protocol nests further.
        assertTrue(
            StrictControlJson.hasUniqueTopLevelObject(
                "{\"t\":\"paired\",\"v\":2,\"key\":\"k\",\"keyId\":\"i\",\"tv\":\"TV\",\"tvId\":\"d\"," +
                    "\"peerIp\":\"10.0.0.2\",\"serverHost\":\"10.0.0.3\",\"serverPort\":8080," +
                    "\"cap\":[\"a\",\"b\"]}",
            ),
        )
    }
}
