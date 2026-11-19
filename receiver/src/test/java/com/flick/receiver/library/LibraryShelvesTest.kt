package com.flick.receiver.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryShelvesTest {

    @Test
    fun episodesGroupByShowKeyAndOrderBySeasonThenEpisode() {
        val items = listOf(ep("a3", "tv:1", 1, 3), ep("b1", "tv:2", 1, 1, show = "Alpha"), ep("a1", "tv:1", 1, 1), ep("a21", "tv:1", 2, 1), film("f1"))
        val shows = LibraryShelving.shows(items)
        assertEquals(listOf("Alpha", "Lantern"), shows.map { it.title })
        val lantern = shows[1]
        assertEquals(listOf(1, 2), lantern.seasons)
        assertEquals(listOf("a1", "a3", "a21"), lantern.all.map { it.id })
        assertEquals(3, lantern.episodeCount)
    }

    @Test
    fun nextEpisodeIsTheOneAfterTheLatestWatched() {
        val show = LibraryShelving.shows(listOf(ep("e1", "tv:1", 1, 1, watched = true), ep("e2", "tv:1", 1, 2), ep("e3", "tv:1", 1, 3, watched = true), ep("e4", "tv:1", 1, 4))).single()
        assertEquals("e4", LibraryShelving.nextEpisode(show)?.id)
    }

    @Test
    fun noNextEpisodeBeforeAnythingIsWatchedOrAfterTheFinale() {
        assertNull(LibraryShelving.nextEpisode(LibraryShelving.shows(listOf(ep("e1", "tv:1", 1, 1), ep("e2", "tv:1", 1, 2))).single()))
        assertNull(LibraryShelving.nextEpisode(LibraryShelving.shows(listOf(ep("e1", "tv:1", 1, 1), ep("e2", "tv:1", 1, 2, watched = true))).single()))
    }

    @Test
    fun anInProgressNextEpisodeIsLeftToContinueWatching() {
        val show = LibraryShelving.shows(listOf(ep("e1", "tv:1", 1, 1, watched = true), ep("e2", "tv:1", 1, 2, resumeMs = 5_000L), ep("e3", "tv:1", 1, 3))).single()
        // Continue watching holds e2; Next episodes does not reach past it to e3.
        assertNull(LibraryShelving.nextEpisode(show))
        assertEquals("e2", LibraryShelving.showPick(show)?.id)
    }

    @Test
    fun nextEpisodeSkipsASecondFileOfTheWatchedEpisode() {
        val show = LibraryShelving.shows(listOf(ep("e1", "tv:1", 1, 1, watched = true), ep("e1b", "tv:1", 1, 1), ep("e2", "tv:1", 1, 2))).single()
        assertEquals("e2", LibraryShelving.nextEpisode(show)?.id)
    }

    @Test
    fun nextEpisodeIsNullWhenOnlyASecondFileOfTheWatchedEpisodeFollows() {
        val finished = LibraryShelving.shows(listOf(ep("e1", "tv:1", 1, 1), ep("e2", "tv:1", 1, 2, watched = true), ep("e2b", "tv:1", 1, 2))).single()
        assertNull(LibraryShelving.nextEpisode(finished))
    }

    @Test
    fun followingSkipsASecondFileOfTheSameEpisode() {
        val e1 = ep("e1", "tv:1", 1, 1)
        val catalog = LibraryCatalog("AAAAAAAAAAAAAAAAAAAAAA", listOf(e1, ep("e1b", "tv:1", 1, 1), ep("e2", "tv:1", 1, 2), ep("x1", "tv:9", 1, 2)))
        assertEquals("e2", LibraryShelving.following(e1, catalog)?.id)
        assertNull(LibraryShelving.following(catalog["e2"]!!, catalog))
        assertNull(LibraryShelving.following(film("f1"), catalog))
    }

    @Test
    fun followingCrossesIntoTheNextSeason() {
        val last = ep("s1e9", "tv:1", 1, 9)
        val catalog = LibraryCatalog("AAAAAAAAAAAAAAAAAAAAAA", listOf(ep("s2e1", "tv:1", 2, 1), last))
        assertEquals("s2e1", LibraryShelving.following(last, catalog)?.id)
    }

    @Test
    fun shelvesFillTheHomeRows() {
        val catalog = LibraryCatalog(
            "AAAAAAAAAAAAAAAAAAAAAA",
            listOf(
                ep("e1", "tv:1", 1, 1, watched = true, added = 10),
                ep("e2", "tv:1", 1, 2, added = 9),
                film("f1", resumeMs = 1_000L, added = 8),
                film("f2", added = 7, art = LibraryArt(1L, false, "/p.jpg", null, null)),
                video("v1", added = 6),
            ),
        )
        val shelves = LibraryShelving.shelves(catalog)
        assertEquals(listOf("f1"), shelves.continueWatching.map { it.id })
        assertEquals(listOf("e2"), shelves.nextEpisodes.map { it.id })
        assertEquals(listOf("e1", "e2", "f1", "f2", "v1"), shelves.recentlyAdded.map { it.id })
        assertEquals(listOf("f1", "f2"), shelves.films.map { it.id })
        assertEquals(listOf("v1"), shelves.homeVideos.map { it.id })
        assertEquals(1, shelves.shows.size)
    }

    @Test
    fun folderTreeCountsEveryAncestorAndOffersTheChooserOnlyWhenItCanNarrow() {
        val items = listOf(video("a", folder = "Movies/Shows/A/"), video("b", folder = "/Movies/Films"), video("c", folder = null))
        val nodes = LibraryFolderTree.derive(items)
        assertEquals(listOf("Movies" to 2, "Movies/Films" to 1, "Movies/Shows" to 1, "Movies/Shows/A" to 1), nodes.map { it.path to it.count })
        assertEquals(listOf(0, 1, 1, 2), nodes.map { it.depth })
        assertEquals(listOf("a"), LibraryFolderTree.scoped(items, "Movies/Shows").map { it.id })
        assertTrue(LibraryFolderTree.chooserOffered(nodes, null, items.size))
        val one = listOf(video("a", folder = "Movies/"), video("b", folder = "Movies/"))
        assertFalse(LibraryFolderTree.chooserOffered(LibraryFolderTree.derive(one), null, one.size))
    }

    @Test
    fun theStreakAsksAfterThreeAutoplaysUntilAKeyPress() {
        val streak = UpNextStreak()
        repeat(3) {
            assertTrue(streak.mayAutoplay(true))
            streak.autoplayed()
        }
        assertFalse(streak.mayAutoplay(true))
        streak.viewerPressed()
        assertTrue(streak.mayAutoplay(true))
        assertFalse(streak.mayAutoplay(false))
    }

    @Test
    fun searchNeedsEveryWordAnywhere() {
        val items = listOf(film("f1", find = "thewailing2016"), film("f2", find = "spiderman"))
        assertEquals(listOf("f1"), LibrarySearch.matching(items, "Wail 16").map { it.id })
        assertEquals(listOf("f2"), LibrarySearch.matching(items, "SPIDER man").map { it.id })
        assertTrue(LibrarySearch.matching(items, "  ") === items)
    }

    @Test
    fun foldDropsAccentsAndCaseAndKeepsWordBoundaries() {
        assertEquals("amelie 2001", LibraryFold.fold("Amélie (2001)"))
        assertEquals("olstykke aero strasse", LibraryFold.fold("Ølstykke Ærø Straße"))
        assertEquals(listOf("spider", "man"), LibraryFold.words("Spider.Man"))
    }

    @Test
    fun shortestSortsUnknownDurationsLast() {
        val items = listOf(film("unknown", duration = 0L), film("long", duration = 9_000L), film("short", duration = 1_000L))
        assertEquals(listOf("short", "long", "unknown"), LibrarySorting.sorted(items, LibrarySortOrder.SHORTEST).map { it.id })
        assertEquals(listOf("long", "short", "unknown"), LibrarySorting.sorted(items, LibrarySortOrder.LONGEST).map { it.id })
    }

    @Test
    fun imageUrlsAreBuiltOnlyFromValidPaths() {
        assertEquals("https://image.tmdb.org/t/p/w342/a.jpg", LibraryImages.poster("/a.jpg"))
        assertNull(LibraryImages.poster("https://evil.example/a.jpg"))
        assertNull(LibraryImages.heroBackdrop(null))
    }

    private fun ep(
        id: String, showKey: String, season: Int, episode: Int, show: String = "Lantern", watched: Boolean = false,
        resumeMs: Long? = null, added: Long = 0L,
    ) = LibraryItem(
        id = id, name = "$id.mkv", title = id, sortKey = id, find = id, kind = LibraryItemKind.EPISODE,
        show = show, showKey = showKey, season = season, episode = episode, watched = watched, resumeMs = resumeMs, addedSeconds = added,
    )

    private fun film(id: String, resumeMs: Long? = null, added: Long = 0L, art: LibraryArt? = null, find: String = id, duration: Long = 0L) = LibraryItem(
        id = id, name = "$id.mkv", title = id, sortKey = id, find = find, kind = LibraryItemKind.FILM,
        resumeMs = resumeMs, addedSeconds = added, art = art, durationMs = duration,
    )

    private fun video(id: String, added: Long = 0L, folder: String? = null) = LibraryItem(
        id = id, name = "$id.mkv", title = id, sortKey = id, find = id, kind = LibraryItemKind.VIDEO, addedSeconds = added, folder = folder,
    )
}
