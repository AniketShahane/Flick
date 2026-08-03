package com.flick.sender.net

import java.text.Normalizer
import java.util.Locale

/** How well the work a result belongs to agrees with the title that was searched for. */
enum class SubtitleTitleAgreement {
    /** A different work. Ranked last, and dropped when anything else was recognizable. */
    CONFLICTS,

    /** Nothing to compare: a hash-only search, or a result that named no feature at all. */
    UNKNOWN,

    /** One title contains the other: a sequel, a dual-title filename, an episode of it. */
    RELATED,

    /** The same work. */
    AGREES,
}

/** Whether a result is the kind of work — a film or an episode — the filename described. */
enum class SubtitleKindAgreement { CONFLICTS, UNKNOWN, AGREES }

/**
 * Why one text result outranks another, most significant part first. Declared as a type
 * rather than a packed integer so the sort reads in the order it actually decides in.
 */
data class SubtitleRelevance(
    val title: SubtitleTitleAgreement,
    val kind: SubtitleKindAgreement,
    val metadata: Int,
) : Comparable<SubtitleRelevance> {
    override fun compareTo(other: SubtitleRelevance): Int =
        compareValuesBy(this, other, { it.title }, { it.kind }, { it.metadata })
}

/**
 * Whether a text result is actually about the film the phone is casting.
 *
 * OpenSubtitles' own guidance is that `query` is error-prone and that an id should be sent
 * in its place — but an id is exactly what a phone holding nothing but a filename does not
 * have, so the fuzzy answer has to be taken and then checked. Everything needed for that
 * check is already in the response and was previously discarded: which work each subtitle
 * belongs to, and whether that work is a film or an episode.
 *
 * Without the check the ranking falls through to provenance and popularity, so the *most
 * downloaded* member of a fuzzy match set wins whatever it is about. That is why a short
 * title that another catalogue entry shares — `The Chaser`, a 2008 film and also a 2012
 * series — returned somebody else's subtitles at the top: a long-running series has orders
 * of magnitude more downloads than a single film, and nothing here disagreed with it.
 *
 * Pure, and deliberately conservative: only an outright disagreement is ever demoted to
 * last or removed, because a catalogue title and a filename disagree for innocent reasons
 * far more often than they disagree for real ones.
 */
object OpenSubtitlesMatchPolicy {

    /**
     * The Combining Diacritical Marks block, and only it. Folding these lets `Amelie` match
     * `Amélie`; folding every non-spacing mark would instead destroy Indic vowel signs and
     * Semitic points, which are letters of their titles rather than decoration on them.
     */
    private val LatinDiacritics = 0x0300..0x036F

    /** Dropped only when the rest already matched, so an over-broad list cannot mislead. */
    private val Articles = setOf("the", "a", "an")

    private val Whitespace = Regex("\\s+")

    /**
     * The best agreement any of the names this result carries can reach.
     *
     * A parent title is compared **only** for an episode query. It names the series, which
     * is what a `Show.S01E02` filename asked for and is not what a film's filename asked
     * for; matching it either way is precisely how a popular series outranks the film whose
     * name it shares.
     */
    fun titleAgreement(
        result: OnlineSubtitle,
        title: String?,
        season: Int?,
        episode: Int?,
    ): SubtitleTitleAgreement {
        val wanted = tokensOf(title) ?: return SubtitleTitleAgreement.UNKNOWN
        val candidates = buildList {
            result.featureTitle?.let(::add)
            result.featureName?.let(::add)
            if (isEpisodeQuery(season, episode)) result.featureParentTitle?.let(::add)
        }.mapNotNull(::tokensOf)
        if (candidates.isEmpty()) return SubtitleTitleAgreement.UNKNOWN
        return candidates.maxOf { candidate -> agreementOf(wanted, candidate) }
    }

    /**
     * A filename carrying no `SxxEyy` describes a film, and one carrying it describes an
     * episode. This is a ranking signal only and never reaches the wire: `type=movie` on
     * the request would filter out the correct answer for a badly named episode, while
     * ranking it lower merely puts it under results that are not badly named.
     */
    fun kindAgreement(
        result: OnlineSubtitle,
        season: Int?,
        episode: Int?,
    ): SubtitleKindAgreement {
        val kind = result.featureType?.lowercase(Locale.ROOT) ?: return SubtitleKindAgreement.UNKNOWN
        val episodic = kind == "episode" || kind == "tvshow"
        if (!episodic && kind != "movie") return SubtitleKindAgreement.UNKNOWN
        return if (episodic == isEpisodeQuery(season, episode)) {
            SubtitleKindAgreement.AGREES
        } else {
            SubtitleKindAgreement.CONFLICTS
        }
    }

    fun relevance(
        result: OnlineSubtitle,
        title: String?,
        year: Int?,
        season: Int?,
        episode: Int?,
    ): SubtitleRelevance = SubtitleRelevance(
        title = titleAgreement(result, title, season, episode),
        kind = kindAgreement(result, season, episode),
        metadata = metadataAgreement(result, year, season, episode),
    )

    /**
     * The API is the authority on matching, but its result metadata lets Flick avoid
     * promoting a different year or episode above one that agrees with the user's query.
     * Missing metadata is neutral; only an explicit contradiction is weaker.
     */
    fun metadataAgreement(
        result: OnlineSubtitle,
        year: Int?,
        season: Int?,
        episode: Int?,
    ): Int {
        val expected = listOfNotNull(
            year?.takeIf { !isEpisodeQuery(season, episode) && OpenSubtitlesSearchPolicy.validYear(it) }
                ?.let { it to result.featureYear },
            season?.takeIf(OpenSubtitlesSearchPolicy::validSeason)?.let { it to result.season },
            episode?.takeIf(OpenSubtitlesSearchPolicy::validEpisode)?.let { it to result.episode },
        )
        if (expected.isEmpty()) return 1
        if (expected.any { (wanted, actual) -> actual != null && actual != wanted }) return 0
        return if (expected.all { (wanted, actual) -> actual == wanted }) 2 else 1
    }

    /**
     * [results] with the ones naming a different work removed — but only once one of them
     * is a work this recognized, and never otherwise.
     *
     * The gate is [SubtitleTitleAgreement.AGREES] and deliberately not merely "something
     * did not conflict". A row that named no feature at all is UNKNOWN, and a row sharing
     * one word is RELATED; neither is evidence about anything, and letting either arm the
     * filter is how `Rambo.2` — filed as `Rambo: First Blood Part II`, which agrees with
     * nothing — would be deleted in favour of the 2008 `Rambo` that merely shares its first
     * word. Whole classes of title disagree innocently: transliterations, original-language
     * names, festival titles, numbering conventions. Removing an answer Flick failed to
     * recognize would be a worse failure than the one this fixes, so a disagreement is only
     * acted on beside a positive identification of the same search.
     */
    fun recognizable(
        results: List<OnlineSubtitle>,
        title: String?,
        season: Int?,
        episode: Int?,
    ): List<OnlineSubtitle> {
        val agreements = results.map { titleAgreement(it, title, season, episode) }
        if (agreements.none { it == SubtitleTitleAgreement.AGREES }) return results
        return results.filterIndexed { index, _ ->
            agreements[index] != SubtitleTitleAgreement.CONFLICTS
        }
    }

    internal fun isEpisodeQuery(season: Int?, episode: Int?): Boolean =
        season?.let(OpenSubtitlesSearchPolicy::validSeason) == true &&
            episode?.let(OpenSubtitlesSearchPolicy::validEpisode) == true

    private fun agreementOf(
        wanted: List<String>,
        candidate: List<String>,
    ): SubtitleTitleAgreement {
        val wantedNumbered = numbered(wanted)
        val candidateNumbered = numbered(candidate)
        return when {
            wantedNumbered == candidateNumbered -> SubtitleTitleAgreement.AGREES
            // `Wailing, The` and `The Wailing` are one title written two ways.
            wantedNumbered.sorted() == candidateNumbered.sorted() -> SubtitleTitleAgreement.AGREES
            // And so are `Oceans.Eleven` and `Ocean's Eleven`. A release name drops the
            // apostrophe the catalogue keeps and keeps the hyphen of `Spider-Man` that the
            // catalogue may not, so the words are also compared with every boundary between
            // them removed: a separator present on one side only must not make two films.
            // Dropping a leading article first is what lets `Chaser` reach `The Chaser`.
            //
            // Both spellings of the numbers are fused, because the numeral folding happens
            // per word and a word boundary is exactly what the fused form has removed:
            // `Xmen` reaches `X-Men` only unnumbered, and `Rocky2` reaches `Rocky II` only
            // numbered. Comparing one form alone re-breaks whichever pair it does not suit.
            fused(wanted) == fused(candidate) -> SubtitleTitleAgreement.AGREES
            fused(wantedNumbered) == fused(candidateNumbered) -> SubtitleTitleAgreement.AGREES
            covers(wantedNumbered, candidateNumbered) ||
                covers(candidateNumbered, wantedNumbered) -> SubtitleTitleAgreement.RELATED
            else -> SubtitleTitleAgreement.CONFLICTS
        }
    }

    private fun fused(tokens: List<String>): String = withoutArticle(tokens).joinToString("")

    /** `Rocky II` and `Rocky.2` are one film written two ways, and filenames use both. */
    private fun numbered(tokens: List<String>): List<String> =
        tokens.map { token -> RomanNumerals[token] ?: token }

    /** Every word of [inner] appears in [outer]: `Blade Runner` inside `Blade Runner 2049`. */
    private fun covers(outer: List<String>, inner: List<String>): Boolean =
        inner.size < outer.size && outer.containsAll(inner)

    private fun withoutArticle(tokens: List<String>): List<String> =
        if (tokens.size > 1 && tokens.first() in Articles) tokens.drop(1) else tokens

    private fun tokensOf(text: String?): List<String>? = folded(text ?: return null)
        .split(' ')
        .filter { it.isNotEmpty() }
        .takeIf { it.isNotEmpty() }

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
    private fun folded(text: String): String {
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

    private val RomanNumerals = mapOf(
        "i" to "1", "ii" to "2", "iii" to "3", "iv" to "4", "v" to "5", "vi" to "6",
        "vii" to "7", "viii" to "8", "ix" to "9", "x" to "10", "xi" to "11",
        "xii" to "12", "xiii" to "13",
    )

    private val WordMarks = setOf(
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(),
    )
}
