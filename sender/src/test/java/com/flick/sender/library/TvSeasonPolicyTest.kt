package com.flick.sender.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvSeasonPolicyTest {

    @Test
    fun onlyATvShowKeyNamesATmdbShow() {
        assertEquals(91234L, TvSeasonPolicy.tmdbId("tv:91234"))
        assertNull(TvSeasonPolicy.tmdbId("name:thelanterncoast"))
        assertNull(TvSeasonPolicy.tmdbId("tv:0"))
    }

    @Test
    fun aV3KeyTravelsInTheQueryAndAReadTokenDoesNot() {
        val v3 = TvSeasonPolicy.url(91234L, 2, "abc123")
        assertEquals("https://api.themoviedb.org/3/tv/91234/season/2?language=en-US&api_key=abc123", v3)
        assertFalse(TvSeasonPolicy.url(91234L, 2, "eyJtoken").contains("api_key"))
    }

    @Test
    fun aSeasonBodyReadsAsItsEpisodes() {
        val body = """{"id":1,"episodes":[
            {"episode_number":4,"name":"The Quiet Tide","overview":"  A storm. ","still_path":"/d.jpg"},
            {"episode_number":5,"name":null,"overview":"","still_path":null},
            {"name":"no number"}
        ]}"""
        val episodes = TvSeasonPolicy.parse(body)!!
        assertEquals(
            listOf(
                LibraryFrames.EpisodeInfo(4, "The Quiet Tide", "A storm.", "/d.jpg"),
                LibraryFrames.EpisodeInfo(5, null, null, null),
            ),
            episodes,
        )
        assertNull(TvSeasonPolicy.parse("not json"))
        assertNull(TvSeasonPolicy.parse("""{"id":1}"""))
        assertTrue(TvSeasonPolicy.parse("""{"episodes":[]}""")!!.isEmpty())
    }

    @Test
    fun theCacheRoundTrips() {
        val episodes = listOf(
            LibraryFrames.EpisodeInfo(1, "Pilot", "It begins.", "/a.jpg"),
            LibraryFrames.EpisodeInfo(2, null, null, null),
        )
        assertEquals(episodes, TvSeasonPolicy.decode(TvSeasonPolicy.encode(episodes)))
        assertNull(TvSeasonPolicy.decode("{"))
    }
}
