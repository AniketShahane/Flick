package com.flick.sender.ui.screens

import com.flick.sender.model.FourKLabel

/** The three axes the library chips select between (design §5.2.3). */
internal enum class LibFilter { ALL, DOLBY_VISION, FOUR_K }

/**
 * Pure library filtering, kept out of the composable so the chip behaviour is unit
 * testable: `MediaItem` holds an `android.net.Uri` and cannot be built on the JVM, so
 * the rules read their two inputs through projections instead of whole items.
 */
internal object LibraryFilterPolicy {

    fun <T> apply(
        items: List<T>,
        filter: LibFilter,
        resolutionLabel: (T) -> String,
        isDolbyVision: (T) -> Boolean,
    ): List<T> = when (filter) {
        LibFilter.ALL -> items
        LibFilter.FOUR_K -> items.filter { resolutionLabel(it) == FourKLabel }
        LibFilter.DOLBY_VISION -> items.filter(isDolbyVision)
    }
}
