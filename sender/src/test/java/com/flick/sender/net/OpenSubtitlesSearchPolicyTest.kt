package com.flick.sender.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSubtitlesSearchPolicyTest {
    private fun subtitle(hashMatch: Boolean) = OnlineSubtitle(
        fileId = 1L,
        fileName = "subtitle.srt",
        language = "en",
        release = "release",
        downloads = 1,
        hashMatch = hashMatch,
    )

    @Test fun officialLanguageCatalogIsUniqueAndDefaultsToEnglish() {
        val codes = OpenSubtitlesLanguage.entries.map(OpenSubtitlesLanguage::code)
        val officialCodes = (
            "ab af sq am ar an hy as at az-az eu be bn bs br bg my ca ze zh-ca zh-cn zh-tw " +
                "hr cs da pr nl en eo et ex fi fr gd gl ka de el he hi hu is ig id ia ga it " +
                "ja kn kk km ko ku lv lt lb mk ms ml ma mr mn me nv ne se no oc or fa pl " +
                "pt-pt pt-br pm ps ro ru sx sr sd si sk sl so az-zb es sp ea sw sv sy tl ta " +
                "tt te tm-td th tp tr tk uk ur uz vi cy"
            ).split(' ').toSet()
        assertEquals(105, codes.size)
        assertEquals(codes.size, codes.toSet().size)
        assertEquals(officialCodes, codes.toSet())
        assertEquals("en", OpenSubtitlesSearchPolicy.DefaultLanguage.code)
        assertTrue("pt-pt" in codes)
        assertTrue("pt-br" in codes)
        assertTrue("zh-cn" in codes)
        assertTrue("zh-tw" in codes)
        assertFalse("pt" in codes)
        assertFalse("pb" in codes)
        assertFalse("zh" in codes)
    }

    @Test fun languageParametersAreCanonicalizedWithoutBroadening() {
        assertEquals(
            "en,es,pt-br",
            OpenSubtitlesSearchPolicy.languageParameter(
                listOf(
                    OpenSubtitlesLanguage.PORTUGUESE_BRAZIL,
                    OpenSubtitlesLanguage.ENGLISH,
                    OpenSubtitlesLanguage.SPANISH,
                    OpenSubtitlesLanguage.ENGLISH,
                ),
            ),
        )
    }

    @Test fun movieSearchCarriesLanguageAndPositiveBoundedYear() {
        assertEquals(
            listOf("languages" to "fr", "query" to "amélie & co.", "year" to "2001"),
            OpenSubtitlesWire.canonicalQuery(
                OpenSubtitlesWire.textSearchParameters(
                    query = "  Amélie & Co.  ",
                    year = 2001,
                    season = null,
                    episode = null,
                    language = OpenSubtitlesLanguage.FRENCH,
                ),
            ),
        )
    }

    @Test fun episodeSearchCarriesOnlyValidStructuredFieldsAndLanguage() {
        assertEquals(
            listOf(
                "episode_number" to "12",
                "languages" to "en",
                "query" to "example show",
                "season_number" to "3",
                "year" to "2025",
            ),
            OpenSubtitlesWire.canonicalQuery(
                OpenSubtitlesWire.textSearchParameters(
                    query = "Example Show",
                    year = 2025,
                    season = 3,
                    episode = 12,
                    language = OpenSubtitlesLanguage.ENGLISH,
                ),
            ),
        )
    }

    @Test fun invalidNumericFieldsAreNeverSent() {
        assertEquals(
            listOf("languages" to "en", "query" to "movie"),
            OpenSubtitlesWire.canonicalQuery(
                OpenSubtitlesWire.textSearchParameters(
                    query = "Movie",
                    year = 0,
                    season = -1,
                    episode = 1000,
                    language = OpenSubtitlesLanguage.ENGLISH,
                ),
            ),
        )
    }

    @Test fun shortOrBlankTextNeverProducesATextRequest() {
        assertTrue(OpenSubtitlesWire.textSearchParameters("ab", null, null, null, OpenSubtitlesLanguage.ENGLISH).isEmpty())
        assertTrue(OpenSubtitlesWire.textSearchParameters(" \n ", null, null, null, OpenSubtitlesLanguage.ENGLISH).isEmpty())
        assertEquals(OpenSubtitlesTextState.TOO_SHORT, OpenSubtitlesSearchPolicy.textQuery("é").state)
        assertEquals(OpenSubtitlesTextState.READY, OpenSubtitlesSearchPolicy.textQuery("東京物語").state)
    }

    @Test fun queryPreservesPunctuationDiacriticsAndMeaningfulScriptJoiners() {
        val joinedIndic = "क्\u200Dषितिज"
        assertEquals(joinedIndic, OpenSubtitlesSearchPolicy.textQuery(joinedIndic).value)
        assertEquals(
            "Léon: The Professional — O'Connor",
            OpenSubtitlesSearchPolicy.textQuery("  Léon:  The Professional — O'Connor  ").value,
        )
        assertEquals("Safe title", OpenSubtitlesSearchPolicy.textQuery("Safe\u202E title").value)
    }

    @Test fun hashRequestAlwaysCarriesLanguageAndAddsPositiveMovieByteSize() {
        val hash = "8e245d9679d31e12"
        assertEquals(
            listOf("languages" to "en", "moviebytesize" to "987654321", "moviehash" to hash),
            OpenSubtitlesWire.canonicalQuery(
                OpenSubtitlesWire.hashSearchParameters(hash, 987_654_321L, OpenSubtitlesLanguage.ENGLISH),
            ),
        )
        assertEquals(
            listOf("languages" to "en", "moviehash" to hash),
            OpenSubtitlesWire.canonicalQuery(
                OpenSubtitlesWire.hashSearchParameters(hash, -1L, OpenSubtitlesLanguage.ENGLISH),
            ),
        )
        assertTrue(OpenSubtitlesWire.hashSearchParameters("not-a-hash", 100L, OpenSubtitlesLanguage.ENGLISH).isEmpty())
    }

    @Test fun exactHashStopsFallbackButHeuristicHashResultsDoNot() {
        val query = OpenSubtitlesSearchPolicy.textQuery("Example")
        assertFalse(OpenSubtitlesSearchPolicy.shouldRunTextFallback(listOf(subtitle(true)), query))
        assertTrue(OpenSubtitlesSearchPolicy.shouldRunTextFallback(listOf(subtitle(false)), query))
        assertTrue(OpenSubtitlesSearchPolicy.shouldRunTextFallback(emptyList(), query))
        assertFalse(
            OpenSubtitlesSearchPolicy.shouldRunTextFallback(
                listOf(subtitle(false)),
                OpenSubtitlesSearchPolicy.textQuery("ab"),
            ),
        )
    }
}
