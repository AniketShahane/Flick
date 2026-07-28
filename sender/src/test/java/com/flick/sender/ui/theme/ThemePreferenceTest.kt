package com.flick.sender.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePreferenceTest {

    @Test
    fun systemDefersToThePlatformBothWays() {
        assertTrue(ThemePreference.SYSTEM.resolvesDark(systemInDark = true))
        assertFalse(ThemePreference.SYSTEM.resolvesDark(systemInDark = false))
    }

    @Test
    fun anExplicitChoiceOverridesThePlatformBothWays() {
        // The whole point of the row: a choice is an override, so the platform's night
        // mode must not reach it in either direction.
        assertFalse(ThemePreference.LIGHT.resolvesDark(systemInDark = true))
        assertFalse(ThemePreference.LIGHT.resolvesDark(systemInDark = false))
        assertTrue(ThemePreference.DARK.resolvesDark(systemInDark = true))
        assertTrue(ThemePreference.DARK.resolvesDark(systemInDark = false))
    }

    @Test
    fun onlySystemEverConsultsTheModeItsOwnOverrideWouldChange() {
        // From API 31 MainActivity hands an explicit choice to the platform as this app's
        // night mode, which makes the app's own Configuration report that choice back.
        // Consulting it for LIGHT or DARK would be reading Flick's answer as if it were
        // the phone's, and the rule survives that only because exactly one entry looks at
        // the argument at all — the one that leaves no override behind to read.
        val consultsNightMode = ThemePreference.entries.filter {
            it.resolvesDark(systemInDark = true) != it.resolvesDark(systemInDark = false)
        }
        assertEquals(listOf(ThemePreference.SYSTEM), consultsNightMode)
    }

    @Test
    fun everyStoredSpellingRoundTrips() {
        ThemePreference.entries.forEach {
            assertEquals(it, ThemePreference.fromStored(it.stored))
        }
    }

    @Test
    fun storedSpellingsAreTheFileFormat() {
        // Pinned, not derived: renaming an entry is a refactor, and it must not silently
        // reset the choice on every phone that already made it.
        assertEquals("system", ThemePreference.SYSTEM.stored)
        assertEquals("light", ThemePreference.LIGHT.stored)
        assertEquals("dark", ThemePreference.DARK.stored)
    }

    @Test
    fun storedSpellingsAreDistinct() {
        val spellings = ThemePreference.entries.map { it.stored }
        assertEquals(spellings, spellings.distinct())
    }

    @Test
    fun anUnreadableStoredValueFallsBackToSystem() {
        // A missing key, a corrupt file, an entry written by a later version, and the
        // wrong case — none of them is worth failing to launch over.
        assertEquals(ThemePreference.SYSTEM, ThemePreference.fromStored(null))
        assertEquals(ThemePreference.SYSTEM, ThemePreference.fromStored(""))
        assertEquals(ThemePreference.SYSTEM, ThemePreference.fromStored("Dark"))
        assertEquals(ThemePreference.SYSTEM, ThemePreference.fromStored("high_contrast"))
    }

    @Test
    fun aStoredChoiceSurvivesAsTheResolvedPalette() {
        // The round trip the phone actually performs across process death: what was
        // written, read back, and resolved against a platform that disagrees.
        val reloaded = ThemePreference.fromStored(ThemePreference.LIGHT.stored)
        assertFalse(reloaded.resolvesDark(systemInDark = true))
    }
}
