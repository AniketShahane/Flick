package com.flick.sender.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decisions the online subtitle path makes before any socket is opened: whose key a
 * request carries, whether a token may be put in a header, which download addresses may be
 * fetched at all, and what order results reach the sheet in.
 *
 * These are the mistakes that would be security mistakes, so they are the half of the path
 * that is pure.
 */
class OpenSubtitlesWireTest {

    private fun subtitle(
        id: Long,
        hashMatch: Boolean = false,
        trusted: Boolean = false,
        aiTranslated: Boolean = false,
        rating: Double = 0.0,
        votes: Int = 0,
        downloads: Int = id.toInt(),
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
    ) = OnlineSubtitle(
        fileId = id,
        fileName = "file-$id.srt",
        language = "en",
        release = "release-$id",
        downloads = downloads,
        hashMatch = hashMatch,
        trusted = trusted,
        aiTranslated = aiTranslated,
        rating = rating,
        votes = votes,
        featureYear = year,
        season = season,
        episode = episode,
    )

    // --- which key a request carries ----------------------------------------

    @Test fun aKeyTheUserPastedBeatsTheKeyTheBuildShipped() {
        val resolved = OpenSubtitlesWire.resolveKey("mine", "shipped")
        assertEquals(ResolvedApiKey("mine", ApiKeySource.USER), resolved)
    }

    @Test fun theBuildsKeyIsUsedWhenTheUserPastedNone() {
        assertEquals(
            ResolvedApiKey("shipped", ApiKeySource.BUNDLED),
            OpenSubtitlesWire.resolveKey(null, "shipped"),
        )
        assertEquals(
            ResolvedApiKey("shipped", ApiKeySource.BUNDLED),
            OpenSubtitlesWire.resolveKey("   ", "shipped"),
        )
    }

    @Test fun aBlankBuildKeyIsTheDefaultStateAndResolvesToNoKey() {
        // This is what a clone of this public repository compiles with, so it is a state
        // the whole feature has to survive rather than an error anything may report.
        assertNull(OpenSubtitlesWire.resolveKey(null, ""))
        assertNull(OpenSubtitlesWire.resolveKey(null, "   "))
        assertNull(OpenSubtitlesWire.resolveKey("", ""))
    }

    @Test fun keysAreTrimmedBecauseAPasteCarriesWhitespace() {
        assertEquals(
            ResolvedApiKey("mine", ApiKeySource.USER),
            OpenSubtitlesWire.resolveKey("  mine\n", ""),
        )
    }

    @Test fun aKeyThatCouldSplitAHeaderIsNotAKey() {
        // A pasted value carrying CR, LF or a space would be concatenated into a request
        // header. It is refused here, which falls through to the build's own key.
        assertEquals(
            ResolvedApiKey("shipped", ApiKeySource.BUNDLED),
            OpenSubtitlesWire.resolveKey("abc\r\nX-Injected: 1", "shipped"),
        )
        assertNull(OpenSubtitlesWire.resolveKey("abc def", ""))
        assertNull(OpenSubtitlesWire.resolveKey("a".repeat(257), ""))
        assertNull(OpenSubtitlesWire.resolveKey(null, "abc\u0000def"))
    }

    // --- the session a login amounts to -------------------------------------

    @Test fun aLoginAnswerBecomesASessionWithAStatedLife() {
        val session = OpenSubtitlesWire.sessionOf("jwt-value", "viewer", nowMillis = 1_000L)
        assertEquals("jwt-value", session?.token)
        assertEquals("viewer", session?.username)
        // OpenSubtitles documents 24 hours and does not restate it in the answer.
        assertEquals(1_000L + 24L * 60L * 60L * 1_000L, session?.expiresAtMillis)
    }

    @Test fun aStatedExpiryIsUsedWhenTheAnswerCarriesOne() {
        val session = OpenSubtitlesWire.sessionOf("jwt", "viewer", nowMillis = 500L, ttlSeconds = 60L)
        assertEquals(60_500L, session?.expiresAtMillis)
        // A nonsense life falls back to the documented one rather than expiring at once.
        assertEquals(
            24L * 60L * 60L * 1_000L,
            OpenSubtitlesWire.sessionOf("jwt", "viewer", 0L, ttlSeconds = 0L)?.expiresAtMillis,
        )
        assertEquals(
            24L * 60L * 60L * 1_000L,
            OpenSubtitlesWire.sessionOf("jwt", "viewer", 0L, ttlSeconds = -5L)?.expiresAtMillis,
        )
    }

    @Test fun anExpiryThatWouldOverflowSaturatesInstead() {
        val session = OpenSubtitlesWire.sessionOf("jwt", "viewer", nowMillis = Long.MAX_VALUE - 5L)
        assertEquals(Long.MAX_VALUE, session?.expiresAtMillis)
    }

    @Test fun aTokenThatCouldSplitAHeaderIsNoSession() {
        assertNull(OpenSubtitlesWire.sessionOf(null, "viewer", 0L))
        assertNull(OpenSubtitlesWire.sessionOf("", "viewer", 0L))
        assertNull(OpenSubtitlesWire.sessionOf("   ", "viewer", 0L))
        assertNull(OpenSubtitlesWire.sessionOf("jwt value", "viewer", 0L))
        assertNull(OpenSubtitlesWire.sessionOf("jwt\r\nAuthorization: other", "viewer", 0L))
        assertNull(OpenSubtitlesWire.sessionOf("a".repeat(4_097), "viewer", 0L))
    }

    @Test fun theDisplayedNameIsNormalizedAndNeverNull() {
        assertEquals("a b", OpenSubtitlesWire.sessionOf("jwt", "  a   b ", 0L)?.username)
        assertEquals("", OpenSubtitlesWire.sessionOf("jwt", null, 0L)?.username)
        assertEquals("", OpenSubtitlesWire.sessionOf("jwt", "\u0000", 0L)?.username)
    }

    @Test fun aRestoredSessionKeepsTheExpiryThatWasStored() {
        val restored = OpenSubtitlesWire.restoredSession("jwt", "viewer", expiresAtMillis = 42L)
        assertEquals(42L, restored?.expiresAtMillis)
        // A preferences file is still a file: the token is re-checked on the way out.
        assertNull(OpenSubtitlesWire.restoredSession("jwt with space", "viewer", 42L))
        assertNull(OpenSubtitlesWire.restoredSession(null, "viewer", 42L))
    }

    @Test fun aSessionIsLiveUntilItsStatedExpiry() {
        val session = OpenSubtitlesSession("jwt", "viewer", expiresAtMillis = 1_000L)
        assertTrue(OpenSubtitlesWire.sessionIsLive(session, 999L))
        assertFalse(OpenSubtitlesWire.sessionIsLive(session, 1_000L))
        assertFalse(OpenSubtitlesWire.sessionIsLive(session, 2_000L))
    }

    // --- which download addresses may be fetched -----------------------------

    @Test fun openSubtitlesOwnHostsAreFetchable() {
        assertTrue(OpenSubtitlesWire.downloadLinkIsAllowed("https://www.opensubtitles.com/download/A/subfile/x.srt"))
        assertTrue(OpenSubtitlesWire.downloadLinkIsAllowed("https://opensubtitles.com/download/A"))
        assertTrue(OpenSubtitlesWire.downloadLinkIsAllowed("https://dl.opensubtitles.org/en/download/file/1"))
        assertTrue(OpenSubtitlesWire.downloadLinkIsAllowed("https://vip-cdn.opensubtitles.com/x"))
        assertTrue(OpenSubtitlesWire.downloadLinkIsAllowed("https://opensubtitles.com:443/x"))
        // Scheme and host are case-insensitive in the URL, so they are here too.
        assertTrue(OpenSubtitlesWire.downloadLinkIsAllowed("HTTPS://WWW.OPENSUBTITLES.COM/x"))
        assertTrue(OpenSubtitlesWire.downloadLinkIsAllowed("  https://opensubtitles.org/x  "))
    }

    @Test fun cleartextIsNeverFetched() {
        assertFalse(OpenSubtitlesWire.downloadLinkIsAllowed("http://www.opensubtitles.com/download/A"))
        assertFalse(OpenSubtitlesWire.downloadLinkIsAllowed("ftp://opensubtitles.com/x"))
        assertFalse(OpenSubtitlesWire.downloadLinkIsAllowed("//opensubtitles.com/x"))
        assertFalse(OpenSubtitlesWire.downloadLinkIsAllowed("/download/A"))
    }

    @Test fun aHostThatMerelyContainsTheNameIsNotTheHost() {
        assertFalse(OpenSubtitlesWire.downloadLinkIsAllowed("https://opensubtitles.com.evil.example/x"))
        assertFalse(OpenSubtitlesWire.downloadLinkIsAllowed("https://notopensubtitles.com/x"))
        assertFalse(OpenSubtitlesWire.downloadLinkIsAllowed("https://evil.example/opensubtitles.com/x"))
        assertFalse(OpenSubtitlesWire.downloadLinkIsAllowed("https://evil.example/?to=opensubtitles.com"))
    }

    @Test fun credentialsAndOddPortsInTheUrlAreRefused() {
        // `https://opensubtitles.com@evil.example/` reads as a host of evil.example to
        // every parser and as OpenSubtitles to every human, so user-info is refused.
        assertFalse(OpenSubtitlesWire.downloadLinkIsAllowed("https://opensubtitles.com@evil.example/x"))
        assertFalse(OpenSubtitlesWire.downloadLinkIsAllowed("https://user@opensubtitles.com/x"))
        assertFalse(OpenSubtitlesWire.downloadLinkIsAllowed("https://opensubtitles.com:8443/x"))
    }

    @Test fun nothingAndNonsenseAreRefused() {
        assertFalse(OpenSubtitlesWire.downloadLinkIsAllowed(null))
        assertFalse(OpenSubtitlesWire.downloadLinkIsAllowed(""))
        assertFalse(OpenSubtitlesWire.downloadLinkIsAllowed("   "))
        assertFalse(OpenSubtitlesWire.downloadLinkIsAllowed("not a url at all"))
        assertFalse(OpenSubtitlesWire.downloadLinkIsAllowed("https://evil.example\\@opensubtitles.com/x"))
    }

    @Test fun aRedirectMayOnlyNameAnotherAllowedAddress() {
        assertEquals(
            "https://dl.opensubtitles.org/f",
            OpenSubtitlesWire.allowedRedirect("https://dl.opensubtitles.org/f"),
        )
        assertNull(OpenSubtitlesWire.allowedRedirect("https://evil.example/f"))
        assertNull(OpenSubtitlesWire.allowedRedirect("http://dl.opensubtitles.org/f"))
        // A relative target means trusting the server about which base it resolves
        // against, so it is refused rather than resolved.
        assertNull(OpenSubtitlesWire.allowedRedirect("/somewhere/else"))
        assertNull(OpenSubtitlesWire.allowedRedirect(null))
    }

    // --- what order results reach the sheet in -------------------------------

    @Test fun hashMatchesRankFirst() {
        val ordered = OpenSubtitlesWire.ordered(
            listOf(subtitle(1), subtitle(2, hashMatch = true), subtitle(3), subtitle(4, hashMatch = true)),
        )
        assertEquals(listOf(4L, 2L, 3L, 1L), ordered.map { it.fileId })
    }

    @Test fun matchingEpisodeMetadataBeatsAnExplicitlyDifferentEpisode() {
        val ordered = OpenSubtitlesWire.ordered(
            results = listOf(
                subtitle(1, year = 2025, season = 2, episode = 4, downloads = 50_000),
                subtitle(2, year = 2025, season = 2, episode = 3, downloads = 10),
                subtitle(3),
            ),
            year = 2025,
            season = 2,
            episode = 3,
        )

        assertEquals(listOf(2L, 3L, 1L), ordered.map { it.fileId })
    }

    @Test fun exactFileMatchesUseQualitySignalsRatherThanFallibleFeatureMetadata() {
        val ordered = OpenSubtitlesWire.ordered(
            results = listOf(
                subtitle(
                    1,
                    hashMatch = true,
                    rating = 9.0,
                    votes = 10,
                    season = 9,
                    episode = 9,
                ),
                subtitle(
                    2,
                    hashMatch = true,
                    rating = 8.0,
                    votes = 10,
                    season = 2,
                    episode = 3,
                ),
            ),
            season = 2,
            episode = 3,
        )

        assertEquals(listOf(1L, 2L), ordered.map { it.fileId })
    }

    @Test fun ratingsDownloadsAndProvenanceBreakOtherwiseEqualTies() {
        val ordered = OpenSubtitlesWire.ordered(
            listOf(
                subtitle(1, downloads = 50_000),
                subtitle(2, trusted = true, aiTranslated = true, rating = 9.5, votes = 20, downloads = 1),
                subtitle(3, trusted = true, rating = 8.0, votes = 20, downloads = 100),
                subtitle(4, trusted = true, rating = 8.0, votes = 20, downloads = 1_000),
            ),
        )

        assertEquals(listOf(2L, 4L, 3L, 1L), ordered.map { it.fileId })
    }

    @Test fun theHashSearchIsMergedAheadOfTheTextSearch() {
        val merged = OpenSubtitlesWire.merged(
            hashResults = listOf(subtitle(10), subtitle(11, hashMatch = true)),
            textResults = listOf(subtitle(20), subtitle(21)),
            limit = 30,
        )
        assertEquals(listOf(11L, 10L, 21L, 20L), merged.map { it.fileId })
    }

    @Test fun aFileBothSearchesFoundKeepsTheCopyThatKnowsItMatches() {
        val merged = OpenSubtitlesWire.merged(
            hashResults = listOf(subtitle(7, hashMatch = true)),
            textResults = listOf(subtitle(7), subtitle(8)),
            limit = 30,
        )
        assertEquals(listOf(7L, 8L), merged.map { it.fileId })
        assertTrue(merged.first().hashMatch)
    }

    @Test fun theMergedListStopsAtTheLimit() {
        val merged = OpenSubtitlesWire.merged(
            hashResults = listOf(subtitle(1, hashMatch = true)),
            textResults = listOf(subtitle(2), subtitle(3), subtitle(4)),
            limit = 2,
        )
        assertEquals(listOf(1L, 4L), merged.map { it.fileId })
    }

    @Test fun aTextOnlySearchUsesTheSameDeterministicQualityOrder() {
        val text = listOf(subtitle(1), subtitle(2), subtitle(3))
        assertEquals(listOf(3L, 2L, 1L), OpenSubtitlesWire.merged(emptyList(), text, 30).map { it.fileId })
    }

    // --- what is left of the day's allowance ---------------------------------

    @Test fun theRemainingCountIsCarriedThrough() {
        assertEquals(SubtitleQuota(5, "20 hours"), OpenSubtitlesWire.quotaOf(5, "20 hours"))
        assertEquals(SubtitleQuota(0, null), OpenSubtitlesWire.quotaOf(0, null))
        assertEquals(SubtitleQuota(0, null), OpenSubtitlesWire.quotaOf(0, "  "))
    }

    @Test fun anAnswerThatStatesNoQuotaProducesNone() {
        // -1 is what the parser reports for an absent field; it must not read as a count.
        assertNull(OpenSubtitlesWire.quotaOf(-1, "20 hours"))
    }

    @Test fun theResetTextIsServerProseAndIsTreatedAsSuch() {
        // It goes on screen, so it is collapsed to one line and cut short first.
        assertEquals("in 5 hours", OpenSubtitlesWire.quotaOf(3, "in\r\n5    hours")?.resetsIn)
        assertEquals(48, OpenSubtitlesWire.quotaOf(3, "z".repeat(200))?.resetsIn?.length)
    }

    // --- the one spelling of a query the server answers ----------------------
    //
    // Every expectation below was read off the live API rather than off its documentation:
    // the un-canonical form of each answers 301 with a Location naming the canonical one,
    // and this client follows no redirect, so a 301 is a search that returned nothing.

    @Test fun parametersAreSortedByName() {
        // The episode search sends query, season_number, episode_number in reading order,
        // which is exactly the order the server rejects.
        assertEquals(
            listOf("episode_number" to "2", "query" to "house", "season_number" to "1"),
            OpenSubtitlesWire.canonicalQuery(
                listOf("query" to "house", "season_number" to 1, "episode_number" to 2),
            ),
        )
    }

    @Test fun structuredFilenameFieldsReachTheTextSearchInCanonicalOrder() {
        val parameters = OpenSubtitlesWire.textSearchParameters(
            query = "Example Show",
            year = 2025,
            season = 1,
            episode = 3,
            language = OpenSubtitlesLanguage.ENGLISH,
        )
        assertEquals(
            listOf(
                "episode_number" to "3",
                "languages" to "en",
                "query" to "example show",
                "season_number" to "1",
                "type" to "episode",
            ),
            OpenSubtitlesWire.canonicalQuery(parameters),
        )
    }

    @Test fun invalidParsedYearIsOmittedWithoutChangingEpisodeParameters() {
        assertEquals(
            listOf(
                "query" to "Example Show",
                "languages" to "en",
                "season_number" to 2,
                "episode_number" to 5,
                "type" to "episode",
            ),
            OpenSubtitlesWire.textSearchParameters(
                query = "Example Show",
                year = 2200,
                season = 2,
                episode = 5,
                language = OpenSubtitlesLanguage.ENGLISH,
            ),
        )
    }

    @Test fun valuesAreLowerCased() {
        // A term derived from a filename is almost never already lower-case.
        assertEquals(
            listOf("query" to "blade runner 2049"),
            OpenSubtitlesWire.canonicalQuery(listOf("query" to "Blade Runner 2049")),
        )
    }

    @Test fun theSpaceIsLeftForTheCallerToEncodeAndNothingElseIsTouched() {
        // Normalising is not escaping: the client appends these already percent-encoded,
        // and the server takes %27, %3A, %26, %C3%A9 and a literal * unchanged.
        assertEquals(
            listOf("query" to "ocean's eleven: amélie & co *"),
            OpenSubtitlesWire.canonicalQuery(listOf("query" to "Ocean's Eleven: Amélie & Co *")),
        )
    }

    @Test fun aHashItselfIsCanonicalAndSurvivesUnchanged() {
        val hash = listOf("moviehash" to "8e245d9679d31e12")
        assertEquals(listOf("moviehash" to "8e245d9679d31e12"), OpenSubtitlesWire.canonicalQuery(hash))
    }

    @Test fun anIntegerParameterSurvivesTheLowerCasing() {
        // season_number and episode_number arrive as Int, and toString must not widen them.
        assertEquals(
            listOf("episode_number" to "12", "season_number" to "3"),
            OpenSubtitlesWire.canonicalQuery(listOf("season_number" to 3, "episode_number" to 12)),
        )
    }
}
