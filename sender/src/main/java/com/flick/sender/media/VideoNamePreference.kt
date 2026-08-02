package com.flick.sender.media

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

const val DefaultSimplifiedVideoNames = true

class VideoNamePreferenceStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("flick_video_names", Context.MODE_PRIVATE)

    fun simplified(): Boolean = prefs.getBoolean(SIMPLIFIED, DefaultSimplifiedVideoNames)

    fun save(simplified: Boolean) {
        prefs.edit().putBoolean(SIMPLIFIED, simplified).apply()
    }

    private companion object { const val SIMPLIFIED = "simplified" }
}

class VideoNamePreferenceController(
    initial: Boolean,
    private val persist: (Boolean) -> Unit,
) {
    private val _simplified = MutableStateFlow(initial)
    val simplified = _simplified.asStateFlow()

    fun select(value: Boolean) {
        if (value == _simplified.value) return
        _simplified.value = value
        persist(value)
    }
}
