package com.flick.sender.ui.screens

import com.flick.sender.model.FourKLabel
import com.flick.sender.model.resolutionLabelFor
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryFilterPolicyTest {

    private data class Row(val id: Long, val label: String, val dv: Boolean)

    private val library = listOf(
        Row(1L, resolutionLabelFor(3840, 2160), dv = true),
        Row(2L, resolutionLabelFor(3840, 2160), dv = false),
        Row(3L, resolutionLabelFor(1920, 1080), dv = true),
        Row(4L, resolutionLabelFor(1280, 720), dv = false),
        Row(5L, resolutionLabelFor(640, 480), dv = false),
    )

    private fun ids(filter: LibFilter): List<Long> =
        LibraryFilterPolicy.apply(library, filter, Row::label, Row::dv).map { it.id }

    @Test fun allKeepsEveryItemInOrder() {
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), ids(LibFilter.ALL))
    }

    // The 4K chip matches MediaItem.resolutionLabel by exact string; renaming that
    // bucket without renaming FourKLabel would silently empty the chip.
    @Test fun fourKMatchesTheBucketTheModelActuallyProduces() {
        assertEquals(FourKLabel, resolutionLabelFor(3840, 2160))
        assertEquals(FourKLabel, resolutionLabelFor(4096, 2160))
        assertEquals(listOf(1L, 2L), ids(LibFilter.FOUR_K))
    }

    @Test fun dolbyVisionReadsTheProbeVerdictAndNotTheResolution() {
        assertEquals(listOf(1L, 3L), ids(LibFilter.DOLBY_VISION))
    }

    // Nothing probed yet is an empty result, never a fallback to everything.
    @Test fun anUnprobedLibraryFiltersToNothingUnderDolbyVision() {
        val unprobed = library.map { it.copy(dv = false) }
        assertEquals(
            emptyList<Long>(),
            LibraryFilterPolicy.apply(unprobed, LibFilter.DOLBY_VISION, Row::label, Row::dv)
                .map { it.id },
        )
    }

    @Test fun anEmptyLibraryStaysEmptyOnEveryAxis() {
        LibFilter.entries.forEach { filter ->
            assertEquals(
                emptyList<Row>(),
                LibraryFilterPolicy.apply(emptyList<Row>(), filter, Row::label, Row::dv),
            )
        }
    }
}
