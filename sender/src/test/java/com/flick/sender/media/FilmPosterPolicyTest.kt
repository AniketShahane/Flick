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
        assertEquals(FilmArt("/p.jpg", "/plain.jpg", "/logo.png"), FilmPosterPolicy.pickArt(match, images))
    }

    @Test fun withoutALogoTheTextlessBackdropStandsAlone() {
        val match = FilmMatch(1, "/p.jpg", "/keyart.jpg")
        val images = """{"backdrops":[{"file_path":"/plain.jpg","iso_639_1":null,"aspect_ratio":1.778}],"logos":[]}"""
        assertEquals(FilmArt("/p.jpg", "/plain.jpg", null), FilmPosterPolicy.pickArt(match, images))
        assertEquals(FilmArt("/p.jpg", "/keyart.jpg", null), FilmPosterPolicy.pickArt(match, null))
    }
}
