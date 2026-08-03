package com.flick.sender.media

import java.text.Normalizer
import java.util.Locale

/**
 * One spelling of a name reduced to the letters and digits every other spelling of it shares.
 *
 * Shared, rather than written twice, because Flick compares names it did not choose the
 * spelling of in two places — the library's own search box against a filename, and a
 * subtitle catalogue's title against that same filename — and both ask Unicode the identical
 * question. Two answers to it would be a defect in whichever side lost: a phone that finds
 * `Amélie` by typing `amelie` and then cannot fetch subtitles for the film it just found is
 * one behavior, not two. What each caller does with the words afterwards is its own policy;
 * which characters ARE the words is not.
 */
object FoldedText {

    /**
     * The Combining Diacritical Marks block, and only it. Folding these lets `Amelie` match
     * `Amélie`; folding every non-spacing mark would instead destroy Indic vowel signs and
     * Semitic points, which are letters of their titles rather than decoration on them.
     */
    private val LatinDiacritics = 0x0300..0x036F

    private val Whitespace = Regex("\\s+")

    /**
     * Case, accents and punctuation removed, so only the words are left to compare.
     *
     * Lower-cased first and with [Locale.ROOT], never the device's: a phone set to Turkish
     * would map the `I` of `INCEPTION` to a dotless `ı` and agree with nothing. The
     * decomposition is compatibility rather than canonical, so a full-width `ＴＥＮＥＴ` and
     * a `ﬁ` ligature reach the same letters an ASCII release name spells them with.
     *
     * A mark that is not a Latin diacritic is kept, never turned into a separator: an Indic
     * vowel sign or virama is a letter of its word, and spacing one out would leave two
     * fragments that agree with nothing. Joiners are dropped on both sides for the same
     * reason in reverse — one spelling carrying one and another not is still one word.
     */
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

    /** [fold]ed and cut at the separators folding leaves behind: the words, and nothing else. */
    fun words(text: String): List<String> = fold(text).split(' ').filter(String::isNotEmpty)

    /**
     * The Latin letters that carry their mark *inside* the glyph, which no decomposition
     * will separate. Without these `Brodre` disagrees with `Brødre` and `Kis Uykusu` with
     * `Kış Uykusu`, and a release name spells those the ASCII way far more often than not.
     */
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
