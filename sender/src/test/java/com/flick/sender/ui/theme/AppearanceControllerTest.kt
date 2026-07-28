package com.flick.sender.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The write path. `ThemeStore` itself needs a Context and is covered only through the
 * spellings it puts on disk (see [ThemePreferenceTest]); what is testable here is the
 * part a bug would actually hide in — whether the tap reaches the disk at all, and
 * whether the live value moves with it.
 */
class AppearanceControllerTest {

    @Test
    fun theControllerStartsOnWhatWasStored() {
        val controller = AppearanceController(ThemePreference.DARK) { }
        assertEquals(ThemePreference.DARK, controller.preference)
    }

    @Test
    fun selectingWritesThroughAndTakesEffect() {
        val written = mutableListOf<ThemePreference>()
        val controller = AppearanceController(ThemePreference.SYSTEM) { written += it }

        controller.select(ThemePreference.LIGHT)

        assertEquals(ThemePreference.LIGHT, controller.preference)
        assertEquals(listOf(ThemePreference.LIGHT), written)
    }

    @Test
    fun reselectingTheLitSegmentWritesNothing() {
        val written = mutableListOf<ThemePreference>()
        val controller = AppearanceController(ThemePreference.DARK) { written += it }

        controller.select(ThemePreference.DARK)

        assertEquals(ThemePreference.DARK, controller.preference)
        assertTrue(written.isEmpty())
    }

    @Test
    fun everyOptionIsReachableFromEveryOther() {
        val written = mutableListOf<ThemePreference>()
        val controller = AppearanceController(ThemePreference.SYSTEM) { written += it }

        controller.select(ThemePreference.DARK)
        controller.select(ThemePreference.LIGHT)
        controller.select(ThemePreference.SYSTEM)

        assertEquals(ThemePreference.SYSTEM, controller.preference)
        assertEquals(
            listOf(ThemePreference.DARK, ThemePreference.LIGHT, ThemePreference.SYSTEM),
            written,
        )
    }
}
