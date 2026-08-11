package com.flick.sender.media

/**
 * The orders the library grid can be dealt in.
 *
 * A direction is offered where both of them are a question somebody asks of their own films:
 * the title being looked for sits at the end of the alphabet as often as the start, and the
 * clip is found by being brief exactly as the feature is found by being long. Newest-first
 * is the one cell with no reverse, because nobody opens their library looking for the thing
 * they have had the longest. How large the file is has no cell at all — it is a fact about
 * storage rather than about films, and it was the one order nobody was asking of a gallery.
 *
 * [RECENT] is the order MediaStore already hands the library over in, so it is both the
 * default and the one cell that cannot re-deal a freshly read library.
 */
enum class LibrarySort(
    /**
     * Which way the grid runs under this order, which is the direction the control's mark
     * draws. Declared here, beside the comparator that implements it, rather than worked out
     * a second time in the UI: a pill claiming one direction while the grid is dealt in the
     * other is then a failing test rather than something a user has to notice.
     */
    internal val ascending: Boolean,
    /**
     * Whether this order compares folded titles, so the screen knows to build them.
     *
     * A property rather than a check against [NAME] where the titles are folded. The moment
     * a second name order existed that check went silently wrong: [NAME_REVERSED] would have
     * been handed an empty title map, every row would have tied, and the grid would have
     * shown library order with nothing anywhere reporting a fault.
     */
    internal val readsTitle: Boolean,
) {
    RECENT(ascending = false, readsTitle = false),
    NAME(ascending = true, readsTitle = true),
    NAME_REVERSED(ascending = false, readsTitle = true),
    LONGEST(ascending = false, readsTitle = false),
    SHORTEST(ascending = true, readsTitle = false),
}

val DefaultLibrarySort = LibrarySort.RECENT

/**
 * A stored order, or the default. Anything else — a record from a build that offered a cell
 * this one does not, a preference file edited by hand — reads as the default rather than as
 * a refusal: the library has to open on something.
 */
fun librarySortOf(stored: String?): LibrarySort =
    LibrarySort.entries.firstOrNull { it.name == stored } ?: DefaultLibrarySort

/**
 * What a row sorts under when the order is alphabetical: the title the tile shows, folded.
 *
 * The name on disk is not what the user is reading. `The.Wailing.2016.1080p.BluRay.mkv` is
 * a tile that says `The Wailing (2016)`, and `[Group] The Wailing.mkv` is another one that
 * says the same thing — sorting the raw names files the second under G, in an A–Z list
 * where nothing on screen says G. Folding then removes the case and the accents, so
 * `amélie` and `Amelie` land together rather than in two different parts of the grid.
 *
 * The raw name is the fallback and only the fallback: a parse that leaves nothing at all is
 * still a file the user owns and has to appear somewhere findable.
 */
fun librarySortTitle(name: String): String {
    val parsed = FoldedText.fold(VideoNames.parse(name).displayName)
    return parsed.ifBlank { FoldedText.fold(name) }
}

internal object LibrarySortPolicy {

    /**
     * [items] re-dealt, keeping the library's own order wherever [order] cannot separate two
     * rows. `sortedWith` is stable, and what it is stable ABOUT is MediaStore's newest-first
     * cursor — so two files added in the same second, and the zero-duration rows MediaStore
     * never managed to scan, hold the places the grid already had them in rather than
     * swapping every time the library is read again.
     *
     * The fields arrive as accessors rather than being read off a type, because `MediaItem`
     * carries an Android `Uri` and none of this is worth being unable to prove on a JVM.
     *
     * A withheld measurement sorts last under every order that reads it, and the bottom of
     * the grid is where a row nothing is known about belongs — never the top, which would be
     * a claim. Under the descending orders that costs nothing, since MediaStore's 0 for a
     * file it could not scan is already the smallest number there is. Ascending, it costs
     * [shortestKey].
     */
    fun <T> sorted(
        items: List<T>,
        order: LibrarySort,
        title: (T) -> String,
        addedSeconds: (T) -> Long,
        durationMs: (T) -> Long,
    ): List<T> {
        val comparator = when (order) {
            LibrarySort.RECENT -> compareByDescending<T> { addedSeconds(it) }
            LibrarySort.NAME -> Comparator<T> { a, b -> LibraryNameOrder.compare(title(a), title(b)) }
            LibrarySort.NAME_REVERSED -> Comparator<T> { a, b -> LibraryNameOrder.compare(title(b), title(a)) }
            LibrarySort.LONGEST -> compareByDescending<T> { durationMs(it) }
            LibrarySort.SHORTEST -> compareBy<T> { shortestKey(durationMs(it)) }
        }
        return if (items.alreadyInOrder(comparator)) items else items.sortedWith(comparator)
    }

    /**
     * A duration as [LibrarySort.SHORTEST] weighs it: an unscanned one is the longest thing
     * in the library rather than the shortest.
     *
     * MediaStore's 0 is a silence, not a measurement. Read ascending it is the smallest
     * number there is, which would open the grid on the rows Flick knows least about while
     * calling them the briefest films the user owns — the one claim every other order here
     * is built to avoid making. Every silence takes the same key, so the stable sort leaves
     * those rows in the order the library arrived in rather than inventing one among rows it
     * has nothing to tell apart.
     */
    private fun shortestKey(durationMs: Long): Long = if (durationMs > 0L) durationMs else Long.MAX_VALUE

    /**
     * Whether [this] is already the answer, so that it can be handed back as the SAME
     * instance rather than copied into an identical one.
     *
     * This is what keeps `LibrarySearchIndexMemo`'s promise reaching the grid. A blank query
     * returns the scoped list itself, so clearing search is meant to be a repaint of the
     * list that is already there — and a sort that allocated unconditionally would quietly
     * have turned every one of those into a new list of the same items, on the default order
     * that never reorders anything, on every keystroke.
     *
     * Checked rather than assumed, which is the difference between this and simply returning
     * [items] for [LibrarySort.RECENT]. The library does arrive newest-first, but that is a
     * fact about MediaLibrary's cursor that this policy cannot see: short-circuiting on the
     * cell name would make RECENT mean "however the provider happened to answer" the day
     * that query changed, and the test for it would still pass because it feeds a list that
     * is already sorted. Verifying costs one pass, and a list that is NOT in order bails at
     * the first pair rather than walking it.
     */
    private fun <T> List<T>.alreadyInOrder(comparator: Comparator<T>): Boolean {
        for (index in 1 until size) {
            if (comparator.compare(this[index - 1], this[index]) > 0) return false
        }
        return true
    }
}

/**
 * A–Z over names people gave their own files, which means the digits inside them count as
 * numbers.
 *
 * Plain string order puts `Episode 10` between `Episode 1` and `Episode 2`, and a video
 * library is mostly names of exactly that shape. Runs of ASCII digits are therefore weighed
 * as magnitudes: with leading zeros dropped the longer run is the larger number, and runs of
 * equal length fall back to the digits themselves. Nothing is parsed into a number, so a
 * forty-digit hash in a filename is compared rather than overflowed.
 *
 * Everything else compares by code point, which is enough because every key reaching here
 * has been through [FoldedText.fold] — one case, no accents, punctuation already gone. A
 * digit outside ASCII is left to that same code-point comparison rather than being weighed:
 * it is a letter of its own script's number, and nothing here can tell which.
 */
internal object LibraryNameOrder {

    fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isAsciiDigit() && b[j].isAsciiDigit()) {
                val startA = a.skipZeros(i)
                val startB = b.skipZeros(j)
                val endA = a.endOfDigits(startA)
                val endB = b.endOfDigits(startB)
                val lengthA = endA - startA
                val lengthB = endB - startB
                if (lengthA != lengthB) return lengthA - lengthB
                for (offset in 0 until lengthA) {
                    val step = a[startA + offset].compareTo(b[startB + offset])
                    if (step != 0) return step
                }
                i = endA
                j = endB
            } else {
                if (a[i] != b[j]) return a[i].compareTo(b[j])
                i++
                j++
            }
        }
        // Whatever is left over: the shorter name is a prefix of the longer one and comes
        // first. Measured from the cursors rather than from zero, because a digit run the
        // loop consumed may have been spelled with a different number of characters on
        // each side.
        return (a.length - i) - (b.length - j)
    }

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

    private fun String.skipZeros(from: Int): Int {
        var at = from
        while (at < length && this[at] == '0') at++
        return at
    }

    private fun String.endOfDigits(from: Int): Int {
        var at = from
        while (at < length && this[at].isAsciiDigit()) at++
        return at
    }
}

/**
 * One folded title per row, kept for as long as the list it was folded from is the same list.
 *
 * Built only when a name order actually asks for it. Folding a title runs a filename parse
 * and two Unicode normalizations, and a phone that never sorts alphabetically must not pay
 * for a single one of them; a phone that does pays once per library rather than once per
 * keystroke and once per visit.
 *
 * Keyed on the list's IDENTITY, never its contents, exactly as `LibrarySearchIndexMemo` is:
 * the controller hands out the same instance until MediaStore answers again. Held by
 * whatever outlives the screen for the same measured reason — a `remember` inside the
 * library route dies with the route, and refolding a library that has not changed is work
 * done on the frame a tab change has already spent.
 *
 * Plain fields rather than snapshot state: this is a memo, it is filled during composition,
 * and publishing it would invalidate the composition that just filled it.
 */
class LibrarySortTitles<T>(
    private val id: (T) -> Long,
    private val name: (T) -> String,
) {
    private var rows: List<T>? = null
    private var titles: Map<Long, String>? = null

    fun of(rows: List<T>): Map<Long, String> {
        val cached = titles
        if (cached != null && this.rows === rows) return cached
        val built = rows.associate { id(it) to librarySortTitle(name(it)) }
        this.rows = rows
        this.titles = built
        return built
    }
}
