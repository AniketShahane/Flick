package com.flick.sender.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The library's orders, proved on a JVM. `MediaItem` carries an Android `Uri`, so the policy
 * takes its fields as accessors and this stands in for it.
 */
private data class Row(
    val id: Long,
    val title: String = "",
    val added: Long = 0L,
    val duration: Long = 0L,
)

private fun sort(rows: List<Row>, order: LibrarySort): List<Row> = LibrarySortPolicy.sorted(
    items = rows,
    order = order,
    title = Row::title,
    addedSeconds = Row::added,
    durationMs = Row::duration,
)

class LibrarySortPolicyTest {

    @Test fun recentIsTheDefaultAndIsWhatMediaStoreAlreadyHandsOver() {
        assertEquals(LibrarySort.RECENT, DefaultLibrarySort)
        val newestFirst = listOf(
            Row(id = 1, added = 900),
            Row(id = 2, added = 500),
            Row(id = 3, added = 100),
        )
        assertEquals(newestFirst, sort(newestFirst, LibrarySort.RECENT))
    }

    /**
     * The search index returns the scoped list ITSELF for a blank query, so that clearing
     * search repaints the list already on screen rather than replacing it with an identical
     * one. That promise is only kept if the sort hands the same instance back, and the
     * default order re-deals nothing — so on the ordinary path, typing and clearing must
     * allocate no list at all.
     */
    @Test fun aListAlreadyInTheOrderAskedForIsHandedBackUntouched() {
        val newestFirst = listOf(Row(id = 1, added = 900), Row(id = 2, added = 500))
        assertSame(newestFirst, sort(newestFirst, LibrarySort.RECENT))

        val longestFirst = listOf(Row(id = 1, duration = 90), Row(id = 2, duration = 10))
        assertSame(longestFirst, sort(longestFirst, LibrarySort.LONGEST))

        val alphabetical = listOf(Row(id = 1, title = "amelie"), Row(id = 2, title = "parasite"))
        assertSame(alphabetical, sort(alphabetical, LibrarySort.NAME))

        val reversed = listOf(Row(id = 1, title = "parasite"), Row(id = 2, title = "amelie"))
        assertSame(reversed, sort(reversed, LibrarySort.NAME_REVERSED))

        val shortestFirst = listOf(Row(id = 1, duration = 10), Row(id = 2, duration = 90))
        assertSame(shortestFirst, sort(shortestFirst, LibrarySort.SHORTEST))

        // And a row SHORTEST has to hold back is at the bottom already, not out of place —
        // the check has to read the same key the sort does or it re-deals the list every time.
        val shortestPastASilence = listOf(Row(id = 1, duration = 10), Row(id = 2, duration = 0))
        assertSame(shortestPastASilence, sort(shortestPastASilence, LibrarySort.SHORTEST))
    }

    @Test fun aListOutOfOrderIsReDealtIntoANewOne() {
        val rows = listOf(Row(id = 1, added = 100), Row(id = 2, added = 900))
        val sorted = sort(rows, LibrarySort.RECENT)
        assertNotSame(rows, sorted)
        assertEquals(listOf(2L, 1L), sorted.map(Row::id))
    }

    /** Ties are already in order — a tie is not a row out of place. */
    @Test fun aRunOfTiesDoesNotCountAsOutOfOrder() {
        val rows = listOf(Row(id = 7, added = 500), Row(id = 4, added = 500), Row(id = 9, added = 500))
        assertSame(rows, sort(rows, LibrarySort.RECENT))
    }

    @Test fun recentPutsTheNewestArrivalFirst() {
        val rows = listOf(Row(id = 1, added = 100), Row(id = 2, added = 900), Row(id = 3, added = 500))
        assertEquals(listOf(2L, 3L, 1L), sort(rows, LibrarySort.RECENT).map(Row::id))
    }

    @Test fun rowsMediaStoreCouldNotDateKeepTheirPlaceRatherThanTakingTheTop() {
        val rows = listOf(Row(id = 1, added = 0), Row(id = 2, added = 400), Row(id = 3, added = 0))
        assertEquals(listOf(2L, 1L, 3L), sort(rows, LibrarySort.RECENT).map(Row::id))
    }

    @Test fun tiesKeepTheOrderTheLibraryArrivedIn() {
        val rows = listOf(Row(id = 7, added = 500), Row(id = 4, added = 500), Row(id = 9, added = 500))
        assertEquals(listOf(7L, 4L, 9L), sort(rows, LibrarySort.RECENT).map(Row::id))
    }

    @Test fun nameOrdersByTheFoldedTitle() {
        val rows = listOf(
            Row(id = 1, title = "the wailing 2016"),
            Row(id = 2, title = "amelie"),
            Row(id = 3, title = "parasite 2019"),
        )
        assertEquals(listOf(2L, 3L, 1L), sort(rows, LibrarySort.NAME).map(Row::id))
    }

    @Test fun nameReversedIsTheSameOrderReadFromTheOtherEnd() {
        val rows = listOf(
            Row(id = 1, title = "the wailing 2016"),
            Row(id = 2, title = "amelie"),
            Row(id = 3, title = "parasite 2019"),
        )
        assertEquals(listOf(1L, 3L, 2L), sort(rows, LibrarySort.NAME_REVERSED).map(Row::id))
        // No two of these tie, so Z–A is exactly A–Z backwards and can be checked against it.
        assertEquals(
            sort(rows, LibrarySort.NAME).map(Row::id).reversed(),
            sort(rows, LibrarySort.NAME_REVERSED).map(Row::id),
        )
    }

    @Test fun longestReadsDurationAndLeavesUnscannedFilesAtTheBottom() {
        val rows = listOf(
            Row(id = 1, duration = 0L),
            Row(id = 2, duration = 5_400_000L),
            Row(id = 3, duration = 90_000L),
        )
        assertEquals(listOf(2L, 3L, 1L), sort(rows, LibrarySort.LONGEST).map(Row::id))
    }

    /**
     * The order this whole policy is most able to lie in. `0` is what MediaStore reports for
     * a file it could not scan, and ascending order reads that as the smallest number there
     * is — so a naive SHORTEST opens the grid on the rows Flick knows nothing about and calls
     * them the briefest films the user owns.
     */
    @Test fun shortestReadsDurationAndStillLeavesUnscannedFilesAtTheBottom() {
        val rows = listOf(
            Row(id = 1, duration = 0L),
            Row(id = 2, duration = 5_400_000L),
            Row(id = 3, duration = 90_000L),
        )
        assertEquals(listOf(3L, 2L, 1L), sort(rows, LibrarySort.SHORTEST).map(Row::id))
    }

    @Test fun shortestDoesNotOrderOneSilenceAgainstAnother() {
        // Nothing separates rows that were never scanned, so they hold the places the library
        // gave them — including a negative, which is no more a length than a zero is.
        val rows = listOf(
            Row(id = 1, duration = 0L),
            Row(id = 2, duration = 60_000L),
            Row(id = 3, duration = -1L),
            Row(id = 4, duration = 0L),
        )
        assertEquals(listOf(2L, 1L, 3L, 4L), sort(rows, LibrarySort.SHORTEST).map(Row::id))
    }

    @Test fun everyOrderKeepsEveryRow() {
        val rows = (1L..20L).map { Row(id = it, title = "film $it", added = it, duration = it) }
        LibrarySort.entries.forEach { order ->
            assertEquals(order.name, rows.map(Row::id).sorted(), sort(rows, order).map(Row::id).sorted())
        }
    }

    /**
     * `sortedWith` is TimSort, which THROWS mid-merge on a self-contradicting comparator once
     * a list reaches 32 — an ordinary number of films. SHORTEST is the one order here whose
     * comparator is composed rather than a single field read, so every order is put through
     * the real sort on a library long enough for TimSort to police it, over the values that
     * could break the composition: both silences, ordinary lengths, and the sentinel a
     * withheld row is weighed as.
     */
    @Test fun everyOrderSortsALibraryLongEnoughForTimSortToPoliceIt() {
        val durations = listOf(0L, -1L, 1L, 90_000L, Long.MAX_VALUE, 5_400_000L, 42L, 0L)
        val rows = (0 until 96).map { index ->
            Row(
                id = index.toLong(),
                title = "film ${index % 7}",
                added = (index % 5).toLong(),
                duration = durations[index % durations.size],
            )
        }
        LibrarySort.entries.forEach { order ->
            val sorted = sort(rows, order)
            assertEquals(order.name, rows.map(Row::id).sorted(), sorted.map(Row::id).sorted())
        }
    }

    /**
     * The pill draws an up or a down arrow straight off [LibrarySort.ascending], and nothing
     * a user can see would report it drawing the wrong one. These three rows rank the same
     * way under every order, so the sort's own answer is what the direction is checked
     * against rather than the declaration being taken at its word.
     */
    @Test fun everyOrderRunsTheDirectionItsControlClaims() {
        val rows = listOf(
            Row(id = 1, title = "a", added = 1, duration = 1),
            Row(id = 2, title = "b", added = 2, duration = 2),
            Row(id = 3, title = "c", added = 3, duration = 3),
        )
        LibrarySort.entries.forEach { order ->
            val expected = if (order.ascending) listOf(1L, 2L, 3L) else listOf(3L, 2L, 1L)
            assertEquals(order.name, expected, sort(rows, order).map(Row::id))
        }
    }

    /**
     * [LibrarySort.readsTitle] is what the screen folds titles on, and an order that reads a
     * title without declaring it gets an empty map and a grid dealt on blank strings. Proved
     * by taking the titles away and seeing which orders notice.
     */
    @Test fun exactlyTheOrdersThatCompareTitlesDeclareThatTheyDo() {
        val titled = listOf(Row(id = 1, title = "b"), Row(id = 2, title = "a"), Row(id = 3, title = "c"))
        val untitled = titled.map { it.copy(title = "") }
        LibrarySort.entries.forEach { order ->
            val noticed = sort(titled, order).map(Row::id) != sort(untitled, order).map(Row::id)
            assertEquals(order.name, order.readsTitle, noticed)
        }
    }

    @Test fun anUnknownStoredOrderOpensOnTheDefault() {
        assertEquals(LibrarySort.RECENT, librarySortOf(null))
        assertEquals(LibrarySort.RECENT, librarySortOf(""))
        assertEquals(LibrarySort.RECENT, librarySortOf("SMALLEST"))
        assertEquals(LibrarySort.RECENT, librarySortOf("name"))
        assertEquals(LibrarySort.NAME, librarySortOf("NAME"))
        assertEquals(LibrarySort.NAME_REVERSED, librarySortOf("NAME_REVERSED"))
        assertEquals(LibrarySort.SHORTEST, librarySortOf("SHORTEST"))
    }

    /**
     * A phone that had "Largest first" chosen still has `LARGEST` written in
     * `flick_library_sort` after the update, naming an order this build does not offer. It
     * opens on the default rather than on nothing, which is the same fallback a hand-edited
     * preference gets — but this one is a real record on a real phone rather than a
     * hypothetical, so it is checked rather than assumed.
     */
    @Test fun theOrderThisBuildDroppedOpensOnTheDefault() {
        assertTrue(LibrarySort.entries.none { it.name == "LARGEST" })
        assertEquals(LibrarySort.RECENT, librarySortOf("LARGEST"))
    }
}

class LibraryNameOrderTest {

    private fun before(a: String, b: String) {
        assertTrue("$a should sort before $b", LibraryNameOrder.compare(a, b) < 0)
        assertTrue("$b should sort after $a", LibraryNameOrder.compare(b, a) > 0)
    }

    @Test fun digitsInsideANameAreWeighedAsNumbers() {
        before("episode 2", "episode 10")
        before("episode 9", "episode 10")
        before("s01e09", "s01e10")
        before("a2b", "a10b")
    }

    @Test fun leadingZerosAreNotADifference() {
        assertEquals(0, LibraryNameOrder.compare("episode 02", "episode 2"))
        assertEquals(0, LibraryNameOrder.compare("part 007", "part 7"))
    }

    @Test fun aLongRunOfDigitsIsComparedRatherThanOverflowed() {
        val thirtyOneDigits = "1" + "0".repeat(30)
        val thirtyNines = "9".repeat(30)
        before(thirtyNines, thirtyOneDigits)
        assertEquals(0, LibraryNameOrder.compare(thirtyNines, thirtyNines))
    }

    @Test fun aShorterNameThatIsAPrefixComesFirst() {
        before("the wailing", "the wailing 2016")
        before("", "a")
    }

    @Test fun ordinaryLettersStillDecideWhenNoDigitsAreInvolved() {
        before("amelie", "parasite")
        assertEquals(0, LibraryNameOrder.compare("parasite", "parasite"))
    }

    /**
     * Every folded name of up to three characters over the alphabet that can actually break
     * this: two letters to align on, two digits to be weighed as numbers rather than
     * characters, and the space that folding leaves between words.
     */
    private val everyShortName: List<String> = buildList {
        val alphabet = listOf("a", "b", "0", "1", " ")
        add("")
        addAll(alphabet)
        alphabet.forEach { a -> alphabet.forEach { b -> add(a + b) } }
        alphabet.forEach { a -> alphabet.forEach { b -> alphabet.forEach { c -> add(a + b + c) } } }
    }

    /**
     * What this rules out is not a badly ordered grid.
     *
     * A digit run is weighed as a number only where BOTH names have digits, so which rule
     * decides a position depends on the name being compared against — and a comparator whose
     * answers can disagree with each other does not merely sort oddly: `sortedWith` is
     * TimSort, and it THROWS mid-merge on any list of 32 or more once it catches one. That is
     * the library screen crashing on a phone with an ordinary number of films on it, so the
     * contract is checked over every triple rather than argued.
     *
     * The matrix is filled once and the triples then read it, because the check is 3.8
     * million triples and only 24 thousand comparisons.
     */
    @Test fun theOrderIsTotalSoSortingCanNeverThrow() {
        val names = everyShortName
        val size = names.size
        // So a name set that ever collapsed could not let the loops below pass by not running.
        assertTrue("the name set collapsed to $size", size > 100)
        val sign = Array(size) { i -> IntArray(size) { j -> Integer.signum(LibraryNameOrder.compare(names[i], names[j])) } }

        for (i in 0 until size) {
            if (sign[i][i] != 0) fail("'${names[i]}' does not equal itself")
            for (j in 0 until size) {
                if (sign[i][j] != -sign[j][i]) {
                    fail("'${names[i]}' vs '${names[j]}' disagrees with its own reverse")
                }
            }
        }

        for (i in 0 until size) {
            for (j in 0 until size) {
                if (sign[i][j] > 0) continue
                for (k in 0 until size) {
                    if (sign[j][k] > 0) continue
                    if (sign[i][k] > 0) {
                        fail("'${names[i]}' ≤ '${names[j]}' ≤ '${names[k]}' but '${names[i]}' > '${names[k]}'")
                    }
                }
            }
        }
    }

    /**
     * And the same set put through the real sort, which is what TimSort actually polices —
     * in both directions, because Z–A is this comparator with its arguments swapped and a
     * reversed total order is only total if the order it reverses was.
     */
    @Test fun aLibraryOfThoseNamesSortsWithoutThrowing() {
        val rows = everyShortName.mapIndexed { index, name -> Row(id = index.toLong(), title = name) }

        val forwards = LibrarySortPolicy.sorted(
            items = rows,
            order = LibrarySort.NAME,
            title = Row::title,
            addedSeconds = Row::added,
            durationMs = Row::duration,
        )
        assertEquals(rows.size, forwards.size)
        forwards.zipWithNext { a, b ->
            if (LibraryNameOrder.compare(a.title, b.title) > 0) fail("'${a.title}' was placed before '${b.title}'")
        }

        val backwards = LibrarySortPolicy.sorted(
            items = rows,
            order = LibrarySort.NAME_REVERSED,
            title = Row::title,
            addedSeconds = Row::added,
            durationMs = Row::duration,
        )
        assertEquals(rows.size, backwards.size)
        backwards.zipWithNext { a, b ->
            if (LibraryNameOrder.compare(b.title, a.title) > 0) fail("'${a.title}' was placed before '${b.title}'")
        }
    }
}

class LibrarySortTitleTest {

    @Test fun aRowSortsUnderTheTitleItsTileShows() {
        assertEquals("the wailing 2016", librarySortTitle("The.Wailing.2016.1080p.BluRay.x265-GRP.mkv"))
    }

    @Test fun caseAndAccentsAreFoldedSoOneFilmIsOnePlaceInTheGrid() {
        assertEquals(librarySortTitle("Amelie.mkv"), librarySortTitle("Amélie.mkv"))
    }

    @Test fun aNameTheParserCanMakeNothingOfStillSortsSomewhere() {
        assertTrue(librarySortTitle("...mkv").isNotEmpty())
    }
}

class LibrarySortTitlesMemoTest {

    private val memo = LibrarySortTitles<Row>(id = Row::id, name = Row::title)

    @Test fun theSameListIsFoldedOnce() {
        val rows = listOf(Row(id = 1, title = "Amelie.mkv"))
        assertSame(memo.of(rows), memo.of(rows))
    }

    @Test fun aNewLibraryIsFoldedAgainEvenWhenItReadsTheSame() {
        val rows = listOf(Row(id = 1, title = "Amelie.mkv"))
        val first = memo.of(rows)
        val second = memo.of(rows.toList())
        assertEquals(first, second)
        assertTrue(first !== second)
    }

    @Test fun everyRowIsReachableByItsOwnIdentity() {
        val rows = listOf(Row(id = 4, title = "Parasite.2019.mkv"), Row(id = 9, title = "Amelie.mkv"))
        val titles = memo.of(rows)
        assertEquals("parasite 2019", titles[4L])
        assertEquals("amelie", titles[9L])
    }
}

class LibrarySortControllerTest {

    @Test fun selectingAnOrderPublishesAndPersistsItOnce() {
        val writes = mutableListOf<LibrarySort>()
        val controller = LibrarySortController(LibrarySort.RECENT, writes::add)

        controller.select(LibrarySort.NAME)
        controller.select(LibrarySort.NAME)

        assertEquals(LibrarySort.NAME, controller.order.value)
        assertEquals(listOf(LibrarySort.NAME), writes)
    }

    @Test fun aStoredOrderIsWhatTheFirstGridIsDealtIn() {
        val controller = LibrarySortController(LibrarySort.SHORTEST) { }
        assertEquals(LibrarySort.SHORTEST, controller.order.value)
    }
}
