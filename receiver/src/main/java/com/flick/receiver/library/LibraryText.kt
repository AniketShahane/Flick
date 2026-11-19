package com.flick.receiver.library

import java.text.Normalizer
import java.util.Locale

/**
 * The phone's `FoldedText`, ported byte for byte.
 *
 * Only the TV's search box needs it: every row's searchable text and sort key arrive already
 * folded by the phone, so the one string folded here is what the viewer typed. It has to fold
 * exactly as the phone does or a query that matches on the phone silently misses on the TV.
 */
object LibraryFold {

    private val LatinDiacritics = 0x0300..0x036F

    private val Whitespace = Regex("\\s+")

    fun fold(text: String): String {
        val decomposed = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
        val stripped = StringBuilder(decomposed.length)
        decomposed.codePoints().forEach { codePoint ->
            val substitute = StrokedLetters[codePoint]
            when {
                codePoint in LatinDiacritics -> Unit
                Character.getType(codePoint) == Character.FORMAT.toInt() -> Unit
                substitute != null -> stripped.append(substitute)
                Character.isLetterOrDigit(codePoint) -> stripped.appendCodePoint(codePoint)
                Character.getType(codePoint) in WordMarks -> stripped.appendCodePoint(codePoint)
                else -> stripped.append(' ')
            }
        }
        return Normalizer.normalize(stripped.toString(), Normalizer.Form.NFC)
            .trim()
            .replace(Whitespace, " ")
    }

    fun words(text: String): List<String> = fold(text).split(' ').filter(String::isNotEmpty)

    private val StrokedLetters = mapOf(
        'ø'.code to "o", 'ł'.code to "l", 'đ'.code to "d", 'ð'.code to "d",
        'ħ'.code to "h", 'ŧ'.code to "t", 'ı'.code to "i", 'ß'.code to "ss",
        'æ'.code to "ae", 'œ'.code to "oe", 'þ'.code to "th",
    )

    private val WordMarks = setOf(
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(),
    )
}

/**
 * The phone's `LibraryNameOrder`, ported: A–Z where runs of ASCII digits weigh as numbers, so
 * `Episode 2` comes before `Episode 10`. Both keys are already folded by the phone.
 */
object LibraryNameOrder : Comparator<String> {

    override fun compare(a: String, b: String): Int {
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
 * The phone's five library orders, with the phone's names for them so a stored choice reads
 * the same on both. Declared in the order the sort menu lists them.
 */
enum class LibrarySortOrder {
    RECENT,
    NAME,
    NAME_REVERSED,
    LONGEST,
    SHORTEST,
    ;

    companion object {
        val Default = RECENT

        fun of(stored: String?): LibrarySortOrder = entries.firstOrNull { it.name == stored } ?: Default
    }
}

object LibrarySorting {

    /**
     * [items] in [order], stable over the order the phone sent them in — which is MediaStore's
     * newest-first — exactly as the phone's `LibrarySortPolicy` is stable over the same list.
     * An unknown duration sorts last under both duration orders, never first.
     */
    fun sorted(items: List<LibraryItem>, order: LibrarySortOrder): List<LibraryItem> {
        val comparator: Comparator<LibraryItem> = when (order) {
            LibrarySortOrder.RECENT -> compareByDescending { it.addedSeconds }
            LibrarySortOrder.NAME -> Comparator { a, b -> LibraryNameOrder.compare(a.sortKey, b.sortKey) }
            LibrarySortOrder.NAME_REVERSED -> Comparator { a, b -> LibraryNameOrder.compare(b.sortKey, a.sortKey) }
            LibrarySortOrder.LONGEST -> compareByDescending { it.durationMs }
            LibrarySortOrder.SHORTEST -> compareBy { if (it.durationMs > 0L) it.durationMs else Long.MAX_VALUE }
        }
        for (index in 1 until items.size) {
            if (comparator.compare(items[index - 1], items[index]) > 0) return items.sortedWith(comparator)
        }
        return items
    }
}

object LibrarySearch {

    /**
     * The rows carrying every word of [query], anywhere in their searchable text — the phone's
     * rule. A query with no words returns [items] itself.
     */
    fun matching(items: List<LibraryItem>, query: String): List<LibraryItem> {
        val words = LibraryFold.words(query)
        if (words.isEmpty()) return items
        return items.filter { item -> words.all { word -> word in item.find } }
    }
}
