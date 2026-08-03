package com.flick.sender.net

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a fuzzy text answer is actually about the film being cast.
 *
 * The API is asked with words off a filename because a phone has no id to ask with, so
 * every one of these cases is a way those words and a catalogue entry can disagree while
 * meaning the same work — or agree while meaning a different one.
 */
class OpenSubtitlesMatchPolicyTest {

    private fun result(
        id: Long = 1L,
        title: String? = null,
        name: String? = null,
        parentTitle: String? = null,
        type: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
    ) = OnlineSubtitle(
        fileId = id,
        fileName = "file-$id.srt",
        language = "en",
        release = "release-$id",
        downloads = 0,
        featureType = type,
        featureTitle = title,
        featureName = name,
        featureParentTitle = parentTitle,
        featureYear = year,
        season = season,
        episode = episode,
    )

    private fun agreement(
        query: String?,
        title: String? = null,
        name: String? = null,
        parentTitle: String? = null,
        season: Int? = null,
        episode: Int? = null,
    ) = OpenSubtitlesMatchPolicy.titleAgreement(
        result(title = title, name = name, parentTitle = parentTitle),
        query,
        season,
        episode,
    )

    // --- one work written two ways -------------------------------------------

    @Test fun caseAccentsAndPunctuationAreNotDisagreements() {
        assertEquals(SubtitleTitleAgreement.AGREES, agreement("the wailing", title = "The Wailing"))
        assertEquals(SubtitleTitleAgreement.AGREES, agreement("Amelie", title = "Amélie"))
        assertEquals(SubtitleTitleAgreement.AGREES, agreement("Amélie", title = "Amelie"))
        assertEquals(
            SubtitleTitleAgreement.AGREES,
            agreement("Leon: The Professional", title = "Léon — the professional"),
        )
        assertEquals(SubtitleTitleAgreement.AGREES, agreement("Spider-Man", title = "Spider Man"))
    }

    @Test fun aSeparatorOnOneSideOnlyDoesNotMakeTwoFilms() {
        // Release names drop the apostrophe the catalogue files these under, which is the
        // single most common way a correct answer looks like a different work.
        assertEquals(SubtitleTitleAgreement.AGREES, agreement("Oceans Eleven", title = "Ocean's Eleven"))
        assertEquals(SubtitleTitleAgreement.AGREES, agreement("Schindlers List", title = "Schindler's List"))
        assertEquals(SubtitleTitleAgreement.AGREES, agreement("Dont Look Up", title = "Don't Look Up"))
        assertEquals(SubtitleTitleAgreement.AGREES, agreement("Spiderman", title = "Spider-Man"))
        assertEquals(SubtitleTitleAgreement.AGREES, agreement("Wall E", title = "WALL·E"))
        // Fusing must not reach across a missing word.
        assertEquals(SubtitleTitleAgreement.CONFLICTS, agreement("Oceans Eleven", title = "Oceans Thirteen"))
    }

    @Test fun aSequelNumberedTwoWaysIsOneFilm() {
        assertEquals(SubtitleTitleAgreement.AGREES, agreement("Rocky 2", title = "Rocky II"))
        assertEquals(SubtitleTitleAgreement.AGREES, agreement("Kill Bill Vol 2", title = "Kill Bill: Vol. II"))
        assertEquals(SubtitleTitleAgreement.CONFLICTS, agreement("Rocky 2", title = "Rocky 3"))
    }

    @Test fun numberingAndFusingDoNotUndoEachOther() {
        // The numeral folding is per word, and the fused form is the one with the word
        // boundaries taken out, so each spelling needs the comparison the other breaks.
        assertEquals(SubtitleTitleAgreement.AGREES, agreement("Xmen", title = "X-Men"))
        assertEquals(SubtitleTitleAgreement.AGREES, agreement("X Men", title = "X-Men"))
        assertEquals(SubtitleTitleAgreement.AGREES, agreement("RockyII", title = "Rocky II"))
        assertEquals(SubtitleTitleAgreement.AGREES, agreement("Rocky2", title = "Rocky II"))
        assertEquals(SubtitleTitleAgreement.CONFLICTS, agreement("Xmen", title = "X-Files"))
    }

    @Test fun aLetterCarryingItsMarkInsideTheGlyphStillFolds() {
        // No decomposition separates these, so without an explicit fold the ASCII spelling
        // a release name almost always uses disagrees with the catalogue.
        assertEquals(SubtitleTitleAgreement.AGREES, agreement("Brodre", title = "Brødre"))
        assertEquals(SubtitleTitleAgreement.AGREES, agreement("Kis Uykusu", title = "Kış Uykusu"))
        assertEquals(SubtitleTitleAgreement.AGREES, agreement("Strasse", title = "Straße"))
        assertEquals(SubtitleTitleAgreement.AGREES, agreement("Lodz", title = "Łódź"))
    }

    @Test fun compatibilityFormsReachTheLettersAReleaseNameSpellsThemWith() {
        assertEquals(SubtitleTitleAgreement.AGREES, agreement("TENET", title = "ＴＥＮＥＴ"))
        assertEquals(SubtitleTitleAgreement.AGREES, agreement("The Final Cut", title = "The ﬁnal Cut"))
    }

    @Test fun anArticleAndWhereItSitsAreNotDisagreementsEither() {
        // A library that files titles as `Wailing, The` describes the same film.
        assertEquals(SubtitleTitleAgreement.AGREES, agreement("Wailing, The", title = "The Wailing"))
        assertEquals(SubtitleTitleAgreement.AGREES, agreement("The Chaser", title = "Chaser"))
        assertEquals(SubtitleTitleAgreement.AGREES, agreement("Chaser", title = "The Chaser"))
    }

    @Test fun aTurkishPhoneStillMatchesAnAsciiTitle() {
        // Lower-casing with the device's locale maps this I to a dotless ı on exactly one
        // side of the comparison, and the film then agrees with nothing.
        val device = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(SubtitleTitleAgreement.AGREES, agreement("INCEPTION", title = "Inception"))
        } finally {
            Locale.setDefault(device)
        }
    }

    @Test fun aNonLatinTitleKeepsTheMarksThatAreItsLetters() {
        // Folding every combining mark would leave fragments of these, matching nothing.
        assertEquals(SubtitleTitleAgreement.AGREES, agreement("곡성", title = "곡성"))
        assertEquals(SubtitleTitleAgreement.AGREES, agreement("क्षितिज", title = "क्षितिज"))
        assertEquals(SubtitleTitleAgreement.AGREES, agreement("क्‍षितिज", title = "क्षितिज"))
        assertEquals(SubtitleTitleAgreement.CONFLICTS, agreement("곡성", title = "추격자"))
    }

    // --- a different work ------------------------------------------------------

    @Test fun aTitleThatMerelySoundsCloseIsADifferentWork() {
        assertEquals(SubtitleTitleAgreement.CONFLICTS, agreement("The Wailing", title = "The Walking Dead"))
        assertEquals(SubtitleTitleAgreement.CONFLICTS, agreement("The Chaser", title = "Chasers"))
        assertEquals(SubtitleTitleAgreement.CONFLICTS, agreement("The Chaser", title = "Storm Riders"))
    }

    @Test fun oneTitleInsideAnotherIsRelatedAndNotTheSame() {
        // A sequel is a real answer to a vague filename, but never a better one than the
        // film that was actually named.
        assertEquals(SubtitleTitleAgreement.RELATED, agreement("Blade Runner", title = "Blade Runner 2049"))
        assertEquals(SubtitleTitleAgreement.RELATED, agreement("Blade Runner 2049", title = "Blade Runner"))
        // A filename that kept the original title beside the English one still resolves.
        assertEquals(SubtitleTitleAgreement.RELATED, agreement("Goksung The Wailing", title = "The Wailing"))
        assertTrue(SubtitleTitleAgreement.RELATED > SubtitleTitleAgreement.CONFLICTS)
        assertTrue(SubtitleTitleAgreement.AGREES > SubtitleTitleAgreement.RELATED)
    }

    @Test fun aResultThatNamesNoWorkIsUnknownRatherThanWrong() {
        assertEquals(SubtitleTitleAgreement.UNKNOWN, agreement("The Wailing"))
        assertEquals(SubtitleTitleAgreement.UNKNOWN, agreement(null, title = "The Wailing"))
        assertEquals(SubtitleTitleAgreement.UNKNOWN, agreement("   ", title = "The Wailing"))
        assertTrue(SubtitleTitleAgreement.UNKNOWN > SubtitleTitleAgreement.CONFLICTS)
    }

    @Test fun theBestOfTheNamesAResultCarriesDecides() {
        assertEquals(
            SubtitleTitleAgreement.AGREES,
            agreement("The Wailing", title = "Some Other Thing", name = "The Wailing"),
        )
    }

    // --- the series that shares a film's name ---------------------------------

    @Test fun aSeriesParentTitleAnswersAnEpisodeQueryAndNotAFilmQuery() {
        // `The Chaser` is a 2008 film and a 2012 series, and the series' episodes each
        // carry the series name as their parent. Reading it for a film's filename is
        // exactly how the series' far larger download counts won.
        assertEquals(
            SubtitleTitleAgreement.CONFLICTS,
            agreement("The Chaser", title = "Episode 3", parentTitle = "The Chaser"),
        )
        assertEquals(
            SubtitleTitleAgreement.AGREES,
            agreement(
                "The Chaser",
                title = "Episode 3",
                parentTitle = "The Chaser",
                season = 1,
                episode = 3,
            ),
        )
    }

    @Test fun aFilenameWithoutAnEpisodeMarkerWantsTheFilm() {
        val film = result(type = "movie")
        val episode = result(type = "episode")
        val show = result(type = "tvshow")
        val silent = result(type = null)

        assertEquals(SubtitleKindAgreement.AGREES, OpenSubtitlesMatchPolicy.kindAgreement(film, null, null))
        assertEquals(SubtitleKindAgreement.CONFLICTS, OpenSubtitlesMatchPolicy.kindAgreement(episode, null, null))
        assertEquals(SubtitleKindAgreement.CONFLICTS, OpenSubtitlesMatchPolicy.kindAgreement(show, null, null))
        assertEquals(SubtitleKindAgreement.UNKNOWN, OpenSubtitlesMatchPolicy.kindAgreement(silent, null, null))

        assertEquals(SubtitleKindAgreement.CONFLICTS, OpenSubtitlesMatchPolicy.kindAgreement(film, 1, 3))
        assertEquals(SubtitleKindAgreement.AGREES, OpenSubtitlesMatchPolicy.kindAgreement(episode, 1, 3))
        // An out-of-range marker is not a marker, so this is still a film's filename.
        assertEquals(SubtitleKindAgreement.AGREES, OpenSubtitlesMatchPolicy.kindAgreement(film, 0, 3))
    }

    // --- what the sort actually compares --------------------------------------

    @Test fun titleOutranksKindWhichOutranksCatalogMetadata() {
        val wrongWork = OpenSubtitlesMatchPolicy.relevance(
            result(title = "The Walking Dead", type = "movie", year = 2016),
            title = "The Wailing", year = 2016, season = null, episode = null,
        )
        val rightWorkWrongKind = OpenSubtitlesMatchPolicy.relevance(
            result(title = "The Wailing", type = "episode", year = 2016),
            title = "The Wailing", year = 2016, season = null, episode = null,
        )
        val rightWorkWrongYear = OpenSubtitlesMatchPolicy.relevance(
            result(title = "The Wailing", type = "movie", year = 1999),
            title = "The Wailing", year = 2016, season = null, episode = null,
        )
        val exact = OpenSubtitlesMatchPolicy.relevance(
            result(title = "The Wailing", type = "movie", year = 2016),
            title = "The Wailing", year = 2016, season = null, episode = null,
        )

        assertTrue(exact > rightWorkWrongYear)
        assertTrue(rightWorkWrongYear > rightWorkWrongKind)
        assertTrue(rightWorkWrongKind > wrongWork)
    }

    @Test fun anUnaskedQuestionIsNeutralRatherThanAgreement() {
        val neutral = OpenSubtitlesMatchPolicy.relevance(
            result(title = "The Wailing"),
            title = "The Wailing", year = null, season = null, episode = null,
        )
        assertEquals(SubtitleKindAgreement.UNKNOWN, neutral.kind)
        assertEquals(1, neutral.metadata)
    }

    // --- what is removed, and when it is not ----------------------------------

    @Test fun aDifferentWorkIsDroppedOnlyOnceSomethingWasRecognized() {
        val right = result(id = 1L, title = "The Wailing")
        val wrong = result(id = 2L, title = "The Walking Dead")

        assertEquals(
            listOf(1L),
            OpenSubtitlesMatchPolicy.recognizable(listOf(right, wrong), "The Wailing", null, null)
                .map { it.fileId },
        )
    }

    @Test fun anUnrecognizedAnswerIsKeptWholeRatherThanEmptied() {
        // A film catalogued under a transliteration disagrees with every filename that
        // used the English title. Losing the whole search to that would be the worse bug.
        val catalog = listOf(result(id = 1L, title = "Chugyeokja"), result(id = 2L, title = "Goksung"))

        assertEquals(
            listOf(1L, 2L),
            OpenSubtitlesMatchPolicy.recognizable(catalog, "The Chaser", null, null).map { it.fileId },
        )
    }

    @Test fun onlyAPositiveIdentificationArmsTheFilter() {
        // `Rambo.2` is filed as `Rambo: First Blood Part II` and agrees with nothing, while
        // the 2008 `Rambo` merely shares its first word. Letting that shared word delete the
        // film the user is actually casting would be worse than any ordering fault.
        val numbered = listOf(
            result(id = 1L, title = "Rambo: First Blood Part II", year = 1985),
            result(id = 2L, title = "Rambo", year = 2008),
        )
        assertEquals(
            listOf(1L, 2L),
            OpenSubtitlesMatchPolicy.recognizable(numbered, "Rambo 2", null, null).map { it.fileId },
        )

        // A row that named no work at all is evidence of nothing and arms nothing either.
        val silent = listOf(result(id = 1L, title = "The Walking Dead"), result(id = 2L))
        assertEquals(
            listOf(1L, 2L),
            OpenSubtitlesMatchPolicy.recognizable(silent, "The Wailing", null, null).map { it.fileId },
        )
    }

    @Test fun aRecognizedWorkStillClearsOutTheAnswersAboutOtherOnes() {
        val answer = listOf(
            result(id = 1L, title = "The Walking Dead"),
            result(id = 2L, title = "Ocean's Eleven"),
            result(id = 3L),
            result(id = 4L, title = "Oceans Eleven Revisited"),
        )
        assertEquals(
            listOf(2L, 3L, 4L),
            OpenSubtitlesMatchPolicy.recognizable(answer, "Oceans Eleven", null, null).map { it.fileId },
        )
    }

    @Test fun aHashOnlySearchNeverDropsAnything() {
        val results = listOf(result(id = 1L, title = "Anything At All"), result(id = 2L))
        assertEquals(results, OpenSubtitlesMatchPolicy.recognizable(results, null, null, null))
    }
}
