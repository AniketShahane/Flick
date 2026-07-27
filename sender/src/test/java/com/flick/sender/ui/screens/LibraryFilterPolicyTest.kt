package com.flick.sender.ui.screens

import com.flick.sender.model.FourKLabel
import com.flick.sender.model.FullHdLabel
import com.flick.sender.model.resolutionLabelFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryFilterPolicyTest {

    private data class Row(val id: Long, val label: String)

    private val library = listOf(
        Row(1L, resolutionLabelFor(3840, 2160)),
        Row(2L, resolutionLabelFor(4096, 2160)),
        Row(3L, resolutionLabelFor(1920, 1080)),
        Row(4L, resolutionLabelFor(1280, 720)),
        Row(5L, resolutionLabelFor(640, 480)),
    )

    private fun ids(filter: LibFilter): List<Long> =
        LibraryFilterPolicy.apply(library, filter, Row::label).map { it.id }

    @Test fun allKeepsEveryItemInOrder() {
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), ids(LibFilter.ALL))
    }

    // Both chips match MediaItem.resolutionLabel by exact string; renaming a bucket
    // without renaming its constant would silently empty the chip.
    @Test fun fourKMatchesTheBucketTheModelActuallyProduces() {
        assertEquals(FourKLabel, resolutionLabelFor(3840, 2160))
        assertEquals(FourKLabel, resolutionLabelFor(4096, 2160))
        assertEquals(listOf(1L, 2L), ids(LibFilter.FOUR_K))
    }

    @Test fun fullHdMatchesTheBucketTheModelActuallyProduces() {
        assertEquals(FullHdLabel, resolutionLabelFor(1920, 1080))
        assertEquals(FullHdLabel, resolutionLabelFor(1440, 1080))
        assertEquals(listOf(3L), ids(LibFilter.FULL_HD))
    }

    // The two quality chips never claim the same file.
    @Test fun theTwoQualityChipsDoNotOverlap() {
        assertTrue(ids(LibFilter.FOUR_K).intersect(ids(LibFilter.FULL_HD).toSet()).isEmpty())
    }

    // 720p and below belong to no chip, which is why "All" survived the cut: without it
    // there is no way to reach these files at all.
    @Test fun anItemBelowBothChipsIsReachableOnlyUnderAll() {
        listOf(4L, 5L).forEach { id ->
            assertTrue(id in ids(LibFilter.ALL))
            assertFalse(id in ids(LibFilter.FOUR_K))
            assertFalse(id in ids(LibFilter.FULL_HD))
        }
    }

    @Test fun aLibraryWithoutTheBucketFiltersToNothingRatherThanToEverything() {
        val onlySmall = library.filter { it.id >= 4L }
        LibFilter.entries.filter { it != LibFilter.ALL }.forEach { filter ->
            assertEquals(
                emptyList<Long>(),
                LibraryFilterPolicy.apply(onlySmall, filter, Row::label).map { it.id },
            )
        }
    }

    @Test fun anEmptyLibraryStaysEmptyOnEveryAxis() {
        LibFilter.entries.forEach { filter ->
            assertEquals(
                emptyList<Row>(),
                LibraryFilterPolicy.apply(emptyList<Row>(), filter, Row::label),
            )
        }
    }
}
