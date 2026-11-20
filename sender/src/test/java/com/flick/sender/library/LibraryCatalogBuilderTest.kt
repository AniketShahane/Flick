package com.flick.sender.library

import com.flick.sender.media.FilmArt
import com.flick.sender.media.FilmPosterQuery
import com.flick.sender.media.FoldedText
import com.flick.sender.media.PlaybackCheckpoint
import com.flick.sender.media.VideoEdition
import com.flick.sender.media.librarySortTitle
import com.flick.sender.ui.screens.LibrarySearchPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryCatalogBuilderTest {

    private val episodeName = "The.Lantern.Coast.S02E04.2160p.mkv"
    private val filmName = "Blade.Runner.1982.Final.Cut.2160p.mkv"

    private fun row(name: String, fingerprint: String = "A".repeat(43), uriKey: String = "content://media/1") = CatalogSourceRow(
        fingerprint = fingerprint,
        uriKey = uriKey,
        name = name,
        durationMs = 3_130_000L,
        addedSeconds = 1_795_000_000L,
        relativePath = "Movies/Shows/Lantern Coast/",
        width = 3840,
        height = 2160,
    )

    private fun inputs(
        simplified: Boolean = true,
        art: ((FilmPosterQuery) -> FilmArt?)? = { null },
        resume: Long? = null,
        checkpoint: PlaybackCheckpoint? = null,
        watched: Map<String, Long> = emptyMap(),
        unplayable: Set<String> = emptySet(),
        silent: Set<String> = emptySet(),
        hdr: CatalogItem.Hdr? = null,
    ) = CatalogInputs(
        simplifiedNames = simplified,
        genericTitle = "Video",
        editionLabel = { edition -> if (edition == VideoEdition.FINAL_CUT) "Final Cut" else edition.name },
        resumeMs = { _, _ -> resume },
        checkpoint = { checkpoint },
        watched = watched,
        unplayable = unplayable,
        silent = silent,
        art = art,
        hdr = { hdr },
    )

    @Test
    fun anEpisodeMatchedToATmdbShowGroupsByItsShowId() {
        val art = FilmArt("/p.jpg", "/b.jpg", "/l.png", tmdbId = 91234L, show = true)
        val item = LibraryCatalogBuilder.build(listOf(row(episodeName)), inputs(art = { art })).single()

        assertEquals(CatalogItem.Kind.EPISODE, item.kind)
        assertEquals("The Lantern Coast", item.show)
        assertEquals("tv:91234", item.showKey)
        assertEquals(2, item.season)
        assertEquals(4, item.episode)
        assertEquals(CatalogItem.Art(91234L, true, "/p.jpg", "/b.jpg", "/l.png"), item.art)
    }

    @Test
    fun anEpisodeWithoutArtGroupsByItsFoldedShowTitle() {
        val item = LibraryCatalogBuilder.build(listOf(row(episodeName)), inputs()).single()

        assertEquals(CatalogItem.Kind.EPISODE, item.kind)
        assertEquals("name:" + FoldedText.fold("The Lantern Coast"), item.showKey)
        assertNull(item.art)
        val json = CatalogRules.json(item)!!
        assertTrue(json.contains("\"kind\":\"episode\""))
        assertTrue(json.contains("\"showKey\":\"name:the lantern coast\""))
    }

    @Test
    fun aTmdbMovieMatchIsAFilmAndAnOldEntryTakesTheQueryKind() {
        val movie = FilmArt("/p.jpg", "/b.jpg", null, tmdbId = 78L, show = false)
        assertEquals(
            CatalogItem.Kind.FILM,
            LibraryCatalogBuilder.build(listOf(row(filmName)), inputs(art = { movie })).single().kind,
        )
        val legacy = FilmArt("/p.jpg", null, null)
        val item = LibraryCatalogBuilder.build(listOf(row(filmName)), inputs(art = { legacy })).single()
        assertEquals(CatalogItem.Kind.FILM, item.kind)
        assertEquals(false, item.art?.show)
    }

    @Test
    fun aFilmShapedNameMatchedToAShowIsNotAFilm() {
        val show = FilmArt("/p.jpg", null, null, tmdbId = 5L, show = true)
        val item = LibraryCatalogBuilder.build(listOf(row(filmName)), inputs(art = { show })).single()
        assertEquals(CatalogItem.Kind.VIDEO, item.kind)
        assertNull(item.showKey)
    }

    @Test
    fun withPostersOffTmdbShapesNothing() {
        val item = LibraryCatalogBuilder.build(listOf(row(filmName)), inputs(art = null)).single()
        assertEquals(CatalogItem.Kind.VIDEO, item.kind)
        assertNull(item.art)
        val episode = LibraryCatalogBuilder.build(listOf(row(episodeName)), inputs(art = null)).single()
        assertTrue(episode.showKey!!.startsWith("name:"))
    }

    @Test
    fun aCameraClipIsAPlainVideo() {
        val item = LibraryCatalogBuilder.build(listOf(row("PXL_20240101_120000.mp4")), inputs()).single()
        assertEquals(CatalogItem.Kind.VIDEO, item.kind)
        assertNull(item.show)
        assertNull(item.showKey)
        assertNull(item.season)
    }

    @Test
    fun theTitleIsTheTilesTitleUnderThePreference() {
        val simplified = LibraryCatalogBuilder.build(listOf(row(filmName)), inputs()).single()
        assertEquals("Blade Runner (1982) - Final Cut", simplified.title)
        val raw = LibraryCatalogBuilder.build(listOf(row(filmName)), inputs(simplified = false)).single()
        assertEquals(filmName, raw.title)
        assertEquals("Video", LibraryCatalogBuilder.build(listOf(row("")), inputs()).single().title)
    }

    @Test
    fun sortAndSearchTextAreThePhonesOwn() {
        val item = LibraryCatalogBuilder.build(listOf(row(filmName)), inputs()).single()
        assertEquals(librarySortTitle(filmName), item.sortKey)
        assertEquals(LibrarySearchPolicy.searchText(filmName), item.find)
        assertFalse(item.find.contains(' '))
    }

    @Test
    fun theRowsFactsAndThePhonesMarksCarryOver() {
        val fingerprint = "B".repeat(43)
        val item = LibraryCatalogBuilder.build(
            listOf(row(filmName, fingerprint = fingerprint, uriKey = "k")),
            inputs(resume = 1_260_000L, watched = mapOf(fingerprint to 1_790_000_000_999L), unplayable = setOf("k"), silent = setOf("k"), hdr = CatalogItem.Hdr.DOLBY_VISION),
        ).single()

        assertEquals(LibraryProtocol.itemId(fingerprint), item.id)
        assertEquals(filmName, item.name)
        assertEquals(1982, item.year)
        assertEquals(3_130_000L, item.durationMs)
        assertEquals(1_795_000_000L, item.addedSeconds)
        assertEquals("Movies/Shows/Lantern Coast/", item.folder)
        assertEquals(3840, item.width)
        assertEquals(2160, item.height)
        assertEquals(1_260_000L, item.resumeMs)
        assertTrue(item.watched)
        assertEquals(1_790_000_000L, item.watchedAtSeconds)
        // Unplayable wins, as the tile shows no "no sound" chip on a refused file.
        assertEquals(CatalogItem.Block.UNPLAYABLE, item.blocked)
        assertEquals(CatalogItem.Hdr.DOLBY_VISION, item.hdr)
    }

    @Test
    fun aSilentFileIsMarkedSilentAndAnUnmarkedOneCarriesNothing() {
        val silent = LibraryCatalogBuilder.build(listOf(row(filmName, uriKey = "k")), inputs(silent = setOf("k"))).single()
        assertEquals(CatalogItem.Block.SILENT, silent.blocked)
        val plain = LibraryCatalogBuilder.build(listOf(row(filmName)), inputs()).single()
        assertNull(plain.blocked)
        assertNull(plain.resumeMs)
        assertFalse(plain.watched)
        assertNull(plain.hdr)
        assertNull(plain.playedAtSeconds)
        assertNull(plain.watchedAtSeconds)
    }

    @Test
    fun aResumeCarriesItsCheckpointsTime() {
        val item = LibraryCatalogBuilder.build(
            listOf(row(filmName)),
            inputs(resume = 1_260_000L, checkpoint = PlaybackCheckpoint(1_260_000L, 1_800_000_123_456L)),
        ).single()
        assertEquals(1_800_000_123L, item.playedAtSeconds)
        assertFalse(item.watched)
        assertNull(item.watchedAtSeconds)
    }

    @Test
    fun aCheckpointInTheFinishZoneIsWatchedWithoutTheLedger() {
        // 3_000_000 of 3_130_000 is past 95 %, with 130 s left; the resume rule offers nothing there.
        val checkpoint = PlaybackCheckpoint(3_000_000L, 1_800_000_000_500L)
        val item = LibraryCatalogBuilder.build(listOf(row(episodeName)), inputs(checkpoint = checkpoint)).single()
        assertNull(item.resumeMs)
        assertNull(item.playedAtSeconds)
        assertTrue(item.watched)
        assertEquals(1_800_000_000L, item.watchedAtSeconds)
    }

    @Test
    fun theLedgersTimeWinsOverABackfill() {
        val fingerprint = "C".repeat(43)
        val item = LibraryCatalogBuilder.build(
            listOf(row(episodeName, fingerprint = fingerprint)),
            inputs(checkpoint = PlaybackCheckpoint(3_000_000L, 1_800_000_000_000L), watched = mapOf(fingerprint to 1_700_000_000_000L)),
        ).single()
        assertTrue(item.watched)
        assertEquals(1_700_000_000L, item.watchedAtSeconds)
    }

    @Test
    fun aCheckpointOutsideTheZoneOrWithoutADurationIsNoFinish() {
        val midway = LibraryCatalogBuilder.build(
            listOf(row(episodeName)),
            inputs(checkpoint = PlaybackCheckpoint(2_973_499L, 1_800_000_000_000L)),
        ).single()
        assertFalse(midway.watched)
        assertNull(midway.watchedAtSeconds)
        val unmeasured = LibraryCatalogBuilder.build(
            listOf(row(episodeName).copy(durationMs = 0L)),
            inputs(checkpoint = PlaybackCheckpoint(3_000_000L, 1_800_000_000_000L)),
        ).single()
        assertFalse(unmeasured.watched)
    }

    @Test
    fun theLibraryOrderIsKeptAndEveryItemIsSendable() {
        val names = listOf(episodeName, filmName, "PXL_20240101_120000.mp4", "Amélie (2001).mkv")
        val rows = names.mapIndexed { index, name -> row(name, fingerprint = ('A' + index).toString().repeat(43), uriKey = "k$index") }
        val items = LibraryCatalogBuilder.build(rows, inputs())
        assertEquals(names, items.map { it.name })
        val encoded = EncodedCatalog.of(items)
        assertEquals(names.size, encoded.size)
        assertEquals(0, encoded.dropped)
        items.forEach { assertNotNull(CatalogRules.json(it)) }
    }

    @Test
    fun theNameCacheAnswersAsAFreshParseDoes() {
        val cache = CatalogNameFacts.Cache()
        val first = LibraryCatalogBuilder.build(listOf(row(filmName)), inputs(), cache)
        val second = LibraryCatalogBuilder.build(listOf(row(filmName)), inputs(), cache)
        assertEquals(first, second)
        assertEquals(LibraryCatalogBuilder.build(listOf(row(filmName)), inputs()), second)
    }
}
