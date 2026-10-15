package com.flick.sender.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilmDetailsPolicyTest {

    private val film = FilmPosterQuery("Example Movie", 1999, show = false)
    private val show = FilmPosterQuery("Example Show", null, show = true)

    @Test fun detailsUrlAsksForCreditsAndIdsInOneCall() {
        val url = FilmDetailsPolicy.detailsUrl(film, 603, "abc123")
        assertTrue(url.startsWith("https://api.themoviedb.org/3/movie/603?"))
        assertTrue(url.contains("append_to_response=external_ids,credits"))
        assertTrue(url.contains("api_key=abc123"))
        assertTrue(FilmDetailsPolicy.detailsUrl(show, 1, "k").contains("/3/tv/1?"))
        assertFalse(FilmDetailsPolicy.detailsUrl(film, 603, "eyJhbGciOi.x.y").contains("api_key"))
    }

    @Test fun readsAFilm() {
        val body = """{"id":603,"overview":"  A hacker   learns\nthe truth. ","tagline":"Believe.",
            "genres":[{"name":"Action"},{"name":"Science Fiction"},{"name":"Drama"},{"name":"Fourth"}],
            "release_date":"1999-03-30","runtime":136,"imdb_id":"tt0133093",
            "credits":{
              "cast":[{"name":"Second","order":1},{"name":"First","order":0},{"name":"Third","order":2},
                      {"name":"Fourth","order":3},{"name":"Fifth","order":4}],
              "crew":[{"name":"Composer","job":"Original Music Composer"},
                      {"name":"Director One","job":"Director"},{"name":"Director Two","job":"Director"}]}}"""
        val details = FilmDetailsPolicy.parse(film, body)!!
        assertEquals("A hacker learns the truth.", details.overview)
        assertEquals("Believe.", details.tagline)
        assertEquals(listOf("Action", "Science Fiction", "Drama"), details.genres)
        assertEquals(1999, details.year)
        assertEquals(136, details.runtimeMinutes)
        assertEquals(listOf("Director One", "Director Two"), details.creators)
        assertEquals(listOf("First", "Second", "Third", "Fourth"), details.cast)
        assertEquals("tt0133093", details.imdbId)
        assertTrue(details.hasSynopsis)
        assertFalse(details.show)
    }

    @Test fun readsAShowFromItsCreatorsAndExternalIds() {
        val body = """{"id":1,"overview":"A show.","first_air_date":"2008-01-20",
            "episode_run_time":[],"last_episode_to_air":{"runtime":47},
            "created_by":[{"name":"Creator"}],
            "external_ids":{"imdb_id":"tt0903747"},
            "credits":{"cast":[{"name":"Lead","order":0}],"crew":[{"name":"Not Him","job":"Director"}]}}"""
        val details = FilmDetailsPolicy.parse(show, body)!!
        assertEquals(2008, details.year)
        assertEquals(47, details.runtimeMinutes)
        assertEquals(listOf("Creator"), details.creators)
        assertEquals("tt0903747", details.imdbId)
        assertTrue(details.show)
    }

    @Test fun missingFieldsAreAbsentNotInvented() {
        val details = FilmDetailsPolicy.parse(film, """{"id":7,"overview":"","tagline":null,"runtime":0,"imdb_id":"","release_date":""}""")!!
        assertNull(details.overview)
        assertNull(details.tagline)
        assertNull(details.runtimeMinutes)
        assertNull(details.year)
        assertNull(details.imdbId)
        assertTrue(details.genres.isEmpty() && details.creators.isEmpty() && details.cast.isEmpty())
        assertFalse(details.hasSynopsis)
    }

    @Test fun anImdbIdThatIsNotOneIsDropped() {
        val details = FilmDetailsPolicy.parse(film, """{"id":7,"imdb_id":"../../etc"}""")!!
        assertNull(details.imdbId)
    }

    @Test fun anythingButADetailsObjectIsNoAnswer() {
        assertNull(FilmDetailsPolicy.parse(film, "not json"))
        assertNull(FilmDetailsPolicy.parse(film, """{"status_code":34}"""))
    }

    @Test fun cacheRoundTripsAndRejectsOtherVersions() {
        val details = FilmDetails(
            overview = "Text", tagline = null, genres = listOf("Drama"), year = 2001,
            runtimeMinutes = 90, creators = listOf("A"), cast = listOf("B", "C"),
            imdbId = "tt1234567", show = false,
        )
        assertEquals(details, FilmDetailsPolicy.decode(FilmDetailsPolicy.encode(details)))
        assertNull(FilmDetailsPolicy.decode("""{"v":0,"overview":"Text"}"""))
        assertNull(FilmDetailsPolicy.decode("garbage"))
    }
}
