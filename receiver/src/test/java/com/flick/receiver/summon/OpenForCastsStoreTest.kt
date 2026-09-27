package com.flick.receiver.summon

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenForCastsStoreTest {

    @Test fun `a fresh store is off with no strike record`() {
        val store = OpenForCastsStore(MemoryPrefs())
        assertFalse(store.enabled)
        assertEquals(0, store.strikes)
        assertFalse(store.blocked)
        assertEquals("", store.strikeFingerprint)
        assertEquals(0L, store.strikeVersion)
    }

    @Test fun `enabled persists`() {
        val prefs = MemoryPrefs()
        OpenForCastsStore(prefs).enabled = true
        assertTrue(OpenForCastsStore(prefs).enabled)
    }

    @Test fun `the strike record is saved with its scope`() {
        val prefs = MemoryPrefs()
        OpenForCastsStore(prefs).saveStrikes(strikes = 2, blocked = true, fingerprint = "build/a", version = 5L)
        val reread = OpenForCastsStore(prefs)
        assertEquals(2, reread.strikes)
        assertTrue(reread.blocked)
        assertEquals("build/a", reread.strikeFingerprint)
        assertEquals(5L, reread.strikeVersion)
    }

    @Test fun `clearing strikes keeps the viewer's choice`() {
        val store = OpenForCastsStore(MemoryPrefs())
        store.enabled = true
        store.saveStrikes(strikes = 2, blocked = true, fingerprint = "build/a", version = 5L)
        store.clearStrikes()
        assertTrue(store.enabled)
        assertEquals(0, store.strikes)
        assertFalse(store.blocked)
        assertEquals("", store.strikeFingerprint)
        assertEquals(0L, store.strikeVersion)
    }

    /** Writes land on apply(), as they do on a device. */
    private class MemoryPrefs : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String, defValue: String?): String? = values[key] as String? ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String, defValue: Int): Int = values[key] as Int? ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as Long? ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as Float? ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as Boolean? ?: defValue
        override fun contains(key: String): Boolean = key in values
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private var cleared = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor = put(key, value)
            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = put(key, values)
            override fun putInt(key: String, value: Int): SharedPreferences.Editor = put(key, value)
            override fun putLong(key: String, value: Long): SharedPreferences.Editor = put(key, value)
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = put(key, value)
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = put(key, value)
            override fun remove(key: String): SharedPreferences.Editor = put(key, null)
            override fun clear(): SharedPreferences.Editor {
                cleared = true
                return this
            }

            private fun put(key: String, value: Any?): SharedPreferences.Editor {
                pending[key] = value
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (cleared) values.clear()
                pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            }
        }
    }
}
