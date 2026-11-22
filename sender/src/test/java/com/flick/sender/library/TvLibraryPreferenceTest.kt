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

    @Test
    fun theConnectModeStartsAtAskAndPersistsEachChangeOnce() {
        val writes = mutableListOf<TvConnectMode>()
        val controller = TvConnectModeController(initial = TvConnectMode.DEFAULT, persist = writes::add)
        assertEquals(TvConnectMode.ASK, controller.mode.value)

        controller.select(TvConnectMode.AUTO)
        controller.select(TvConnectMode.AUTO)

        assertEquals(TvConnectMode.AUTO, controller.mode.value)
        assertEquals(listOf(TvConnectMode.AUTO), writes)
    }
}
