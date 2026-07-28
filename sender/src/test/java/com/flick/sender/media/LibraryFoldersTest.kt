package com.flick.sender.media

import com.flick.sender.model.resolutionLabelFor
import com.flick.sender.ui.screens.LibFilter
import com.flick.sender.ui.screens.LibraryFilterPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryFoldersTest {

    private data class Row(
        val id: Long,
        val bucketId: Long?,
        val bucketName: String?,
        val label: String = resolutionLabelFor(3840, 2160),
    )

    private val films = 7L
    private val camera = 12L

    // A gallery shaped like the one this feature exists for: a films folder, a camera
    // folder full of clips, and a file MediaStore placed in neither.
    private val library = listOf(
        Row(1L, films, "Films"),
        Row(2L, camera, "Camera", resolutionLabelFor(1920, 1080)),
        Row(3L, films, "Films", resolutionLabelFor(1920, 1080)),
        Row(4L, camera, "Camera", resolutionLabelFor(1280, 720)),
        Row(5L, null, null, resolutionLabelFor(1920, 1080)),
        Row(6L, camera, "Camera"),
    )

    private fun folders(items: List<Row> = library) =
        LibraryFolders.derive(items, Row::bucketId, Row::bucketName)

    private fun ids(scope: LibraryScope, items: List<Row> = library) =
        LibraryFolders.scoped(items, scope, Row::bucketId).map { it.id }

    @Test fun everyBucketBecomesOneFolderCountingItsOwnRows() {
        assertEquals(
            listOf(LibraryFolder(camera, "Camera", 3), LibraryFolder(films, "Films", 2)),
            folders(),
        )
    }

    // Ordered by name rather than by the order MediaStore happened to return rows in,
    // and without letting case decide: "camera" must not sort past "Films".
    @Test fun foldersAreOrderedByNameCaseInsensitively() {
        val mixed = listOf(
            Row(1L, 3L, "zebras"),
            Row(2L, 1L, "Alps"),
            Row(3L, 2L, "movies"),
        )
        assertEquals(listOf("Alps", "movies", "zebras"), folders(mixed).map { it.name })
    }

    // Pre-Q, and any provider that withholds the column: there is nothing to offer, so
    // the control has nothing to open and never appears.
    @Test fun aLibraryWithNoBucketsAtAllOffersNoFolders() {
        val preQ = library.map { it.copy(bucketId = null, bucketName = null) }
        assertEquals(emptyList<LibraryFolder>(), folders(preQ))
        assertFalse(LibraryFolders.chooserOffered(folders(preQ), LibraryScope.All))
    }

    @Test fun anItemWithNoBucketJoinsNoFolderAndIsStillListedUnderAll() {
        // Six rows, five of them in a folder: the sixth is counted by neither folder...
        assertEquals(library.size - 1, folders().sumOf { it.videoCount })
        assertFalse(5L in ids(LibraryScope.Folder(films, "Films")))
        assertFalse(5L in ids(LibraryScope.Folder(camera, "Camera")))
        // ...and it is still a video the user can reach.
        assertTrue(5L in ids(LibraryScope.All))
    }

    // A folder Flick cannot name is a folder it cannot put in front of anyone. Its
    // files are not hidden by that — they are listed where they were, under All.
    @Test fun aBucketNothingCouldNameIsNotOfferedButItsFilesStayVisible() {
        val unnamed = library + Row(7L, 99L, null) + Row(8L, 99L, "  ")
        assertEquals(listOf("Camera", "Films"), folders(unnamed).map { it.name })
        assertTrue(listOf(7L, 8L).all { it in ids(LibraryScope.All, unnamed) })
    }

    @Test fun scopingKeepsOnlyTheChosenFoldersItems() {
        assertEquals(listOf(1L, 3L), ids(LibraryScope.Folder(films, "Films")))
        assertEquals(listOf(2L, 4L, 6L), ids(LibraryScope.Folder(camera, "Camera")))
        assertEquals(library.map { it.id }, ids(LibraryScope.All))
    }

    @Test fun aFolderThatIsStillThereScopesToTheNameTheLibraryReportsNow() {
        val chosen = LibraryFolderChoice(films, "Films")
        assertEquals(
            LibraryScope.Folder(films, "Films"),
            LibraryFolders.scope(chosen, folders(), resolved = true),
        )
    }

    // The distinction the whole feature turns on: an id the resolved library does not
    // carry is a folder that is GONE, which is a different fact from a folder that is
    // there with nothing to show, and it must never resolve to All.
    @Test fun aChosenFolderAbsentFromAResolvedLibraryIsMissing() {
        val chosen = LibraryFolderChoice(404L, "Holiday")
        val scope = LibraryFolders.scope(chosen, folders(), resolved = true)
        assertEquals(LibraryScope.Missing("Holiday"), scope)
        assertEquals(emptyList<Long>(), ids(scope))
    }

    // Every cold start begins with no folders at all. Accusing the folder of being gone
    // before a query has run would put that card on screen on every launch.
    @Test fun anUnresolvedLibraryNeverAccusesTheStoredFolderOfBeingGone() {
        val chosen = LibraryFolderChoice(films, "Films")
        assertEquals(
            LibraryScope.Folder(films, "Films"),
            LibraryFolders.scope(chosen, emptyList(), resolved = false),
        )
    }

    // A read that broke partway is the same kind of evidence as one that has not run:
    // MediaStore returns rows newest first, so a walk that dies mid-cursor drops the
    // OLDEST folder's rows — precisely the ones whose absence would read as a deletion.
    @Test fun aReadThatDidNotCompleteNeverAccusesTheStoredFolderOfBeingGone() {
        val chosen = LibraryFolderChoice(films, "Films")
        val truncated = library.filter { it.bucketId == camera }
        assertEquals(
            LibraryScope.Folder(films, "Films"),
            LibraryFolders.scope(chosen, folders(truncated), resolved = false),
        )
        // The very same folder list, from a read that reached the end of the cursor, is
        // the one thing that can support the verdict.
        assertEquals(
            LibraryScope.Missing("Films"),
            LibraryFolders.scope(chosen, folders(truncated), resolved = true),
        )
    }

    // Withholding the verdict is not the same as throwing the read away: the files that
    // did arrive are on the phone, and a folder that survived the truncation still lists
    // exactly its own rows.
    @Test fun aPartialReadStillListsEverythingItManagedToRead() {
        val truncated = library.filter { it.bucketId == camera }
        val scope = LibraryFolders.scope(
            LibraryFolderChoice(camera, "Camera"),
            folders(truncated),
            resolved = false,
        )
        assertEquals(LibraryScope.Folder(camera, "Camera"), scope)
        assertEquals(listOf(2L, 4L, 6L), ids(scope, truncated))
    }

    @Test fun noStoredChoiceIsAlwaysAllRegardlessOfWhatWasScanned() {
        assertEquals(LibraryScope.All, LibraryFolders.scope(null, folders(), resolved = true))
        assertEquals(LibraryScope.All, LibraryFolders.scope(null, emptyList(), resolved = false))
    }

    @Test fun oneFolderIsNoChoiceButAScopeInForceIsAlwaysEscapable() {
        val single = folders(library.filter { it.bucketId == films })
        assertEquals(1, single.size)
        assertFalse(LibraryFolders.chooserOffered(single, LibraryScope.All))
        assertTrue(LibraryFolders.chooserOffered(single, LibraryScope.Folder(films, "Films")))
        assertTrue(LibraryFolders.chooserOffered(emptyList(), LibraryScope.Missing("Holiday")))
        assertTrue(LibraryFolders.chooserOffered(folders(), LibraryScope.All))
    }

    // The chip row states "All %d" for the set the folder left behind, so the number and
    // the tiles under it have to come from the same list in the same order.
    @Test fun theAllChipCountsExactlyWhatTheFolderLeftToRender() {
        val scoped = LibraryFolders.scoped(library, LibraryScope.Folder(camera, "Camera"), Row::bucketId)
        assertEquals(3, scoped.size)
        assertEquals(
            scoped.map { it.id },
            LibraryFilterPolicy.apply(scoped, LibFilter.ALL, Row::label).map { it.id },
        )
    }

    // A quality chip can still empty a folder that is genuinely there — the one case the
    // folder-empty copy speaks for, and never the missing folder's case. The two are
    // told apart here by the scope, not by the emptiness they share.
    @Test fun aQualityChipCanEmptyAFolderThatIsStillThere() {
        val clips = listOf(
            Row(9L, 21L, "Clips", resolutionLabelFor(1280, 720)),
            Row(10L, 21L, "Clips", resolutionLabelFor(640, 480)),
        )
        val scope = LibraryFolders.scope(LibraryFolderChoice(21L, "Clips"), folders(clips), resolved = true)
        val scoped = LibraryFolders.scoped(clips, scope, Row::bucketId)
        assertEquals(LibraryScope.Folder(21L, "Clips"), scope)
        assertEquals(listOf(9L, 10L), scoped.map { it.id })
        assertTrue(LibraryFilterPolicy.apply(scoped, LibFilter.FOUR_K, Row::label).isEmpty())
    }

    // Quality filtering must not be able to reach past the folder into the rest of the
    // library: the 4K file in Camera is not in Films, chip or no chip.
    @Test fun aChipNeverReachesOutsideTheFolderTheLibraryIsScopedTo() {
        val scoped = LibraryFolders.scoped(library, LibraryScope.Folder(films, "Films"), Row::bucketId)
        assertEquals(
            listOf(1L),
            LibraryFilterPolicy.apply(scoped, LibFilter.FOUR_K, Row::label).map { it.id },
        )
    }

    @Test fun anEmptyLibraryDerivesNothingAndScopesToNothing() {
        assertEquals(emptyList<LibraryFolder>(), folders(emptyList()))
        assertEquals(emptyList<Long>(), ids(LibraryScope.All, emptyList()))
    }
}
