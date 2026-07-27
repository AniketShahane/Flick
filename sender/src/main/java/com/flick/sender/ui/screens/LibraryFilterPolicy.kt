package com.flick.sender.ui.screens

import com.flick.sender.model.FourKLabel
import com.flick.sender.model.FullHdLabel

/**
 * The three axes the library chips select between (design §5.2.3). "All" is not a
 * quality claim — it is the only way to reach a file below both buckets.
 */
internal enum class LibFilter { ALL, FOUR_K, FULL_HD }

/**
 * Pure library filtering, kept out of the composable so the chip behaviour is unit
 * testable: `MediaItem` holds an `android.net.Uri` and cannot be built on the JVM, so
 * the rules read the one input they need through a projection instead of whole items.
 */
internal object LibraryFilterPolicy {

    fun <T> apply(
        items: List<T>,
        filter: LibFilter,
        resolutionLabel: (T) -> String,
    ): List<T> = when (filter) {
        LibFilter.ALL -> items
        LibFilter.FOUR_K -> items.filter { resolutionLabel(it) == FourKLabel }
        LibFilter.FULL_HD -> items.filter { resolutionLabel(it) == FullHdLabel }
    }
}
