package com.flick.sender.library

import com.flick.sender.model.HdrType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryLinkPolicyTest {

    @Test
    fun dialBackoffSteps2s5s15sThen60sAndResets() {
        val backoff = LibraryDialBackoff()
        assertEquals(listOf(2_000L, 5_000L, 15_000L, 60_000L, 60_000L), List(5) { backoff.next() })
        backoff.reset()
        assertEquals(2_000L, backoff.next())
    }

    @Test
    fun onlyASocketThatStayedUpThirtySecondsResetsTheBackoff() {
        val backoff = LibraryDialBackoff()
        assertEquals(2_000L, backoff.next())
        // A TV that accepts and then drops the socket keeps the phone climbing the steps.
        backoff.socketEnded(29_999L)
        assertEquals(5_000L, backoff.next())
        backoff.socketEnded(0L)
        assertEquals(15_000L, backoff.next())
        backoff.socketEnded(30_000L)
        assertEquals(2_000L, backoff.next())
    }

    @Test
    fun detailAndSeasonAnswersAreTwentyAdmittedPer10Seconds() {
        val gate = RequestGate(4, 20, 10_000L)
        repeat(20) { assertTrue(gate.tryAdmit(it.toLong())) }
        assertFalse(gate.tryAdmit(100L))
        // A refusal does not count against the window.
        assertTrue(gate.tryAdmit(10_000L))
        assertFalse(gate.tryAdmit(10_000L))
    }

    @Test
    fun aFifthAdmittedAnswerWaitsForASlotInsteadOfBeingDropped() = runTest {
        val gate = RequestGate(4, 20, 10_000L)
        val release = List(5) { CompletableDeferred<Unit>() }
        val started = ArrayList<Int>()
        val answered = ArrayList<Int>()
        val jobs = List(5) { n ->
            assertTrue(gate.tryAdmit(n * 100L))
            launch {
                gate.run {
                    started += n
                    release[n].await()
                }
                answered += n
            }
        }
        runCurrent()
        assertEquals(listOf(0, 1, 2, 3), started)

        release[0].complete(Unit)
        runCurrent()
        assertEquals(listOf(0, 1, 2, 3, 4), started)
        release.drop(1).forEach { it.complete(Unit) }
        jobs.joinAll()
        assertEquals(setOf(0, 1, 2, 3, 4), answered.toSet())
    }

    @Test
    fun cancelledAnswersGiveTheirSlotsToTheNextSocket() = runTest {
        val gate = RequestGate(4, 20, 10_000L)
        // The old socket's lookups hang, and one more of its asks is waiting behind them.
        val old = Job()
        repeat(5) { launch(old) { gate.run { awaitCancellation() } } }
        runCurrent()

        old.cancel()
        runCurrent()
        val next = ArrayList<Int>()
        repeat(4) { n -> launch { gate.run { next += n } } }
        runCurrent()
        assertEquals(listOf(0, 1, 2, 3), next)
    }

    @Test
    fun aCancelledDialsEndLeavesTheNextDialsEndpoint() {
        val slot = AttemptSlot<String>()
        val first = slot.begin("tv")
        // The next dial, to an equal endpoint, begins before the cancelled one's finally runs.
        val second = slot.begin("tv")
        slot.end(first)
        assertEquals("tv", slot.value)
        slot.end(second)
        assertNull(slot.value)
    }

    @Test
    fun aChangeDuringABuildOrABuildThatNeverFinishedLeavesTheCatalogStale() {
        val freshness = CatalogFreshness()
        assertTrue(freshness.dirty)
        val first = freshness.buildStarting()
        freshness.built(first)
        assertFalse(freshness.dirty)

        freshness.changed()
        val during = freshness.buildStarting()
        freshness.changed()
        freshness.built(during)
        assertTrue(freshness.dirty)

        // Started and never kept: still stale.
        freshness.buildStarting()
        assertTrue(freshness.dirty)

        val again = freshness.buildStarting()
        freshness.built(again)
        assertFalse(freshness.dirty)
        // An older token handed back late does not undo a newer build.
        freshness.built(during)
        assertFalse(freshness.dirty)
    }

    @Test
    fun theLiveCastsCastingIsSaidOnceOnEachNewSocket() {
        val memory = CastingMemory()
        assertNull(memory.afterDelivery())
        memory.loaded("cast-a", "id-a")
        // The socket that saw the loadMedia live needs nothing again.
        assertNull(memory.afterDelivery())
        memory.socketOpened()
        assertEquals("cast-a" to "id-a", memory.afterDelivery())
        assertNull(memory.afterDelivery())
        memory.socketOpened()
        memory.ended("cast-other")
        assertEquals("cast-a" to "id-a", memory.afterDelivery())
        memory.ended("cast-a")
        memory.socketOpened()
        assertNull(memory.afterDelivery())
    }

    @Test
    fun aProbeThatFoundNoHdrIsNeverSentAsSdr() {
        assertEquals(CatalogItem.Hdr.DOLBY_VISION, LibraryHdr.wire(HdrType.DOLBY_VISION))
        assertEquals(CatalogItem.Hdr.HDR10, LibraryHdr.wire(HdrType.HDR10))
        assertNull(LibraryHdr.wire(HdrType.NONE))
        assertNull(LibraryHdr.wire(null))
    }

    @Test
    fun thumbnailsAre60Per10Seconds() {
        val limiter = SlidingWindowLimiter(60, 10_000L)
        repeat(60) { assertTrue(limiter.tryPass(it * 10L)) }
        assertFalse(limiter.tryPass(9_999L))
        // The first pass leaves the window exactly 10 s after it was made.
        assertTrue(limiter.tryPass(10_000L))
        assertFalse(limiter.tryPass(10_005L))
        assertTrue(limiter.tryPass(10_010L))
    }

    @Test
    fun aRefusedThumbnailDoesNotCountAgainstTheWindow() {
        val limiter = SlidingWindowLimiter(2, 10_000L)
        assertTrue(limiter.tryPass(0L))
        assertTrue(limiter.tryPass(1L))
        repeat(100) { assertFalse(limiter.tryPass(2L + it)) }
        assertTrue(limiter.tryPass(10_000L))
    }

    @Test
    fun refreshIsOncePer10Seconds() {
        val gate = MinIntervalGate(10_000L)
        assertTrue(gate.tryPass(100L))
        assertFalse(gate.tryPass(10_099L))
        assertTrue(gate.tryPass(10_100L))
    }

    @Test
    fun backgroundStartsNeedVisibilityTheBatteryExemptionOrAForegroundProcessFromAndroid12() {
        fun may(sdk: Int, visible: Boolean = false, exempt: Boolean = false, process: Boolean = false) =
            LibraryPlayPolicy.mayStartForegroundService(sdk, visible, exempt, process)
        assertTrue(may(30))
        assertFalse(may(31))
        assertTrue(may(31, visible = true))
        assertTrue(may(35, exempt = true))
        // A process still holding the cast's foreground service.
        assertTrue(may(35, process = true))
    }

    @Test
    fun playRefusalsComeInTheirOrder() {
        fun refusal(enabled: Boolean = true, listed: Boolean = true, busy: Boolean = false, fgs: Boolean = true) =
            LibraryPlayPolicy.refusal(enabled, listed, busy, fgs)
        assertNull(refusal())
        assertEquals(PlayRefusal.DISABLED, refusal(enabled = false, listed = false, busy = true, fgs = false))
        assertEquals(PlayRefusal.MISSING, refusal(listed = false, busy = true, fgs = false))
        assertEquals(PlayRefusal.BUSY, refusal(busy = true, fgs = false))
        assertEquals(PlayRefusal.BACKGROUND, refusal(fgs = false))
    }

    private fun item(id: Int, title: String = "Title $id") = CatalogItem(
        id = LibraryProtocol.itemId(id.toString().padStart(43, 'A')),
        name = "file$id.mkv",
        title = title,
        sortKey = "title $id",
        find = "file${id}mkv",
        kind = CatalogItem.Kind.VIDEO,
    )

    @Test
    fun catWantIsAnsweredWithNothingAPatchOrTheWholeCatalog() {
        val old = EncodedCatalog.of(listOf(item(1), item(2), item(3)))
        val next = EncodedCatalog.of(listOf(item(4), item(1), item(2, "Renamed"), item(3)))

        assertEquals(CatalogAnswer.None, CatalogDelivery.answer(next.rev, next, old))
        assertTrue(CatalogDelivery.answer(null, next, old) is CatalogAnswer.Full)
        assertTrue(CatalogDelivery.answer("x".repeat(22), next, old) is CatalogAnswer.Full)
        assertTrue(CatalogDelivery.answer(old.rev, next, null) is CatalogAnswer.Full)
        val patch = CatalogDelivery.answer(old.rev, next, old) as CatalogAnswer.Patch
        assertTrue(patch.frame.contains("\"base\":\"${old.rev}\""))
        assertTrue(patch.frame.contains("\"rev\":\"${next.rev}\""))
    }

    @Test
    fun aPushIsAPatchWhenTheTvsOrderRuleReproducesThePhonesOrder() {
        val old = EncodedCatalog.of(listOf(item(1), item(2), item(3)))
        assertEquals(CatalogAnswer.None, CatalogDelivery.push(old, old))
        assertTrue(CatalogDelivery.push(old, null) is CatalogAnswer.Full)
        val removed = EncodedCatalog.of(listOf(item(1), item(3)))
        assertTrue(CatalogDelivery.push(removed, old) is CatalogAnswer.Patch)
        // A new item that is not first cannot be placed by the TV's rule.
        val inserted = EncodedCatalog.of(listOf(item(1), item(9), item(2), item(3)))
        assertTrue(CatalogDelivery.push(inserted, old) is CatalogAnswer.Full)
        val full = CatalogDelivery.push(inserted, old) as CatalogAnswer.Full
        assertTrue(full.frames.first().contains("\"t\":\"catBegin\""))
        assertTrue(full.frames.last().contains("\"t\":\"catEnd\""))
    }

    @Test
    fun theThumbnailCropIsTheCentred16By9() {
        assertArrayEquals(intArrayOf(0, 0, 1920, 1080), LibraryThumbPolicy.crop(1920, 1080))
        assertArrayEquals(intArrayOf(0, 135, 1440, 810), LibraryThumbPolicy.crop(1440, 1080))
        assertArrayEquals(intArrayOf(0, 656, 1080, 607), LibraryThumbPolicy.crop(1080, 1920))
        assertArrayEquals(intArrayOf(111, 0, 1777, 1000), LibraryThumbPolicy.crop(2000, 1000))
        assertArrayEquals(intArrayOf(0, 0, 0, 0), LibraryThumbPolicy.crop(0, 1080))
    }

    @Test
    fun theJpegQualityFallsUntilTheFrameFits() {
        val tried = mutableListOf<Int>()
        val bytes = LibraryThumbPolicy.encodeWithin(16_000) { quality ->
            tried += quality
            ByteArray(quality * 300)
        }
        assertEquals(listOf(85, 75, 65, 55, 45), tried)
        assertEquals(45 * 300, bytes!!.size)
    }

    @Test
    fun aThumbnailThatNeverFitsOrCannotEncodeIsAMiss() {
        assertNull(LibraryThumbPolicy.encodeWithin(10) { ByteArray(1_000) })
        val tried = mutableListOf<Int>()
        assertNull(LibraryThumbPolicy.encodeWithin(16_000) { tried += it; null })
        assertEquals(listOf(85), tried)
    }

    @Test
    fun theReadyWindowRunsThirtyMinutesFromTheLastActivity() {
        val window = TvReadyWindowPolicy()
        assertNull(window.deadlineMs())
        window.touch(5L)
        assertFalse(window.open)
        window.opened(1_000L)
        assertEquals(1_000L + 30 * 60_000L, window.deadlineMs())
        window.touch(600_000L)
        assertEquals(600_000L + 30 * 60_000L, window.deadlineMs())
        assertFalse(window.expired(600_000L + 30 * 60_000L - 1))
        assertTrue(window.expired(600_000L + 30 * 60_000L))
        window.closed()
        assertFalse(window.open)
        assertNull(window.deadlineMs())
    }

    @Test
    fun aLostControlLinkClosesTheWindowAfterTwoMinutesUnlessRegained() {
        val window = TvReadyWindowPolicy()
        window.controlLost(0L)
        assertNull(window.deadlineMs())
        window.opened(0L)
        window.controlLost(60_000L)
        assertEquals(180_000L, window.deadlineMs())
        // A second report does not push the grace out.
        window.controlLost(120_000L)
        assertEquals(180_000L, window.deadlineMs())
        window.controlRestored()
        assertEquals(30 * 60_000L, window.deadlineMs())
        // Activity near the end of a grace still ends at the grace.
        window.controlLost(29 * 60_000L)
        window.touch(29 * 60_000L + 1)
        assertEquals(31 * 60_000L, window.deadlineMs())
    }

    @Test
    fun theWindowOpensOnlyWithTheLinkUpAndNoCastHoldingTheProcess() {
        fun mayOpen(enabled: Boolean = true, control: Boolean = true, link: Boolean = true, cast: Boolean = false, fgs: Boolean = true) =
            TvReadyWindowPolicy.mayOpen(enabled, control, link, cast, fgs)
        assertTrue(mayOpen())
        assertFalse(mayOpen(enabled = false))
        assertFalse(mayOpen(control = false))
        assertFalse(mayOpen(link = false))
        assertFalse(mayOpen(cast = true))
        assertFalse(mayOpen(fgs = false))
    }
}
