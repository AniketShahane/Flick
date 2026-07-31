package com.flick.sender.ui.screens

/**
 * Filename search is deliberately scoped before it gets here. The blank path returns the
 * original list so clearing search is allocation-free and preserves the exact list identity
 * the grid already holds.
 */
internal object LibrarySearchPolicy {

    fun <T> apply(
        items: List<T>,
        query: String,
        name: (T) -> String,
    ): List<T> {
        val needle = query.trim()
        if (needle.isEmpty()) return items
        return items.filter { name(it).contains(needle, ignoreCase = true) }
    }
}
