package com.flick.sender.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TvDoorbellTest {

    // Library contract §13 fixtures, shared byte for byte with the TV.
    private val key = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    private val tvId = "EREREREREREREREREREREQ"
    private val keyId = "IiIiIiIiIiIiIiIiIiIiIg"
    private val phoneNonce = "MzMzMzMzMzMzMzMzMzMzMw"
    private val tvNonce = "RERERERERERERERERERERA"
    private val host = "192.168.1.20"
    private val port = 47670
    private val fixtureProof = "DdZzClsQ82tZwVfbbFKIaLEbTklR8zZpwnZFxMX4_JA"

    private fun knock(
        proof: String = fixtureProof,
        echo: String = phoneNonce,
        nonce: String = tvNonce,
        id: String = tvId,
    ) = """{"t":"knock","v":1,"tvId":"$id","keyId":"$keyId","phoneNonce":"$echo","tvNonce":"$nonce","proof":"$proof"}"""

    private val keys: (String, String) -> String? = { t, k -> key.takeIf { t == tvId && k == keyId } }

    @Test fun fixtureProofIsPinned() {
        assertEquals(fixtureProof, KnockProtocol.proof(key, tvId, keyId, phoneNonce, tvNonce, host, port))
    }

    @Test fun transcriptIsLengthPrefixedAndDomainSeparated() {
        val bytes = KnockProtocol.transcript(tvId, keyId, phoneNonce, tvNonce, host, port)
        assertEquals(listOf<Byte>(0, 0, 0, 14), bytes.take(4))
        assertEquals("Flick-Knock-V1", String(bytes, 4, 14, StandardCharsets.UTF_8))
        assertEquals(listOf<Byte>(0, 0, 0, 2), bytes.drop(18).take(4))
        assertEquals("tv", String(bytes, 22, 2, StandardCharsets.UTF_8))
        assertTrue(String(bytes, StandardCharsets.UTF_8).endsWith("47670"))
    }

    @Test fun framesArePinned() {
        assertEquals("""{"t":"knockHello","v":1,"phoneNonce":"$phoneNonce"}""", KnockProtocol.hello(phoneNonce))
        assertEquals("""{"t":"knockOk","v":1}""", KnockProtocol.ok())
        assertEquals("""{"t":"knockAt","v":1,"port":47670}""", LibraryFrames.knockAt(47670))
    }

    @Test fun theFixtureKnockVerifies() {
        assertEquals(
            KnockProtocol.Verdict.Valid(tvId, keyId),
            KnockProtocol.verify(knock(), phoneNonce, host, port, keys),
        )
    }

    @Test fun theProofBindsTheAddressTheTvDialed() {
        assertEquals(KnockProtocol.Verdict.Invalid, KnockProtocol.verify(knock(), phoneNonce, "192.168.1.21", port, keys))
        assertEquals(KnockProtocol.Verdict.Invalid, KnockProtocol.verify(knock(), phoneNonce, host, 47671, keys))
    }

    @Test fun aKnockFailsOnEveryOtherMismatch() {
        val other = "AAAAAAAAAAAAAAAAAAAAAA"
        assertEquals(KnockProtocol.Verdict.Invalid, KnockProtocol.verify(knock(), other, host, port, keys))
        assertEquals(KnockProtocol.Verdict.Invalid, KnockProtocol.verify(knock(nonce = phoneNonce), phoneNonce, host, port, keys))
        assertEquals(KnockProtocol.Verdict.Invalid, KnockProtocol.verify(knock(id = other), phoneNonce, host, port, keys))
        val flipped = fixtureProof.replaceRange(0, 1, "E")
        assertEquals(KnockProtocol.Verdict.Invalid, KnockProtocol.verify(knock(proof = flipped), phoneNonce, host, port, keys))
        assertEquals(KnockProtocol.Verdict.Invalid, KnockProtocol.verify(knock(), phoneNonce, host, port) { _, _ -> null })
    }

    @Test fun theReaderIsStrict() {
        assertTrue(KnockProtocol.parseKnock(knock()) != null)
        assertNull(KnockProtocol.parseKnock(knock().replace("\"v\":1", "\"v\":2")))
        assertNull(KnockProtocol.parseKnock(knock().replace("\"t\":\"knock\"", "\"t\":\"knockOk\"")))
        assertNull(KnockProtocol.parseKnock(knock().replace("\"v\":1", "\"v\":1.0")))
        assertNull(KnockProtocol.parseKnock(knock().dropLast(1) + ",\"tvId\":\"$tvId\"}"))
        assertNull(KnockProtocol.parseKnock(knock() + " x"))
        assertNull(KnockProtocol.parseKnock(knock(proof = fixtureProof.dropLast(1))))
        assertNull(KnockProtocol.parseKnock("[]"))
        assertNull(KnockProtocol.parseKnock(knock().dropLast(1) + ",\"pad\":\"${"a".repeat(400)}\"}"))
        assertTrue(KnockProtocol.parseKnock(knock().dropLast(1) + ",\"extra\":1}") != null)
    }

    @Test fun linesAreBoundedAndUtf8() {
        fun read(bytes: ByteArray) = KnockProtocol.readLine(ByteArrayInputStream(bytes))
        assertEquals("abc", read("abc\nrest".toByteArray()))
        assertNull(read("abc".toByteArray()))
        assertEquals("a".repeat(512), read(("a".repeat(512) + "\n").toByteArray()))
        assertNull(read(("a".repeat(513) + "\n").toByteArray()))
        assertNull(read(byteArrayOf(0xC3.toByte(), 0x28, '\n'.code.toByte())))
    }

    @Test fun theReaderGivesUpWhenTheDeadlineSaysSo() {
        val endless = object : InputStream() {
            override fun read(): Int = 'a'.code
        }
        var reads = 0
        val result = runCatching {
            KnockProtocol.readLine(endless) { if (++reads > 3) throw java.net.SocketTimeoutException() }
        }
        assertTrue(result.isFailure)
    }

    @Test fun admissionIsOnePerFiveSecondsAndSixPerMinutePerPeer() {
        val gate = KnockGate()
        val peer = "192.168.1.30"
        assertTrue(gate.tryAdmit(peer, 0))
        assertFalse(gate.tryAdmit(peer, 4_999))
        assertTrue(gate.tryAdmit(peer, 5_000))
        for (t in listOf(10_000L, 15_000L, 20_000L, 25_000L)) assertTrue(gate.tryAdmit(peer, t))
        assertFalse(gate.tryAdmit(peer, 30_000))
        assertFalse(gate.tryAdmit(peer, 59_999))
        assertTrue(gate.tryAdmit(peer, 60_000))
    }

    @Test fun oneBusyPeerCannotLockTheTvOut() {
        val gate = KnockGate()
        val noisy = "192.168.1.66"
        var t = 0L
        while (t < 60_000L) {
            gate.tryAdmit(noisy, t)
            t += 5_000L
        }
        assertTrue(gate.tryAdmit("192.168.1.20", 59_000))
        // A refusal for one peer is not spent against another.
        assertFalse(gate.tryAdmit(noisy, 59_500))
        assertTrue(gate.tryAdmit("192.168.1.21", 59_500))
    }

    @Test fun admissionsAcrossAllPeersAreCappedPerMinute() {
        val gate = KnockGate()
        for (i in 0 until KnockPolicy.GLOBAL_PER_MINUTE) assertTrue(gate.tryAdmit("10.0.0.${i + 1}", i.toLong()))
        assertFalse(gate.tryAdmit("10.0.1.1", 1_000))
        assertTrue(gate.tryAdmit("10.0.1.1", 60_000))
    }

    @Test fun theListenerBindsOnlyWhereAPairedTvLives() {
        val home = DoorbellBinding.Address("192.168.1.30", 24)
        assertEquals("192.168.1.30", DoorbellBinding.listenAddress(listOf(home), listOf("192.168.1.20")))
        assertNull(DoorbellBinding.listenAddress(listOf(home), listOf("192.168.2.20")))
        assertNull(DoorbellBinding.listenAddress(listOf(home), emptyList()))
        // A public network's private range that happens to hold no paired TV hears nothing.
        assertNull(DoorbellBinding.listenAddress(listOf(DoorbellBinding.Address("10.20.0.5", 16)), listOf("192.168.1.20")))
        assertEquals(
            "10.20.0.5",
            DoorbellBinding.listenAddress(listOf(DoorbellBinding.Address("10.20.0.5", 16)), listOf("10.20.200.9")),
        )
        assertTrue(DoorbellBinding.sameSubnet(DoorbellBinding.Address("172.16.5.1", 20), "172.16.15.254"))
        assertFalse(DoorbellBinding.sameSubnet(DoorbellBinding.Address("172.16.5.1", 20), "172.16.16.1"))
        // Nonsense prefixes and anything not private never qualify.
        assertFalse(DoorbellBinding.sameSubnet(DoorbellBinding.Address("192.168.1.30", 0), "192.168.1.20"))
        assertFalse(DoorbellBinding.sameSubnet(DoorbellBinding.Address("192.168.1.30", 33), "192.168.1.20"))
        assertFalse(DoorbellBinding.sameSubnet(DoorbellBinding.Address("8.8.8.8", 8), "8.8.4.4"))
        assertFalse(DoorbellBinding.sameSubnet(home, "192.168.1.020"))
    }

    @Test fun aTvStoredAtThePhonesOwnAddressStillQualifies() {
        // DHCP swapped the two: the phone now holds the TV's old address, and the TV's knock
        // from its new one is exactly what the knock-host fallback is for.
        val phone = DoorbellBinding.Address("192.168.1.30", 24)
        assertTrue(DoorbellBinding.sameSubnet(phone, "192.168.1.30"))
        assertEquals("192.168.1.30", DoorbellBinding.listenAddress(listOf(phone), listOf("192.168.1.30")))
    }

    @Test fun rateRefusalsAreLoggedOncePerMinuteWithACount() {
        val log = ThrottledLog()
        val rate = "knock refused reason=rate"
        assertEquals(rate, log.line(0, rate))
        for (t in 1L..500L) assertNull(log.line(t, rate))
        assertNull(log.line(KnockPolicy.MINUTE_MS - 1, rate))
        assertEquals("$rate unlogged=501", log.line(KnockPolicy.MINUTE_MS, rate))
        assertNull(log.line(KnockPolicy.MINUTE_MS + 1, rate))
        assertEquals("$rate unlogged=1", log.line(3 * KnockPolicy.MINUTE_MS, rate))
        assertEquals(rate, log.line(5 * KnockPolicy.MINUTE_MS, rate))
    }

    @Test fun losingTheBoundNetworkMovesTheListenerToAnotherThatQualifies() {
        val bound = DoorbellBinding.Choice("a", "192.168.1.30")
        assertEquals(bound, DoorbellBinding.choose(bound, linkedMapOf("b" to "192.168.1.31", "a" to "192.168.1.30")))
        assertEquals(
            DoorbellBinding.Choice("a", "192.168.1.40"),
            DoorbellBinding.choose(bound, linkedMapOf("b" to "192.168.1.31", "a" to "192.168.1.40")),
        )
        assertEquals(
            DoorbellBinding.Choice("b", "192.168.1.31"),
            DoorbellBinding.choose(bound, linkedMapOf("a" to null, "b" to "192.168.1.31")),
        )
        assertEquals(
            DoorbellBinding.Choice("c", "192.168.1.32"),
            DoorbellBinding.choose(bound, linkedMapOf("b" to null, "c" to "192.168.1.32")),
        )
        assertNull(DoorbellBinding.choose(bound, linkedMapOf("b" to null)))
        assertNull(DoorbellBinding.choose<String>(null, emptyMap()))
    }

    @Test fun acceptFailuresBackOffBoundedly() {
        assertEquals(KnockPolicy.ACCEPT_BACKOFF_FIRST_MS, KnockPolicy.acceptBackoffMs(0))
        assertEquals(200L, KnockPolicy.acceptBackoffMs(100))
        assertEquals(KnockPolicy.ACCEPT_BACKOFF_MAX_MS, KnockPolicy.acceptBackoffMs(800))
        assertEquals(KnockPolicy.ACCEPT_BACKOFF_MAX_MS, KnockPolicy.acceptBackoffMs(KnockPolicy.ACCEPT_BACKOFF_MAX_MS))
    }

    @Test fun onlyPrivateIpv4PeersAreAllowed() {
        listOf("10.0.0.2", "172.16.0.1", "172.31.255.255", "192.168.1.30").forEach { assertTrue(it, KnockPolicy.peerAllowed(it)) }
        listOf(null, "127.0.0.1", "8.8.8.8", "172.32.0.1", "169.254.1.1", "::1", "fe80::1", "192.168.01.1")
            .forEach { assertFalse("$it", KnockPolicy.peerAllowed(it)) }
    }

    @Test fun knockAtGoesOutOncePerPortPerSocket() {
        assertEquals(47670, KnockPolicy.knockAtPort(47670, null))
        assertNull(KnockPolicy.knockAtPort(47670, 47670))
        assertEquals(47671, KnockPolicy.knockAtPort(47671, 47670))
        assertNull(KnockPolicy.knockAtPort(null, null))
        assertNull(KnockPolicy.knockAtPort(0, null))
    }

    @Test fun aValidKnockDoesTheLeastThatBringsTheLibraryBack() {
        fun action(
            cast: Boolean = false,
            control: KnockControl = KnockControl.NONE,
            library: Boolean = false,
            dialing: Boolean = false,
            alive: Boolean = true,
        ) = KnockResponse.action(
            tvId = tvId,
            castLive = cast,
            knockResumingTv = null,
            control = control,
            libraryOpen = library,
            dialing = dialing,
            visible = false,
            controlAlive = alive,
        )
        assertEquals(KnockAction.RESUME, action())
        assertEquals(KnockAction.CAST_LIVE, action(cast = true, control = KnockControl.THAT_TV, library = true))
        assertEquals(KnockAction.CAST_LIVE, action(cast = true, control = KnockControl.OTHER_TV))
        assertEquals(KnockAction.BUSY, action(dialing = true))
        // Linked and answering: the library socket the phone believes open is redialled.
        assertEquals(KnockAction.LINKED, action(control = KnockControl.THAT_TV, library = true))
        assertEquals(KnockAction.LIBRARY, action(control = KnockControl.THAT_TV))
        // Linked but dead: the sockets a powered-off TV left behind give way to a resume.
        assertEquals(KnockAction.RESUME, action(control = KnockControl.THAT_TV, library = true, alive = false))
        assertEquals(KnockAction.RESUME, action(control = KnockControl.THAT_TV, alive = false))
        // Another TV's session in use is not taken over; a dead one is.
        assertEquals(KnockAction.BUSY, action(control = KnockControl.OTHER_TV))
        assertEquals(KnockAction.RESUME, action(control = KnockControl.OTHER_TV, alive = false))
        assertEquals(KnockAction.BUSY, action(control = KnockControl.OTHER_TV, alive = false, dialing = true))
    }

    @Test fun aKnockDuringAnEarlierKnocksResumeLeavesThatResumeToAnswerIt() {
        // The earlier resume is the dial in flight: the later knock from the same TV must not
        // read it as busy and let the wake lock go under it.
        for (control in KnockControl.values()) {
            for (alive in listOf(false, true)) {
                assertEquals(
                    KnockAction.RESUMING,
                    KnockResponse.action(
                        tvId = tvId, castLive = false, knockResumingTv = tvId, control = control, libraryOpen = false,
                        dialing = true, visible = false, controlAlive = alive,
                    ),
                )
            }
        }
        assertEquals(
            KnockAction.CAST_LIVE,
            KnockResponse.action(
                tvId = tvId, castLive = true, knockResumingTv = tvId, control = KnockControl.NONE, libraryOpen = false,
                dialing = true, visible = false, controlAlive = false,
            ),
        )
        assertEquals(KnockAction.RESUMING.journal, "resuming")
        assertFalse(KnockResponse.needsLivenessCheck(false, tvId, KnockControl.THAT_TV, visible = false))
    }

    @Test fun aKnockFromAnotherTvDuringAKnocksResumeIsBusy() {
        // Nothing would ever dial the second TV, so it must not keep the wake lock.
        val other = "T".repeat(22)
        for (control in KnockControl.values()) {
            for (alive in listOf(false, true)) {
                assertEquals(
                    KnockAction.BUSY,
                    KnockResponse.action(
                        tvId = other, castLive = false, knockResumingTv = tvId, control = control, libraryOpen = false,
                        dialing = true, visible = false, controlAlive = alive,
                    ),
                )
            }
        }
        assertFalse(KnockResponse.needsLivenessCheck(false, tvId, KnockControl.OTHER_TV, visible = false))
        assertTrue(KnockResponse.needsLivenessCheck(false, null, KnockControl.OTHER_TV, visible = false))
    }

    @Test fun withTheAppInFrontControlOnAnotherTvIsNeverTakenOver() {
        fun action(alive: Boolean, visible: Boolean) = KnockResponse.action(
            tvId = tvId, castLive = false, knockResumingTv = null, control = KnockControl.OTHER_TV, libraryOpen = false,
            dialing = false, visible = visible, controlAlive = alive,
        )
        assertEquals(KnockAction.BUSY, action(alive = false, visible = true))
        assertEquals(KnockAction.BUSY, action(alive = true, visible = true))
        assertEquals(KnockAction.RESUME, action(alive = false, visible = false))
        // That TV's own dead line still gives way while the app is in front.
        assertEquals(
            KnockAction.RESUME,
            KnockResponse.action(
                tvId = tvId, castLive = false, knockResumingTv = null, control = KnockControl.THAT_TV, libraryOpen = true,
                dialing = false, visible = true, controlAlive = false,
            ),
        )
    }

    @Test fun onlyAControlLineUpNeedsALivenessCheck() {
        fun check(
            cast: Boolean = false,
            resuming: String? = null,
            control: KnockControl,
            visible: Boolean = false,
        ) = KnockResponse.needsLivenessCheck(cast, resuming, control, visible)
        assertFalse(check(control = KnockControl.NONE))
        assertTrue(check(control = KnockControl.THAT_TV))
        assertTrue(check(control = KnockControl.OTHER_TV))
        assertFalse(check(cast = true, control = KnockControl.OTHER_TV))
        assertFalse(check(resuming = tvId, control = KnockControl.THAT_TV))
        // Visible with another TV's control: busy whatever the ping would say, so no ping.
        assertFalse(check(control = KnockControl.OTHER_TV, visible = true))
        assertTrue(check(control = KnockControl.THAT_TV, visible = true))
    }

    @Test fun readyModeNeedsEveryCondition() {
        assertTrue(TvReadyMode.holds(true, true, true, true, false))
        assertFalse(TvReadyMode.holds(false, true, true, true, false))
        assertFalse(TvReadyMode.holds(true, false, true, true, false))
        assertFalse(TvReadyMode.holds(true, true, false, true, false))
        assertFalse(TvReadyMode.holds(true, true, true, false, false))
        assertFalse(TvReadyMode.holds(true, true, true, true, true))
    }

    @Test fun readyModeIgnoresControlLossAndHangsUpOnceWhenIdle() {
        val window = TvReadyWindowPolicy()
        window.opened(0, ready = true)
        window.controlLost(1_000)
        assertEquals(TvReadyWindowPolicy.WINDOW_MS, window.deadlineMs())
        assertEquals(
            TvReadyWindowPolicy.Expiry.TOUCH,
            window.onExpired(TvReadyWindowPolicy.WINDOW_MS, castLive = false, dialInFlight = false, visible = true),
        )
        assertEquals(2 * TvReadyWindowPolicy.WINDOW_MS, window.deadlineMs())
        assertEquals(
            TvReadyWindowPolicy.Expiry.HANG_UP,
            window.onExpired(2 * TvReadyWindowPolicy.WINDOW_MS, castLive = false, dialInFlight = false, visible = false),
        )
        assertNull(window.deadlineMs())
        assertTrue(window.open)
        window.touch(5)
        assertEquals(5 + TvReadyWindowPolicy.WINDOW_MS, window.deadlineMs())
    }

    @Test fun theIdleHangUpWaitsForADialInFlight() {
        val window = TvReadyWindowPolicy()
        window.opened(0, ready = true)
        val w = TvReadyWindowPolicy.WINDOW_MS
        assertEquals(TvReadyWindowPolicy.Expiry.TOUCH, window.onExpired(w, castLive = false, dialInFlight = true, visible = false))
        assertEquals(2 * w, window.deadlineMs())
        assertEquals(TvReadyWindowPolicy.Expiry.TOUCH, window.onExpired(2 * w, castLive = true, dialInFlight = false, visible = false))
        assertEquals(TvReadyWindowPolicy.Expiry.HANG_UP, window.onExpired(3 * w, castLive = false, dialInFlight = false, visible = false))
    }

    @Test fun withoutReadyModeTheWindowClosesAsBefore() {
        val window = TvReadyWindowPolicy()
        window.opened(0)
        window.controlLost(1_000)
        assertEquals(1_000 + TvReadyWindowPolicy.CONTROL_GRACE_MS, window.deadlineMs())
        assertEquals(TvReadyWindowPolicy.Expiry.CLOSE, window.onExpired(200_000, castLive = false, dialInFlight = false, visible = false))
        window.modeChanged(true)
        assertEquals(TvReadyWindowPolicy.WINDOW_MS, window.deadlineMs())
        window.modeChanged(false)
        assertEquals(1_000 + TvReadyWindowPolicy.CONTROL_GRACE_MS, window.deadlineMs())
    }

    @Test fun readyModeOpensWithoutALink() {
        assertTrue(TvReadyWindowPolicy.mayOpen(true, false, false, false, true, readyMode = true))
        assertFalse(TvReadyWindowPolicy.mayOpen(true, false, false, true, true, readyMode = true))
        assertFalse(TvReadyWindowPolicy.mayOpen(false, false, false, false, true, readyMode = true))
        assertFalse(TvReadyWindowPolicy.mayOpen(true, false, false, false, true, readyMode = false))
    }

    // --- The listener over loopback --------------------------------------------

    private class Bell {
        val knocks = CountDownLatch(1)
        @Volatile var knocked: Pair<String, String>? = null
        @Volatile var from: String? = null
        val nonce = "MzMzMzMzMzMzMzMzMzMzMw"
    }

    private fun listener(clock: () -> Long, bell: Bell, newServer: () -> ServerSocket = ::ServerSocket): TvDoorbell =
        TvDoorbell(
            keyFor = keys,
            onKnock = { t, k, peer ->
                bell.knocked = t to k
                bell.from = peer
                bell.knocks.countDown()
            },
            nowMs = clock,
            peerAllowed = { it == "127.0.0.1" },
            nonce = { bell.nonce },
            log = {},
            newServer = newServer,
        )

    private fun validKnock(bound: Int, bell: Bell): (String) -> String = { hello ->
        assertEquals(KnockProtocol.hello(bell.nonce), hello)
        val proof = KnockProtocol.proof(key, tvId, keyId, bell.nonce, tvNonce, "127.0.0.1", bound)
        knock(proof = proof, echo = bell.nonce)
    }

    private fun exchange(port: Int, answer: (hello: String) -> String?): String? = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 3_000
        val input = socket.getInputStream()
        val hello = KnockProtocol.readLine(input) ?: return null
        val frame = answer(hello) ?: return null
        socket.getOutputStream().write((frame + "\n").toByteArray())
        KnockProtocol.readLine(input)
    }

    @Test fun aValidKnockOverASocketIsAnsweredAndHandedOn() {
        var now = 0L
        val bell = Bell()
        val doorbell = listener({ now }, bell)
        val bound = doorbell.bind(InetAddress.getByName("127.0.0.1"))!!
        try {
            val reply = exchange(bound, validKnock(bound, bell))
            assertEquals(KnockProtocol.ok(), reply)
            assertTrue(bell.knocks.await(3, TimeUnit.SECONDS))
            assertEquals(tvId to keyId, bell.knocked)
            assertEquals("127.0.0.1", bell.from)
        } finally {
            doorbell.shutdown()
        }
    }

    @Test fun aBadKnockIsClosedWithNothingSentAndARushedOneIsNotGreeted() {
        var now = 0L
        val bell = Bell()
        val doorbell = listener({ now }, bell)
        val bound = doorbell.bind(InetAddress.getByName("127.0.0.1"))!!
        try {
            assertNull(exchange(bound) { knock(echo = bell.nonce) })
            assertNull(bell.knocked)
            // Inside five seconds of the last admitted handshake: closed before any hello.
            now = 1_000
            assertNull(exchange(bound) { error("no hello expected") })
        } finally {
            doorbell.shutdown()
        }
    }

    @Test fun anAdmittedNoisyPeerLogsOneRefusedProofAndOneDropPerMinute() {
        val now = java.util.concurrent.atomic.AtomicLong(0)
        val logged = java.util.Collections.synchronizedList(mutableListOf<String>())
        val bell = Bell()
        val doorbell = TvDoorbell(
            keyFor = keys,
            onKnock = { _, _, _ -> error("no knock here is valid") },
            nowMs = { now.get() },
            peerAllowed = { it == "127.0.0.1" },
            nonce = { bell.nonce },
            log = { logged += it },
        )
        val bound = doorbell.bind(InetAddress.getByName("127.0.0.1"))!!
        fun badProof() = assertNull(exchange(bound) { knock(echo = bell.nonce) })
        fun reset() = Socket("127.0.0.1", bound).use { socket ->
            socket.soTimeout = 3_000
            assertTrue(KnockProtocol.readLine(socket.getInputStream()) != null)
            socket.setSoLinger(true, 0)
        }
        fun proofs() = logged.filter { it.startsWith("knock refused reason=proof") }
        fun drops() = logged.filter { it.startsWith("knock dropped") }
        try {
            // Six admitted handshakes a minute, the most one peer gets.
            for (i in 0 until 18) { now.set(i * 10_000L); badProof() }
            assertEquals(
                listOf("knock refused reason=proof", "knock refused reason=proof unlogged=5", "knock refused reason=proof unlogged=5"),
                proofs(),
            )
            for (i in 18 until 30) { now.set(i * 10_000L); reset() }
            // A last refused proof: one handshake at a time, so every drop before it has been logged.
            now.set(300_000L)
            badProof()
            assertEquals(4, proofs().size)
            assertEquals("knock refused reason=proof unlogged=5", proofs().last())
            assertEquals(2, drops().size)
            assertTrue(drops()[0], !drops()[0].contains("unlogged"))
            assertTrue(drops()[1], drops()[1].endsWith(" unlogged=5"))
        } finally {
            doorbell.shutdown()
        }
    }

    @Test fun theListenerPrefersTheDoorbellPorts() {
        val doorbell = listener({ 0L }, Bell())
        val bound = doorbell.bind(InetAddress.getByName("127.0.0.1"))
        try {
            assertTrue("$bound", bound != null && (bound in KnockProtocol.PORTS || bound > 0))
            assertEquals(bound, doorbell.port)
        } finally {
            doorbell.shutdown()
        }
        assertNull(doorbell.port)
        assertNull(doorbell.bind(InetAddress.getByName("127.0.0.1")))
    }

    @Test fun failedBindsAreClosedAndEveryCandidateReusesTheAddress() {
        val made = mutableListOf<ServerSocket>()
        val reused = mutableListOf<Boolean>()
        var refusals = 2
        val doorbell = listener({ 0L }, Bell()) {
            val server = object : ServerSocket() {
                override fun bind(endpoint: SocketAddress?, backlog: Int) {
                    reused += reuseAddress
                    if (refusals-- > 0) throw BindException("taken")
                    super.bind(endpoint, backlog)
                }
            }
            made += server
            server
        }
        val bound = doorbell.bind(InetAddress.getByName("127.0.0.1"))
        try {
            assertTrue("$bound", bound != null)
            assertEquals(3, made.size)
            assertTrue(made[0].isClosed && made[1].isClosed)
            assertFalse(made[2].isClosed)
            assertEquals(listOf(true, true, true), reused)
        } finally {
            doorbell.shutdown()
        }
    }

    @Test fun aFailedCloseOfOneConnectionDoesNotStopTheListener() {
        var now = 0L
        val bell = Bell()
        val closes = AtomicInteger(0)
        val doorbell = listener({ now }, bell) {
            object : ServerSocket() {
                override fun accept(): Socket {
                    val socket = object : Socket() {
                        override fun close() {
                            super.close()
                            if (closes.getAndIncrement() == 0) throw IOException("close")
                        }
                    }
                    implAccept(socket)
                    return socket
                }
            }
        }
        val bound = doorbell.bind(InetAddress.getByName("127.0.0.1"))!!
        try {
            assertNull(exchange(bound) { knock(echo = bell.nonce) })
            now = 10_000
            assertEquals(KnockProtocol.ok(), exchange(bound, validKnock(bound, bell)))
            assertTrue(bell.knocks.await(3, TimeUnit.SECONDS))
            assertTrue(closes.get() >= 1)
        } finally {
            doorbell.shutdown()
        }
    }

    @Test fun aTransientAcceptFailureDoesNotStopTheListener() {
        val bell = Bell()
        val failures = AtomicInteger(1)
        val doorbell = listener({ 0L }, bell) {
            object : ServerSocket() {
                override fun accept(): Socket {
                    if (failures.getAndDecrement() > 0) throw SocketException("aborted")
                    return super.accept()
                }
            }
        }
        val bound = doorbell.bind(InetAddress.getByName("127.0.0.1"))!!
        try {
            assertEquals(KnockProtocol.ok(), exchange(bound, validKnock(bound, bell)))
            assertTrue(bell.knocks.await(3, TimeUnit.SECONDS))
            assertEquals(bound, doorbell.port)
        } finally {
            doorbell.shutdown()
        }
    }
}
