package com.flick.sender.media

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

const val DefaultFilmPosters = true

class FilmPosterPreferenceStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("flick_film_poster_pref", Context.MODE_PRIVATE)

    fun enabled(): Boolean = prefs.getBoolean(ENABLED, DefaultFilmPosters)

    fun save(enabled: Boolean) {
        prefs.edit().putBoolean(ENABLED, enabled).apply()
    }

    private companion object { const val ENABLED = "enabled" }
}

class FilmPosterPreferenceController(
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
