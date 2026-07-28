package com.flick.receiver.net

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The nesting ceiling, proved at its boundary rather than by throwing something
 * enormous at the parser.
 *
 * A boundary test is the only honest one here: `objectOnly` catches `Throwable`,
 * so a parser that blew its stack and one that refused the frame both answer
 * null. Showing that 31 levels still parse and 32 do not is what distinguishes an
 * explicit ceiling from an absorbed `StackOverflowError`.
 */
class StrictJsonDepthTest {

    /** `{"a":[[…]]}` — one object plus [arrays] nested arrays. */
    private fun nested(arrays: Int) = "{\"a\":" + "[".repeat(arrays) + "]".repeat(arrays) + "}"

    @Test fun acceptsTheDeepestFrameInsideTheCeiling() {
        // 31 arrays under the top-level object is exactly 32 levels.
        assertNotNull(StrictJson.objectOnly(nested(31)))
    }

    @Test fun rejectsOneLevelPastTheCeiling() {
        assertNull(StrictJson.objectOnly(nested(32)))
    }

    @Test fun rejectsTheAdversarialPreAuthFrameWithoutRelyingOnTheStack() {
        // The shape the finding describes: a whole pre-auth frame of nothing but
        // openers, sized to the wire limit. It must be refused as malformed.
        assertNull(StrictJson.objectOnly("{\"a\":" + "[".repeat(16 * 1024)))
        assertNull(StrictJson.objectOnly(nested(8_000)))
    }

    @Test fun everyRealCommandFrameIsFlatAndUnaffected() {
        // The deepest legitimate frame the receiver parses is one object of scalars.
        assertNotNull(
            StrictJson.objectOnly(
                "{\"t\":\"loadMedia\",\"v\":2,\"castId\":\"abc\",\"url\":\"http://10.0.0.2:8080/v/t\"," +
                    "\"title\":\"Film\",\"durationMs\":1,\"startMs\":0}",
            ),
        )
    }
}
