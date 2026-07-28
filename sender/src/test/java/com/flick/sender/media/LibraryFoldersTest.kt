package com.flick.sender.media

import com.flick.sender.model.resolutionLabelFor
import com.flick.sender.ui.screens.LibFilter
import com.flick.sender.ui.screens.LibraryFilterPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryFoldersTest {

    private data class Row(
        val id: Long,
        val path: String?,
        val bucketId: Long?,
        val label: String = resolutionLabelFor(3840, 2160),
    )

    // Bucket ids as MediaStore hands them out: one per LEAF folder, and none at all for a
    // folder that holds nothing but other folders.
    private val movies = 7L
    private val marvel = 11L
    private val phase4 = 12L
    private val camera = 20L

    // The gallery this feature exists for: a films folder with a series nested two deep
    // inside it, a camera roll under a parent that holds no files itself, and a file
    // MediaStore placed nowhere at all.
    private val library = listOf(
        Row(1L, "Movies/", movies),
        Row(2L, "DCIM/Camera/", camera, resolutionLabelFor(1920, 1080)),
        Row(3L, "Movies/", movies, resolutionLabelFor(1920, 1080)),
        Row(4L, "Movies/Marvel/", marvel),
        Row(5L, "Movies/Marvel/Phase 4/", phase4, resolutionLabelFor(1920, 1080)),
        Row(6L, "Movies/Marvel/Phase 4/", phase4),
        Row(7L, null, null, resolutionLabelFor(1920, 1080)),
    )

    private fun folders(items: List<Row> = library) =
        LibraryFolders.derive(items, Row::path, Row::bucketId)

    private fun ids(scope: LibraryScope, items: List<Row> = library) =
        LibraryFolders.scoped(items, scope, Row::path, Row::bucketId).map { it.id }

    private fun at(path: String) =
        LibraryScope.Folder(LibraryFolderId.Path(path), path.substringAfterLast('/'))

    private fun chose(path: String) =
        LibraryFolderChoice(LibraryFolderId.Path(path), path.substringAfterLast('/'))

    // Every ancestor of a path is a folder in its own right. `DCIM` is the one the flat
    // chooser could never offer: no file sits in it, so MediaStore reports no bucket for
    // it, and it has no identity at all beyond the path its child is under.
    @Test fun everyAncestorBecomesAFolderIncludingOneWithNoBucketOfItsOwn() {
        assertEquals(
            listOf(
                LibraryFolder("DCIM", "DCIM", depth = 0, videoCount = 1, bucketId = null),
                LibraryFolder("DCIM/Camera", "Camera", depth = 1, videoCount = 1, bucketId = camera),
                LibraryFolder("Movies", "Movies", depth = 0, videoCount = 5, bucketId = movies),
                LibraryFolder("Movies/Marvel", "Marvel", depth = 1, videoCount = 3, bucketId = marvel),
                LibraryFolder("Movies/Marvel/Phase 4", "Phase 4", depth = 2, videoCount = 2, bucketId = phase4),
            ),
            folders(),
        )
    }

    // The count is what CHOOSING it would list, which is the whole subtree: Movies holds
    // two of its own, one in Marvel and two more in Phase 4.
    @Test fun aFolderCountsEveryVideoNestedBeneathIt() {
        val counts = folders().associate { it.id to it.videoCount }
        assertEquals(5, counts["Movies"])
        assertEquals(3, counts["Movies/Marvel"])
        assertEquals(2, counts["Movies/Marvel/Phase 4"])
        assertEquals(1, counts["DCIM"])
        // …and the count and the tiles come from the same rule, so they cannot disagree.
        folders().forEach { folder ->
            assertEquals(folder.videoCount, ids(at(folder.id)).size)
        }
    }

    // Depth-first, so a folder is always immediately followed by what it holds; siblings
    // by name without letting case decide, so "alps" cannot sort past "Zebras".
    @Test fun foldersAreOrderedDepthFirstWithSiblingsByNameCaseInsensitively() {
        val mixed = listOf(
            Row(1L, "zebras/", 1L),
            Row(2L, "Alps/", 2L),
            Row(3L, "movies/", 3L),
            Row(4L, "Alps/beta/", 4L),
            Row(5L, "Alps/Alpha/", 5L),
        )
        assertEquals(
            listOf("Alps", "Alps/Alpha", "Alps/beta", "movies", "zebras"),
            folders(mixed).map { it.id },
        )
    }

    // MediaStore hands these out with a trailing separator, and providers vary. Four
    // spellings of one folder must be one folder, or the sheet lists the same place four
    // times and each copy claims a quarter of its files.
    @Test fun separatorsAndBlankSegmentsNeverMakeASecondFolder() {
        val spellings = listOf(
            Row(1L, "Movies/", 1L),
            Row(2L, "Movies", 1L),
            Row(3L, "/Movies/", 1L),
            Row(4L, "Movies//", 1L),
        )
        assertEquals(listOf(LibraryFolder("Movies", "Movies", 0, 4, 1L)), folders(spellings))
        assertEquals("Movies/Marvel", LibraryFolders.normalized("/Movies//Marvel/"))
        assertNull(LibraryFolders.normalized("/"))
        assertNull(LibraryFolders.normalized(null))
    }

    // Pre-Q, and any provider that withholds the column: there is nothing to offer, so
    // the control has nothing to open and never appears.
    @Test fun aLibraryWithNoPathsAtAllOffersNoFolders() {
        val preQ = library.map { it.copy(path = null, bucketId = null) }
        assertEquals(emptyList<LibraryFolder>(), folders(preQ))
        assertFalse(LibraryFolders.chooserOffered(folders(preQ), LibraryScope.All, preQ.size))
    }

    @Test fun aRowWithNoPathJoinsNoFolderAndIsStillListedUnderAll() {
        // Seven rows, six of them placed: the seventh is counted by no folder…
        assertEquals(library.size - 1, folders().filter { it.depth == 0 }.sumOf { it.videoCount })
        assertFalse(7L in ids(at("Movies")))
        assertFalse(7L in ids(at("DCIM")))
        // …and it is still a video the user can reach.
        assertTrue(7L in ids(LibraryScope.All))
    }

    @Test fun choosingAParentListsEverythingNestedUnderIt() {
        assertEquals(listOf(1L, 3L, 4L, 5L, 6L), ids(at("Movies")))
        assertEquals(listOf(4L, 5L, 6L), ids(at("Movies/Marvel")))
        assertEquals(listOf(5L, 6L), ids(at("Movies/Marvel/Phase 4")))
        assertEquals(listOf(2L), ids(at("DCIM/Camera")))
        assertEquals(library.map { it.id }, ids(LibraryScope.All))
    }

    // The prefix rule is separator-aware or it is wrong: "Movies" would otherwise claim
    // every file in the folder next to it.
    @Test fun aFolderNeverSwallowsASiblingWhoseNameItPrefixes() {
        val neighbours = library + Row(8L, "Movies HD/", 30L)
        assertFalse(8L in ids(at("Movies"), neighbours))
        assertEquals(listOf(8L), ids(at("Movies HD"), neighbours))
        assertEquals(5, folders(neighbours).first { it.id == "Movies" }.videoCount)
    }

    @Test fun aFolderThatIsStillThereScopesToTheNameTheLibraryReportsNow() {
        assertEquals(
            at("Movies/Marvel"),
            LibraryFolders.scope(chose("Movies/Marvel"), folders(), resolved = true),
        )
    }

    // The migration. A record written by the flat chooser names a bucket and nothing
    // else; the first library that can say which folder that bucket is rewrites it, so
    // the user who picked Phase 4 before this tree existed is still in Phase 4.
    @Test fun aStoredBucketChoiceMigratesToTheFolderThatBucketIsIn() {
        val stored = LibraryFolderChoice(LibraryFolderId.Bucket(phase4), "Phase 4")
        val scope = LibraryFolders.scope(stored, folders(), resolved = true)
        assertEquals(at("Movies/Marvel/Phase 4"), scope)
        assertEquals(chose("Movies/Marvel/Phase 4"), LibraryFolders.migration(stored, scope))
        assertEquals(listOf(5L, 6L), ids(scope))
    }

    // …and the migrated record is the one that gets written, so it never runs twice.
    @Test fun aPathChoiceIsNeverRewritten() {
        val stored = chose("Movies")
        val scope = LibraryFolders.scope(stored, folders(), resolved = true)
        assertNull(LibraryFolders.migration(stored, scope))
        assertNull(LibraryFolders.migration(null, scope))
    }

    // Until a library arrives that can locate the bucket, the old record goes on scoping
    // by bucket. Nothing is rewritten on that evidence, and the library the user narrowed
    // is not quietly widened while the app waits.
    @Test fun anUnlocatedBucketChoiceKeepsScopingByBucketAndIsNotRewritten() {
        val stored = LibraryFolderChoice(LibraryFolderId.Bucket(phase4), "Phase 4")
        val scope = LibraryFolders.scope(stored, emptyList(), resolved = false)
        assertEquals(LibraryScope.Folder(LibraryFolderId.Bucket(phase4), "Phase 4"), scope)
        assertNull(LibraryFolders.migration(stored, scope))
        assertEquals(listOf(5L, 6L), ids(scope))
    }

    // The distinction the whole feature turns on: a folder the resolved library does not
    // carry is GONE, which is a different fact from a folder that is there with nothing
    // to show, and it must never resolve to All.
    @Test fun aChosenFolderAbsentFromAResolvedLibraryIsMissing() {
        val scope = LibraryFolders.scope(chose("Movies/Holiday"), folders(), resolved = true)
        assertEquals(LibraryScope.Missing("Holiday"), scope)
        assertEquals(emptyList<Long>(), ids(scope))
    }

    // A bucket-keyed record gets exactly the same verdict: an id no folder reports, from
    // a read that reached the end, is a folder that has gone.
    @Test fun aStoredBucketNoFolderReportsIsAlsoMissing() {
        val stored = LibraryFolderChoice(LibraryFolderId.Bucket(404L), "Holiday")
        assertEquals(
            LibraryScope.Missing("Holiday"),
            LibraryFolders.scope(stored, folders(), resolved = true),
        )
    }

    // Every cold start begins with no folders at all. Accusing the folder of being gone
    // before a query has run would put that card on screen on every launch.
    @Test fun anUnresolvedLibraryNeverAccusesTheStoredFolderOfBeingGone() {
        assertEquals(
            at("Movies/Marvel"),
            LibraryFolders.scope(chose("Movies/Marvel"), emptyList(), resolved = false),
        )
    }

    // A read that broke partway is the same kind of evidence as one that has not run:
    // MediaStore returns rows newest first, so a walk that dies mid-cursor drops the
    // OLDEST folder's rows — precisely the ones whose absence would read as a deletion.
    @Test fun aReadThatDidNotCompleteNeverAccusesTheStoredFolderOfBeingGone() {
        val truncated = library.filter { it.bucketId == camera }
        assertEquals(
            at("Movies"),
            LibraryFolders.scope(chose("Movies"), folders(truncated), resolved = false),
        )
        // The very same folder list, from a read that reached the end of the cursor, is
        // the one thing that can support the verdict.
        assertEquals(
            LibraryScope.Missing("Movies"),
            LibraryFolders.scope(chose("Movies"), folders(truncated), resolved = true),
        )
    }

    // Withholding the verdict is not the same as throwing the read away: the files that
    // did arrive are on the phone, and a folder that survived the truncation still lists
    // exactly its own rows.
    @Test fun aPartialReadStillListsEverythingItManagedToRead() {
        val truncated = library.filter { it.bucketId == camera }
        val scope = LibraryFolders.scope(chose("DCIM/Camera"), folders(truncated), resolved = false)
        assertEquals(at("DCIM/Camera"), scope)
        assertEquals(listOf(2L), ids(scope, truncated))
    }

    @Test fun noStoredChoiceIsAlwaysAllRegardlessOfWhatWasScanned() {
        assertEquals(LibraryScope.All, LibraryFolders.scope(null, folders(), resolved = true))
        assertEquals(LibraryScope.All, LibraryFolders.scope(null, emptyList(), resolved = false))
    }

    // A folder that already holds the whole library is the library by another name — and
    // a tree produces those in pairs, because a leaf's parents hold everything it does.
    // The control appears when a folder would actually narrow something.
    @Test fun aFolderThatHoldsEverythingIsNoChoiceButAScopeInForceIsAlwaysEscapable() {
        val oneChain = listOf(Row(1L, "Movies/Marvel/", marvel), Row(2L, "Movies/Marvel/", marvel))
        val chain = folders(oneChain)
        assertEquals(listOf("Movies", "Movies/Marvel"), chain.map { it.id })
        assertFalse(LibraryFolders.chooserOffered(chain, LibraryScope.All, oneChain.size))
        assertTrue(LibraryFolders.chooserOffered(chain, at("Movies/Marvel"), oneChain.size))
        assertTrue(LibraryFolders.chooserOffered(emptyList(), LibraryScope.Missing("Holiday"), 0))
        assertTrue(LibraryFolders.chooserOffered(folders(), LibraryScope.All, library.size))
    }

    // The chip row states "All %d" for the set the folder left behind, so the number and
    // the tiles under it have to come from the same list in the same order.
    @Test fun theAllChipCountsExactlyWhatTheFolderLeftToRender() {
        val scoped = LibraryFolders.scoped(library, at("Movies/Marvel"), Row::path, Row::bucketId)
        assertEquals(3, scoped.size)
        assertEquals(
            scoped.map { it.id },
            LibraryFilterPolicy.apply(scoped, LibFilter.ALL, Row::label).map { it.id },
        )
    }

    // A quality chip can still empty a folder that is genuinely there — the one case the
    // folder-empty copy speaks for, and never the missing folder's case. The two are told
    // apart by the scope, not by the emptiness they share.
    @Test fun aFolderAQualityChipEmptiedIsStillNotAFolderThatIsGone() {
        val clips = listOf(
            Row(9L, "Clips/", 21L, resolutionLabelFor(1280, 720)),
            Row(10L, "Clips/", 21L, resolutionLabelFor(640, 480)),
        )
        val present = LibraryFolders.scope(chose("Clips"), folders(clips), resolved = true)
        val gone = LibraryFolders.scope(chose("Holiday"), folders(clips), resolved = true)
        assertEquals(at("Clips"), present)
        assertEquals(LibraryScope.Missing("Holiday"), gone)
        // The folder that is there lists its files, and only the chip empties them…
        val scoped = LibraryFolders.scoped(clips, present, Row::path, Row::bucketId)
        assertEquals(listOf(9L, 10L), scoped.map { it.id })
        assertTrue(LibraryFilterPolicy.apply(scoped, LibFilter.FOUR_K, Row::label).isEmpty())
        // …while the folder that is gone lists nothing before any chip is consulted.
        assertEquals(emptyList<Long>(), LibraryFolders.scoped(clips, gone, Row::path, Row::bucketId).map { it.id })
    }

    // Quality filtering must not be able to reach past the folder into the rest of the
    // library: the 4K file in Marvel is not in DCIM, chip or no chip.
    @Test fun aChipNeverReachesOutsideTheFolderTheLibraryIsScopedTo() {
        val scoped = LibraryFolders.scoped(library, at("DCIM"), Row::path, Row::bucketId)
        assertTrue(LibraryFilterPolicy.apply(scoped, LibFilter.FOUR_K, Row::label).isEmpty())
        assertEquals(
            listOf(1L, 4L, 6L),
            LibraryFilterPolicy.apply(
                LibraryFolders.scoped(library, at("Movies"), Row::path, Row::bucketId),
                LibFilter.FOUR_K,
                Row::label,
            ).map { it.id },
        )
    }

    @Test fun anEmptyLibraryDerivesNothingAndScopesToNothing() {
        assertEquals(emptyList<LibraryFolder>(), folders(emptyList()))
        assertEquals(emptyList<Long>(), ids(LibraryScope.All, emptyList()))
        assertFalse(LibraryFolders.chooserOffered(emptyList(), LibraryScope.All, 0))
    }
}
