package com.flick.sender.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilmPosterPolicyTest {

    @Test fun releaseNameAsksForTheFilmAndItsYear() {
        val query = FilmPosterPolicy.queryFor(VideoNames.parse("Example.Movie.2024.2160p.WEB.x265-GROUP.mkv"))!!
        assertEquals("Example Movie", query.title)
        assertEquals(2024, query.year)
        assertFalse(query.show)
    }

    @Test fun episodeAsksForTheShow() {
        val query = FilmPosterQuery("Example Show", year = null, show = true)
        assertTrue(FilmPosterPolicy.searchUrl(query, "k").contains("/search/tv?"))
    }

    @Test fun cameraRollNameIsNeverSent() {
        assertNull(FilmPosterPolicy.queryFor(VideoNames.parse("PXL_20240101_120000123.mp4")))
        assertNull(FilmPosterPolicy.queryFor(VideoNames.parse("VID_20240101_120000.mp4")))
    }

    @Test fun spellingsOfOneReleaseShareACacheEntry() {
        val dotted = FilmPosterPolicy.queryFor(VideoNames.parse("Amelie.2001.1080p.mkv"))!!
        val spaced = FilmPosterPolicy.queryFor(VideoNames.parse("Amélie (2001).mkv"))!!
        assertEquals(dotted.cacheKey, spaced.cacheKey)
    }

    @Test fun v3KeyTravelsAsAParameterAndATokenDoesNot() {
        val query = FilmPosterQuery("Spider-Man: No Way Home", 2021, show = false)
        val v3 = FilmPosterPolicy.searchUrl(query, "abc123")
        assertTrue(v3.startsWith("https://api.themoviedb.org/3/search/movie?"))
        assertTrue(v3.contains("query=Spider-Man%3A%20No%20Way%20Home"))
        assertTrue(v3.contains("year=2021"))
        assertTrue(v3.contains("api_key=abc123"))
        assertFalse(FilmPosterPolicy.searchUrl(query, "eyJhbGciOi.x.y").contains("api_key"))
    }

    @Test fun picksTheFirstExactTitleWithAPoster() {
        val body = """{"results":[
            {"title":"Example Movie Returns","id":1,"poster_path":"/wrong.jpg","vote_count":900},
            {"title":"Example Movie","id":1,"poster_path":null,"vote_count":900},
            {"title":"Example: Movie!","id":1,"poster_path":"/right.jpg","vote_count":900},
            {"title":"Example Movie","id":1,"poster_path":"/later.jpg","vote_count":900}
        ]}"""
        assertEquals("/right.jpg", FilmPosterPolicy.pickMatch(FilmPosterQuery("Example Movie", null, false), body)?.poster)
    }

    @Test fun originalTitleCountsToo() {
        val body = """{"results":[{"title":"Amelie","original_title":"Le Fabuleux Destin d'Amélie Poulain","id":1,"poster_path":"/a.jpg"}]}"""
        val query = FilmPosterQuery("Le Fabuleux Destin d Amelie Poulain", 2001, false)
        assertEquals("/a.jpg", FilmPosterPolicy.pickMatch(query, body)?.poster)
    }

    @Test fun nearMissIsNoPoster() {
        val body = """{"results":[{"title":"Beach Day Massacre","id":1,"poster_path":"/x.jpg"}]}"""
        assertNull(FilmPosterPolicy.pickMatch(FilmPosterQuery("Beach Day", null, false), body)?.poster)
    }

    @Test fun withoutAYearAnObscureNamesakeIsNoPoster() {
        val body = """{"results":[{"title":"Birthday","id":1,"poster_path":"/b.jpg","vote_count":12}]}"""
        assertNull(FilmPosterPolicy.pickMatch(FilmPosterQuery("Birthday", null, false), body)?.poster)
        assertEquals("/b.jpg", FilmPosterPolicy.pickMatch(FilmPosterQuery("Birthday", 2019, false), body)?.poster)
    }

    @Test fun showsMatchOnName() {
        val body = """{"results":[{"name":"Example Show","id":1,"poster_path":"/s.jpg","vote_count":500}]}"""
        assertEquals("/s.jpg", FilmPosterPolicy.pickMatch(FilmPosterQuery("Example Show", null, true), body)?.poster)
    }

    @Test fun imageUrlRejectsAnythingButABarePath() {
        assertEquals("https://image.tmdb.org/t/p/w342/abc.jpg", FilmPosterPolicy.imageUrl("/abc.jpg", FilmPosterPolicy.TILE_WIDTH))
        assertNull(FilmPosterPolicy.imageUrl("//evil.example/x.jpg", FilmPosterPolicy.TILE_WIDTH))
        assertNull(FilmPosterPolicy.imageUrl("/../x.jpg", FilmPosterPolicy.TILE_WIDTH))
    }

    @Test fun backdropWithoutPosterStillMatches() {
        val body = """{"results":[{"id":7,"title":"Example Movie","poster_path":null,"backdrop_path":"/b.jpg","vote_count":900}]}"""
        assertEquals(FilmMatch(7, null, "/b.jpg"), FilmPosterPolicy.pickMatch(FilmPosterQuery("Example Movie", null, false), body))
    }

    @Test fun textlessBackdropAndEnglishLogoWinTogether() {
        val match = FilmMatch(1, "/p.jpg", "/keyart.jpg")
        val images = """{
            "backdrops":[
                {"file_path":"/titled.jpg","iso_639_1":"en","aspect_ratio":1.778,"vote_average":9.0},
                {"file_path":"/plain-low.jpg","iso_639_1":null,"aspect_ratio":1.778,"vote_average":5.0},
                {"file_path":"/plain.jpg","iso_639_1":null,"aspect_ratio":1.778,"vote_average":6.0}
            ],
            "logos":[
                {"file_path":"/logo.svg","iso_639_1":"en","vote_average":9.0},
                {"file_path":"/logo-fr.png","iso_639_1":"fr","vote_average":9.0},
                {"file_path":"/logo.png","iso_639_1":"en","vote_average":5.0}
            ]
        }"""
        assertEquals(FilmArt("/p.jpg", "/plain.jpg", "/logo.png", match.id, show = false), FilmPosterPolicy.pickArt(match, images))
    }

    @Test fun withoutALogoTheTextlessBackdropStandsAlone() {
        val match = FilmMatch(1, "/p.jpg", "/keyart.jpg")
        val images = """{"backdrops":[{"file_path":"/plain.jpg","iso_639_1":null,"aspect_ratio":1.778}],"logos":[]}"""
        assertEquals(FilmArt("/p.jpg", "/plain.jpg", null, match.id, show = false), FilmPosterPolicy.pickArt(match, images))
        assertEquals(FilmArt("/p.jpg", "/keyart.jpg", null, match.id, show = false), FilmPosterPolicy.pickArt(match, null))
    }

    /** A fake TMDB: each URL fragment answers with its body; anything else is a test failure. */
    private fun tmdb(vararg answers: Pair<String, String>): (String) -> String = { url ->
        answers.firstOrNull { (fragment, _) -> fragment in url }?.second ?: error("unexpected request $url")
    }

    @Test fun filmShapedNameOfAShowFallsBackToTv() {
        val query = FilmPosterPolicy.queryFor(VideoNames.parse("Example Show (2022).mkv"))!!
        assertFalse(query.show)
        val match = FilmPosterPolicy.findMatch(
            query, "k",
            tmdb(
                "/search/movie?" to """{"results":[]}""",
                "/search/tv?" to """{"results":[{"name":"Example Show","id":9,"poster_path":"/s.jpg"}]}""",
            ),
        )
        assertEquals(FilmMatch(9, "/s.jpg", null, show = true), match)
        assertTrue(FilmPosterPolicy.imagesUrl(match!!, "k").contains("/tv/9/images"))
        assertEquals(true, FilmPosterPolicy.pickArt(match, null).show)
    }

    @Test fun anEpisodeIsNeverAskedAsAFilm() {
        val query = FilmPosterPolicy.queryFor(VideoNames.parse("Example.Show.S01E02.1080p.mkv"))!!
        assertNull(FilmPosterPolicy.findMatch(query, "k", tmdb("/search/tv?" to """{"results":[]}""")))
    }

    @Test fun anEpisodeNeedsNoVotes() {
        val body = """{"results":[{"name":"Example Show","id":1,"poster_path":"/s.jpg","vote_count":3}]}"""
        assertEquals("/s.jpg", FilmPosterPolicy.pickMatch(FilmPosterQuery("Example Show", null, true), body)?.poster)
        // The same show found by a film-shaped name, with no year to vouch for it, still needs them.
        assertNull(FilmPosterPolicy.pickMatch(FilmPosterQuery("Example Show", null, true), body, episodic = false))
    }

    @Test fun anApostropheTheReleaseNameDroppedStillMatches() {
        val body = """{"results":[{"title":"There's Something Odd","id":1,"poster_path":"/t.jpg"}]}"""
        assertEquals("/t.jpg", FilmPosterPolicy.pickMatch(FilmPosterQuery("Theres Something Odd", 2011, false), body)?.poster)
        val spaced = """{"results":[{"title":"Rock'n'Roll","id":2,"poster_path":"/r.jpg"}]}"""
        assertEquals("/r.jpg", FilmPosterPolicy.pickMatch(FilmPosterQuery("Rock n Roll", 2017, false), spaced)?.poster)
    }

    @Test fun withAYearAMissingSubtitleStillMatches() {
        val body = """{"results":[
            {"title":"Into the Sea Again","id":1,"poster_path":"/no.jpg","vote_count":900},
            {"title":"Into the Sea: The Long Story","id":2,"poster_path":"/yes.jpg","vote_count":900}
        ]}"""
        assertEquals(2L, FilmPosterPolicy.pickMatch(FilmPosterQuery("Into The Sea", 2020, false), body)?.id)
        assertNull(FilmPosterPolicy.pickMatch(FilmPosterQuery("Into The Sea", null, false), body))
    }

    @Test fun anExactTitleBeatsAnEarlierSubtitledOne() {
        val body = """{"results":[
            {"title":"Example: The Return","id":1,"poster_path":"/sub.jpg"},
            {"title":"Example","id":2,"poster_path":"/exact.jpg"}
        ]}"""
        assertEquals(2L, FilmPosterPolicy.pickMatch(FilmPosterQuery("Example", 2020, false), body)?.id)
    }

    @Test fun aTransliterationIsFoundThroughTheAliases() {
        val query = FilmPosterQuery("Raatchasen", 2018, false)
        val match = FilmPosterPolicy.findMatch(
            query, "k",
            tmdb(
                "/search/movie?" to """{"results":[{"title":"Ratsasen","original_title":"ராட்சசன்","id":41,"poster_path":"/r.jpg","vote_count":212}]}""",
                "/movie/41/alternative_titles" to """{"id":41,"titles":[{"title":"Demon"},{"title":"Raatchasen"}]}""",
                "/search/tv?" to """{"results":[]}""",
            ),
        )
        assertEquals(41L, match?.id)
    }

    @Test fun aliasesThatDisagreeAreNoPoster() {
        val query = FilmPosterQuery("Beach Day", 2020, false)
        val match = FilmPosterPolicy.findMatch(
            query, "k",
            tmdb(
                "/search/movie?" to """{"results":[{"title":"Beach Day Massacre","id":5,"poster_path":"/x.jpg","vote_count":900}]}""",
                "/movie/5/alternative_titles" to """{"id":5,"titles":[{"title":"Massacre at the Beach"}]}""",
                "/search/tv?" to """{"results":[]}""",
            ),
        )
        assertNull(match)
    }

    @Test fun showAliasesAreReadFromResults() {
        val query = FilmPosterQuery("El Ensayo", null, true)
        assertTrue(FilmPosterPolicy.aliasMatches(query, """{"id":1,"results":[{"iso_3166_1":"MX","title":"El Ensayo"}]}"""))
        assertTrue(FilmPosterPolicy.alternativeTitlesUrl(FilmMatch(1, null, null, show = true), "k").contains("/tv/1/alternative_titles"))
    }

    @Test fun aFailedRequestIsAFailureNotAMiss() {
        val query = FilmPosterQuery("Example", 2020, false)
        val thrown = runCatching {
            FilmPosterPolicy.findMatch(query, "k", tmdb("/search/movie?" to """{"results":[]}"""))
        }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
    }

    @Test fun aSpacedTrailingYearIsTriedAsAYear() {
        val query = FilmPosterPolicy.queryFor(VideoNames.parse("Example Show 2022.mkv"))!!
        assertNull(query.year)
        assertEquals(FilmPosterQuery("Example Show", 2022, false), FilmPosterPolicy.trailingYear(query))
        val match = FilmPosterPolicy.findMatch(
            query, "k",
            tmdb(
                "/search/movie?query=Example%20Show%202022&" to """{"results":[]}""",
                "/search/movie?query=Example%20Show&include_adult=false&year=2022&" to """{"results":[]}""",
                "/search/tv?query=Example%20Show&include_adult=false&first_air_date_year=2022&" to
                    """{"results":[{"name":"Example Show","id":3,"poster_path":"/s.jpg","vote_count":289}]}""",
            ),
        )
        assertEquals(3L, match?.id)
    }

    @Test fun aGuessedYearVouchesForNothing() {
        val body = """{"results":[{"title":"Wedding","id":1,"poster_path":"/w.jpg","vote_count":4}]}"""
        val guessed = FilmPosterQuery("Wedding", 2019, false)
        assertNull(FilmPosterPolicy.pickMatch(guessed, body, yearGuessed = true))
        assertEquals(1L, FilmPosterPolicy.pickMatch(guessed, body)?.id)
        assertNull(FilmPosterPolicy.trailingYear(FilmPosterQuery("Blade Runner 2049", null, false)))
        assertNull(FilmPosterPolicy.trailingYear(FilmPosterQuery("1917", null, false)))
        assertNull(FilmPosterPolicy.trailingYear(FilmPosterQuery("Halloween 2018", null, false)))
    }

    @Test fun aYearlessNameIsNotAskedAsAShow() {
        val query = FilmPosterPolicy.queryFor(VideoNames.parse("Friends.mp4"))!!
        assertNull(FilmPosterPolicy.findMatch(query, "k", tmdb("/search/movie?" to """{"results":[]}""")))
    }

    @Test fun anAmpersandTheReleaseNameDroppedStillMatches() {
        val body = """{"results":[{"title":"Fast & Furious 6","id":6,"poster_path":"/f.jpg"}]}"""
        assertEquals(6L, FilmPosterPolicy.pickMatch(FilmPosterQuery("Fast Furious 6", 2013, false), body)?.id)
        assertEquals(6L, FilmPosterPolicy.pickMatch(FilmPosterQuery("Fast and Furious 6", 2013, false), body)?.id)
    }

    @Test fun anExactShowBeatsASubtitledFilm() {
        val query = FilmPosterQuery("Example Show", 2022, false)
        val match = FilmPosterPolicy.findMatch(
            query, "k",
            tmdb(
                "/search/movie?" to """{"results":[{"title":"Example Show: Behind It","id":1,"poster_path":"/m.jpg"}]}""",
                "/search/tv?" to """{"results":[{"name":"Example Show","id":2,"poster_path":"/s.jpg"}]}""",
            ),
        )
        assertEquals(FilmMatch(2, "/s.jpg", null, show = true), match)
    }

    @Test fun anObscureAliasIsNoPosterEvenWithAYear() {
        val body = """{"results":[{"title":"Something Else","id":8,"poster_path":"/x.jpg","vote_count":3}]}"""
        assertTrue(FilmPosterPolicy.aliasCandidates(FilmPosterQuery("Birthday", 2019, false), body).isEmpty())
    }

    @Test fun aYearlessNameReadsNoAliases() {
        val query = FilmPosterQuery("Example 2018", null, false)
        val match = FilmPosterPolicy.findMatch(
            query, "k",
            tmdb("/search/movie?" to """{"results":[{"title":"Example","id":4,"poster_path":"/e.jpg","vote_count":900}]}"""),
        )
        assertNull(match)
    }

    @Test fun theFilmAndTheShowOfOneNameAreToldApartByVotes() {
        val query = FilmPosterQuery("Example", 2022, false)
        val films = """{"results":[{"title":"Example","id":1,"poster_path":"/f.jpg","release_date":"2022-03-01","vote_count":40}]}"""
        val shows = """{"results":[{"name":"Example","id":2,"poster_path":"/s.jpg","first_air_date":"2022-02-18","vote_count":2600}]}"""
        assertEquals(2L, FilmPosterPolicy.findMatch(query, "k", tmdb("/search/movie?" to films, "/search/tv?" to shows))?.id)
        val quietShow = shows.replace("2600", "12")
        assertEquals(1L, FilmPosterPolicy.findMatch(query, "k", tmdb("/search/movie?" to films, "/search/tv?" to quietShow))?.id)
    }

    @Test fun theNamesYearBeatsVotes() {
        val query = FilmPosterQuery("Example", 2022, false)
        val films = """{"results":[{"title":"Example","id":1,"poster_path":"/f.jpg","release_date":"1999-03-01","vote_count":9000}]}"""
        val shows = """{"results":[{"name":"Example","id":2,"poster_path":"/s.jpg","first_air_date":"2022-02-18","vote_count":50}]}"""
        assertEquals(2L, FilmPosterPolicy.findMatch(query, "k", tmdb("/search/movie?" to films, "/search/tv?" to shows))?.id)
        val both = """{"results":[
            {"title":"Example","id":3,"poster_path":"/old.jpg","release_date":"1999-01-01","vote_count":9000},
            {"title":"Example","id":4,"poster_path":"/new.jpg","release_date":"2022-01-01","vote_count":10}
        ]}"""
        assertEquals(4L, FilmPosterPolicy.pickMatch(query, both)?.id)
    }
}
