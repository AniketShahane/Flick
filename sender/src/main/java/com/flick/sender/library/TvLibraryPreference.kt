package com.flick.sender.library

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** "Show my library on my TV": on unless the owner turns it off. */
const val DefaultTvLibrary = true

class TvLibraryPreferenceStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("flick_tv_library", Context.MODE_PRIVATE)

    fun enabled(): Boolean = prefs.getBoolean(ENABLED, DefaultTvLibrary)

    fun save(enabled: Boolean) {
        prefs.edit().putBoolean(ENABLED, enabled).apply()
    }

    /** "When your TV opens Flick". */
    fun connectMode(): TvConnectMode = TvConnectMode.fromStored(prefs.getString(CONNECT_MODE, null))

    fun saveConnectMode(mode: TvConnectMode) {
        prefs.edit().putString(CONNECT_MODE, mode.stored).apply()
    }

    /** §8: the person pressed Stop on the ready notification and has not opened Flick since. */
    fun stoppedSinceOpened(): Boolean = prefs.getBoolean(STOPPED, false)

    fun saveStopped(stopped: Boolean) {
        prefs.edit().putBoolean(STOPPED, stopped).apply()
    }

    private companion object {
        const val ENABLED = "enabled"
        const val STOPPED = "ready_stopped"
        const val CONNECT_MODE = "connect_mode"
    }
}

class TvLibraryPreferenceController(
    initial: Boolean,
    private val persist: (Boolean) -> Unit,
) {
    private val _enabled = MutableStateFlow(initial)
    val enabled = _enabled.asStateFlow()

    fun select(value: Boolean) {
        if (value == _enabled.value) return
        _enabled.value = value
        persist(value)
    }
}

/** The same shape as [TvLibraryPreferenceController], for "When your TV opens Flick". */
class TvConnectModeController(
    initial: TvConnectMode,
    private val persist: (TvConnectMode) -> Unit,
) {
    private val _mode = MutableStateFlow(initial)
    val mode = _mode.asStateFlow()

    fun select(value: TvConnectMode) {
        if (value == _mode.value) return
        _mode.value = value
        persist(value)
    }
}
