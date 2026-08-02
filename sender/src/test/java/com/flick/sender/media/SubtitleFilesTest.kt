package com.flick.sender.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleFilesTest {

    @Test fun extensionDecidesWhatIsASubtitleBecauseProviderMimeTypesDisagree() {
        assertTrue(SubtitleFiles.isSubtitleName("Arrival.srt"))
        assertTrue(SubtitleFiles.isSubtitleName("Arrival.VTT"))
        assertTrue(SubtitleFiles.isSubtitleName("Arrival.ass"))
        assertTrue(SubtitleFiles.isSubtitleName("Arrival.ssa"))
        // Rejected on purpose: MicroDVD has no Media3 parser and a VobSub .sub is a
        // bitmap stream that needs its .idx companion, so neither can ever render.
        assertFalse(SubtitleFiles.isSubtitleName("Arrival.sub"))
        assertFalse(SubtitleFiles.isSubtitleName("Arrival.txt"))
        assertFalse(SubtitleFiles.isSubtitleName("Arrival.nfo"))
        assertFalse(SubtitleFiles.isSubtitleName("Arrival"))
        assertEquals("srt", SubtitleFiles.extensionOf("/tree/primary/Films/Arrival.srt"))
    }

    @Test fun sameBaseNameIsAnExactMatchWhateverTheSeparatorsAndCase() {
        val exact = SubtitleFiles.match("Arrival.2016.2160p.DV.mkv", "Arrival.2016.2160p.DV.srt")
        assertEquals(SubtitleMatchKind.EXACT, exact?.kind)
        assertNull(exact?.language)

        val renamed = SubtitleFiles.match("my movie.mkv", "MY_MOVIE.SRT")
        assertEquals(SubtitleMatchKind.EXACT, renamed?.kind)
    }

    @Test fun aLanguageSuffixIsAPrefixMatchAndSurfacesTheTag() {
        val short = SubtitleFiles.match("Arrival.mkv", "Arrival.en.srt")
        assertEquals(SubtitleMatchKind.PREFIX, short?.kind)
        assertEquals("en", short?.language)

        val threeLetter = SubtitleFiles.match(
            "The.Matrix.1999.1080p.BluRay.mkv",
            "The.Matrix.1999.1080p.BluRay.eng.forced.srt",
        )
        assertEquals(SubtitleMatchKind.PREFIX, threeLetter?.kind)
        assertEquals("en", threeLetter?.language)

        val region = SubtitleFiles.match("Cidade.de.Deus.2002.mkv", "Cidade.de.Deus.2002.pt-BR.srt")
        assertEquals(SubtitleMatchKind.PREFIX, region?.kind)
        assertEquals("pt-BR", region?.language)

        // The subtitle carries fewer release tags than the video it belongs to.
        val shorterSidecar = SubtitleFiles.match("Bee.Movie.2007.1080p.WEB.mkv", "Bee.Movie.2007.srt")
        assertEquals(SubtitleMatchKind.PREFIX, shorterSidecar?.kind)
    }

    @Test fun aReTaggedReleaseStillMatchesOnTokenOverlap() {
        val reordered = SubtitleFiles.match("Interstellar.2014.2160p.HDR.mkv", "Interstellar.2014.fr.srt")
        assertEquals(SubtitleMatchKind.FUZZY, reordered?.kind)
        assertEquals("fr", reordered?.language)

        val spelledOut = SubtitleFiles.match("Dune.Part.Two.2024.2160p.mkv", "Dune.Part.Two.2024.English.srt")
        assertEquals(SubtitleMatchKind.FUZZY, spelledOut?.kind)
        assertEquals("en", spelledOut?.language)
    }

    @Test fun adifferentFilmNeverMatches() {
        assertNull(SubtitleFiles.match("Interstellar.2014.2160p.HDR.mkv", "The.Martian.2015.en.srt"))
    }

    @Test fun sharedReleaseTagsAloneAreNotEvidenceOfTheSameFilm() {
        // Year, resolution and source are identical; only the title differs, and that
        // is the one token that decides.
        assertNull(
            SubtitleFiles.match("Inception.2010.1080p.BluRay.mkv", "Interstellar.2010.1080p.BluRay.srt"),
        )
    }

    @Test fun aDifferentEpisodeOfTheSameShowIsNotAMatch() {
        assertNull(SubtitleFiles.match("The.Office.S01E01.1080p.mkv", "The.Office.S01E02.en.srt"))
        assertEquals(
            SubtitleMatchKind.FUZZY,
            SubtitleFiles.match("The.Office.S01E01.1080p.mkv", "The.Office.S01E01.en.srt")?.kind,
        )
    }

    @Test fun nonSubtitleNeighboursAreNeverOffered() {
        assertNull(SubtitleFiles.match("Arrival.2016.mkv", "Arrival.2016.txt"))
        assertNull(SubtitleFiles.match("Arrival.2016.mkv", "Arrival.2016.nfo"))
        assertNull(SubtitleFiles.match("Arrival.2016.mkv", "Arrival.2016.mkv"))
    }

    @Test fun theFirstSegmentIsATitleAndNeverALanguage() {
        val film = SubtitleFiles.match("It.2017.mkv", "It.2017.srt")
        assertEquals(SubtitleMatchKind.EXACT, film?.kind)
        assertNull(film?.language)
        assertNull(SubtitleFiles.languageTagOf("", "It.srt"))
        // A segment the video already carries is part of the title, not a language.
        assertNull(SubtitleFiles.languageTagOf("De.Zaak.mkv", "De.Zaak.srt"))
    }

    @Test fun rankingPutsAnExactNameAheadOfAPrefixAndAPrefixAheadOfOverlap() {
        val video = "Arrival.2016.2160p.DV.mkv"
        val exact = SubtitleFiles.match(video, "Arrival.2016.2160p.DV.srt")
        val prefix = SubtitleFiles.match(video, "Arrival.2016.2160p.DV.en.srt")
        val fuzzy = SubtitleFiles.match(video, "Arrival.2016.es.srt")
        assertNotNull(exact)
        assertNotNull(prefix)
        assertNotNull(fuzzy)
        assertEquals(
            listOf(SubtitleMatchKind.EXACT, SubtitleMatchKind.PREFIX, SubtitleMatchKind.FUZZY),
            SubtitleFiles.bestFirst(listOf(fuzzy!!, prefix!!, exact!!)).map { it.kind },
        )
    }

    @Test fun theOnlineQueryStopsAtTheFirstReleaseTagAndReadsTheEpisode() {
        assertEquals("The Matrix", SubtitleFiles.searchQuery("The.Matrix.1999.1080p.BluRay.x264.mkv"))
        assertEquals("Bee Movie", SubtitleFiles.searchQuery("Bee.Movie.2007.1080p.WEB.mkv"))
        assertEquals("The Office", SubtitleFiles.searchQuery("The.Office.S01E01.1080p.mkv"))
        assertEquals(1 to 1, SubtitleFiles.episodeOf("The.Office.S01E01.1080p.mkv"))
        assertEquals(2 to 5, SubtitleFiles.episodeOf("Show.s02e05.WEB.mkv"))
        assertNull(SubtitleFiles.episodeOf("Arrival.2016.mkv"))
        // Technical tags alone are no title; a movie hash may still search this file.
        assertTrue(SubtitleFiles.searchQuery("1080p.WEB.mkv").isEmpty())
    }
}
