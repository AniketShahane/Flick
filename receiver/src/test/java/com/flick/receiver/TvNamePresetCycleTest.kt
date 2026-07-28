package com.flick.receiver

import org.junit.Assert.assertEquals
import org.junit.Test

class TvNamePresetCycleTest {

    private val presets = arrayOf("Living Room TV", "Bedroom TV", "Den TV")

    @Test fun renameStepsToTheNextPresetAndWrapsAtTheEnd() {
        assertEquals("Bedroom TV", nextName("Living Room TV", presets))
        assertEquals("Den TV", nextName("Bedroom TV", presets))
        assertEquals("Living Room TV", nextName("Den TV", presets))
    }

    @Test fun aNameThatIsNotAPresetEntersAtTheHead() {
        assertEquals("Living Room TV", nextName("Kitchen TV", presets))
    }

    @Test fun anEmptyPresetArrayLeavesTheNameAlone() {
        // The presets are a resource array now, and the only rename affordance the
        // TV has may not index-crash on a locale that ships an empty one.
        assertEquals("Kitchen TV", nextName("Kitchen TV", emptyArray()))
    }
}
