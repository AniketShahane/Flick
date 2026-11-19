package com.flick.receiver.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryPoliciesTest {

    // --- Handshake (§4, fixtures §11) -----------------------------------------

    private val key = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    private val tvId = "EREREREREREREREREREREQ"
    private val keyId = "IiIiIiIiIiIiIiIiIiIiIg"
    private val clientNonce = "MzMzMzMzMzMzMzMzMzMzMw"
    private val serverNonce = "RERERERERERERERERERERA"
    private val clientProof = "G9mhvmEtExjWSAoOuss3RU3YbAMLeM4-qrYvtUjebGg"
    private val serverProof = "Dl-vmcgpfnTbgSgDKv6NNRa-OvjJKl4JWCh7CUg8j1Q"

    private fun handshake(lookups: MutableList<Pair<String, String>> = ArrayList(), known: Boolean = true) = LibraryHandshake(
        tvId = tvId,
        peerIp = "192.168.1.20",
        serverHost = "192.168.1.30",
        serverPort = 42421,
        findKey = { t, k -> lookups += t to k; if (known && k == keyId) key else null },
        newNonce = { serverNonce },
    )

    @Test
    fun theFixtureHandshakeCompletesWithTheFixtureServerProof() {
        val lookups = ArrayList<Pair<String, String>>()
        val machine = handshake(lookups)
        val challenge = machine.onFrame(PhoneFrame.Hello(tvId, keyId, clientNonce)) as LibraryHandshake.Step.Reply
        assertEquals(
            LibraryFrames.challenge(tvId, keyId, clientNonce, serverNonce, "192.168.1.20", "192.168.1.30", 42421),
            challenge.frame,
        )
        assertEquals(listOf(tvId to keyId), lookups)
        val done = machine.onFrame(PhoneFrame.Proof(clientNonce, serverNonce, clientProof)) as LibraryHandshake.Step.Authenticated
        assertEquals(keyId, done.keyId)
        assertEquals(LibraryFrames.ready(serverNonce, serverProof), done.readyFrame)
    }

    @Test
    fun anUnknownKeyIsDeniedWithoutAChallenge() {
        assertEquals(LibraryHandshake.Step.Deny, handshake(known = false).onFrame(PhoneFrame.Hello(tvId, keyId, clientNonce)))
    }

    @Test
    fun anotherTvsIdIsDeniedWithoutReadingTheStore() {
        val lookups = ArrayList<Pair<String, String>>()
        assertEquals(LibraryHandshake.Step.Deny, handshake(lookups).onFrame(PhoneFrame.Hello("ABABABABABABABABABABAB", keyId, clientNonce)))
        assertTrue(lookups.isEmpty())
    }

    @Test
    fun aWrongProofIsDeniedAndTheChallengeCannotBeTriedAgain() {
        val machine = handshake()
        machine.onFrame(PhoneFrame.Hello(tvId, keyId, clientNonce))
        val wrong = "A".repeat(43)
        assertEquals(LibraryHandshake.Step.Deny, machine.onFrame(PhoneFrame.Proof(clientNonce, serverNonce, wrong)))
        // The right proof after a wrong one finds nothing to answer.
        assertFalse(machine.onFrame(PhoneFrame.Proof(clientNonce, serverNonce, clientProof)) is LibraryHandshake.Step.Authenticated)
    }

    @Test
    fun aProofForOtherNoncesIsDenied() {
        val machine = handshake()
        machine.onFrame(PhoneFrame.Hello(tvId, keyId, clientNonce))
        assertEquals(LibraryHandshake.Step.Deny, machine.onFrame(PhoneFrame.Proof(serverNonce, serverNonce, clientProof)))
    }

    @Test
    fun aProofBeforeAnyHelloIsDenied() {
        assertEquals(LibraryHandshake.Step.Deny, handshake().onFrame(PhoneFrame.Proof(clientNonce, serverNonce, clientProof)))
    }

    @Test
    fun aSecondHelloIsDenied() {
        val machine = handshake()
        machine.onFrame(PhoneFrame.Hello(tvId, keyId, clientNonce))
        assertEquals(LibraryHandshake.Step.Deny, machine.onFrame(PhoneFrame.Hello(tvId, keyId, clientNonce)))
    }

    @Test
    fun unknownTypesAreIgnoredAndMalformedOrPostAuthFramesAreViolations() {
        assertEquals(LibraryHandshake.Step.Ignore, handshake().onFrame(PhoneFrame.Unknown("later")))
        assertEquals(LibraryHandshake.Step.Violation, handshake().onFrame(PhoneFrame.Malformed))
        assertEquals(LibraryHandshake.Step.Violation, handshake().onFrame(PhoneFrame.CatEnd("AAAAAAAAAAAAAAAAAAAAAA")))
    }

    @Test
    fun aKeyTheStoreCannotDecodeIsDenied() {
        val machine = LibraryHandshake(tvId, "192.168.1.20", "192.168.1.30", 42421, { _, _ -> "not base64 !!" }, { serverNonce })
        machine.onFrame(PhoneFrame.Hello(tvId, keyId, clientNonce))
        assertEquals(LibraryHandshake.Step.Deny, machine.onFrame(PhoneFrame.Proof(clientNonce, serverNonce, clientProof)))
    }

    // --- Play (§6.1) -------------------------------------------------------------

    @Test
    fun playWhileOfflineFailsAtOnce() {
        val machine = LibraryPlayMachine()
        assertFalse(machine.request("item0000001", connected = false, reqId = REQ, nowMs = 0L))
        assertEquals(LibraryPlay.Failed("item0000001", LibraryPlayFailure.OFFLINE), machine.state)
        assertNull(machine.deadlineAtMs)
    }

    @Test
    fun noAckWithinEightSecondsIsNoAnswer() {
        val machine = LibraryPlayMachine()
        assertTrue(machine.request("item0000001", true, REQ, 1_000L))
        assertEquals(LibraryPlay.Asking("item0000001"), machine.state)
        assertEquals(9_000L, machine.deadlineAtMs)
        machine.tick(8_999L)
        assertEquals(LibraryPlay.Asking("item0000001"), machine.state)
        machine.tick(9_000L)
        assertEquals(LibraryPlay.Failed("item0000001", LibraryPlayFailure.NO_ANSWER), machine.state)
    }

    @Test
    fun anAcceptedPlayWaitsThirtySecondsForTheCast() {
        val machine = LibraryPlayMachine()
        machine.request("item0000001", true, REQ, 0L)
        assertTrue(machine.onAck(REQ, ok = true, refusal = null, nowMs = 1_000L))
        assertEquals(LibraryPlay.Starting("item0000001"), machine.state)
        assertEquals(31_000L, machine.deadlineAtMs)
        machine.tick(30_999L)
        assertEquals(LibraryPlay.Starting("item0000001"), machine.state)
        machine.tick(31_000L)
        assertEquals(LibraryPlay.Failed("item0000001", LibraryPlayFailure.NOT_STARTED), machine.state)
    }

    @Test
    fun theCastStartingEndsTheRequest() {
        val machine = LibraryPlayMachine()
        machine.request("item0000001", true, REQ, 0L)
        machine.onAck(REQ, true, null, 0L)
        machine.onCastStarted()
        assertEquals(LibraryPlay.Idle, machine.state)
        assertNull(machine.deadlineAtMs)
    }

    @Test
    fun aCastOvertakingItsOwnAckStillEndsTheRequest() {
        val machine = LibraryPlayMachine()
        machine.request("item0000001", true, REQ, 0L)
        machine.onCastStarted()
        assertEquals(LibraryPlay.Asking("item0000001"), machine.state)
        machine.onAck(REQ, true, null, 500L)
        assertEquals(LibraryPlay.Idle, machine.state)
    }

    @Test
    fun refusalsMapToTheirReasonsAndAnUnknownCodeReadsAsUnavailable() {
        for ((code, reason) in listOf(
            "missing" to LibraryPlayFailure.MISSING,
            "disabled" to LibraryPlayFailure.DISABLED,
            "busy" to LibraryPlayFailure.BUSY,
            "unavailable" to LibraryPlayFailure.UNAVAILABLE,
            "background" to LibraryPlayFailure.BACKGROUND,
            "later" to LibraryPlayFailure.UNAVAILABLE,
        )) {
            val machine = LibraryPlayMachine()
            machine.request("item0000001", true, REQ, 0L)
            machine.onAck(REQ, false, PlayRefusal.of(code), 0L)
            assertEquals(code, LibraryPlay.Failed("item0000001", reason), machine.state)
        }
    }

    @Test
    fun aSecondPlayWhileOneIsOutstandingIsIgnored() {
        val machine = LibraryPlayMachine()
        machine.request("item0000001", true, REQ, 0L)
        assertFalse(machine.request("item0000002", true, OTHER_REQ, 10L))
        assertEquals(LibraryPlay.Asking("item0000001"), machine.state)
        machine.onAck(REQ, true, null, 20L)
        assertFalse(machine.request("item0000002", true, OTHER_REQ, 30L))
        assertEquals(LibraryPlay.Starting("item0000001"), machine.state)
    }

    @Test
    fun anAckForAnotherRequestIsIgnored() {
        val machine = LibraryPlayMachine()
        machine.request("item0000001", true, REQ, 0L)
        assertFalse(machine.onAck(OTHER_REQ, true, null, 0L))
        assertEquals(LibraryPlay.Asking("item0000001"), machine.state)
    }

    @Test
    fun aFailureClearsAndAllowsANewRequest() {
        val machine = LibraryPlayMachine()
        machine.request("item0000001", false, REQ, 0L)
        assertTrue(machine.request("item0000002", true, OTHER_REQ, 0L))
        machine.onAck(OTHER_REQ, false, PlayRefusal.BUSY, 0L)
        machine.clearResult()
        assertEquals(LibraryPlay.Idle, machine.state)
    }

    @Test
    fun losingTheSocketFailsOnlyARequestStillAsking() {
        val asking = LibraryPlayMachine()
        asking.request("item0000001", true, REQ, 0L)
        asking.onLinkLost()
        assertEquals(LibraryPlay.Failed("item0000001", LibraryPlayFailure.OFFLINE), asking.state)

        val starting = LibraryPlayMachine()
        starting.request("item0000001", true, REQ, 0L)
        starting.onAck(REQ, true, null, 0L)
        starting.onLinkLost()
        assertEquals(LibraryPlay.Starting("item0000001"), starting.state)
    }

    // --- Thumbnails (§6.4) -------------------------------------------------------

    @Test
    fun atMostSixAreOutstandingAndTheNewestGoesFirst() {
        val queue = ThumbnailQueue()
        (1..8).forEach { queue.want("t$it", 0L) }
        assertEquals(listOf("t8", "t7", "t6", "t5", "t4", "t3"), queue.pump(0L))
        assertEquals(emptyList<String>(), queue.pump(0L))
        assertTrue(queue.onArrived("t8"))
        assertEquals(listOf("t2"), queue.pump(0L))
    }

    @Test
    fun aRepeatedWantMovesToTheFrontAndAnOutstandingOneIsNotAskedTwice() {
        val queue = ThumbnailQueue(maxOutstanding = 1)
        queue.want("a", 0L)
        queue.want("b", 0L)
        queue.want("a", 0L)
        assertEquals(listOf("a"), queue.pump(0L))
        queue.want("a", 0L)
        assertEquals(emptyList<String>(), queue.pump(0L))
        assertFalse(queue.onArrived("zzz"))
    }

    @Test
    fun aMissIsNeverAskedAgain() {
        val queue = ThumbnailQueue()
        queue.want("a", 0L)
        queue.pump(0L)
        queue.onMiss("a")
        queue.want("a", 0L)
        assertEquals(emptyList<String>(), queue.pump(0L))
        assertTrue(queue.isMissed("a"))
    }

    @Test
    fun aDroppedRequestExpiresAndFreesItsSlot() {
        val queue = ThumbnailQueue(maxOutstanding = 1, expiryMs = 15_000L)
        queue.want("a", 0L)
        queue.pump(0L)
        queue.want("b", 1_000L)
        assertEquals(emptyList<String>(), queue.pump(14_999L))
        assertEquals(listOf("b"), queue.pump(15_000L))
        assertFalse(queue.onArrived("a"))
    }

    @Test
    fun inFlightRequestsReturnToTheQueueWhenTheSocketCloses() {
        val queue = ThumbnailQueue()
        queue.want("a", 0L)
        queue.want("b", 0L)
        assertEquals(listOf("b", "a"), queue.pump(0L))
        queue.want("c", 0L)
        queue.onDisconnect()
        assertEquals(0, queue.outstandingCount)
        assertEquals(listOf("b", "a", "c"), queue.pump(0L))
    }

    @Test
    fun theQueueIsBounded() {
        val queue = ThumbnailQueue(maxOutstanding = 1, maxQueued = 3)
        (1..5).forEach { queue.want("t$it", 0L) }
        assertEquals(listOf("t5"), queue.pump(0L))
        queue.onArrived("t5")
        assertEquals(listOf("t4"), queue.pump(0L))
        queue.onArrived("t4")
        assertEquals(listOf("t3"), queue.pump(0L))
        queue.onArrived("t3")
        assertEquals(emptyList<String>(), queue.pump(0L))
    }

    // --- Rate gates (§6.5) -------------------------------------------------------

    @Test
    fun aRateGatePassesOncePerInterval() {
        val gate = LibraryRateGate(10_000L)
        assertTrue(gate.tryAcquire(5_000L))
        assertFalse(gate.tryAcquire(14_999L))
        assertTrue(gate.tryAcquire(15_000L))
        assertFalse(gate.tryAcquire(15_001L))
    }

    // --- Casting (§6.6) ----------------------------------------------------------

    @Test
    fun theCastingMapKeepsTheLastEight() {
        val map = CastingMap()
        (1..10).forEach { map.put("cast$it", "item$it") }
        assertEquals(8, map.size)
        assertNull(map["cast1"])
        assertNull(map["cast2"])
        assertEquals("item3", map["cast3"])
        assertEquals("item10", map["cast10"])
    }

    @Test
    fun aRepeatedCastIdIsRefreshedNotDuplicated() {
        val map = CastingMap(capacity = 2)
        map.put("a", "1")
        map.put("b", "2")
        map.put("a", "3")
        map.put("c", "4")
        assertNull(map["b"])
        assertEquals("3", map["a"])
        assertEquals("4", map["c"])
    }

    // --- Seasons (§6.3) ----------------------------------------------------------

    @Test
    fun aSeasonIsPublishedOnlyOnceEveryPartIsHere() {
        val assembler = SeasonAssembler()
        val key = LibrarySeasonKey("tv:1", 2)
        val e1 = LibraryEpisodeInfo(1, "One", null, null)
        val e2 = LibraryEpisodeInfo(2, "Two", null, null)
        assertNull(assembler.add(key, 1, 2, listOf(e2)))
        assertEquals(listOf(e1, e2), assembler.add(key, 0, 2, listOf(e1)))
    }

    @Test
    fun aPartCountChangeRestartsTheSeason() {
        val assembler = SeasonAssembler()
        val key = LibrarySeasonKey("tv:1", 2)
        val e1 = LibraryEpisodeInfo(1, "One", null, null)
        assertNull(assembler.add(key, 0, 3, listOf(e1)))
        assertEquals(listOf(e1), assembler.add(key, 0, 1, listOf(e1)))
    }

    @Test
    fun anEmptySeasonIsComplete() {
        assertEquals(emptyList<LibraryEpisodeInfo>(), SeasonAssembler().add(LibrarySeasonKey("tv:1", 1), 0, 1, emptyList()))
    }

    // --- Up next (§10) -----------------------------------------------------------

    private val e1 = ep("s01e01xxxxx", 1, 1)
    private val e2 = ep("s01e02xxxxx", 1, 2)
    private val catalog = LibraryCatalog("AAAAAAAAAAAAAAAAAAAAAA", listOf(e1, e2, film("film0000001")))

    @Test
    fun anEndedEpisodeWithANextOneOffersItWithAutoplay() {
        val offer = UpNextPolicy.offer(e1.id, catalog, connected = true, autoplayEnabled = true, streak = UpNextStreak(), nowMs = 100L)
        assertNotNull(offer)
        assertEquals(e1, offer!!.ended)
        assertEquals(e2, offer.next)
        assertEquals(10_100L, offer.autoplayAtMs)
        assertFalse(offer.stillWatching)
    }

    @Test
    fun autoplayOffLeavesTheCardWithoutACountdown() {
        val offer = UpNextPolicy.offer(e1.id, catalog, true, autoplayEnabled = false, streak = UpNextStreak(), nowMs = 0L)!!
        assertNull(offer.autoplayAtMs)
        assertFalse(offer.stillWatching)
    }

    @Test
    fun threeAutoplaysWithoutAKeyAskStillWatching() {
        val streak = UpNextStreak()
        repeat(3) { streak.autoplayed() }
        val offer = UpNextPolicy.offer(e1.id, catalog, true, true, streak, 0L)!!
        assertNull(offer.autoplayAtMs)
        assertTrue(offer.stillWatching)
        streak.viewerPressed()
        assertNotNull(UpNextPolicy.offer(e1.id, catalog, true, true, streak, 0L)!!.autoplayAtMs)
    }

    @Test
    fun noOfferForAFilmTheLastEpisodeAnUnknownCastOrALostLink() {
        assertNull(UpNextPolicy.offer("film0000001", catalog, true, true, UpNextStreak(), 0L))
        assertNull(UpNextPolicy.offer(e2.id, catalog, true, true, UpNextStreak(), 0L))
        assertNull(UpNextPolicy.offer(null, catalog, true, true, UpNextStreak(), 0L))
        assertNull(UpNextPolicy.offer("gone0000001", catalog, true, true, UpNextStreak(), 0L))
        assertNull(UpNextPolicy.offer(e1.id, catalog, connected = false, autoplayEnabled = true, streak = UpNextStreak(), nowMs = 0L))
        assertNull(UpNextPolicy.offer(e1.id, null, true, true, UpNextStreak(), 0L))
    }

    private companion object {
        const val REQ = "cmVxcmVxcmVxcmVxcmVxcg"
        const val OTHER_REQ = "b3RoZXJvdGhlcm90aGVybw"

        fun ep(id: String, s: Int, e: Int) = LibraryItem(
            id = id, name = "$id.mkv", title = id, sortKey = id, find = id, kind = LibraryItemKind.EPISODE,
            show = "Lantern", showKey = "tv:1", season = s, episode = e,
        )

        fun film(id: String) = LibraryItem(id = id, name = "$id.mkv", title = id, sortKey = id, find = id, kind = LibraryItemKind.FILM)
    }
}
