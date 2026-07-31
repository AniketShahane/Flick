package com.flick.sender.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LibrarySearchPolicyTest {

    private data class Row(val id: Long, val name: String)

    private val library = listOf(
        Row(1L, "Arrival 4K.mkv"),
        Row(2L, "The Flick Test.mp4"),
        Row(3L, "arrival-behind-the-scenes.mov"),
    )

    @Test fun blankQueryReturnsTheOriginalScopedList() {
        assertSame(library, LibrarySearchPolicy.apply(library, "  ", Row::name))
    }

    @Test fun trimmedQueryMatchesFilenameIgnoringCaseInStableOrder() {
        assertEquals(
            listOf(1L, 3L),
            LibrarySearchPolicy.apply(library, " ArRiVaL ", Row::name).map { it.id },
        )
    }

    @Test fun substringQueryCanMatchTheMiddleOfAFilename() {
        assertEquals(
            listOf(2L),
            LibrarySearchPolicy.apply(library, "lick te", Row::name).map { it.id },
        )
    }

    @Test fun noMatchReturnsAnEmptyList() {
        assertEquals(emptyList<Row>(), LibrarySearchPolicy.apply(library, "nope", Row::name))
    }
}
