package com.flick.sender.net

import com.flick.sender.media.MovieHash
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSubtitlesClientSearchTest {
    private class Credentials : OpenSubtitlesCredentials {
        override fun resolved(): ResolvedApiKey = ResolvedApiKey("test-key", ApiKeySource.USER)
        override fun session(): OpenSubtitlesSession? = null
        override fun saveSession(session: OpenSubtitlesSession): Boolean = true
        override fun clearSession(): Boolean = true
    }

    private class Recorder(
        private val outcomes: ArrayDeque<SubtitleSearchOutcome>,
    ) : OpenSubtitlesSearchTransport {
        val urls = mutableListOf<String>()

        override suspend fun get(
            url: String,
            apiKey: String,
            session: OpenSubtitlesSession?,
        ): SubtitleSearchOutcome {
            assertEquals("test-key", apiKey)
            assertEquals(null, session)
            urls += url
            return outcomes.removeFirst()
        }
    }

    @Test fun exactHashHitMakesOneSizedLanguageBoundRequestAndNoTextRequest() = runBlocking {
        val recorder = Recorder(ArrayDeque(listOf(found(hashMatch = true))))
        val client = OpenSubtitlesClient(Credentials(), recorder)

        val outcome = client.search(
            query = "Example Movie",
            year = 2024,
            season = null,
            episode = null,
            movieFingerprint = MovieHash.Fingerprint(HASH, 8_765_432_100L),
            language = OpenSubtitlesLanguage.PORTUGUESE_BRAZIL,
        )

        assertTrue(outcome is SubtitleSearchOutcome.Found)
        assertEquals(
            listOf(
                "$SUBTITLES?languages=pt-br&moviebytesize=8765432100&moviehash=$HASH" +
                    "&moviehash_match=only",
            ),
            recorder.urls,
        )
    }

    @Test fun heuristicHashHitFallsBackToCanonicalStructuredTextInTheSameLanguage() = runBlocking {
        val recorder = Recorder(
            ArrayDeque(
                listOf(
                    found(hashMatch = false),
                    SubtitleSearchOutcome.Found(emptyList()),
                ),
            ),
        )
        val client = OpenSubtitlesClient(Credentials(), recorder)

        val outcome = client.search(
            query = "  Example   Show  ",
            year = 2025,
            season = 3,
            episode = 12,
            movieFingerprint = MovieHash.Fingerprint(HASH, 456_789L),
            language = OpenSubtitlesLanguage.CHINESE_TRADITIONAL,
        )

        assertTrue(outcome is SubtitleSearchOutcome.Found)
        assertEquals(
            listOf(
                "$SUBTITLES?languages=zh-tw&moviebytesize=456789&moviehash=$HASH" +
                    "&moviehash_match=only",
                "$SUBTITLES?episode_number=12&languages=zh-tw&query=example+show" +
                    "&season_number=3&type=episode",
            ),
            recorder.urls,
        )
    }

    @Test fun shortQueryWithoutAnAtomicFingerprintMakesNoRequest() = runBlocking {
        val recorder = Recorder(ArrayDeque<SubtitleSearchOutcome>())
        val client = OpenSubtitlesClient(Credentials(), recorder)

        val outcome = client.search(
            query = "ab",
            year = null,
            season = null,
            episode = null,
            language = OpenSubtitlesLanguage.ENGLISH,
        )

        assertTrue(outcome is SubtitleSearchOutcome.Found)
        assertEquals(
            emptyList<String>(),
            recorder.urls,
        )
    }

    private fun found(hashMatch: Boolean): SubtitleSearchOutcome.Found =
        SubtitleSearchOutcome.Found(
            listOf(
                OnlineSubtitle(
                    fileId = 7L,
                    fileName = "subtitle.srt",
                    language = "en",
                    release = "release",
                    downloads = 10,
                    hashMatch = hashMatch,
                ),
            ),
        )

    private companion object {
        const val HASH = "8e245d9679d31e12"
        const val SUBTITLES = "https://api.opensubtitles.com/api/v1/subtitles"
    }
}
