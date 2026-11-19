package com.flick.sender.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvLibraryPreferenceTest {

    @Test
    fun theLibraryIsShownOnTheTvByDefault() {
        assertTrue(DefaultTvLibrary)
    }

    @Test
    fun selectingAValuePublishesAndPersistsItOnce() {
        val writes = mutableListOf<Boolean>()
        val controller = TvLibraryPreferenceController(initial = true, persist = writes::add)

        controller.select(false)
        controller.select(false)

        assertEquals(false, controller.enabled.value)
        assertEquals(listOf(false), writes)
    }
}
