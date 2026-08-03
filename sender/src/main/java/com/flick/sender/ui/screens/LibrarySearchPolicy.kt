package com.flick.sender.ui.screens

import com.flick.sender.media.FoldedText
import com.flick.sender.media.VideoNames

/**
 * What the library's search box means by a match.
 *
 * The grid does not show the name on disk. `The.Wailing.2016.1080p.BluRay.x265-GRP.mkv` is a
 * tile reading `The Wailing (2016)`, so looking for the whole query as one unbroken run of
 * that filename answered a question nobody asked: typing what was on the screen matched
 * nothing, because the dots the user replaced with spaces were still in the string being
 * searched, and one run of characters cannot reach across the release junk between
 * `Wailing` and `2016` either. Both spellings are therefore searched, and the query is read
 * as words rather than as a run: every word must be found, in any order.
 *
 * A word is found ANYWHERE inside the row's text, and not only where a word of it begins.
 * That is the looser of the two rules on purpose. This is a filter box over one person's
 * own videos, where failing to show a file they have is the expensive mistake and showing
 * one extra is not; it keeps a half-typed word useful (`wail` reaches `Wailing`), it is what
 * `lick te` reaching `The Flick Test` has always meant here, and it is the only rule that
 * resolves the separators in BOTH directions — a filename spelling `Spider.Man` searched as
 * `spiderman`, and one spelling `Spiderman` searched as `spider man`. What it costs is that
 * a very short word narrows less than it appears to: `man` also answers with `Woman`. Every
 * further word typed is an AND, so the query tightens again immediately.
 */
internal object LibrarySearchPolicy {

    /**
     * Every name folded once, for a library that then answers a keystroke with nothing but
     * substring tests.
     *
     * The per-row work is Unicode normalization and, behind the displayed title, a regex
     * parse of the filename. Paying either for every row on every character typed is what
     * makes a phone drop frames while the user is still typing, so it is paid when the
     * library itself changes — which is also the only time any of it can change.
     */
    fun <T> index(items: List<T>, name: (T) -> String): Index<T> =
        Index(items, items.map { entryOf(name(it)) })

    class Index<T> internal constructor(
        private val items: List<T>,
        private val entries: List<Entry>,
    ) {
        /**
         * The rows carrying every word of [query], in the library's own order.
         *
         * A query with no words in it — blank, or punctuation alone — returns the SAME list
         * instance it was indexed from, which is the one the grid already holds: clearing
         * search is then a repaint of the list that is already there rather than a new list
         * of the same items.
         */
        fun matching(query: String): List<T> {
            val words = FoldedText.words(query)
            if (words.isEmpty()) return items
            return items.filterIndexed { index, _ -> entries[index].carries(words) }
        }
    }

    /**
     * One row's searchable text with its word boundaries dropped rather than kept.
     *
     * A separator present on one side only must not make two different videos: the filename
     * spells `Spider.Man`, the phone types `spiderman`, and neither of them is wrong. With
     * the boundaries gone from the row and the query cut at them, both spellings meet in the
     * middle — and so does a name that fuses what the query separates.
     */
    internal class Entry(private val text: String) {
        fun carries(words: List<String>): Boolean = words.all { word -> word in text }
    }

    /**
     * The name on disk AND the title the tile shows, because the user may have read either.
     *
     * The parsed title is appended only when the raw name does not already spell it, which
     * is the common case — it is built out of that name's own words — so what this usually
     * adds is nothing, and what it adds otherwise is only what parsing changed: an `S1E2`
     * shown as `S01E02`.
     */
    private fun entryOf(name: String): Entry {
        val raw = FoldedText.fold(name)
        val displayed = FoldedText.fold(VideoNames.parse(name).displayName)
        val text = if (displayed.isEmpty() || displayed in raw) raw else "$raw $displayed"
        return Entry(text.replace(" ", ""))
    }
}
