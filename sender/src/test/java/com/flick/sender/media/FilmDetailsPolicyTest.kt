package com.flick.sender.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilmDetailsPolicyTest {

    private val film = FilmPosterQuery("Example Movie", 1999, show = false)
    private val show = FilmPosterQuery("Example Show", null, show = true)

    @Test fun detailsUrlAsksForTheDetailsAlone() {
        val url = FilmDetailsPolicy.detailsUrl(film, 603, "abc123")
        assertTrue(url.startsWith("https://api.themoviedb.org/3/movie/603?"))
        assertFalse("credits are not shown, so not asked for", url.contains("append_to_response"))
        assertTrue(url.contains("api_key=abc123"))
        assertTrue(FilmDetailsPolicy.detailsUrl(show, 1, "k").contains("/3/tv/1?"))
        assertFalse(FilmDetailsPolicy.detailsUrl(film, 603, "eyJhbGciOi.x.y").contains("api_key"))
    }

    @Test fun readsAFilm() {
        val body = """{"id":603,"overview":"  A hacker   learns\nthe truth. ","tagline":"Believe.",
            "genres":[{"name":"Action"}],"release_date":"1999-03-30","runtime":136,
            "credits":{"cast":[{"name":"First","order":0}]}}"""
        val details = FilmDetailsPolicy.parse(film, body)!!
        assertEquals("A hacker learns the truth.", details.overview)
        assertEquals("Believe.", details.tagline)
        assertEquals(1999, details.year)
        assertTrue(details.hasSynopsis)
    }

    @Test fun readsAShowsYearFromItsFirstAirDate() {
        val body = """{"id":1,"overview":"A show.","first_air_date":"2008-01-20","release_date":"1990-01-01"}"""
        assertEquals(2008, FilmDetailsPolicy.parse(show, body)!!.year)
    }

    @Test fun missingFieldsAreAbsentNotInvented() {
        val details = FilmDetailsPolicy.parse(film, """{"id":7,"overview":"","tagline":null,"release_date":""}""")!!
        assertNull(details.overview)
        assertNull(details.tagline)
        assertNull(details.year)
        assertFalse(details.hasSynopsis)
    }

    @Test fun anythingButADetailsObjectIsNoAnswer() {
        assertNull(FilmDetailsPolicy.parse(film, "not json"))
        assertNull(FilmDetailsPolicy.parse(film, """{"status_code":34}"""))
    }

    @Test fun cacheRoundTripsAndRejectsOtherVersions() {
        val details = FilmDetails(overview = "Text", tagline = null, year = 2001)
        assertEquals(details, FilmDetailsPolicy.decode(FilmDetailsPolicy.encode(details)))
        // The previous format, with credits, is asked for again rather than read.
        assertNull(FilmDetailsPolicy.decode("""{"v":1,"overview":"Text","cast":["B"]}"""))
        assertNull(FilmDetailsPolicy.decode("garbage"))
    }
}
