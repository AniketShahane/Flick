package com.flick.receiver.net

import org.junit.Assert.assertEquals
import org.junit.Test

class StoppedFrameSchemaTest {
    @Test fun stoppedIsExactlyCastCorrelatedAndReplayable() {
        val castId = "MDEyMzQ1Njc4OWFiY2RlZg"
        assertEquals(
            linkedMapOf<String, Any?>("t" to "stopped", "v" to 2, "castId" to castId),
            stoppedFrameFields(castId),
        )
    }
}
