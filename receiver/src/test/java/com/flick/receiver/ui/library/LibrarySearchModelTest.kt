package com.flick.receiver.ui.library

import com.flick.receiver.library.LibraryItem
import com.flick.receiver.library.LibraryItemKind
import com.flick.receiver.library.LibraryShelving
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySearchModelTest {

    private val e1 = ep("e1", "thelanterncoasts01e01mkv", 1, 1)
    private val e2 = ep("e2", "thelanterncoasts01e02thequiettidemkv", 1, 2)
    private val festival = video("fest", "lanternfestival2024mp4")
    private val trip = video("trip", "atlantistripmp4")
    private val items = listOf(festival, e1, trip, e2)
    private val shows = LibraryShelving.shows(items)

    @Test
    fun aQueryNamingTheShowCollapsesItsEpisodesIntoOneResult() {
        val hits = LibrarySearchModel.hits(items, shows, "lant")
        assertEquals(listOf("v:fest", "s:tv:1", "v:trip"), hits.map { it.key })
    }

    @Test
    fun aQueryOnlyAnEpisodeCarriesListsThatEpisode() {
        val hits = LibrarySearchModel.hits(items, shows, "quiet")
        assertEquals(listOf("v:e2"), hits.map { it.key })
    }

    @Test
    fun everyWordMustMatchAnywhere() {
        assertEquals(listOf("v:fest"), LibrarySearchModel.hits(items, shows, "Festival LANT").map { it.key })
        assertTrue(LibrarySearchModel.hits(items, shows, "lant zebra").isEmpty())
    }

    @Test
    fun anEmptyQueryListsNothing() {
        assertTrue(LibrarySearchModel.hits(items, shows, "").isEmpty())
        assertTrue(LibrarySearchModel.hits(items, shows, "   ").isEmpty())
    }

    @Test
    fun typingRefusesLeadingAndDoubledSpacesAndOverlongQueries() {
        assertEquals("", LibrarySearchModel.typed("", ' '))
        assertEquals("a ", LibrarySearchModel.typed("a", ' '))
        assertEquals("a ", LibrarySearchModel.typed("a ", ' '))
        assertEquals("a b", LibrarySearchModel.typed("a ", 'b'))
        val full = "x".repeat(LibrarySearchModel.MAX_QUERY)
        assertEquals(full, LibrarySearchModel.typed(full, 'y'))
        assertEquals("a", LibrarySearchModel.deleted("ab"))
        assertEquals("", LibrarySearchModel.deleted(""))
    }

    @Test
    fun theKeyboardHasEveryLetterAndDigitOnce() {
        assertEquals(36, LibrarySearchModel.KEYS.length)
        assertEquals(36, LibrarySearchModel.KEYS.toSet().size)
    }

    private fun ep(id: String, find: String, season: Int, episode: Int) = LibraryItem(
        id = id, name = "$id.mkv", title = id, sortKey = id, find = find, kind = LibraryItemKind.EPISODE,
        show = "The Lantern Coast", showKey = "tv:1", season = season, episode = episode,
    )

    private fun video(id: String, find: String) = LibraryItem(
        id = id, name = "$id.mp4", title = id, sortKey = id, find = find, kind = LibraryItemKind.VIDEO,
    )
}
