package com.flick.sender.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilmRatingsPolicyTest {

    @Test fun readsBothScores() {
        val body = """{"Response":"True","imdbRating":"8.7",
            "Ratings":[{"Source":"Internet Movie Database","Value":"8.7/10"},
                       {"Source":"Rotten Tomatoes","Value":"83%"},
                       {"Source":"Metacritic","Value":"73/100"}]}"""
        assertEquals(FilmRatings("8.7", 83), FilmRatingsPolicy.parse(body))
    }

    @Test fun notApplicableIsNoScore() {
        val body = """{"Response":"True","imdbRating":"N/A","Ratings":[]}"""
        assertTrue(FilmRatingsPolicy.parse(body)!!.isEmpty)
    }

    @Test fun missingTomatometerLeavesImdbAlone() {
        val body = """{"Response":"True","imdbRating":"6.1","Ratings":[{"Source":"Internet Movie Database","Value":"6.1/10"}]}"""
        assertEquals(FilmRatings("6.1", null), FilmRatingsPolicy.parse(body))
    }

    @Test fun imdbFallsBackToItsRatingsEntry() {
        val body = """{"Response":"True","imdbRating":"N/A","Ratings":[{"Source":"Internet Movie Database","Value":"7.0/10"}]}"""
        assertEquals("7.0", FilmRatingsPolicy.parse(body)!!.imdb)
    }

    @Test fun notFoundIsAnAnswerAndOutOfRangeIsNoScore() {
        assertTrue(FilmRatingsPolicy.parse("""{"Response":"False","Error":"Incorrect IMDb ID."}""")!!.isEmpty)
        assertTrue(FilmRatingsPolicy.parse("""{"Response":"False","Error":"Movie not found!"}""")!!.isEmpty)
        assertTrue(FilmRatingsPolicy.parse("""{"Response":"True","imdbRating":"11.5","Ratings":[{"Source":"Rotten Tomatoes","Value":"140%"}]}""")!!.isEmpty)
    }

    @Test fun anErrorOrJunkIsNoAnswerAndIsNeverKept() {
        assertNull(FilmRatingsPolicy.parse("<html>"))
        assertNull(FilmRatingsPolicy.parse("""{"Response":"False","Error":"Request limit reached!"}"""))
        assertNull(FilmRatingsPolicy.parse("""{"Response":"False","Error":"Something went wrong."}"""))
    }

    @Test fun onlyAnImdbIdIsEverSent() {
        assertEquals("https://www.omdbapi.com/?i=tt0133093&apikey=k%26x", FilmRatingsPolicy.ratingsUrl("tt0133093", "k&x"))
        assertNull(FilmRatingsPolicy.ratingsUrl("The Matrix", "k"))
        assertNull(FilmRatingsPolicy.ratingsUrl("tt12", "k"))
    }

    @Test fun rottenIsUnderSixty() {
        assertTrue(FilmRatingsPolicy.rotten(59))
        assertFalse(FilmRatingsPolicy.rotten(60))
    }

    @Test fun cacheRoundTrips() {
        for (r in listOf(FilmRatings("8.7", 83), FilmRatings(null, 40), FilmRatings("5.0", null), FilmRatings(null, null))) {
            assertEquals(r, FilmRatingsPolicy.decode(FilmRatingsPolicy.encode(r)))
        }
        assertNull(FilmRatingsPolicy.decode("8.7"))
        assertNull(FilmRatingsPolicy.decode("x|83"))
    }
}
