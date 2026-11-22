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
    private var suspendedMs = 0L
    private val labels = mutableMapOf(KEY_A to "Pixel", KEY_B to "Other")

    private class FakeCredits(var stored: String? = null) : CreditsLeadStorage {
        val saves = ArrayList<String>()
        override fun load(): String? = stored
        override fun save(encoded: String) {
            saves += encoded
            stored = encoded
        }
    }

    private val credits = FakeCredits()

    private val hub = TvLibrary(
        labelOf = { labels[it] },
        autoplayEnabled = { autoplay },
        decodeThumbnail = { null },
        clock = { dispatcher.scheduler.currentTime + suspendedMs },
        newRequestId = { "req" + (reqCounter++).toString().padStart(19, '0') },
        scope = scope,
        workDispatcher = dispatcher,
        creditsStorage = credits,
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
    fun onlyTheCloseThatLeavesNoLinkSaysWhetherTheLinkWasLost() {
        val idle = TvLibrary.LinkPresence(anyLinked = false, lost = false)
        val linked = TvLibrary.LinkPresence(anyLinked = true, lost = false)
        val lostLast = TvLibrary.LinkPresence(anyLinked = false, lost = true)
        assertEquals(idle, hub.links.value)

        val a = connect(KEY_A)
        assertEquals(linked, hub.links.value)
        hub.onClosed(a, lost = false)
        assertEquals(idle, hub.links.value)

        val again = connect(KEY_A)
        hub.onClosed(again, lost = true)
        assertEquals(lostLast, hub.links.value)

        // A lost link while another stays up, then the other closed by its phone: not lost.
        val b = connect(KEY_A)
        val c = connect(KEY_B)
        hub.onClosed(b, lost = true)
        assertEquals(linked, hub.links.value)
        hub.onClosed(c, lost = false)
        assertEquals(idle, hub.links.value)
    }

    @Test
    fun linksTheTvClosesItselfAreNotLost() {
        val idle = TvLibrary.LinkPresence(anyLinked = false, lost = false)
        val older = connect(KEY_A)
        connect(KEY_A)
        // The superseded socket's session reports its end too; the phone still holds a link.
        hub.onClosed(older, lost = true)
        assertEquals(TvLibrary.LinkPresence(anyLinked = true, lost = false), hub.links.value)
        hub.forget(KEY_A)
        assertEquals(idle, hub.links.value)

        val lostFirst = connect(KEY_A)
        hub.onClosed(lostFirst, lost = true)
        connect(KEY_B)
        hub.forgetAll()
        assertEquals(idle, hub.links.value)
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
    fun anAskThePhoneNeverAnsweredIsSentAgainAfterThirtySeconds() {
        val link = connect()
        link.sent.clear()
        hub.wantDetail(E1.id)
        hub.wantSeason("tv:1", 1)
        scope.advanceTimeBy(TvLibrary.WANT_RETRY_MS - 1)
        hub.wantDetail(E1.id)
        hub.wantSeason("tv:1", 1)
        assertEquals(listOf("detailWant", "seasonWant"), link.types())
        scope.advanceTimeBy(1L)
        hub.wantDetail(E1.id)
        hub.wantSeason("tv:1", 1)
        assertEquals(listOf("detailWant", "seasonWant", "detailWant", "seasonWant"), link.types())
        // Answered, it is never asked again.
        hub.onFrame(link, PhoneFrame.Detail(E1.id, "Overview", null, null, null))
        scope.advanceTimeBy(TvLibrary.WANT_RETRY_MS)
        hub.wantDetail(E1.id)
        assertEquals(4, link.sent.size)
    }

    @Test
    fun theEpochChangesWithEachSocketOfTheShownPhone() {
        val first = connect()
        deliver(first, listOf(E1))
        val before = hub.state.value.epoch
        connect()
        assertEquals(LibraryLink.CONNECTED, hub.state.value.link)
        assertTrue(hub.state.value.epoch != before)
    }

    @Test
    fun aStandbyLongerThanTheHideWindowIsJudgedOnTheClockAtWake() {
        val first = connect()
        deliver(first, listOf(E1, E2))
        hub.onClosed(first)
        // Coroutine time stops in suspend; the elapsed clock does not.
        suspendedMs += TvLibrary.LOST_HIDE_MS + 1
        assertEquals(LibraryLink.LOST, hub.state.value.link)
        hub.onPhonesChanged()
        assertEquals(LibraryLink.NONE, hub.state.value.link)
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

    @Test
    fun aFailedUpNextPlayStaysOnTheCardAndCanBeRetried() {
        val link = episodeEnded()
        link.sent.clear()
        hub.playUpNext()
        assertEquals(LibraryPlay.Asking(E2.id), hub.state.value.play)
        hub.onFrame(link, PhoneFrame.PlayAck(lastReq(link), false, PlayRefusal.BUSY))
        assertEquals(LibraryPlay.Failed(E2.id, LibraryPlayFailure.BUSY), hub.state.value.play)
        assertEquals(E2, hub.upNext.value?.next)
        hub.playUpNext()
        assertEquals(LibraryPlay.Asking(E2.id), hub.state.value.play)
        hub.onFrame(link, PhoneFrame.PlayAck(lastReq(link), false, PlayRefusal.BUSY))
        // Leaving the card takes its failure with it, so the library does not show it again.
        hub.dismissUpNext()
        assertEquals(LibraryPlay.Idle, hub.state.value.play)
    }

    @Test
    fun aCastingFrameThatArrivesAfterTheEndStillOffersUpNext() {
        val link = connect()
        deliver(link, listOf(E1, E2))
        hub.onCastStarted(CAST_1)
        hub.onCastEnded(CAST_1)
        assertNull(hub.upNext.value)
        hub.onFrame(link, PhoneFrame.Casting(CAST_1, E1.id))
        assertEquals(E2, hub.upNext.value?.next)
        hub.dismissUpNext()
        hub.onFrame(link, PhoneFrame.Casting(CAST_1, E1.id))
        assertNull(hub.upNext.value)
    }

    @Test
    fun aCastingFrameReSentAfterALibraryBlipRestoresUpNext() {
        val first = episodeEnded()
        hub.onClosed(first)
        assertNull(hub.upNext.value)
        val second = connect()
        hub.onFrame(second, PhoneFrame.Casting(CAST_1, E1.id))
        assertEquals(E2, hub.upNext.value?.next)
        // Once up, a further copy changes nothing; once dismissed, it does not bring it back.
        val offer = hub.upNext.value
        hub.onFrame(second, PhoneFrame.Casting(CAST_1, E1.id))
        assertTrue(offer === hub.upNext.value)
        hub.dismissUpNext()
        hub.onFrame(second, PhoneFrame.Casting(CAST_1, E1.id))
        assertNull(hub.upNext.value)
    }

    @Test
    fun anEndSeenWhileTheLibraryWasDownIsOfferedAfterTheReconnect() {
        val first = connect()
        deliver(first, listOf(E1, E2))
        hub.onFrame(first, PhoneFrame.Casting(CAST_1, E1.id))
        hub.onCastStarted(CAST_1)
        hub.onClosed(first)
        hub.onCastEnded(CAST_1)
        assertNull(hub.upNext.value)
        val second = connect()
        hub.onFrame(second, PhoneFrame.Casting(CAST_1, E1.id))
        assertEquals(E2, hub.upNext.value?.next)
    }

    @Test
    fun anOldRefusalForTheNextEpisodeDoesNotOpenOnTheCard() {
        val link = connect()
        deliver(link, listOf(E1, E2))
        hub.play(E2.id, PlayFrom.START)
        hub.onFrame(link, PhoneFrame.PlayAck(lastReq(link), false, PlayRefusal.BUSY))
        assertEquals(LibraryPlay.Failed(E2.id, LibraryPlayFailure.BUSY), hub.state.value.play)
        hub.onFrame(link, PhoneFrame.Casting(CAST_1, E1.id))
        hub.onCastStarted(CAST_1)
        hub.onCastEnded(CAST_1)
        assertEquals(E2, hub.upNext.value?.next)
        assertEquals(LibraryPlay.Idle, hub.state.value.play)
    }

    @Test
    fun anUpNextRefusalGoesWithTheCardWhenThePhoneStopsTheCast() {
        val link = episodeEnded()
        hub.playUpNext()
        hub.onFrame(link, PhoneFrame.PlayAck(lastReq(link), false, PlayRefusal.BACKGROUND))
        assertEquals(LibraryPlay.Failed(E2.id, LibraryPlayFailure.BACKGROUND), hub.state.value.play)
        hub.onCastNotEnded()
        hub.onCastStopped()
        assertNull(hub.upNext.value)
        assertEquals(LibraryPlay.Idle, hub.state.value.play)
    }

    @Test
    fun aLateCastingFrameForAReplacedCastOffersNothing() {
        val link = connect()
        deliver(link, listOf(E1, E2))
        hub.onCastStarted(CAST_1)
        hub.onCastEnded(CAST_1)
        hub.onCastStarted(CAST_2)
        hub.onFrame(link, PhoneFrame.Casting(CAST_1, E1.id))
        assertNull(hub.upNext.value)
    }

    // --- Up next at the credits ----------------------------------------------------

    private fun episodePlaying(cast: String = CAST_1): FakeLink {
        val link = connect()
        deliver(link, listOf(L1, L2))
        hub.onFrame(link, PhoneFrame.Casting(cast, L1.id))
        hub.onCastStarted(cast)
        return link
    }

    private fun at(remainingMs: Long, cast: String = CAST_1) = hub.onCastPosition(cast, DUR - remainingMs, DUR)

    private fun savedSamples(): String? = credits.saves.lastOrNull()?.substringAfter(CreditsLeadBook.hash("tv:1") + ":")

    @Test
    fun theTailOffersUpNextWithinTheLeadPlayingOrPaused() {
        episodePlaying()
        at(30_001L)
        assertNull(hub.upNext.value)
        at(30_000L)
        val offer = hub.upNext.value
        assertEquals(L2, offer?.next)
        assertEquals(10_000L, offer?.autoplayAtMs)
        // Still the same card a second later, and at `ended`.
        at(29_000L)
        assertTrue(offer === hub.upNext.value)
        hub.onCastEnded(CAST_1)
        assertTrue(offer === hub.upNext.value)
    }

    @Test
    fun aCastWithNoCastingFrameOrNoNextEpisodeOffersNothingFromTheTail() {
        val link = connect()
        deliver(link, listOf(L1, L2))
        hub.onCastStarted(CAST_1)
        at(10_000L)
        assertNull(hub.upNext.value)
        hub.onFrame(link, PhoneFrame.Casting(CAST_2, L2.id))
        hub.onCastStarted(CAST_2)
        at(10_000L, CAST_2)
        assertNull(hub.upNext.value)
    }

    @Test
    fun seekingBackPastTheLeadClearsATailCard() {
        val link = episodePlaying()
        at(20_000L)
        assertNotNull(hub.upNext.value)
        at(40_000L)
        assertNotNull(hub.upNext.value)
        at(40_001L)
        assertNull(hub.upNext.value)
        link.sent.clear()
        scope.advanceTimeBy(20_000L)
        assertTrue(link.sent.isEmpty())
        // Not dismissed: reaching the tail again offers it again.
        at(25_000L)
        assertNotNull(hub.upNext.value)
    }

    @Test
    fun aTailCardWithAPlayOutstandingSurvivesASeekBack() {
        episodePlaying()
        at(20_000L)
        hub.playUpNext()
        at(600_000L)
        assertEquals(L2, hub.upNext.value?.next)
    }

    @Test
    fun backHidesATailCardUntilEnded() {
        episodePlaying()
        at(20_000L)
        hub.dismissUpNext()
        assertNull(hub.upNext.value)
        at(10_000L)
        assertNull(hub.upNext.value)
        hub.onCastEnded(CAST_1)
        assertEquals(L2, hub.upNext.value?.next)
    }

    @Test
    fun stoppingInTheCreditsIsASampleThatMovesTheLead() {
        val link = episodePlaying()
        at(60_000L)
        assertNull(hub.upNext.value)
        hub.onCastStopped(CAST_1)
        scope.runCurrent()
        assertEquals("60000", savedSamples())
        hub.onFrame(link, PhoneFrame.Casting(CAST_2, L1.id))
        hub.onCastStarted(CAST_2)
        at(60_001L, CAST_2)
        assertNull(hub.upNext.value)
        at(59_000L, CAST_2)
        assertEquals(L2, hub.upNext.value?.next)
    }

    @Test
    fun aFailureOrTeardownInTheCreditsIsNoSample() {
        val link = episodePlaying()
        at(60_000L)
        // The stage left the cast with no stop command: an error, a lost link, a teardown.
        hub.onCastStopped()
        hub.onFrame(link, PhoneFrame.Casting(CAST_2, L1.id))
        hub.onCastStarted(CAST_2)
        at(60_000L, CAST_2)
        // A stop of some other cast is not this one's.
        hub.onCastStopped(CAST_1)
        scope.runCurrent()
        assertTrue(credits.saves.isEmpty())
    }

    @Test
    fun theTailLeadIsCappedInsideThePhonesFinishZone() {
        val book = CreditsLeadBook()
        book.record("tv:1", 180_000L)
        val capped = TvLibrary(
            labelOf = { "Pixel" },
            autoplayEnabled = { true },
            decodeThumbnail = { null },
            clock = { dispatcher.scheduler.currentTime },
            scope = scope,
            workDispatcher = dispatcher,
            creditsStorage = FakeCredits(book.encode()),
        )
        val link = FakeLink()
        capped.onAuthenticated(KEY_A, link) { true }
        capped.onFrame(link, PhoneFrame.CatBegin(REV1, 2, 1))
        capped.onFrame(link, PhoneFrame.CatPart(REV1, 0, listOf(L1, L2)))
        capped.onFrame(link, PhoneFrame.CatEnd(REV1))
        capped.onFrame(link, PhoneFrame.Casting(CAST_1, L1.id))
        capped.onCastStarted(CAST_1)
        // A 22-minute episode: the phone's finish zone is its last 66 s, so the card waits for 63 s.
        val duration = 22 * 60_000L
        capped.onCastPosition(CAST_1, duration - 64_000L, duration)
        assertNull(capped.upNext.value)
        capped.onCastPosition(CAST_1, duration - 63_000L, duration)
        assertEquals(L2, capped.upNext.value?.next)
    }

    private fun hubWithLead(learnedMs: Long): TvLibrary {
        val book = CreditsLeadBook()
        book.record("tv:1", learnedMs)
        return TvLibrary(
            labelOf = { "Pixel" },
            autoplayEnabled = { true },
            decodeThumbnail = { null },
            clock = { dispatcher.scheduler.currentTime },
            scope = scope,
            workDispatcher = dispatcher,
            creditsStorage = FakeCredits(book.encode()),
        )
    }

    private fun TvLibrary.casting(items: List<LibraryItem>) {
        val link = FakeLink()
        onAuthenticated(KEY_A, link) { true }
        onFrame(link, PhoneFrame.CatBegin(REV1, items.size, 1))
        onFrame(link, PhoneFrame.CatPart(REV1, 0, items))
        onFrame(link, PhoneFrame.CatEnd(REV1))
        onFrame(link, PhoneFrame.Casting(CAST_1, items.first().id))
        onCastStarted(CAST_1)
    }

    @Test
    fun aPhoneWithNoTimesGetsTheCardOnlyInItsLast27Seconds() {
        val legacy = hubWithLead(180_000L)
        legacy.casting(listOf(L1.copy(playedAtSeconds = null), L2))
        val duration = 60 * 60_000L
        legacy.onCastPosition(CAST_1, duration - 27_001L, duration)
        assertNull(legacy.upNext.value)
        legacy.onCastPosition(CAST_1, duration - 27_000L, duration)
        assertEquals(L2, legacy.upNext.value?.next)
        // Seeking back clears it as soon as it is outside the cap.
        legacy.onCastPosition(CAST_1, duration - 27_001L, duration)
        assertNull(legacy.upNext.value)
    }

    @Test
    fun seekingBackOutsideTheCapClearsATailCard() {
        // A 60-minute episode: learned 180 s is capped at 177 s, so 187 s left is outside it.
        val capped = hubWithLead(180_000L)
        capped.casting(listOf(L1, L2))
        val duration = 60 * 60_000L
        capped.onCastPosition(CAST_1, duration - 177_000L, duration)
        assertEquals(L2, capped.upNext.value?.next)
        capped.onCastPosition(CAST_1, duration - 187_000L, duration)
        assertNull(capped.upNext.value)
        capped.onCastPosition(CAST_1, duration - 177_000L, duration)
        assertNotNull(capped.upNext.value)
        capped.onCastPosition(CAST_1, duration - 177_001L, duration)
        assertNull(capped.upNext.value)
    }

    @Test
    fun aFinaleTailAsksAgainOnlyWhenTheCatalogChanges() {
        val link = connect()
        deliver(link, listOf(L1, L2))
        hub.onFrame(link, PhoneFrame.Casting(CAST_1, L2.id))
        hub.onCastStarted(CAST_1)
        at(10_000L)
        at(9_000L)
        assertNull(hub.upNext.value)
        val l3 = episode("s01e03lllll", 3).copy(durationMs = DUR)
        deliver(link, listOf(L1, L2, l3), REV2)
        at(8_000L)
        assertEquals(l3, hub.upNext.value?.next)
    }

    @Test
    fun replacingTheCastInTheCreditsIsASample() {
        episodePlaying()
        at(100_000L)
        hub.onCastStarted(CAST_2)
        scope.runCurrent()
        assertEquals("100000", savedSamples())
    }

    @Test
    fun playNowFromATailCardIsNoSample() {
        val link = episodePlaying()
        at(25_000L)
        hub.playUpNext()
        hub.onFrame(link, PhoneFrame.PlayAck(lastReq(link), true, null))
        hub.onCastStarted(CAST_2)
        hub.onCastStopped()
        scope.runCurrent()
        assertTrue(credits.saves.isEmpty())
    }

    @Test
    fun stoppingOrReplacingWhileATailCardIsUpIsNoSample() {
        episodePlaying()
        at(25_000L)
        assertNotNull(hub.upNext.value)
        hub.onCastStopped(CAST_1)
        episodePlaying(CAST_2)
        at(25_000L, CAST_2)
        assertNotNull(hub.upNext.value)
        hub.onCastStarted("Y2FzdDNjYXN0M2Nhc3QzYw")
        scope.runCurrent()
        assertTrue(credits.saves.isEmpty())
    }

    @Test
    fun stoppingAfterBackHidTheTailCardIsASample() {
        episodePlaying()
        at(25_000L)
        hub.dismissUpNext()
        hub.onCastStopped(CAST_1)
        scope.runCurrent()
        assertEquals("25000", savedSamples())
    }

    @Test
    fun anAutoplayIsNoSample() {
        val link = episodePlaying()
        at(25_000L)
        scope.advanceTimeBy(10_001L)
        assertTrue(link.sent.last().contains(L2.id))
        hub.onCastStarted(CAST_2)
        scope.runCurrent()
        assertTrue(credits.saves.isEmpty())
    }

    @Test
    fun leavingAfterEndedOrBeforeTheCreditsIsNoSample() {
        episodePlaying()
        at(10_000L)
        hub.onCastEnded(CAST_1)
        hub.onCastNotEnded()
        hub.onCastStopped(CAST_1)
        episodePlaying(CAST_2)
        at(DUR / 2, CAST_2)
        hub.onCastStopped(CAST_2)
        scope.runCurrent()
        assertTrue(credits.saves.isEmpty())
    }

    @Test
    fun storedSamplesSetTheLeadOfANewHub() {
        val book = CreditsLeadBook()
        book.record("tv:1", 120_000L)
        val restored = TvLibrary(
            labelOf = { "Pixel" },
            autoplayEnabled = { true },
            decodeThumbnail = { null },
            clock = { dispatcher.scheduler.currentTime },
            scope = scope,
            workDispatcher = dispatcher,
            creditsStorage = FakeCredits(book.encode()),
        )
        val link = FakeLink()
        restored.onAuthenticated(KEY_A, link) { true }
        restored.onFrame(link, PhoneFrame.CatBegin(REV1, 2, 1))
        restored.onFrame(link, PhoneFrame.CatPart(REV1, 0, listOf(L1, L2)))
        restored.onFrame(link, PhoneFrame.CatEnd(REV1))
        restored.onFrame(link, PhoneFrame.Casting(CAST_1, L1.id))
        restored.onCastStarted(CAST_1)
        restored.onCastPosition(CAST_1, DUR - 120_000L, DUR)
        assertEquals(L2, restored.upNext.value?.next)
    }

    // --- Two phones, lost phones, empty libraries ---------------------------------

    @Test
    fun upNextPlaysOnThePhoneThatCastTheEndedEpisode() {
        val a = connect(KEY_A)
        deliver(a, listOf(E1, E2))
        hub.onFrame(a, PhoneFrame.Casting(CAST_1, E1.id))
        val b = connect(KEY_B)
        assertEquals("Other", hub.state.value.phoneName)
        hub.onCastStarted(CAST_1)
        hub.onCastEnded(CAST_1)
        assertEquals(E2, hub.upNext.value?.next)
        a.sent.clear()
        b.sent.clear()
        hub.playUpNext()
        assertEquals(listOf("play"), a.types())
        assertTrue(b.sent.isEmpty())
        // Only the phone the play went to can answer it.
        hub.onFrame(b, PhoneFrame.PlayAck(lastReq(a), false, PlayRefusal.BUSY))
        assertEquals(LibraryPlay.Asking(E2.id), hub.state.value.play)
        hub.onFrame(a, PhoneFrame.PlayAck(lastReq(a), true, null))
        assertEquals(LibraryPlay.Starting(E2.id), hub.state.value.play)
    }

    @Test
    fun theShownPhoneDoesNotSwitchUnderAnOfferOrAPlayInFlight() {
        val a = episodeEnded()
        connect(KEY_B)
        assertEquals("Pixel", hub.state.value.phoneName)
        assertNotNull(hub.upNext.value)
        hub.dismissUpNext()
        assertEquals("Other", hub.state.value.phoneName)

        hub.forget(KEY_B)
        assertEquals("Pixel", hub.state.value.phoneName)
        hub.play(E1.id, PlayFrom.START)
        connect(KEY_B)
        assertEquals("Pixel", hub.state.value.phoneName)
        hub.onFrame(a, PhoneFrame.PlayAck(lastReq(a), true, null))
        assertEquals("Pixel", hub.state.value.phoneName)
        hub.onCastStarted(CAST_2)
        assertEquals("Other", hub.state.value.phoneName)
    }

    @Test
    fun theLeaseHolderIsShownBeforeTheMostRecentPhone() {
        connect(KEY_A)
        connect(KEY_B)
        assertEquals("Other", hub.state.value.phoneName)
        hub.onControlPeer(KEY_A)
        assertEquals("Pixel", hub.state.value.phoneName)
        hub.onControlPeer(null)
        assertEquals("Other", hub.state.value.phoneName)
    }

    @Test
    fun anEmptyLibraryIsNotShown() {
        val link = connect()
        deliver(link, emptyList())
        assertEquals(LibraryLink.NONE, hub.state.value.link)
        assertNull(hub.state.value.catalog)
        deliver(link, listOf(E1), REV2)
        assertEquals(LibraryLink.CONNECTED, hub.state.value.link)
        assertEquals(REV2, hub.state.value.catalog?.rev)
    }

    @Test
    fun aPhoneGoneForTenMinutesIsHiddenAndItsReconnectShowsItAgain() {
        val first = connect()
        deliver(first, listOf(E1, E2))
        hub.onClosed(first)
        scope.advanceTimeBy(TvLibrary.LOST_HIDE_MS - 1)
        assertEquals(LibraryLink.LOST, hub.state.value.link)
        scope.advanceTimeBy(2L)
        assertEquals(LibraryLink.NONE, hub.state.value.link)
        assertNull(hub.state.value.catalog)
        assertNull(hub.state.value.phoneName)
        val second = connect()
        // Still the patch base for the reconnect.
        assertEquals(listOf(LibraryFrames.catWant(REV1)), second.sent)
        assertEquals(LibraryLink.CONNECTED, hub.state.value.link)
        assertEquals(REV1, hub.state.value.catalog?.rev)
    }

    @Test
    fun aHiddenPhoneGivesWayToAnotherThatIsStillSharing() {
        val b = connect(KEY_B)
        deliver(b, listOf(E1))
        val a = connect(KEY_A)
        deliver(a, listOf(E1, E2))
        hub.onClosed(a)
        assertEquals("Pixel", hub.state.value.phoneName)
        scope.advanceTimeBy(TvLibrary.LOST_HIDE_MS + 1)
        assertEquals("Other", hub.state.value.phoneName)
        assertEquals(LibraryLink.CONNECTED, hub.state.value.link)
    }

    // --- Supersede, browsing, thumbnails, shelves during a cast ---------------------

    @Test
    fun aSupersededSocketsPlaySettlesAsOffline() {
        connect()
        hub.play(E1.id, PlayFrom.START)
        assertEquals(LibraryPlay.Asking(E1.id), hub.state.value.play)
        connect()
        assertEquals(LibraryPlay.Failed(E1.id, LibraryPlayFailure.OFFLINE), hub.state.value.play)
    }

    @Test
    fun aPlayWhoseCastStartedBeforeItsAckSettlesQuietlyAtTheDeadline() {
        connect()
        hub.play(E1.id, PlayFrom.START)
        hub.onCastStarted(CAST_1)
        assertEquals(LibraryPlay.Asking(E1.id), hub.state.value.play)
        scope.advanceTimeBy(8_001L)
        assertEquals(LibraryPlay.Idle, hub.state.value.play)
    }

    @Test
    fun remoteKeysSendBrowsingOnlyOnALibraryScreenAndAtMostOnceAMinute() {
        val link = connect()
        link.sent.clear()
        hub.onRemoteKey()
        hub.onRemoteKey(libraryShowing = false)
        assertTrue(link.sent.isEmpty())
        hub.onRemoteKey(libraryShowing = true)
        hub.onRemoteKey(libraryShowing = true)
        assertEquals(listOf("browsing"), link.types())
        scope.advanceTimeBy(TvLibrary.BROWSING_INTERVAL_MS)
        hub.onRemoteKey(libraryShowing = true)
        assertEquals(listOf("browsing", "browsing"), link.types())
    }

    @Test
    fun expiredThumbnailRequestsAreAskedAgainWithoutANewAsk() {
        val link = connect()
        val artless = (1..8).map { video("video00000$it") }
        deliver(link, artless)
        link.sent.clear()
        artless.forEach { hub.wantThumbnail(it.id) }
        assertEquals(6, link.sent.size)
        scope.advanceTimeBy(15_001L)
        assertEquals(12, link.sent.size)
        assertTrue(link.types().all { it == "thumbWant" })
    }

    @Test
    fun aThumbnailGivenUpOnIsSentAgainWhenTheTileAsksOnTheNextSocket() {
        val old = connect()
        val clip = video("clip0000001")
        deliver(old, listOf(clip))
        hub.wantThumbnail(clip.id)
        scope.advanceTimeBy(60_000L)
        assertEquals(3, old.sent.count { it == LibraryFrames.thumbWant(clip.id) })
        // A supersede leaves the link CONNECTED; the tile's ask is keyed on the epoch instead.
        val fresh = connect()
        scope.advanceTimeBy(60_000L)
        assertEquals(0, fresh.sent.count { it == LibraryFrames.thumbWant(clip.id) })
        hub.wantThumbnail(clip.id)
        scope.runCurrent()
        assertEquals(1, fresh.sent.count { it == LibraryFrames.thumbWant(clip.id) })
    }

    @Test
    fun noThumbnailTimerOutlivesTheSocket() {
        val link = connect()
        val artless = (1..8).map { video("video00000$it") }
        deliver(link, artless)
        artless.forEach { hub.wantThumbnail(it.id) }
        hub.onClosed(link)
        link.sent.clear()
        scope.advanceTimeBy(60_000L)
        assertTrue(link.sent.isEmpty())
    }

    @Test
    fun shelvesForACatalogThatArrivesDuringACastWaitForItToEnd() {
        val link = connect()
        deliver(link, listOf(E1))
        hub.onCastStarted(CAST_1)
        deliver(link, listOf(E1, E2), REV2)
        assertEquals(REV1, hub.state.value.catalog?.rev)
        hub.onCastStopped()
        scope.runCurrent()
        assertEquals(REV2, hub.state.value.catalog?.rev)
        assertEquals(2, hub.state.value.shelves?.shows?.single()?.episodeCount)
    }

    private fun lastReq(link: FakeLink): String = Regex("\"req\":\"([^\"]+)\"").findAll(link.sent.joinToString("\n")).last().groupValues[1]

    private companion object {
        const val KEY_A = "IiIiIiIiIiIiIiIiIiIiIg"
        const val KEY_B = "MzMzMzMzMzMzMzMzMzMzMw"
        const val REV1 = "AAAAAAAAAAAAAAAAAAAAAA"
        const val REV2 = "BBBBBBBBBBBBBBBBBBBBBB"
        const val CAST_1 = "Y2FzdDFjYXN0MWNhc3QxYw"
        const val CAST_2 = "Y2FzdDJjYXN0MmNhc3QyYw"

        val E1 = episode("s01e01xxxxx", 1)
        val E2 = episode("s01e02xxxxx", 2)
        const val DUR = 3_000_000L
        // A time marks the phone as one that counts the longer finish zone.
        val L1 = episode("s01e01lllll", 1).copy(durationMs = DUR, playedAtSeconds = 1L)
        val L2 = episode("s01e02lllll", 2).copy(durationMs = DUR)
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
