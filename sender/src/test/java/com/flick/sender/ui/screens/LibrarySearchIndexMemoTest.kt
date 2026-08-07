package com.flick.sender.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Whether returning to the library re-folds it.
 *
 * This is a performance invariant with no visible failure mode of its own: a memo that stops
 * memoizing still answers every search correctly, and the only symptom is a frame. On the
 * verified phone that frame was 48 ms against a 120 Hz panel's 8.3 ms budget, spent inside
 * the Choreographer callback that also advances the navigation pill's spring — so the pill
 * froze mid-travel every time the library was one of the two tabs involved. Nothing in a
 * screenshot or a log would say so, which is why it is pinned by identity here.
 *
 * Identity rather than a rebuild counter: an index that comes back as the same object is one
 * that was not rebuilt, and that needs no instrumentation inside the thing being tested.
 */
class LibrarySearchIndexMemoTest {

    private fun memo() = LibrarySearchIndexMemo<String> { it }

    private val library = listOf(
        "Troy (2004) [DC] [2160p].mkv",
        "The Wailing (2016) [1080p].mkv",
        "Bramayugam (2024).mp4",
    )

    /** The tab change this exists for: same library, second visit, no work. */
    @Test fun theSameLibraryIsFoldedOnce() {
        val memo = memo()
        assertSame(memo.of(library), memo.of(library))
    }

    /**
     * A reload has to be honoured even when it produces an equal list. MediaStore answering
     * again is a new list instance, and the rows behind it may have been renamed on disk
     * while the names in it stayed put — so contents are not the question identity is.
     */
    @Test fun aReloadedLibraryIsFoldedAgain() {
        val memo = memo()
        val first = memo.of(library)
        val reloaded = library.toList()
        assertEquals("the fixture must be an equal-but-distinct list", library, reloaded)
        assertNotSame(first, memo.of(reloaded))
    }

    /** Scoping to a folder is a different list, and gets its own index. */
    @Test fun narrowingTheScopeFoldsTheNarrowedList() {
        val memo = memo()
        val whole = memo.of(library)
        val folder = memo.of(library.take(1))
        assertNotSame(whole, folder)
        assertEquals(1, folder.matching("").size)
    }

    /** And going back to the wider list re-folds rather than answering from the narrow one. */
    @Test fun wideningBackDoesNotServeTheNarrowIndex() {
        val memo = memo()
        memo.of(library.take(1))
        assertEquals(library.size, memo.of(library).matching("").size)
    }

    /** The cached index has to keep answering, not just keep its identity. */
    @Test fun aReusedIndexStillSearches() {
        val memo = memo()
        memo.of(library)
        assertEquals(listOf("The Wailing (2016) [1080p].mkv"), memo.of(library).matching("wailing"))
    }
}
