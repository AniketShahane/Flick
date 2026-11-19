package com.flick.receiver.library

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TvLibraryTest {

    private class FakeLink : LibraryLinkHandle {
        val sent = ArrayList<String>()
        val closed = ArrayList<String>()
        override fun send(frame: String) {
            sent += frame
        }
        override fun close(reason: String) {
            closed += reason
        }
        fun types(): List<String> = sent.map { Regex("\"t\":\"([A-Za-z]+)\"").find(it)!!.groupValues[1] }
    }

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private var autoplay = true
    private var reqCounter = 0
    private val labels = mutableMapOf(KEY_A to "Pixel", KEY_B to "Other")

    private val hub = TvLibrary(
        labelOf = { labels[it] },
        autoplayEnabled = { autoplay },
        decodeThumbnail = { null },
        clock = { dispatcher.scheduler.currentTime },
        newRequestId = { "req" + (reqCounter++).toString().padStart(19, '0') },
        scope = scope,
        workDispatcher = dispatcher,
    )

    private fun connect(keyId: String = KEY_A, valid: Boolean = true): FakeLink {
        val link = FakeLink()
        assertEquals(valid, hub.onAuthenticated(keyId, link) { valid })
        return link
    }

    private fun deliver(link: FakeLink, items: List<LibraryItem>, rev: String = REV1) {
        assertEquals(TvLibrary.Verdict.CONTINUE, hub.onFrame(link, PhoneFrame.CatBegin(rev, items.size, 1)))
        assertEquals(TvLibrary.Verdict.CONTINUE, hub.onFrame(link, PhoneFrame.CatPart(rev, 0, items)))
        assertEquals(TvLibrary.Verdict.CONTINUE, hub.onFrame(link, PhoneFrame.CatEnd(rev)))
        scope.runCurrent()
    }

    @Test
    fun nothingConnectedIsNone() {
        assertEquals(LibraryLink.NONE, hub.state.value.link)
        assertNull(hub.state.value.catalog)
    }

    @Test
    fun aNewSessionAsksForTheCatalogItHolds() {
        val first = connect()
        assertEquals(listOf(LibraryFrames.catWant(null)), first.sent)
        assertEquals(LibraryLink.CONNECTED, hub.state.value.link)
        assertEquals("Pixel", hub.state.value.phoneName)
        deliver(first, listOf(E1, E2))
        hub.onClosed(first)
        assertEquals(LibraryLink.LOST, hub.state.value.link)
        assertEquals(REV1, hub.state.value.catalog?.rev)
        val second = connect()
        assertEquals(listOf(LibraryFrames.catWant(REV1)), second.sent)
    }

    @Test
    fun theCatalogAndItsShelvesArePublishedTogether() {
        val link = connect()
        hub.onFrame(link, PhoneFrame.CatBegin(REV1, 2, 1))
        hub.onFrame(link, PhoneFrame.CatPart(REV1, 0, listOf(E1, E2)))
        hub.onFrame(link, PhoneFrame.CatEnd(REV1))
        assertNull(hub.state.value.catalog)
        scope.runCurrent()
        assertEquals(listOf(E1, E2), hub.state.value.catalog?.items)
        assertEquals(1, hub.state.value.shelves?.shows?.size)
    }

    @Test
    fun aPatchOnAnotherBaseAsksForTheWholeCatalog() {
        val link = connect()
        link.sent.clear()
        assertEquals(TvLibrary.Verdict.CONTINUE, hub.onFrame(link, PhoneFrame.CatPatch(REV1, REV2, emptyList(), emptyList())))
        assertEquals(listOf(LibraryFrames.catWant(null)), link.sent)
    }

    @Test
    fun aCatalogViolationClosesTheSocket() {
        val link = connect()
        assertEquals(TvLibrary.Verdict.CLOSE, hub.onFrame(link, PhoneFrame.CatPart(REV1, 0, listOf(E1))))
        assertEquals(TvLibrary.Verdict.CLOSE, hub.onFrame(link, PhoneFrame.CatBegin(REV1, LibraryWire.MAX_ITEMS + 1, 1)))
    }

    @Test
    fun handshakeFramesAfterAuthAndMalformedFramesClose() {
        val link = connect()
        assertEquals(TvLibrary.Verdict.CLOSE, hub.onFrame(link, PhoneFrame.Malformed))
        assertEquals(TvLibrary.Verdict.CLOSE, hub.onFrame(link, PhoneFrame.Hello(KEY_A, KEY_A, KEY_A)))
        assertEquals(TvLibrary.Verdict.CONTINUE, hub.onFrame(link, PhoneFrame.Unknown("later")))
    }

    @Test
    fun aRevokedInstallIsRefusedAndChangesNothing() {
        connect(valid = false)
        assertEquals(LibraryLink.NONE, hub.state.value.link)
    }

    @Test
    fun aNewerSessionForTheSamePhoneReplacesTheOlder() {
        val older = connect()
        val newer = connect()
        assertEquals(listOf("superseded"), older.closed)
        assertEquals(TvLibrary.Verdict.CLOSE, hub.onFrame(older, PhoneFrame.CatEnd(REV1)))
        hub.onClosed(older)
        assertEquals(LibraryLink.CONNECTED, hub.state.value.link)
        assertTrue(newer.closed.isEmpty())
    }

    @Test
    fun theMostRecentlyAuthenticatedPhoneIsShownAndForgettingItFallsBack() {
        val a = connect(KEY_A)
        deliver(a, listOf(E1))
        val b = connect(KEY_B)
        assertEquals("Other", hub.state.value.phoneName)
        assertNull(hub.state.value.catalog)
        hub.forget(KEY_B)
        assertEquals(listOf("forgotten"), b.closed)
        assertEquals("Pixel", hub.state.value.phoneName)
        assertEquals(REV1, hub.state.value.catalog?.rev)
        hub.forgetAll()
        assertEquals(listOf("forgotten"), a.closed)
        assertEquals(LibraryLink.NONE, hub.state.value.link)
        assertNull(hub.state.value.catalog)
    }

    @Test
    fun aRenameIsPublished() {
        connect()
        labels[KEY_A] = "Kitchen"
        hub.onPhonesChanged()
        assertEquals("Kitchen", hub.state.value.phoneName)
    }

    // --- Play -------------------------------------------------------------------

    @Test
    fun playIsSentAndTimesOutWithoutAnAck() {
        val link = connect()
        link.sent.clear()
        hub.play(E1.id, PlayFrom.RESUME)
        assertEquals(listOf("play"), link.types())
        assertEquals(LibraryPlay.Asking(E1.id), hub.state.value.play)
        hub.play(E2.id, PlayFrom.START)
        assertEquals(1, link.sent.size)
        scope.advanceTimeBy(8_001L)
        assertEquals(LibraryPlay.Failed(E1.id, LibraryPlayFailure.NO_ANSWER), hub.state.value.play)
        hub.clearPlayResult()
        assertEquals(LibraryPlay.Idle, hub.state.value.play)
    }

    @Test
    fun anAcceptedPlayEndsWhenTheCastStarts() {
        val link = connect()
        hub.play(E1.id, PlayFrom.START)
        val req = Regex("\"req\":\"([^\"]+)\"").find(link.sent.last())!!.groupValues[1]
        hub.onFrame(link, PhoneFrame.PlayAck(req, true, null))
        assertEquals(LibraryPlay.Starting(E1.id), hub.state.value.play)
        scope.advanceTimeBy(20_000L)
        hub.onCastStarted(CAST_1)
        assertEquals(LibraryPlay.Idle, hub.state.value.play)
        scope.advanceTimeBy(20_000L)
        assertEquals(LibraryPlay.Idle, hub.state.value.play)
    }

    @Test
    fun anAcceptedPlayWithNoCastFailsAfterThirtySeconds() {
        val link = connect()
        hub.play(E1.id, PlayFrom.START)
        val req = Regex("\"req\":\"([^\"]+)\"").find(link.sent.last())!!.groupValues[1]
        hub.onFrame(link, PhoneFrame.PlayAck(req, true, null))
        scope.advanceTimeBy(30_001L)
        assertEquals(LibraryPlay.Failed(E1.id, LibraryPlayFailure.NOT_STARTED), hub.state.value.play)
    }

    @Test
    fun aRefusalIsShown() {
        val link = connect()
        hub.play(E1.id, PlayFrom.START)
        val req = Regex("\"req\":\"([^\"]+)\"").find(link.sent.last())!!.groupValues[1]
        hub.onFrame(link, PhoneFrame.PlayAck(req, false, PlayRefusal.BACKGROUND))
        assertEquals(LibraryPlay.Failed(E1.id, LibraryPlayFailure.BACKGROUND), hub.state.value.play)
    }

    @Test
    fun playWithNoSocketIsOffline() {
        hub.play(E1.id, PlayFrom.START)
        assertEquals(LibraryPlay.Failed(E1.id, LibraryPlayFailure.OFFLINE), hub.state.value.play)
    }

    // --- Requests ---------------------------------------------------------------

    @Test
    fun detailsAndSeasonsAreAskedOnceAndOnlyAnswersToAsksAreKept() {
        val link = connect()
        link.sent.clear()
        hub.wantDetail(E1.id)
        hub.wantDetail(E1.id)
        hub.wantSeason("tv:1", 1)
        hub.wantSeason("tv:1", 1)
        hub.wantSeason("name:lantern", 1)
        assertEquals(listOf("detailWant", "seasonWant"), link.types())

        hub.onFrame(link, PhoneFrame.Detail(E2.id, "unasked", null, null, null))
        hub.onFrame(link, PhoneFrame.Detail(E1.id, "Overview", null, LibraryHdr.HDR10, null))
        assertEquals(mapOf(E1.id to LibraryDetail("Overview", null, LibraryHdr.HDR10, null)), hub.details.value)

        val ep = LibraryEpisodeInfo(1, "One", null, null)
        hub.onFrame(link, PhoneFrame.Season("tv:1", 1, 0, 2, listOf(ep)))
        assertTrue(hub.seasons.value.isEmpty())
        hub.onFrame(link, PhoneFrame.Season("tv:1", 1, 1, 2, emptyList()))
        assertEquals(listOf(ep), hub.seasons.value[LibrarySeasonKey("tv:1", 1)])
    }

    @Test
    fun anUnansweredDetailIsAskedAgainOnTheNextSocket() {
        val first = connect()
        hub.wantDetail(E1.id)
        hub.onClosed(first)
        hub.wantDetail(E1.id)
        val second = connect()
        second.sent.clear()
        hub.wantDetail(E1.id)
        assertEquals(listOf("detailWant"), second.types())
    }

    @Test
    fun refreshAndBrowsingAreRateLimited() {
        val link = connect()
        link.sent.clear()
        hub.refresh()
        hub.refresh()
        hub.browsing()
        hub.browsing()
        assertEquals(listOf("refresh", "browsing"), link.types())
        scope.advanceTimeBy(10_000L)
        hub.refresh()
        hub.browsing()
        assertEquals(listOf("refresh", "browsing", "refresh"), link.types())
        scope.advanceTimeBy(50_000L)
        hub.browsing()
        assertEquals(listOf("refresh", "browsing", "refresh", "browsing"), link.types())
    }

    @Test
    fun requestsWhileTheLinkIsDownAreDropped() {
        val link = connect()
        hub.onClosed(link)
        link.sent.clear()
        hub.refresh()
        hub.browsing()
        hub.wantDetail(E1.id)
        hub.wantSeason("tv:1", 1)
        assertTrue(link.sent.isEmpty())
    }

    @Test
    fun thumbnailsAreAskedOnlyForArtlessItemsAndAtMostSixAtATime() {
        val link = connect()
        val artless = (1..8).map { video("video00000$it") }
        deliver(link, artless + FILM)
        link.sent.clear()
        hub.wantThumbnail(FILM.id)
        artless.forEach { hub.wantThumbnail(it.id) }
        assertEquals(6, link.sent.size)
        assertTrue(link.types().all { it == "thumbWant" })
        hub.onFrame(link, PhoneFrame.ThumbMiss(artless.first().id))
        assertEquals(7, link.sent.size)
        // The slot goes to the newest waiting request.
        assertTrue(link.sent.last().contains(artless.last().id))
        hub.wantThumbnail(artless.first().id)
        assertEquals(7, link.sent.size)
    }

    @Test
    fun anUndecodableThumbnailIsAMiss() {
        val link = connect()
        val item = video("video000001")
        deliver(link, listOf(item))
        hub.wantThumbnail(item.id)
        hub.onThumbnail(link, item.id, byteArrayOf(1, 2, 3))
        scope.runCurrent()
        assertTrue(hub.thumbnails.value.isEmpty())
        link.sent.clear()
        hub.wantThumbnail(item.id)
        assertTrue(link.sent.isEmpty())
    }

    // --- Up next ----------------------------------------------------------------

    private fun episodeEnded(): FakeLink {
        val link = connect()
        deliver(link, listOf(E1, E2))
        hub.onFrame(link, PhoneFrame.Casting(CAST_1, E1.id))
        hub.onCastStarted(CAST_1)
        hub.onCastEnded(CAST_1)
        return link
    }

    @Test
    fun anEndedEpisodeOffersTheNextAndAutoplaysAfterTenSeconds() {
        val link = episodeEnded()
        val offer = hub.upNext.value
        assertNotNull(offer)
        assertEquals(E2, offer!!.next)
        assertEquals(10_000L, offer.autoplayAtMs)
        assertEquals("Lantern", offer.show?.title)
        link.sent.clear()
        scope.advanceTimeBy(9_999L)
        assertTrue(link.sent.isEmpty())
        scope.advanceTimeBy(2L)
        assertEquals(listOf("play"), link.types())
        assertTrue(link.sent.single().contains("\"from\":\"start\""))
        assertTrue(link.sent.single().contains(E2.id))
        assertNull(hub.upNext.value?.autoplayAtMs)
        hub.onCastStarted(CAST_2)
        assertNull(hub.upNext.value)
    }

    @Test
    fun theFourthAutoplayInARowWaitsForAKey() {
        val link = connect()
        deliver(link, listOf(E1, E2))
        repeat(3) { round ->
            val cast = "cast" + round.toString().padStart(18, '0')
            hub.onFrame(link, PhoneFrame.Casting(cast, E1.id))
            hub.onCastStarted(cast)
            hub.onCastEnded(cast)
            assertNotNull(hub.upNext.value?.autoplayAtMs)
            scope.advanceTimeBy(10_001L)
            val req = Regex("\"req\":\"([^\"]+)\"").find(link.sent.last())!!.groupValues[1]
            hub.onFrame(link, PhoneFrame.PlayAck(req, true, null))
        }
        val cast = "castXXXXXXXXXXXXXXXXXX"
        hub.onFrame(link, PhoneFrame.Casting(cast, E1.id))
        hub.onCastStarted(cast)
        hub.onCastEnded(cast)
        assertNull(hub.upNext.value!!.autoplayAtMs)
        assertTrue(hub.upNext.value!!.stillWatching)
        hub.onRemoteKey()
        hub.onCastNotEnded()
        hub.onCastEnded(cast)
        assertNotNull(hub.upNext.value!!.autoplayAtMs)
    }

    @Test
    fun autoplayOffOffersWithoutACountdown() {
        autoplay = false
        val link = episodeEnded()
        assertNull(hub.upNext.value!!.autoplayAtMs)
        assertFalse(hub.upNext.value!!.stillWatching)
        link.sent.clear()
        scope.advanceTimeBy(60_000L)
        assertTrue(link.sent.isEmpty())
    }

    @Test
    fun dismissingCancelsTheAutoplay() {
        val link = episodeEnded()
        hub.dismissUpNext()
        assertNull(hub.upNext.value)
        link.sent.clear()
        scope.advanceTimeBy(20_000L)
        assertTrue(link.sent.isEmpty())
    }

    @Test
    fun losingTheLinkClearsTheOffer() {
        val link = episodeEnded()
        hub.onClosed(link)
        assertNull(hub.upNext.value)
    }

    @Test
    fun aCastThePhoneNeverNamedOffersNothing() {
        val link = connect()
        deliver(link, listOf(E1, E2))
        hub.onCastStarted(CAST_1)
        hub.onCastEnded(CAST_1)
        assertNull(hub.upNext.value)
    }

    private companion object {
        const val KEY_A = "IiIiIiIiIiIiIiIiIiIiIg"
        const val KEY_B = "MzMzMzMzMzMzMzMzMzMzMw"
        const val REV1 = "AAAAAAAAAAAAAAAAAAAAAA"
        const val REV2 = "BBBBBBBBBBBBBBBBBBBBBB"
        const val CAST_1 = "Y2FzdDFjYXN0MWNhc3QxYw"
        const val CAST_2 = "Y2FzdDJjYXN0MmNhc3QyYw"

        val E1 = episode("s01e01xxxxx", 1)
        val E2 = episode("s01e02xxxxx", 2)
        val FILM = LibraryItem(
            id = "film0000001", name = "f.mkv", title = "F", sortKey = "f", find = "f", kind = LibraryItemKind.FILM,
            art = LibraryArt(1L, false, "/p.jpg", null, null),
        )

        fun episode(id: String, e: Int) = LibraryItem(
            id = id, name = "$id.mkv", title = id, sortKey = id, find = id, kind = LibraryItemKind.EPISODE,
            show = "Lantern", showKey = "tv:1", season = 1, episode = e,
        )

        fun video(id: String) = LibraryItem(id = id, name = "$id.mp4", title = id, sortKey = id, find = id, kind = LibraryItemKind.VIDEO)
    }
}
