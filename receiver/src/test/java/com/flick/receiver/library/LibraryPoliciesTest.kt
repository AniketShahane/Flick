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
    fun theThirdIgnoredFrameBeforeAuthenticationIsAViolation() {
        val machine = handshake()
        assertEquals(LibraryHandshake.Step.Ignore, machine.onFrame(PhoneFrame.Unknown("later")))
        assertEquals(LibraryHandshake.Step.Ignore, machine.onFrame(PhoneFrame.Unknown("later")))
        assertEquals(LibraryHandshake.Step.Violation, machine.onFrame(PhoneFrame.Unknown("later")))
        assertEquals(LibraryHandshake.Step.Violation, machine.onFrame(PhoneFrame.Hello(tvId, keyId, clientNonce)))
    }

    @Test
    fun ignoredFramesBetweenHelloAndProofCountTowardsTheSameBudget() {
        val machine = handshake()
        machine.onFrame(PhoneFrame.Unknown("later"))
        assertTrue(machine.onFrame(PhoneFrame.Hello(tvId, keyId, clientNonce)) is LibraryHandshake.Step.Reply)
        assertEquals(LibraryHandshake.Step.Ignore, machine.onFrame(PhoneFrame.Unknown("later")))
        assertEquals(LibraryHandshake.Step.Violation, machine.onFrame(PhoneFrame.Unknown("later")))
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
    fun aCastSeenWhileAskingSettlesQuietlyWhenTheAckNeverComes() {
        val machine = LibraryPlayMachine()
        machine.request("item0000001", true, REQ, 0L)
        machine.onCastStarted()
        machine.tick(8_000L)
        assertEquals(LibraryPlay.Idle, machine.state)
        assertNull(machine.deadlineAtMs)

        val lost = LibraryPlayMachine()
        lost.request("item0000001", true, REQ, 0L)
        lost.onCastStarted()
        lost.onLinkLost()
        assertEquals(LibraryPlay.Idle, lost.state)
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
    fun aDroppedRequestExpiresFreesItsSlotAndWaitsBehindNewerOnes() {
        val queue = ThumbnailQueue(maxOutstanding = 1, expiryMs = 15_000L)
        queue.want("a", 0L)
        queue.pump(0L)
        queue.want("b", 1_000L)
        assertEquals(emptyList<String>(), queue.pump(14_999L))
        assertEquals(listOf("b"), queue.pump(15_000L))
        // Still wanted: a late frame for it is accepted, and it is not asked again.
        assertTrue(queue.onArrived("a"))
        assertTrue(queue.onArrived("b"))
        assertEquals(emptyList<String>(), queue.pump(15_000L))
    }

    @Test
    fun anExpiredRequestIsAskedAgainUpToItsAttemptLimit() {
        val queue = ThumbnailQueue(maxOutstanding = 1, expiryMs = 15_000L, maxAttempts = 2)
        queue.want("a", 0L)
        assertEquals(listOf("a"), queue.pump(0L))
        assertEquals(listOf("a"), queue.pump(15_000L))
        assertEquals(emptyList<String>(), queue.pump(30_000L))
        assertFalse(queue.onArrived("a"))
        assertNull(queue.nextPumpAtMs(30_000L))
        // A tile that asks again starts afresh.
        queue.want("a", 31_000L)
        assertEquals(listOf("a"), queue.pump(31_000L))
    }

    @Test
    fun sendsAreCappedAtTheRatePerWindowAndTheNextPumpIsWhenBudgetFrees() {
        val queue = ThumbnailQueue(maxOutstanding = 6, maxQueued = 100, maxSends = 60, sendWindowMs = 10_000L)
        (1..70).forEach { queue.want("t$it", 0L) }
        var sent = 0
        repeat(20) {
            val batch = queue.pump(1_000L)
            sent += batch.size
            batch.forEach { queue.onArrived(it) }
        }
        assertEquals(60, sent)
        assertEquals(11_000L, queue.nextPumpAtMs(1_000L))
        assertEquals(6, queue.pump(11_000L).size)
    }

    @Test
    fun theNextPumpIsWhenASlotExpiresAndNothingWhenIdle() {
        val queue = ThumbnailQueue(maxOutstanding = 1, expiryMs = 15_000L)
        assertNull(queue.nextPumpAtMs(0L))
        queue.want("a", 0L)
        queue.want("b", 0L)
        queue.pump(2_000L)
        assertEquals(17_000L, queue.nextPumpAtMs(2_000L))
        queue.onArrived("b")
        assertEquals(listOf("a"), queue.pump(3_000L))
        queue.onArrived("a")
        assertNull(queue.nextPumpAtMs(3_000L))
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
    fun aSeasonKeepsAtMostItsEpisodeCap() {
        val assembler = SeasonAssembler(maxEpisodes = 3)
        val key = LibrarySeasonKey("tv:1", 1)
        val eps = (1..4).map { LibraryEpisodeInfo(it, null, null, null) }
        assertNull(assembler.add(key, 0, 3, eps.take(2)))
        assertNull(assembler.add(key, 1, 3, eps.drop(2)))
        // A part sent again replaces its own share rather than adding to it.
        assertNull(assembler.add(key, 1, 3, eps.drop(2)))
        assertEquals(eps.take(3), assembler.add(key, 2, 3, eps))
        assertEquals(4_000, SeasonAssembler.MAX_EPISODES)
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

    // --- Session end -------------------------------------------------------------

    @Test
    fun aCloseFromThePhoneIsClosedNotLost() {
        for (code in listOf(1000, 1001, 1008)) {
            assertEquals(LibraryClose.PEER_CLOSED, LibraryClose.of(tvClosed = null, threw = false, closeCode = code))
        }
        assertFalse(LibraryClose.PEER_CLOSED.lost)
    }

    @Test
    fun aPingTimeoutIsLostWhetherOrNotIncomingClosedWithItsException() {
        assertEquals(LibraryClose.PING_TIMEOUT, LibraryClose.of(tvClosed = null, threw = true, closeCode = 1011))
        assertEquals(LibraryClose.PING_TIMEOUT, LibraryClose.of(tvClosed = null, threw = false, closeCode = 1011))
        assertTrue(LibraryClose.PING_TIMEOUT.lost)
        assertEquals("ping_timeout", LibraryClose.PING_TIMEOUT.wire)
    }

    @Test
    fun aSocketGoneWithoutACloseFrameIsLost() {
        assertEquals(LibraryClose.DROPPED, LibraryClose.of(tvClosed = null, threw = false, closeCode = 1006))
        assertEquals(LibraryClose.DROPPED, LibraryClose.of(tvClosed = null, threw = false, closeCode = null))
        assertEquals(LibraryClose.DROPPED, LibraryClose.of(tvClosed = null, threw = true, closeCode = 1006))
        assertEquals(LibraryClose.DROPPED, LibraryClose.of(tvClosed = null, threw = true, closeCode = null))
        assertTrue(LibraryClose.DROPPED.lost)
        assertTrue(LibraryClose.CANCELLED.lost)
    }

    @Test
    fun aSocketTheTvClosedIsNotLostUnlessThePhoneStoppedReading() {
        assertEquals(LibraryClose.SUPERSEDED, LibraryClose.of(tvClosed = "superseded", threw = false, closeCode = 1008))
        assertEquals(LibraryClose.FORGOTTEN, LibraryClose.of(tvClosed = "forgotten", threw = true, closeCode = null))
        assertEquals(LibraryClose.TV_CLOSED, LibraryClose.of(tvClosed = "other", threw = false, closeCode = 1011))
        assertFalse(LibraryClose.SUPERSEDED.lost)
        assertFalse(LibraryClose.FORGOTTEN.lost)
        assertFalse(LibraryClose.TV_CLOSED.lost)
        assertFalse(LibraryClose.VIOLATION.lost)
        assertEquals(LibraryClose.OUTBOX_FULL, LibraryClose.of(tvClosed = "outbox_full", threw = false, closeCode = 1008))
        assertTrue(LibraryClose.OUTBOX_FULL.lost)
    }

    // --- Credits lead -------------------------------------------------------------

    @Test
    fun theLeadIsTheClampedMedianWithThirtySecondsByDefault() {
        assertEquals(30_000L, CreditsLead.leadMs(emptyList()))
        assertEquals(60_000L, CreditsLead.leadMs(listOf(60_000L)))
        assertEquals(70_000L, CreditsLead.leadMs(listOf(60_000L, 80_000L)))
        assertEquals(90_000L, CreditsLead.leadMs(listOf(170_000L, 6_000L, 90_000L)))
        assertEquals(20_000L, CreditsLead.leadMs(listOf(6_000L, 8_000L, 10_000L)))
        assertEquals(180_000L, CreditsLead.leadMs(listOf(180_000L, 180_000L, 179_000L).map { it + 5_000L }))
    }

    @Test
    fun theLeadIsCappedInsideThePhonesFinishZone() {
        val minute = 60_000L
        assertEquals(63_000L, CreditsLead.effectiveLeadMs(180_000L, 22 * minute))
        assertEquals(132_000L, CreditsLead.effectiveLeadMs(180_000L, 45 * minute))
        assertEquals(27_000L, CreditsLead.effectiveLeadMs(180_000L, 10 * minute))
        assertEquals(27_000L, CreditsLead.effectiveLeadMs(30_000L, 0L))
        // Under the cap the learned lead stands.
        assertEquals(40_000L, CreditsLead.effectiveLeadMs(40_000L, 22 * minute))
        assertEquals(20_000L, CreditsLead.effectiveLeadMs(20_000L, 10 * minute))
    }

    @Test
    fun aPhoneWithNoTimesCountsOnlyTheLast30Seconds() {
        val minute = 60_000L
        assertEquals(177_000L, CreditsLead.capMs(60 * minute, phoneHasTimes = true))
        assertEquals(27_000L, CreditsLead.capMs(60 * minute, phoneHasTimes = false))
        assertEquals(27_000L, CreditsLead.capMs(10 * minute, phoneHasTimes = false))
        assertEquals(27_000L, CreditsLead.effectiveLeadMs(180_000L, 60 * minute, phoneHasTimes = false))
        assertEquals(27_000L, CreditsLead.effectiveLeadMs(30_000L, 22 * minute, phoneHasTimes = false))
        assertEquals(20_000L, CreditsLead.effectiveLeadMs(20_000L, 22 * minute, phoneHasTimes = false))
    }

    @Test
    fun onlyALeaveInTheLastStretchIsASample() {
        val hour = 3_600_000L
        assertEquals(60_000L, CreditsLead.sample(hour - 60_000L, hour))
        assertEquals(5_000L, CreditsLead.sample(hour - 5_000L, hour))
        assertEquals(180_000L, CreditsLead.sample(hour - 180_000L, hour))
        assertNull(CreditsLead.sample(hour - 4_999L, hour))
        assertNull(CreditsLead.sample(hour - 180_001L, hour))
        assertNull(CreditsLead.sample(hour, hour))
        assertNull(CreditsLead.sample(0L, 0L))
        // A short episode: 85 % is the binding limit, not the three minutes.
        val short = 600_000L
        assertEquals(90_000L, CreditsLead.sample(510_000L, short))
        assertNull(CreditsLead.sample(509_999L, short))
    }

    @Test
    fun theBookKeepsThreeSamplesPerShowUnderAHashAndTheNewestShows() {
        val book = CreditsLeadBook(maxShows = 2)
        book.record("tv:1", 40_000L)
        book.record("tv:1", 50_000L)
        book.record("tv:1", 60_000L)
        book.record("tv:1", 90_000L)
        assertEquals(listOf(50_000L, 60_000L, 90_000L), book.samples("tv:1"))
        assertEquals(60_000L, book.leadMs("tv:1"))
        assertEquals(30_000L, book.leadMs("tv:2"))
        val encoded = book.encode()
        assertFalse(encoded.contains("tv:"))
        assertTrue(encoded.startsWith(CreditsLeadBook.hash("tv:1") + ":"))
        assertTrue(Regex("[0-9a-f]{16}").matches(CreditsLeadBook.hash("name:The Lantern Coast")))

        book.record("tv:2", 30_000L)
        book.record("tv:3", 30_000L)
        assertEquals(2, book.size)
        assertTrue(book.samples("tv:1").isEmpty())

        val restored = CreditsLeadBook()
        restored.decode(book.encode() + ";garbage;0123456789abcdef:1,x,99999999")
        assertEquals(listOf(30_000L), restored.samples("tv:3"))
        assertEquals(2, restored.size)
        restored.decode(null)
        assertEquals(0, restored.size)
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
