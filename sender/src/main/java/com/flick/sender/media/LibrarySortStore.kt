package com.flick.sender.media

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The order the library is dealt in, remembered between launches.
 *
 * In a file of its own rather than in [LibraryFolderStore]'s, which would have been the
 * obvious place — and the reason is the backup rules. `flick_library` is excluded from cloud
 * backup and device transfer because a folder scope names a path on ONE phone's storage,
 * where restored it either matches nothing or matches a same-named folder holding different
 * films. An order is the opposite kind of fact: it is about the person, like the light/dark
 * choice and the readable-names choice, and both of those deliberately travel. Sharing the
 * file would have quietly dropped it at every restore, for a reason that has nothing to do
 * with it. See `BackupExclusionsTest`, which is where that classification is written down.
 *
 * `apply()` rather than the folder store's `commit()`. Losing this write to a process kill
 * costs one tap on a menu that is two taps from anywhere; losing the folder costs a library
 * that opens showing the wrong films.
 */
class LibrarySortStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun order(): LibrarySort = librarySortOf(prefs.getString(SORT, null))

    fun save(order: LibrarySort) {
        prefs.edit().putString(SORT, order.name).apply()
    }

    private companion object {
        const val PREFS = "flick_library_sort"
        const val SORT = "library_sort"
    }
}

/**
 * The live choice, published for the grid and written through once per change.
 *
 * Split from the store the way [VideoNamePreferenceController] is, and for the same reason:
 * what happens when a value is picked twice is worth proving, and proving it must not need
 * a `Context`.
 */
class LibrarySortController(
    initial: LibrarySort,
    private val persist: (LibrarySort) -> Unit,
) {
    private val _order = MutableStateFlow(initial)
    val order = _order.asStateFlow()

    fun select(value: LibrarySort) {
        if (value == _order.value) return
        _order.value = value
        persist(value)
    }
}
