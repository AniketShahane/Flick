package com.flick.sender.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoNamePreferenceTest {

    @Test fun simplificationDefaultsOn() {
        assertTrue(DefaultSimplifiedVideoNames)
    }

    @Test fun selectingAValuePublishesAndPersistsItOnce() {
        val writes = mutableListOf<Boolean>()
        val controller = VideoNamePreferenceController(initial = true, persist = writes::add)

        controller.select(false)
        controller.select(false)

        assertEquals(false, controller.simplified.value)
        assertEquals(listOf(false), writes)
    }

    @Test fun controllerCanTurnSimplificationBackOn() {
        val writes = mutableListOf<Boolean>()
        val controller = VideoNamePreferenceController(initial = false, persist = writes::add)

        controller.select(true)

        assertEquals(true, controller.simplified.value)
        assertEquals(listOf(true), writes)
    }
}
