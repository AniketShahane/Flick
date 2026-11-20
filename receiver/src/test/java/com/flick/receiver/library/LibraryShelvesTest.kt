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

    // --- Anchors (Next episode v2) ----------------------------------------------

    @Test
    fun touchedAtIsTheLaterOfWatchedAndARealPlay() {
        assertNull(LibraryShelving.touchedAt(ep("a", "tv:1", 1, 1)))
        assertEquals(50L, LibraryShelving.touchedAt(ep("a", "tv:1", 1, 1, watched = true, watchedAt = 50)))
        // watchedAt without watched, and a play under two minutes, count for nothing.
        assertNull(LibraryShelving.touchedAt(ep("a", "tv:1", 1, 1, watchedAt = 50)))
        assertNull(LibraryShelving.touchedAt(ep("a", "tv:1", 1, 1, resumeMs = 119_999L, playedAt = 90)))
        assertEquals(90L, LibraryShelving.touchedAt(ep("a", "tv:1", 1, 1, resumeMs = 120_000L, playedAt = 90)))
        assertEquals(90L, LibraryShelving.touchedAt(ep("a", "tv:1", 1, 1, watched = true, watchedAt = 50, resumeMs = 600_000L, playedAt = 90)))
    }

    @Test
    fun anOldHalfWatchedEpisodeDoesNotBeatTheLatestSeason() {
        val show = LibraryShelving.shows(
            listOf(seen("s1e1", 1, 1, 100), started("s1e2", 1, 2, 600_000L, 110), seen("s3e1", 3, 1, 500), ep("s3e2", "tv:1", 3, 2)),
        ).single()
        assertEquals("s3e1", LibraryShelving.anchor(show)?.id)
        assertEquals("s3e2", LibraryShelving.nextEpisode(show)?.id)
        assertEquals("s3e2", LibraryShelving.showPick(show)?.id)
        val shelves = LibraryShelving.shelves(LibraryCatalog("AAAAAAAAAAAAAAAAAAAAAA", show.all))
        assertTrue(shelves.continueWatching.isEmpty())
        assertEquals(listOf("s3e2"), shelves.nextEpisodes.map { it.id })
    }

    @Test
    fun anInProgressAnchorIsContinuedNotSkipped() {
        val show = LibraryShelving.shows(
            listOf(seen("e1", 1, 1, 100), started("e2", 1, 2, 300_000L, 200), started("e3old", 1, 3, 400_000L, 50), ep("e4", "tv:1", 1, 4)),
        ).single()
        assertEquals("e2", LibraryShelving.anchor(show)?.id)
        assertNull(LibraryShelving.nextEpisode(show))
        assertEquals("e2", LibraryShelving.showPick(show)?.id)
        val shelves = LibraryShelving.shelves(LibraryCatalog("AAAAAAAAAAAAAAAAAAAAAA", show.all))
        // e3old is in progress too, but older than the anchor: it is hidden.
        assertEquals(listOf("e2"), shelves.continueWatching.map { it.id })
        assertTrue(shelves.nextEpisodes.isEmpty())
    }

    @Test
    fun aShortPlayNeverMovesTheAnchor() {
        val show = LibraryShelving.shows(listOf(seen("e1", 1, 1, 100), started("e2", 1, 2, 60_000L, 900), seen("e5", 1, 5, 500), ep("e6", "tv:1", 1, 6))).single()
        assertEquals("e5", LibraryShelving.anchor(show)?.id)
        assertEquals("e6", LibraryShelving.nextEpisode(show)?.id)
        assertTrue(LibraryShelving.shelves(LibraryCatalog("AAAAAAAAAAAAAAAAAAAAAA", show.all)).continueWatching.isEmpty())
    }

    @Test
    fun aStartedEpisodeRightAfterTheAnchorStaysInContinueWatching() {
        val show = LibraryShelving.shows(listOf(seen("e5", 1, 5, 500), started("e6", 1, 6, 60_000L, 600), ep("e7", "tv:1", 1, 7))).single()
        assertEquals("e5", LibraryShelving.anchor(show)?.id)
        assertNull(LibraryShelving.nextEpisode(show))
        assertEquals("e6", LibraryShelving.showPick(show)?.id)
        assertEquals(listOf("e6"), LibraryShelving.shelves(LibraryCatalog("AAAAAAAAAAAAAAAAAAAAAA", show.all)).continueWatching.map { it.id })
    }

    @Test
    fun aRewatchSkipsEpisodesAlreadyWatchedAfterIt() {
        val items = listOf(seen("e1", 1, 1, 400), seen("e2", 1, 2, 200), seen("e3", 1, 3, 300), ep("e4", "tv:1", 1, 4))
        val show = LibraryShelving.shows(items).single()
        assertEquals("e1", LibraryShelving.anchor(show)?.id)
        assertEquals("e4", LibraryShelving.nextEpisode(show)?.id)
        assertEquals("e4", LibraryShelving.showPick(show)?.id)
    }

    @Test
    fun aRewatchOfAFinishedSeriesLeavesItFinished() {
        val show = LibraryShelving.shows(listOf(seen("e1", 1, 1, 100), seen("e2", 1, 2, 900), seen("e3", 1, 3, 300))).single()
        assertEquals("e2", LibraryShelving.anchor(show)?.id)
        assertNull(LibraryShelving.nextEpisode(show))
        assertEquals("e1", LibraryShelving.showPick(show)?.id)
    }

    @Test
    fun anOlderStartedEpisodeThatPlayWouldResumeStaysInContinueWatching() {
        // The finale is the anchor and nothing follows it: Play falls back to the first
        // unwatched episode, e2, which is started, so Continue watching holds it.
        val show = LibraryShelving.shows(listOf(seen("e1", 1, 1, 100), started("e2", 1, 2, 600_000L, 50), seen("e3", 1, 3, 900))).single()
        assertEquals("e3", LibraryShelving.anchor(show)?.id)
        assertNull(LibraryShelving.nextEpisode(show))
        assertEquals("e2", LibraryShelving.showPick(show)?.id)
        assertEquals(listOf("e2"), LibraryShelving.shelves(LibraryCatalog("AAAAAAAAAAAAAAAAAAAAAA", show.all)).continueWatching.map { it.id })
    }

    @Test
    fun anInProgressEpisodePlayWouldNotResumeIsHidden() {
        // e4 is unwatched after the anchor, so Play starts it; the older started e2 is hidden.
        val show = LibraryShelving.shows(listOf(seen("e1", 1, 1, 100), started("e2", 1, 2, 600_000L, 50), seen("e3", 1, 3, 900), ep("e4", "tv:1", 1, 4))).single()
        assertEquals("e4", LibraryShelving.showPick(show)?.id)
        assertTrue(LibraryShelving.shelves(LibraryCatalog("AAAAAAAAAAAAAAAAAAAAAA", show.all)).continueWatching.isEmpty())
    }

    @Test
    fun anAnchorTieGoesToTheLaterEpisodeAndASecondFileIsSkipped() {
        val show = LibraryShelving.shows(listOf(seen("e1", 1, 1, 500), seen("e2", 1, 2, 500), ep("e2b", "tv:1", 1, 2), ep("e3", "tv:1", 1, 3))).single()
        assertEquals("e2", LibraryShelving.anchor(show)?.id)
        assertEquals("e3", LibraryShelving.nextEpisode(show)?.id)
    }

    @Test
    fun theUnwatchedEpisodeAfterTheAnchorIsResumedIfStartedAndNeverReachedPast() {
        // e6 is started (with no time of its own); Next episodes offers nothing, Play resumes e6.
        val show = LibraryShelving.shows(listOf(seen("e5", 1, 5, 500), ep("e6", "tv:1", 1, 6, resumeMs = 30_000L), ep("e7", "tv:1", 1, 7))).single()
        assertNull(LibraryShelving.nextEpisode(show))
        assertEquals("e6", LibraryShelving.showPick(show)?.id)
    }

    @Test
    fun aLegacyShowKeepsTodaysRules() {
        val items = listOf(ep("e1", "tv:1", 1, 1, watched = true), ep("e2", "tv:1", 1, 2, resumeMs = 5_000L), ep("e3", "tv:1", 1, 3, watched = true), ep("e4", "tv:1", 1, 4))
        val show = LibraryShelving.shows(items).single()
        assertTrue(LibraryShelving.isLegacy(show))
        assertEquals("e3", LibraryShelving.anchor(show)?.id)
        assertEquals("e4", LibraryShelving.nextEpisode(show)?.id)
        assertEquals("e2", LibraryShelving.showPick(show)?.id)
        assertEquals(listOf("e2"), LibraryShelving.shelves(LibraryCatalog("AAAAAAAAAAAAAAAAAAAAAA", items)).continueWatching.map { it.id })
    }

    @Test
    fun continueWatchingIsNewestPlayedFirstThenRecentlyAdded() {
        val items = listOf(
            film("f1", resumeMs = 1_000L, playedAt = 100, added = 90),
            film("f2", resumeMs = 1_000L, playedAt = 300, added = 10),
            film("f3", resumeMs = 1_000L, added = 50),
            film("f4", resumeMs = 1_000L, added = 60),
            ep("l2", "tv:2", 1, 2, show = "Legacy", resumeMs = 9_000L, added = 70),
        )
        val shelves = LibraryShelving.shelves(LibraryCatalog("AAAAAAAAAAAAAAAAAAAAAA", items))
        assertEquals(listOf("f2", "f1", "l2", "f4", "f3"), shelves.continueWatching.map { it.id })
    }

    @Test
    fun nextEpisodesAreOrderedByWhenTheirAnchorWasTouched() {
        val items = listOf(
            ep("a1", "tv:1", 1, 1, show = "A", watched = true, watchedAt = 100, added = 900), ep("a2", "tv:1", 1, 2, show = "A"),
            ep("b1", "tv:2", 1, 1, show = "B", watched = true, watchedAt = 300, added = 1), ep("b2", "tv:2", 1, 2, show = "B"),
            ep("c1", "tv:3", 1, 1, show = "C", watched = true, added = 5), ep("c2", "tv:3", 1, 2, show = "C"),
            ep("d1", "tv:4", 1, 1, show = "D", watched = true, added = 7), ep("d2", "tv:4", 1, 2, show = "D"),
        )
        val shelves = LibraryShelving.shelves(LibraryCatalog("AAAAAAAAAAAAAAAAAAAAAA", items))
        // Legacy shows C and D follow, newest-added watched episode first, as before.
        assertEquals(listOf("b2", "a2", "d2", "c2"), shelves.nextEpisodes.map { it.id })
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
        resumeMs: Long? = null, added: Long = 0L, playedAt: Long? = null, watchedAt: Long? = null,
    ) = LibraryItem(
        id = id, name = "$id.mkv", title = id, sortKey = id, find = id, kind = LibraryItemKind.EPISODE,
        show = show, showKey = showKey, season = season, episode = episode, watched = watched, resumeMs = resumeMs, addedSeconds = added,
        playedAtSeconds = playedAt, watchedAtSeconds = watchedAt,
    )

    /** An episode of `tv:1` watched at [at], epoch seconds. */
    private fun seen(id: String, season: Int, episode: Int, at: Long) = ep(id, "tv:1", season, episode, watched = true, watchedAt = at)

    /** An episode of `tv:1` stopped at [resumeMs], at [at]. */
    private fun started(id: String, season: Int, episode: Int, resumeMs: Long, at: Long) = ep(id, "tv:1", season, episode, resumeMs = resumeMs, playedAt = at)

    private fun film(
        id: String, resumeMs: Long? = null, added: Long = 0L, art: LibraryArt? = null, find: String = id, duration: Long = 0L, playedAt: Long? = null,
    ) = LibraryItem(
        id = id, name = "$id.mkv", title = id, sortKey = id, find = find, kind = LibraryItemKind.FILM,
        resumeMs = resumeMs, addedSeconds = added, art = art, durationMs = duration, playedAtSeconds = playedAt,
    )

    private fun video(id: String, added: Long = 0L, folder: String? = null) = LibraryItem(
        id = id, name = "$id.mkv", title = id, sortKey = id, find = id, kind = LibraryItemKind.VIDEO, addedSeconds = added, folder = folder,
    )
}
