package com.flick.receiver.library

import com.flick.receiver.net.StrictJson
import com.flick.receiver.net.string
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

@OptIn(ExperimentalCoroutinesApi::class)
class TvDoorbellTest {

    private val key = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    private val tvId = "EREREREREREREREREREREQ"
    private val keyId = "IiIiIiIiIiIiIiIiIiIiIg"
    private val phoneNonce = "MzMzMzMzMzMzMzMzMzMzMw"
    private val tvNonce = "RERERERERERERERERERERA"
    private val otherKeyId = "VVVVVVVVVVVVVVVVVVVVVQ"

    // --- Transcript and proof ------------------------------------------------

    @Test
    fun proofMatchesTheSharedFixture() {
        assertEquals(
            "DdZzClsQ82tZwVfbbFKIaLEbTklR8zZpwnZFxMX4_JA",
            KnockWire.proof(key, tvId, keyId, phoneNonce, tvNonce, "192.168.1.20", 47670),
        )
    }

    @Test
    fun transcriptIsLengthPrefixedFieldsInContractOrder() {
        val bytes = KnockWire.transcript(tvId, keyId, phoneNonce, tvNonce, "192.168.1.20", 47670)
        val fields = mutableListOf<String>()
        var at = 0
        while (at < bytes.size) {
            val length = java.nio.ByteBuffer.wrap(bytes, at, 4).int
            fields += String(bytes, at + 4, length, StandardCharsets.UTF_8)
            at += 4 + length
        }
        assertEquals(
            listOf("Flick-Knock-V1", "tv", "1", tvId, keyId, phoneNonce, tvNonce, "192.168.1.20", "47670"),
            fields,
        )
    }

    @Test
    fun proofIsDomainSeparatedFromTheLibraryHandshake() {
        val library = LibraryAuth.proof(key, "client", tvId, keyId, phoneNonce, tvNonce, "192.168.1.20", "192.168.1.20", 47670)
        assertFalse(library == KnockWire.proof(key, tvId, keyId, phoneNonce, tvNonce, "192.168.1.20", 47670))
    }

    // --- Frames --------------------------------------------------------------

    @Test
    fun helloParsesAndIgnoresUnknownFields() {
        assertEquals(phoneNonce, KnockWire.parseHello("{\"t\":\"knockHello\",\"v\":1,\"phoneNonce\":\"$phoneNonce\"}"))
        assertEquals(phoneNonce, KnockWire.parseHello("{\"t\":\"knockHello\",\"v\":1,\"phoneNonce\":\"$phoneNonce\",\"later\":2}"))
    }

    @Test
    fun malformedHellosAreRefused() {
        listOf(
            "{\"t\":\"knockHello\",\"v\":2,\"phoneNonce\":\"$phoneNonce\"}",
            "{\"t\":\"knockHello\",\"v\":\"1\",\"phoneNonce\":\"$phoneNonce\"}",
            "{\"t\":\"knockHello\",\"v\":1.0,\"phoneNonce\":\"$phoneNonce\"}",
            "{\"t\":\"knockHello\",\"v\":1,\"phoneNonce\":\"short\"}",
            "{\"t\":\"knockHello\",\"v\":1}",
            "{\"t\":\"libHello\",\"v\":1,\"phoneNonce\":\"$phoneNonce\"}",
            "{\"t\":\"knockHello\",\"v\":1,\"phoneNonce\":\"$phoneNonce\",\"v\":1}",
            "{\"t\":\"knockHello\",\"v\":1,\"phoneNonce\":\"$phoneNonce\"} x",
            "[1]",
            "{\"t\":\"knockHello\",\"v\":1,\"phoneNonce\":\"$phoneNonce\",\"pad\":\"${"a".repeat(512)}\"}",
        ).forEach { assertNull(it, KnockWire.parseHello(it)) }
    }

    @Test
    fun okIsExactlyKnockOkVersionOne() {
        assertTrue(KnockWire.isOk("{\"t\":\"knockOk\",\"v\":1}"))
        assertFalse(KnockWire.isOk("{\"t\":\"knockOk\",\"v\":2}"))
        assertFalse(KnockWire.isOk("{\"t\":\"knockNo\",\"v\":1}"))
        assertFalse(KnockWire.isOk(""))
    }

    @Test
    fun knockOkSaysWhetherThePhoneConnectsAsksOrDeclines() {
        assertEquals(KnockOutcome.ANSWERED, KnockWire.reply("{\"t\":\"knockOk\",\"v\":1}"))
        assertEquals(KnockOutcome.ASKED, KnockWire.reply("{\"t\":\"knockOk\",\"v\":1,\"ask\":true}"))
        assertEquals(KnockOutcome.DECLINED, KnockWire.reply("{\"t\":\"knockOk\",\"v\":1,\"declined\":true}"))
        assertEquals(KnockOutcome.DECLINED, KnockWire.reply("{\"t\":\"knockOk\",\"v\":1,\"ask\":true,\"declined\":true}"))
        // Missing, false or not a boolean is false; unknown fields are ignored.
        listOf("\"ask\":false", "\"ask\":\"true\"", "\"ask\":1", "\"ask\":null", "\"declined\":\"true\"", "\"later\":2").forEach {
            assertEquals(it, KnockOutcome.ANSWERED, KnockWire.reply("{\"t\":\"knockOk\",\"v\":1,$it}"))
        }
        assertTrue(KnockWire.isOk("{\"t\":\"knockOk\",\"v\":1,\"ask\":true}"))
        assertNull(KnockWire.reply("{\"t\":\"knockOk\",\"v\":2,\"ask\":true}"))
        assertNull(KnockWire.reply("{\"t\":\"knockOk\",\"v\":1,\"ask\":true,\"ask\":true}"))
        assertNull(KnockWire.reply("{\"t\":\"knockHello\",\"v\":1}"))
    }

    @Test
    fun knockFrameCarriesEveryFieldAndFitsALine() {
        val proof = KnockWire.proof(key, tvId, keyId, phoneNonce, tvNonce, "192.168.1.20", 47670)
        val frame = KnockWire.knock(tvId, keyId, phoneNonce, tvNonce, proof)!!
        val o = StrictJson.objectOnly(frame)!!
        assertEquals(setOf("t", "v", "tvId", "keyId", "phoneNonce", "tvNonce", "proof"), o.fields.keys)
        assertEquals(proof, o.string("proof")?.value)
        assertTrue(frame.toByteArray().size <= KnockWire.MAX_LINE_BYTES)
        assertTrue(frame.startsWith("{\"t\":\"knock\",\"v\":1,"))
        assertNull(KnockWire.knock(tvId, keyId, "bad\"nonce", tvNonce, proof))
        assertNull(KnockWire.knock(tvId, keyId, phoneNonce, tvNonce, "short"))
    }

    @Test
    fun knockAtIsALibraryFrame() {
        assertEquals(PhoneFrame.KnockAt(47670), PhoneFrame.parse("{\"t\":\"knockAt\",\"v\":1,\"port\":47670}"))
        assertEquals(PhoneFrame.KnockAt(1), PhoneFrame.parse("{\"t\":\"knockAt\",\"v\":1,\"port\":1}"))
        assertEquals(PhoneFrame.KnockAt(65535), PhoneFrame.parse("{\"t\":\"knockAt\",\"v\":1,\"port\":65535}"))
        listOf("0", "65536", "-1", "\"47670\"", "47670.5", "null").forEach { port ->
            assertEquals(port, PhoneFrame.Malformed, PhoneFrame.parse("{\"t\":\"knockAt\",\"v\":1,\"port\":$port}"))
        }
        assertEquals(PhoneFrame.Malformed, PhoneFrame.parse("{\"t\":\"knockAt\",\"v\":2,\"port\":47670}"))
        assertEquals(PhoneFrame.Unknown("knockLater"), PhoneFrame.parse("{\"t\":\"knockLater\",\"v\":1}"))
    }

    // --- Address store -------------------------------------------------------

    @Test
    fun addressRoundTripsWithEitherHalfUnknown() {
        listOf(
            KnockAddress("192.168.1.20", 47670),
            KnockAddress("10.0.0.5", null),
            KnockAddress(null, 47679),
            KnockAddress("172.31.255.254", 65535),
        ).forEach { assertEquals(it, KnockAddressCodec.decode(KnockAddressCodec.encode(it))) }
        assertEquals("1|192.168.1.20|47670", KnockAddressCodec.encode(KnockAddress("192.168.1.20", 47670)))
        assertEquals("1|192.168.1.20|47670|7", KnockAddressCodec.encode(KnockAddress("192.168.1.20", 47670, proven = 7)))
        assertEquals(KnockAddress("192.168.1.20", 47670, 7), KnockAddressCodec.decode("1|192.168.1.20|47670|7"))
        assertEquals(KnockAddress("192.168.1.20", 47670, 0), KnockAddressCodec.decode("1|192.168.1.20|47670"))
    }

    @Test
    fun undecodableAddressesAreDropped() {
        listOf(
            null, "", "1||", "2|192.168.1.20|47670", "1|192.168.1.20", "1|8.8.8.8|47670", "1|172.32.0.1|1",
            "1|192.168.01.20|47670", "1|192.168.1.256|47670", "1|192.168.1.20|0", "1|192.168.1.20|65536",
            "1|192.168.1.20|047670", "1|192.168.1.20|+80", "1|127.0.0.1|47670", "1|fe80::1|47670",
            "1|192.168.1.20|47670|x", "1|192.168.1.20|47670|0", "1|192.168.1.20|47670|07", "1|192.168.1.20|47670|",
            "1|192.168.1.20|47670|1|2", "1|192.168.1.20|47670|-1",
        ).forEach { assertNull(it, KnockAddressCodec.decode(it)) }
    }

    @Test
    fun bookRecordsMergesAndForgets() {
        val storage = MemoryStorage()
        val book = KnockAddressBook(storage)
        book.recordPeer(keyId, "192.168.1.20") { true }
        book.recordPort(keyId, 47670) { true }
        book.recordPeer(otherKeyId, "192.168.1.21") { true }
        assertEquals(KnockAddress("192.168.1.20", 47670, proven = 1), book.snapshot()[keyId])
        assertEquals("1|192.168.1.20|47670|1", storage.values[keyId])

        book.recordPeer(keyId, "192.168.1.40") { true }
        assertEquals(KnockAddress("192.168.1.40", 47670, proven = 3), KnockAddressBook(storage).snapshot()[keyId])

        book.forget(keyId)
        assertNull(book.snapshot()[keyId])
        assertNull(storage.values[keyId])
        book.forgetAll()
        assertTrue(book.snapshot().isEmpty())
        assertTrue(storage.values.isEmpty())
    }

    @Test
    fun aWriteThatLosesTheRaceWithAForgetIsUndone() {
        val storage = MemoryStorage()
        val book = KnockAddressBook(storage)
        book.recordPeer(keyId, "192.168.1.20") { false }
        book.recordPort(keyId, 47670) { false }
        assertTrue(book.snapshot().isEmpty())
        assertTrue(storage.values.isEmpty())
    }

    @Test
    fun bookRefusesPublicPeersBadPortsAndBadKeyIds() {
        val storage = MemoryStorage()
        val book = KnockAddressBook(storage)
        book.recordPeer(keyId, "8.8.8.8") { true }
        book.recordPort(keyId, 0) { true }
        book.recordPeer("not-a-key-id", "192.168.1.20") { true }
        assertTrue(book.snapshot().isEmpty())
        assertTrue(storage.values.isEmpty())
    }

    @Test
    fun loadingDropsCorruptEntriesAndRetainOnlyPrunesUnpaired() {
        val storage = MemoryStorage(mutableMapOf(keyId to "1|192.168.1.20|47670", otherKeyId to "junk", "bad" to "1|10.0.0.1|1"))
        val book = KnockAddressBook(storage)
        assertEquals(setOf(keyId), book.snapshot().keys)
        assertEquals(setOf(keyId), storage.values.keys)
        book.retainOnly(setOf(otherKeyId))
        assertTrue(book.snapshot().isEmpty())
        assertTrue(storage.values.isEmpty())
    }

    @Test
    fun anAddressBelongsToTheLastPairingProvenFromIt() {
        val storage = MemoryStorage()
        val book = KnockAddressBook(storage)
        book.recordPeer(keyId, "192.168.1.20") { true }
        book.recordPort(keyId, 47670) { true }
        book.recordPeer(otherKeyId, "192.168.1.21") { true }
        book.recordPort(otherKeyId, 47671) { true }

        book.recordPeer(otherKeyId, "192.168.1.20") { true }
        assertEquals(setOf(otherKeyId), book.snapshot().keys)
        assertEquals(setOf(otherKeyId), storage.values.keys)
        assertEquals(KnockAddress("192.168.1.20", 47671, proven = 3), KnockAddressBook(storage).snapshot()[otherKeyId])
    }

    @Test
    fun aBookLoadedWithProvenEntriesKeepsCountingUp() {
        val storage = MemoryStorage(mutableMapOf(keyId to "1|192.168.1.20|47670|41", otherKeyId to "1|192.168.1.21|47671"))
        val book = KnockAddressBook(storage)
        book.recordPeer(otherKeyId, "192.168.1.21") { true }
        assertEquals(42L, book.snapshot()[otherKeyId]?.proven)
        book.recordPeer(otherKeyId, "192.168.1.21") { true }
        assertEquals("1|192.168.1.21|47671|42", storage.values[otherKeyId])
    }

    @Test
    fun oneKnockPerHostPreferringTheMostRecentlyProven() {
        val third = "WWWWWWWWWWWWWWWWWWWWWw"
        val book = mapOf(
            keyId to KnockAddress("192.168.1.20", 47670, proven = 5),
            otherKeyId to KnockAddress("192.168.1.20", 47670, proven = 9),
            third to KnockAddress("192.168.1.22", 47671),
        )
        assertEquals(listOf(otherKeyId, third), KnockPolicy.targets(setOf(keyId, otherKeyId, third), book, emptySet()))
        // Legacy values with no recency still yield one knock, and the same one each time.
        val legacy = mapOf(keyId to KnockAddress("192.168.1.20", 47670), otherKeyId to KnockAddress("192.168.1.20", 47670))
        assertEquals(listOf(keyId), KnockPolicy.targets(setOf(keyId, otherKeyId), legacy, emptySet()))
        // A host a linked phone holds is that phone's.
        assertEquals(listOf(third), KnockPolicy.targets(setOf(keyId, otherKeyId, third), book, setOf(otherKeyId)))
    }

    // --- Policy --------------------------------------------------------------

    @Test
    fun targetsAreUnlinkedPhonesWithAWholeAddress() {
        val book = mapOf(
            keyId to KnockAddress("192.168.1.20", 47670),
            otherKeyId to KnockAddress("192.168.1.21", null),
            "WWWWWWWWWWWWWWWWWWWWWw" to KnockAddress("192.168.1.22", 47671),
        )
        assertEquals(listOf(keyId), KnockPolicy.targets(setOf(keyId, otherKeyId), book, emptySet()))
        assertEquals(emptyList<String>(), KnockPolicy.targets(setOf(keyId), book, setOf(keyId)))
        assertEquals(emptyList<String>(), KnockPolicy.targets(emptySet(), book, emptySet()))
    }

    @Test
    fun aKeyKnocksAtMostOncePerTenSecondsAndOnlyWhileNothingIsLinked() {
        assertTrue(KnockPolicy.keyMayKnock(false, null, 0))
        assertFalse(KnockPolicy.keyMayKnock(false, 1_000, 10_999))
        assertTrue(KnockPolicy.keyMayKnock(false, 1_000, 11_000))
        assertFalse(KnockPolicy.keyMayKnock(true, null, 0))
    }

    @Test
    fun onlyAKnockThatNeverConnectedIsRetried() {
        assertTrue(KnockPolicy.retries(KnockOutcome.UNREACHABLE, 1))
        assertFalse(KnockPolicy.retries(KnockOutcome.NO_HELLO, 1))
        assertTrue(KnockPolicy.retries(KnockOutcome.NO_LAN, 1))
        assertFalse(KnockPolicy.retries(KnockOutcome.SILENT, 1))
        assertFalse(KnockPolicy.retries(KnockOutcome.UNREACHABLE, 2))
        assertFalse(KnockPolicy.retries(KnockOutcome.ANSWERED, 1))
        assertFalse(KnockPolicy.retries(KnockOutcome.NO_KEY, 1))
        assertFalse(KnockPolicy.retries(KnockOutcome.NOT_STARTED, 1))
    }

    @Test
    fun onlyAPhoneThatWillNotConnectMakesWayForTheNext() {
        val movesOn = KnockOutcome.entries.filter(KnockPolicy::triesNext).toSet()
        assertEquals(
            setOf(KnockOutcome.UNREACHABLE, KnockOutcome.NO_HELLO, KnockOutcome.SILENT, KnockOutcome.NO_KEY, KnockOutcome.DECLINED),
            movesOn,
        )
    }

    @Test
    fun targetsAreKnockedMostRecentlyProvenFirst() {
        val third = "WWWWWWWWWWWWWWWWWWWWWw"
        val book = mapOf(
            keyId to KnockAddress("192.168.1.20", 47670, proven = 3),
            otherKeyId to KnockAddress("192.168.1.21", 47670),
            third to KnockAddress("192.168.1.22", 47670, proven = 8),
        )
        assertEquals(listOf(third, keyId, otherKeyId), KnockPolicy.targets(setOf(keyId, otherKeyId, third), book, emptySet()))
    }

    @Test
    fun faceFollowsTheRound() {
        assertEquals(DoorbellFace.QUIET, KnockPolicy.face(0, false, false, false, began = false, hinted = true))
        assertEquals(DoorbellFace.WAKING, KnockPolicy.face(1, false, false, false, began = true, hinted = true))
        assertEquals(DoorbellFace.WAKING, KnockPolicy.face(0, true, false, false, began = true, hinted = true))
        assertEquals(DoorbellFace.UNANSWERED, KnockPolicy.face(0, true, true, false, began = true, hinted = false))
        assertEquals(DoorbellFace.UNANSWERED, KnockPolicy.face(0, false, false, false, began = true, hinted = true))
        assertEquals(DoorbellFace.QUIET, KnockPolicy.face(0, false, false, false, began = true, hinted = false))
        assertEquals(DoorbellFace.QUIET, KnockPolicy.face(1, true, false, true, began = true, hinted = true))
    }

    @Test
    fun onlyAnUnansweringPhoneEarnsTheBatteryHint() {
        val hinting = KnockOutcome.entries.filter(KnockPolicy::hints).toSet()
        assertEquals(setOf(KnockOutcome.UNREACHABLE, KnockOutcome.NO_HELLO, KnockOutcome.SILENT), hinting)
    }

    @Test
    fun startRoundsKeepTheirGap() {
        assertEquals(0L, KnockPolicy.startWaitMs(null, 0))
        assertEquals(KnockPolicy.START_GAP_MS - 1_000, KnockPolicy.startWaitMs(0, 1_000))
        assertEquals(0L, KnockPolicy.startWaitMs(0, KnockPolicy.START_GAP_MS))
        assertTrue(KnockPolicy.START_GAP_MS >= 5_000)
        assertTrue(KnockPolicy.stillConnecting(0, KnockPolicy.CONNECTING_GRACE_MS - 1))
        assertFalse(KnockPolicy.stillConnecting(0, KnockPolicy.CONNECTING_GRACE_MS))
        assertFalse(KnockPolicy.stillConnecting(null, 0))
    }

    @Test
    fun aPhoneIsKnockedAgainOnlyAfterItsGateFromTheLastConnect() {
        assertEquals(0L, KnockPolicy.admissionWaitMs(null, 0))
        assertEquals(KnockPolicy.ADMISSION_GAP_MS - 2_000, KnockPolicy.admissionWaitMs(1_000, 3_000))
        assertEquals(0L, KnockPolicy.admissionWaitMs(1_000, 1_000 + KnockPolicy.ADMISSION_GAP_MS))
        assertTrue(KnockPolicy.ADMISSION_GAP_MS >= 6_000)
    }

    @Test
    fun aPhoneThatHeardTheKnockEarnsNoHintAndNoRetry() {
        listOf(KnockOutcome.ASKED, KnockOutcome.DECLINED).forEach {
            assertFalse(KnockPolicy.hints(it))
            assertFalse(KnockPolicy.retries(it, 1))
        }
        assertFalse(KnockPolicy.triesNext(KnockOutcome.ASKED))
    }

    @Test
    fun faceSaysCheckYourPhoneUntilTheAskWindowEnds() {
        assertEquals(DoorbellFace.ASKING, KnockPolicy.face(0, false, false, false, true, hinted = true, asked = true))
        assertEquals(DoorbellFace.QUIET, KnockPolicy.face(0, false, false, false, true, hinted = true, asked = true, askOver = true))
        assertEquals(DoorbellFace.WAKING, KnockPolicy.face(1, false, false, false, true, hinted = false, asked = true))
        assertEquals(DoorbellFace.QUIET, KnockPolicy.face(0, false, false, true, true, hinted = false, asked = true))
        // An answer anywhere in the round wins over an ask.
        assertEquals(DoorbellFace.WAKING, KnockPolicy.face(0, true, false, false, true, hinted = false, asked = true))
        assertEquals(DoorbellFace.QUIET, KnockPolicy.face(0, false, false, false, true, hinted = true, declined = true))
        // One phone asking outranks another declining.
        assertEquals(DoorbellFace.ASKING, KnockPolicy.face(0, false, false, false, true, hinted = false, asked = true, declined = true))
    }

    @Test
    fun theSweepTriesEveryOtherListenerPortInBindOrder() {
        assertEquals((47_671..47_679).toList(), KnockPolicy.sweepPorts(47_670))
        assertEquals(listOf(47_670, 47_671, 47_672, 47_674, 47_675, 47_676, 47_677, 47_678, 47_679), KnockPolicy.sweepPorts(47_673))
        assertEquals((47_670..47_679).toList(), KnockPolicy.sweepPorts(50_123))
        assertEquals(
            setOf(KnockOutcome.ANSWERED, KnockOutcome.ASKED, KnockOutcome.DECLINED),
            KnockOutcome.entries.filter(KnockPolicy::sweepFound).toSet(),
        )
        assertEquals(setOf(KnockOutcome.UNREACHABLE, KnockOutcome.NO_HELLO), KnockOutcome.entries.filter(KnockPolicy::sweepGoesOn).toSet())
    }

    @Test
    fun aRoundReportsItsOutcomeClosestToALink() {
        assertNull(KnockPolicy.best(emptyList()))
        assertEquals(KnockOutcome.ASKED, KnockPolicy.best(listOf(KnockOutcome.UNREACHABLE, KnockOutcome.ASKED)))
        assertEquals(KnockOutcome.SILENT, KnockPolicy.best(listOf(KnockOutcome.UNREACHABLE, KnockOutcome.SILENT, KnockOutcome.NO_HELLO)))
        assertEquals(KnockOutcome.UNREACHABLE, KnockPolicy.best(listOf(KnockOutcome.NO_KEY, KnockOutcome.UNREACHABLE)))
        assertEquals(KnockOutcome.ANSWERED, KnockPolicy.best(KnockOutcome.entries))
        assertEquals(KnockOutcome.ASKED, KnockPolicy.best(listOf(KnockOutcome.DECLINED, KnockOutcome.ASKED)))
        assertEquals(KnockOutcome.DECLINED, KnockPolicy.best(listOf(KnockOutcome.SILENT, KnockOutcome.DECLINED, KnockOutcome.UNREACHABLE)))
    }

    // --- Scheduling ------------------------------------------------------------

    private class Harness(
        val test: TestScope,
        outcomes: List<KnockOutcome>,
        val paired: Set<String>,
        var linked: Set<String> = emptySet(),
        val times: KnockTimes = KnockTimes(),
        hosts: List<String> = paired.indices.map { "192.168.1.${20 + it}" },
    ) {
        val calls = mutableListOf<Long>()
        val knocked = mutableListOf<String>()

        /** Knocks at a port other than the stored one: (time, port). */
        val swept = mutableListOf<Pair<Long, Int>>()

        /** What a listener at a swept port replies; a port missing here refuses at once. */
        val listening = mutableMapOf<Int, KnockOutcome>()
        val journal = mutableListOf<String>()
        val sweepTimeouts = mutableListOf<Int>()

        /** How long a swept port's connect takes to fail: 0 is a refusal, more a host that drops it. */
        var sweepDropMs = 0L
        var local: String? = "192.168.1.30"
        var key: String? = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        private val script = ArrayDeque(outcomes)
        val book = KnockAddressBook(MemoryStorage()).apply {
            paired.forEachIndexed { i, id ->
                recordPeer(id, hosts[i]) { true }
                recordPort(id, 47670) { true }
            }
        }
        var doorbell = newDoorbell()

        /** A doorbell as a reopened screen makes it: new state, the process's [times]. */
        fun newDoorbell() = TvDoorbell(
            book = book,
            pairedKeyIds = { paired },
            linkedKeyIds = { linked },
            keyFor = { key },
            tvId = { "EREREREREREREREREREREQ" },
            localHost = { local },
            transport = object : KnockTransport {
                override suspend fun knock(
                    host: String, port: Int, localHost: String, tvId: String, keyId: String, key: String,
                    connectTimeoutMs: Int, connected: () -> Unit, mayProve: () -> Boolean,
                ): KnockOutcome {
                    if (port != 47670) {
                        swept += test.testScheduler.currentTime to port
                        sweepTimeouts += connectTimeoutMs
                        val reply = listening[port] ?: run {
                            if (sweepDropMs > 0) delay(minOf(sweepDropMs, connectTimeoutMs.toLong()))
                            return KnockOutcome.UNREACHABLE
                        }
                        connected()
                        delay(100)
                        return if (!mayProve()) KnockOutcome.NOT_STARTED else reply
                    }
                    calls += test.testScheduler.currentTime
                    knocked += keyId
                    if ((script.firstOrNull() ?: KnockOutcome.UNREACHABLE) != KnockOutcome.UNREACHABLE) connected()
                    delay(100)
                    return if (!mayProve()) KnockOutcome.NOT_STARTED else script.removeFirstOrNull() ?: KnockOutcome.UNREACHABLE
                }
            },
            clock = { test.testScheduler.currentTime },
            scope = test,
            work = StandardTestDispatcher(test.testScheduler),
            times = times,
            journal = { event, fields -> journal += (listOf(event) + fields.map { (k, v) -> "$k=$v" }).joinToString(" ") },
        )

        /** STARTED, then the bind loop's first pass binds the server: a cold start's order. */
        fun start() {
            doorbell.onStarted()
            doorbell.onServer(bound = true, rebound = true)
        }

        /** STARTED again, then the bind loop finds the server still bound: a return's order. */
        fun resume() {
            doorbell.onStarted()
            doorbell.onServer(bound = true, rebound = false)
        }
    }

    @Test
    fun startKnocksTwiceOnePointFiveSecondsApartThenGivesUp() = runTest {
        val h = Harness(this, listOf(KnockOutcome.UNREACHABLE, KnockOutcome.UNREACHABLE), setOf(keyId))
        h.start()
        assertEquals(DoorbellFace.WAKING, h.doorbell.face.value)
        advanceUntilIdle()
        assertEquals(listOf(0L, 1_600L), h.calls)
        assertEquals(DoorbellFace.UNANSWERED, h.doorbell.face.value)
    }

    @Test
    fun anAnsweredKnockWaitsForTheLinkThenFallsBack() = runTest {
        val h = Harness(this, listOf(KnockOutcome.ANSWERED), setOf(keyId))
        h.start()
        advanceTimeBy(200)
        runCurrent()
        assertEquals(1, h.calls.size)
        assertEquals(DoorbellFace.WAKING, h.doorbell.face.value)
        advanceTimeBy(KnockPolicy.CONNECTING_GRACE_MS - 500)
        runCurrent()
        assertEquals(DoorbellFace.WAKING, h.doorbell.face.value)
        advanceUntilIdle()
        assertEquals(DoorbellFace.UNANSWERED, h.doorbell.face.value)
    }

    @Test
    fun theLinkComingUpQuietsTheScreen() = runTest {
        val h = Harness(this, listOf(KnockOutcome.ANSWERED), setOf(keyId))
        h.start()
        advanceTimeBy(200)
        runCurrent()
        h.doorbell.onLinkChanged(true)
        advanceUntilIdle()
        assertEquals(DoorbellFace.QUIET, h.doorbell.face.value)
    }

    @Test
    fun nothingKnocksForALinkedPhoneOrWithoutAnAddress() = runTest {
        val linked = Harness(this, emptyList(), setOf(keyId), linked = setOf(keyId))
        linked.start()
        advanceUntilIdle()
        assertTrue(linked.calls.isEmpty())
        assertEquals(DoorbellFace.QUIET, linked.doorbell.face.value)

        val bare = Harness(this, emptyList(), emptySet())
        bare.start()
        advanceUntilIdle()
        assertTrue(bare.calls.isEmpty())
    }

    @Test
    fun stoppingCancelsTheRoundAndNothingKnocksWhileStopped() = runTest {
        val h = Harness(this, listOf(KnockOutcome.UNREACHABLE, KnockOutcome.UNREACHABLE), setOf(keyId))
        h.start()
        advanceTimeBy(500)
        runCurrent()
        h.doorbell.onStopped()
        advanceUntilIdle()
        assertEquals(1, h.calls.size)
        assertEquals(DoorbellFace.QUIET, h.doorbell.face.value)
        h.doorbell.onIdleKey()
        advanceUntilIdle()
        assertEquals(1, h.calls.size)
    }

    @Test
    fun idleKeysAreRateLimited() = runTest {
        val h = Harness(this, List(10) { KnockOutcome.ANSWERED }, setOf(keyId))
        h.start()
        advanceUntilIdle()
        h.doorbell.onIdleKey()
        advanceUntilIdle()
        assertEquals(1, h.calls.size)
        advanceTimeBy(KnockPolicy.KEY_GAP_MS)
        h.doorbell.onIdleKey()
        advanceUntilIdle()
        assertEquals(2, h.calls.size)
    }

    // The harness proves keyId first, so otherKeyId is the most recently proven.

    @Test
    fun aRoundWakesOnlyTheMostRecentlyProvenPhoneWhenItAnswers() = runTest {
        val h = Harness(this, listOf(KnockOutcome.ANSWERED, KnockOutcome.ANSWERED), setOf(keyId, otherKeyId))
        h.start()
        advanceUntilIdle()
        assertEquals(listOf(otherKeyId), h.knocked)
        assertEquals(DoorbellFace.UNANSWERED, h.doorbell.face.value)
    }

    @Test
    fun theNextPhoneIsTriedOnlyAfterTheFirstFails() = runTest {
        val h = Harness(
            this, listOf(KnockOutcome.UNREACHABLE, KnockOutcome.UNREACHABLE, KnockOutcome.ANSWERED), setOf(keyId, otherKeyId),
        )
        h.start()
        advanceTimeBy(1_650)
        runCurrent()
        assertEquals(listOf(otherKeyId, otherKeyId), h.knocked)
        assertEquals(DoorbellFace.WAKING, h.doorbell.face.value)
        advanceTimeBy(200)
        runCurrent()
        assertEquals(listOf(otherKeyId, otherKeyId, keyId), h.knocked)
        assertEquals(listOf(0L, 1_600L, 1_700L), h.calls)
        assertEquals(DoorbellFace.WAKING, h.doorbell.face.value)
    }

    @Test
    fun aPhoneSilentAfterTheProofMakesWayWithoutARetry() = runTest {
        val h = Harness(this, listOf(KnockOutcome.SILENT, KnockOutcome.SILENT), setOf(keyId, otherKeyId))
        h.start()
        advanceUntilIdle()
        assertEquals(listOf(otherKeyId, keyId), h.knocked)
        assertEquals(listOf(0L, 100L), h.calls)
        assertEquals(DoorbellFace.UNANSWERED, h.doorbell.face.value)
    }

    @Test
    fun noLanAddressMidRoundStopsBeforeTheNextPhone() = runTest {
        val h = Harness(this, emptyList(), setOf(keyId, otherKeyId))
        h.start()
        h.local = null
        advanceUntilIdle()
        assertTrue(h.knocked.isEmpty())
        assertEquals(DoorbellFace.QUIET, h.doorbell.face.value)
    }

    @Test
    fun noStartRoundWhileAnotherPhoneIsLinked() = runTest {
        val h = Harness(this, listOf(KnockOutcome.ANSWERED), setOf(keyId, otherKeyId), linked = setOf(otherKeyId))
        h.start()
        advanceUntilIdle()
        h.doorbell.onServer(bound = true, rebound = true)
        advanceUntilIdle()
        assertTrue(h.knocked.isEmpty())
        assertEquals(DoorbellFace.QUIET, h.doorbell.face.value)
    }

    @Test
    fun aLinkThatComesUpMidRoundStopsTheNextPhone() = runTest {
        val h = Harness(this, listOf(KnockOutcome.UNREACHABLE, KnockOutcome.UNREACHABLE), setOf(keyId, otherKeyId))
        h.start()
        advanceTimeBy(500)
        runCurrent()
        h.linked = setOf(keyId)
        h.doorbell.onLinkChanged(true)
        advanceUntilIdle()
        assertEquals(listOf(otherKeyId), h.knocked)
    }

    @Test
    fun aRebindInsideTheConnectingWindowStillKnocks() = runTest {
        val h = Harness(this, listOf(KnockOutcome.ANSWERED, KnockOutcome.ANSWERED), setOf(keyId))
        h.start()
        advanceTimeBy(500)
        runCurrent()
        h.doorbell.onServer(bound = true, rebound = true)
        advanceUntilIdle()
        // Answered at 100 ms; the rebind's start waits only for the start gap.
        assertEquals(listOf(0L, KnockPolicy.START_GAP_MS), h.calls)
    }

    @Test
    fun aRebindWhileStoppedDoesNotOverrideTheConnectingWindow() = runTest {
        val h = Harness(this, listOf(KnockOutcome.ANSWERED, KnockOutcome.ANSWERED), setOf(keyId))
        h.start()
        advanceTimeBy(1_000)
        runCurrent()
        h.doorbell.onStopped()
        advanceTimeBy(1_000)
        runCurrent()
        h.doorbell.onServer(bound = true, rebound = true)
        h.doorbell.onStarted()
        advanceUntilIdle()
        assertEquals(1, h.calls.size)
    }

    @Test
    fun theStartKnockWaitsForTheControlServer() = runTest {
        val startedFirst = Harness(this, listOf(KnockOutcome.ANSWERED), setOf(keyId))
        startedFirst.doorbell.onStarted()
        advanceUntilIdle()
        assertTrue(startedFirst.calls.isEmpty())
        assertEquals(DoorbellFace.QUIET, startedFirst.doorbell.face.value)
        startedFirst.doorbell.onServer(bound = true, rebound = true)
        startedFirst.doorbell.onServer(bound = true, rebound = false)
        advanceUntilIdle()
        assertEquals(1, startedFirst.calls.size)

        val readyFirst = Harness(this, listOf(KnockOutcome.ANSWERED), setOf(keyId))
        readyFirst.doorbell.onServer(bound = true, rebound = true)
        advanceUntilIdle()
        assertTrue(readyFirst.calls.isEmpty())
        readyFirst.doorbell.onStarted()
        readyFirst.doorbell.onServer(bound = true, rebound = false)
        advanceUntilIdle()
        assertEquals(1, readyFirst.calls.size)

        val unbound = Harness(this, emptyList(), setOf(keyId))
        unbound.doorbell.onStarted()
        unbound.doorbell.onServer(bound = false, rebound = false)
        advanceUntilIdle()
        assertTrue(unbound.calls.isEmpty())
    }

    @Test
    fun aLinkEndsTheRoundSoItsDropLeavesTheScreenQuiet() = runTest {
        val answered = Harness(this, listOf(KnockOutcome.ANSWERED), setOf(keyId))
        answered.start()
        advanceTimeBy(200)
        runCurrent()
        answered.doorbell.onLinkChanged(true)
        answered.doorbell.onLinkChanged(false)
        assertEquals(DoorbellFace.QUIET, answered.doorbell.face.value)
        advanceUntilIdle()
        assertEquals(DoorbellFace.QUIET, answered.doorbell.face.value)

        val failed = Harness(this, listOf(KnockOutcome.SILENT), setOf(keyId))
        failed.start()
        advanceUntilIdle()
        assertEquals(DoorbellFace.UNANSWERED, failed.doorbell.face.value)
        failed.doorbell.onLinkChanged(true)
        failed.doorbell.onLinkChanged(false)
        assertEquals(DoorbellFace.QUIET, failed.doorbell.face.value)
    }

    @Test
    fun aRoundEndedByALinkIgnoresItsLateResult() = runTest {
        val h = Harness(this, listOf(KnockOutcome.UNREACHABLE, KnockOutcome.UNREACHABLE), setOf(keyId))
        h.start()
        advanceTimeBy(50)
        runCurrent()
        h.doorbell.onLinkChanged(true)
        h.doorbell.onLinkChanged(false)
        advanceUntilIdle()
        assertEquals(1, h.calls.size)
        assertEquals(DoorbellFace.QUIET, h.doorbell.face.value)
    }

    @Test
    fun noLanAddressIsNotReadyYetNeverUnanswered() = runTest {
        val h = Harness(this, listOf(KnockOutcome.ANSWERED), setOf(keyId))
        h.local = null
        h.start()
        advanceUntilIdle()
        assertTrue(h.calls.isEmpty())
        assertEquals(DoorbellFace.QUIET, h.doorbell.face.value)

        h.local = "192.168.1.30"
        h.doorbell.onServer(bound = true, rebound = false)
        advanceUntilIdle()
        assertEquals(1, h.calls.size)
    }

    @Test
    fun anAddressLostMidRoundLeavesTheStartOwed() = runTest {
        val h = Harness(this, listOf(KnockOutcome.ANSWERED), setOf(keyId))
        h.start()
        h.local = null
        advanceUntilIdle()
        assertTrue(h.calls.isEmpty())
        assertEquals(DoorbellFace.QUIET, h.doorbell.face.value)

        h.local = "192.168.1.30"
        advanceTimeBy(KnockPolicy.START_GAP_MS)
        h.doorbell.onServer(bound = true, rebound = false)
        advanceUntilIdle()
        assertEquals(1, h.calls.size)
    }

    @Test
    fun aQuickReturnWaitsOutTheStartGap() = runTest {
        val h = Harness(this, listOf(KnockOutcome.UNREACHABLE, KnockOutcome.UNREACHABLE), setOf(keyId))
        h.start()
        advanceTimeBy(500)
        runCurrent()
        h.doorbell.onStopped()
        advanceTimeBy(500)
        runCurrent()
        h.resume()
        runCurrent()
        assertEquals(listOf(0L), h.calls)
        advanceTimeBy(KnockPolicy.START_GAP_MS - 1_000)
        runCurrent()
        assertEquals(listOf(0L, KnockPolicy.START_GAP_MS), h.calls)
    }

    @Test
    fun aQuickReturnAfterAnAnswerKeepsWaking() = runTest {
        val h = Harness(this, listOf(KnockOutcome.ANSWERED), setOf(keyId))
        h.start()
        advanceTimeBy(1_000)
        runCurrent()
        h.doorbell.onStopped()
        assertEquals(DoorbellFace.QUIET, h.doorbell.face.value)
        advanceTimeBy(1_000)
        runCurrent()
        h.resume()
        assertEquals(DoorbellFace.WAKING, h.doorbell.face.value)
        // Answered at 100 ms: the grace runs from the answer, not from the return.
        advanceTimeBy(100 + KnockPolicy.CONNECTING_GRACE_MS - 2_000 - 1)
        runCurrent()
        assertEquals(DoorbellFace.WAKING, h.doorbell.face.value)
        advanceUntilIdle()
        assertEquals(DoorbellFace.UNANSWERED, h.doorbell.face.value)
        assertEquals(1, h.calls.size)
    }

    @Test
    fun noStoredKeyIsNotABatteryProblem() = runTest {
        val h = Harness(this, emptyList(), setOf(keyId))
        h.key = null
        h.start()
        advanceUntilIdle()
        assertTrue(h.calls.isEmpty())
        assertEquals(DoorbellFace.QUIET, h.doorbell.face.value)
    }

    @Test
    fun twoPairingsAtOneAddressKnockOnce() = runTest {
        val h = Harness(this, listOf(KnockOutcome.ANSWERED), setOf(keyId, otherKeyId), hosts = listOf("192.168.1.20", "192.168.1.20"))
        assertEquals(setOf(otherKeyId), h.book.snapshot().keys)
        h.start()
        advanceUntilIdle()
        assertEquals(listOf(otherKeyId), h.knocked)
    }

    @Test
    fun aRebindDuringARoundKnocksAgainAfterIt() = runTest {
        val h = Harness(this, listOf(KnockOutcome.UNREACHABLE, KnockOutcome.UNREACHABLE), setOf(keyId))
        h.start()
        advanceTimeBy(500)
        runCurrent()
        h.doorbell.onServer(bound = true, rebound = true)
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(listOf(0L, 1_600L), h.calls)
        advanceUntilIdle()
        // The round ended at 1.7 s; the start owed by the rebind waits out the start gap.
        assertEquals(listOf(0L, 1_600L, KnockPolicy.START_GAP_MS, KnockPolicy.START_GAP_MS + 1_600), h.calls)
    }

    @Test
    fun theNextRoundWaitsForThePhonesGateFromTheLastConnect() = runTest {
        val h = Harness(this, listOf(KnockOutcome.UNREACHABLE, KnockOutcome.NO_HELLO, KnockOutcome.ANSWERED), setOf(keyId))
        h.start()
        advanceTimeBy(500)
        runCurrent()
        h.doorbell.onServer(bound = true, rebound = true)
        advanceUntilIdle()
        // Connected at 1.6 s: the round at 6 s holds its knock until 1.6 + 6 s.
        assertEquals(listOf(0L, 1_600L, 1_600L + KnockPolicy.ADMISSION_GAP_MS), h.calls)
    }

    @Test
    fun aReopenedScreenKeepsTheStartGap() = runTest {
        val times = KnockTimes()
        val h = Harness(this, listOf(KnockOutcome.UNREACHABLE, KnockOutcome.UNREACHABLE), setOf(keyId), times = times)
        h.start()
        advanceTimeBy(500)
        runCurrent()
        h.doorbell.onStopped()
        h.doorbell = h.newDoorbell()
        advanceTimeBy(500)
        runCurrent()
        h.start()
        runCurrent()
        assertEquals(listOf(0L), h.calls)
        advanceTimeBy(KnockPolicy.START_GAP_MS - 1_000)
        runCurrent()
        assertEquals(listOf(0L, KnockPolicy.START_GAP_MS), h.calls)
    }

    @Test
    fun aReopenedScreenKeepsWakingAfterAnAnswer() = runTest {
        val h = Harness(this, listOf(KnockOutcome.ANSWERED), setOf(keyId))
        h.start()
        advanceTimeBy(1_000)
        runCurrent()
        h.doorbell.onStopped()
        h.doorbell = h.newDoorbell()
        advanceTimeBy(1_000)
        runCurrent()
        h.resume()
        assertEquals(DoorbellFace.WAKING, h.doorbell.face.value)
        advanceUntilIdle()
        assertEquals(DoorbellFace.UNANSWERED, h.doorbell.face.value)
        assertEquals(1, h.calls.size)
    }

    @Test
    fun aKeyRoundMakesAnOwedStart() = runTest {
        val h = Harness(this, List(4) { KnockOutcome.SILENT }, setOf(keyId))
        h.local = null
        h.start()
        advanceUntilIdle()
        assertTrue(h.calls.isEmpty())
        h.local = "192.168.1.30"
        h.doorbell.onIdleKey()
        runCurrent()
        assertEquals(1, h.calls.size)
        h.doorbell.onServer(bound = true, rebound = false)
        advanceUntilIdle()
        assertEquals(1, h.calls.size)
    }

    @Test
    fun aPhoneForgottenMidRoundIsNotUnreachable() = runTest {
        val h = Harness(this, listOf(KnockOutcome.UNREACHABLE), setOf(keyId))
        h.start()
        advanceTimeBy(200)
        runCurrent()
        h.key = null
        h.book.forget(keyId)
        advanceUntilIdle()
        assertEquals(1, h.calls.size)
        assertEquals(DoorbellFace.QUIET, h.doorbell.face.value)
    }

    @Test
    fun anAskingPhoneShowsCheckYourPhoneForSixtySecondsThenQuiet() = runTest {
        val h = Harness(this, listOf(KnockOutcome.ASKED), setOf(keyId))
        h.start()
        advanceTimeBy(200)
        runCurrent()
        assertEquals(DoorbellFace.ASKING, h.doorbell.face.value)
        advanceTimeBy(KnockPolicy.ASK_WINDOW_MS - 500)
        runCurrent()
        assertEquals(DoorbellFace.ASKING, h.doorbell.face.value)
        advanceUntilIdle()
        assertEquals(DoorbellFace.QUIET, h.doorbell.face.value)
        assertEquals(listOf("knock_round trigger=start phones=1 outcome=asked sweep=none"), h.journal)
    }

    @Test
    fun whileAskingOnlyAKeyKnocksAgain() = runTest {
        val h = Harness(this, listOf(KnockOutcome.ASKED, KnockOutcome.ASKED), setOf(keyId))
        h.start()
        advanceTimeBy(200)
        runCurrent()
        h.doorbell.onServer(bound = true, rebound = true)
        advanceTimeBy(KnockPolicy.START_GAP_MS * 2)
        runCurrent()
        assertEquals(1, h.calls.size)
        assertEquals(DoorbellFace.ASKING, h.doorbell.face.value)
        h.doorbell.onIdleKey()
        runCurrent()
        assertEquals(2, h.calls.size)
    }

    @Test
    fun anAskingPhoneThatLinksQuietsTheScreen() = runTest {
        val h = Harness(this, listOf(KnockOutcome.ASKED), setOf(keyId))
        h.start()
        advanceTimeBy(200)
        runCurrent()
        h.linked = setOf(keyId)
        h.doorbell.onLinkChanged(true)
        assertEquals(DoorbellFace.QUIET, h.doorbell.face.value)
    }

    @Test
    fun aDeclineQuietsTheScreenAndEndsAutomaticRoundsForTheStartedPeriod() = runTest {
        val h = Harness(this, List(4) { KnockOutcome.DECLINED }, setOf(keyId, otherKeyId))
        h.start()
        advanceUntilIdle()
        // A decline makes way for the next phone.
        assertEquals(listOf(otherKeyId, keyId), h.knocked)
        assertEquals(DoorbellFace.QUIET, h.doorbell.face.value)
        h.doorbell.onServer(bound = true, rebound = true)
        advanceUntilIdle()
        assertEquals(2, h.calls.size)
        // The next STARTED period knocks again.
        h.doorbell.onStopped()
        h.start()
        advanceUntilIdle()
        assertEquals(4, h.calls.size)
        assertEquals(
            listOf(
                "knock_round trigger=start phones=2 outcome=declined sweep=none",
                "knock_round trigger=start phones=2 outcome=declined sweep=none",
            ),
            h.journal,
        )
    }

    @Test
    fun aListenerThatMovedPortIsFoundAndRemembered() = runTest {
        val h = Harness(this, listOf(KnockOutcome.UNREACHABLE, KnockOutcome.UNREACHABLE), setOf(keyId))
        h.listening[47_672] = KnockOutcome.ANSWERED
        h.start()
        advanceTimeBy(1_700)
        runCurrent()
        // The stored port twice, as before; then the sweep, which refusals cost nothing.
        assertEquals(listOf(0L, 1_600L), h.calls)
        assertEquals(listOf(1_700L to 47_671, 1_700L to 47_672), h.swept)
        advanceTimeBy(200)
        runCurrent()
        assertEquals(DoorbellFace.WAKING, h.doorbell.face.value)
        assertEquals(47_672, h.book.snapshot()[keyId]?.port)
        assertEquals("192.168.1.20", h.book.snapshot()[keyId]?.host)
        advanceUntilIdle()
        assertEquals(listOf("knock_round trigger=start phones=1 outcome=answered sweep=found"), h.journal)
    }

    @Test
    fun anAskFromASweptPortIsRememberedButARefusedProofIsNot() = runTest {
        val asked = Harness(this, listOf(KnockOutcome.UNREACHABLE, KnockOutcome.UNREACHABLE), setOf(keyId))
        asked.listening[47_679] = KnockOutcome.ASKED
        asked.start()
        advanceUntilIdle()
        assertEquals(47_679, asked.book.snapshot()[keyId]?.port)

        val silent = Harness(this, listOf(KnockOutcome.UNREACHABLE, KnockOutcome.UNREACHABLE), setOf(keyId))
        silent.listening[47_671] = KnockOutcome.SILENT
        silent.listening[47_672] = KnockOutcome.ANSWERED
        silent.start()
        advanceUntilIdle()
        // A connection ends the sweep: the phone counted its admission there.
        assertEquals(listOf(47_671), silent.swept.map { it.second })
        assertEquals(47_670, silent.book.snapshot()[keyId]?.port)
        assertEquals(DoorbellFace.UNANSWERED, silent.doorbell.face.value)
    }

    @Test
    fun aSweepThatFindsNothingKeepsTheStoredPortAndHints() = runTest {
        val h = Harness(this, listOf(KnockOutcome.UNREACHABLE, KnockOutcome.UNREACHABLE), setOf(keyId))
        h.start()
        advanceUntilIdle()
        assertEquals((47_671..47_679).toList(), h.swept.map { it.second })
        assertEquals(47_670, h.book.snapshot()[keyId]?.port)
        assertEquals(DoorbellFace.UNANSWERED, h.doorbell.face.value)
        assertEquals(listOf("knock_round trigger=start phones=1 outcome=unreachable sweep=missed"), h.journal)
    }

    @Test
    fun aSweepOfAHostThatDropsConnectsKeepsToItsBudget() = runTest {
        val h = Harness(this, listOf(KnockOutcome.UNREACHABLE, KnockOutcome.UNREACHABLE), setOf(keyId))
        h.sweepDropMs = KnockPolicy.CONNECT_TIMEOUT_MS.toLong()
        h.start()
        advanceUntilIdle()
        assertEquals(listOf(KnockPolicy.CONNECT_TIMEOUT_MS, (KnockPolicy.SWEEP_BUDGET_MS - KnockPolicy.CONNECT_TIMEOUT_MS).toInt()), h.sweepTimeouts)
        assertEquals(DoorbellFace.UNANSWERED, h.doorbell.face.value)
    }

    @Test
    fun aSweepStopsWhenTheAppStops() = runTest {
        val h = Harness(this, listOf(KnockOutcome.UNREACHABLE, KnockOutcome.UNREACHABLE), setOf(keyId))
        h.sweepDropMs = 1_000
        h.start()
        advanceTimeBy(2_000)
        runCurrent()
        h.doorbell.onStopped()
        advanceUntilIdle()
        assertEquals(1, h.swept.size)
        assertEquals(listOf("knock_round trigger=start phones=1 outcome=not_started sweep=missed"), h.journal)
    }

    @Test
    fun aReturnWithTheServerStillBoundKnocksAgain() = runTest {
        val h = Harness(this, List(2) { KnockOutcome.SILENT }, setOf(keyId))
        h.start()
        advanceUntilIdle()
        h.doorbell.onStopped()
        advanceTimeBy(60_000)
        // ON_START before the bind loop's first pass, which finds the same address still bound.
        h.doorbell.onStarted()
        h.doorbell.onServer(bound = true, rebound = false)
        advanceUntilIdle()
        assertEquals(2, h.calls.size)
        assertEquals(DoorbellFace.UNANSWERED, h.doorbell.face.value)
    }

    @Test
    fun skippedStartsAreJournaledOncePerReasonPerStartedPeriod() = runTest {
        val bare = Harness(this, emptyList(), emptySet())
        bare.start()
        bare.doorbell.onServer(bound = true, rebound = true)
        advanceUntilIdle()
        assertEquals(listOf("knock_skip reason=no_target"), bare.journal)
        bare.doorbell.onStopped()
        bare.start()
        advanceUntilIdle()
        assertEquals(listOf("knock_skip reason=no_target", "knock_skip reason=no_target"), bare.journal)

        val linked = Harness(this, emptyList(), setOf(keyId), linked = setOf(keyId))
        linked.start()
        advanceUntilIdle()
        assertEquals(listOf("knock_skip reason=linked"), linked.journal)

        val unbound = Harness(this, emptyList(), setOf(keyId))
        unbound.doorbell.onStarted()
        advanceUntilIdle()
        assertTrue(unbound.journal.isEmpty())
        unbound.doorbell.onStopped()
        assertEquals(listOf("knock_skip reason=not_ready"), unbound.journal)

        val noLan = Harness(this, emptyList(), setOf(keyId))
        noLan.local = null
        noLan.start()
        noLan.doorbell.onServer(bound = true, rebound = false)
        advanceUntilIdle()
        noLan.doorbell.onStopped()
        assertEquals(listOf("knock_skip reason=no_lan"), noLan.journal)
    }

    @Test
    fun aStartPostponedPastTheStopIsJournaledAsTheGap() = runTest {
        val h = Harness(this, List(2) { KnockOutcome.SILENT }, setOf(keyId))
        h.start()
        advanceUntilIdle()
        h.doorbell.onStopped()
        h.start()
        runCurrent()
        h.doorbell.onStopped()
        advanceUntilIdle()
        assertEquals(1, h.calls.size)
        assertEquals(listOf("knock_round trigger=start phones=1 outcome=silent sweep=none", "knock_skip reason=gap"), h.journal)
    }

    @Test
    fun roundsAreJournaledWithTheirTrigger() = runTest {
        val h = Harness(this, List(3) { KnockOutcome.SILENT }, setOf(keyId))
        h.start()
        advanceUntilIdle()
        h.doorbell.onServer(bound = true, rebound = true)
        advanceUntilIdle()
        advanceTimeBy(KnockPolicy.KEY_GAP_MS)
        h.doorbell.onIdleKey()
        advanceUntilIdle()
        assertEquals(
            listOf("start", "rebind", "key").map { "knock_round trigger=$it phones=1 outcome=silent sweep=none" },
            h.journal,
        )
    }

    @Test
    fun aStartDroppedForALinkIsOwedWhenTheLastLinkDrops() = runTest {
        val h = Harness(this, List(3) { KnockOutcome.SILENT }, setOf(keyId))
        h.start()
        advanceUntilIdle()
        assertEquals(listOf(0L), h.calls)
        h.doorbell.onStopped()
        advanceTimeBy(1_000)
        h.linked = setOf(keyId)
        h.doorbell.onLinkChanged(true)
        h.resume()
        advanceUntilIdle()
        assertEquals(listOf(0L), h.calls)
        advanceTimeBy(1_000)
        h.linked = emptySet()
        h.doorbell.onLinkChanged(false)
        runCurrent()
        // Still inside the start gap from the round at 0.
        assertEquals(listOf(0L), h.calls)
        advanceUntilIdle()
        assertEquals(listOf(0L, KnockPolicy.START_GAP_MS), h.calls)
        // Owed once: a link that comes and goes after that round knocks nothing more.
        h.linked = setOf(keyId)
        h.doorbell.onLinkChanged(true)
        h.linked = emptySet()
        h.doorbell.onLinkChanged(false)
        advanceUntilIdle()
        assertEquals(2, h.calls.size)
        assertEquals(
            listOf(
                "knock_round trigger=start phones=1 outcome=silent sweep=none",
                "knock_skip reason=linked",
                "knock_round trigger=start phones=1 outcome=silent sweep=none",
            ),
            h.journal,
        )
    }

    @Test
    fun aLinkArrivingWhileTheStartIsOwedSkipsItAsLinkedUntilItDrops() = runTest {
        val h = Harness(this, listOf(KnockOutcome.SILENT), setOf(keyId))
        // STARTED before the server is bound: the start is owed.
        h.doorbell.onStarted()
        h.linked = setOf(keyId)
        h.doorbell.onLinkChanged(true)
        h.doorbell.onServer(bound = true, rebound = false)
        advanceUntilIdle()
        assertTrue(h.calls.isEmpty())
        assertEquals(listOf("knock_skip reason=linked"), h.journal)
        h.linked = emptySet()
        h.doorbell.onLinkChanged(false)
        advanceUntilIdle()
        assertEquals(1, h.calls.size)
    }

    @Test
    fun theAskWindowEndsSixtySecondsAfterTheAsk() = runTest {
        val h = Harness(this, listOf(KnockOutcome.ASKED), setOf(keyId))
        h.start()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(DoorbellFace.ASKING, h.doorbell.face.value)
        // Asked at 100 ms.
        advanceTimeBy(KnockPolicy.ASK_WINDOW_MS - 1)
        runCurrent()
        assertEquals(DoorbellFace.ASKING, h.doorbell.face.value)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(DoorbellFace.QUIET, h.doorbell.face.value)
    }

    @Test
    fun aRebindDuringTheAskWindowKnocksWhenItEnds() = runTest {
        val h = Harness(this, listOf(KnockOutcome.ASKED, KnockOutcome.SILENT), setOf(keyId))
        h.start()
        advanceTimeBy(200)
        runCurrent()
        h.doorbell.onServer(bound = true, rebound = true)
        h.doorbell.onServer(bound = true, rebound = false)
        advanceTimeBy(KnockPolicy.ASK_WINDOW_MS - 1_000)
        runCurrent()
        assertEquals(1, h.calls.size)
        assertEquals(DoorbellFace.ASKING, h.doorbell.face.value)
        advanceUntilIdle()
        assertEquals(listOf(0L, 100L + KnockPolicy.ASK_WINDOW_MS), h.calls)
        assertEquals(
            listOf(
                "knock_round trigger=start phones=1 outcome=asked sweep=none",
                "knock_round trigger=rebind phones=1 outcome=silent sweep=none",
            ),
            h.journal,
        )
    }

    @Test
    fun aDeclineMakesWayAndAnAskAfterItKeepsAutomaticRounds() = runTest {
        val h = Harness(
            this, listOf(KnockOutcome.DECLINED, KnockOutcome.ASKED, KnockOutcome.ANSWERED), setOf(keyId, otherKeyId),
        )
        h.start()
        advanceTimeBy(300)
        runCurrent()
        assertEquals(listOf(otherKeyId, keyId), h.knocked)
        assertEquals(DoorbellFace.ASKING, h.doorbell.face.value)
        advanceUntilIdle()
        assertEquals(DoorbellFace.QUIET, h.doorbell.face.value)
        assertEquals(listOf("knock_round trigger=start phones=2 outcome=asked sweep=none"), h.journal)
        // Not declined for the STARTED period: a rebind still knocks.
        h.doorbell.onServer(bound = true, rebound = true)
        advanceUntilIdle()
        assertEquals(listOf(otherKeyId, keyId, otherKeyId), h.knocked)
    }

    @Test
    fun theSweepGoesPastAListenerThatNeverSaysHello() = runTest {
        val h = Harness(this, listOf(KnockOutcome.UNREACHABLE, KnockOutcome.UNREACHABLE), setOf(keyId))
        h.listening[47_671] = KnockOutcome.NO_HELLO
        h.listening[47_673] = KnockOutcome.ANSWERED
        h.start()
        advanceUntilIdle()
        assertEquals(listOf(47_671, 47_672, 47_673), h.swept.map { it.second })
        assertEquals(47_673, h.book.snapshot()[keyId]?.port)
        assertEquals(listOf("knock_round trigger=start phones=1 outcome=answered sweep=found"), h.journal)

        val none = Harness(this, listOf(KnockOutcome.UNREACHABLE, KnockOutcome.UNREACHABLE), setOf(keyId))
        none.listening[47_671] = KnockOutcome.NO_HELLO
        none.start()
        advanceUntilIdle()
        assertEquals((47_671..47_679).toList(), none.swept.map { it.second })
        assertEquals(47_670, none.book.snapshot()[keyId]?.port)
        assertEquals(DoorbellFace.UNANSWERED, none.doorbell.face.value)
        assertEquals(listOf("knock_round trigger=start phones=1 outcome=unreachable sweep=missed"), none.journal)
    }

    // --- Socket client against a loopback phone --------------------------------

    @Test
    fun linesAreStrictUtf8() {
        assertEquals("{\"t\":\"knockOk\",\"v\":1}", KnockWire.decodeLine("{\"t\":\"knockOk\",\"v\":1}".toByteArray()))
        assertEquals("é", KnockWire.decodeLine(byteArrayOf(0xC3.toByte(), 0xA9.toByte())))
        assertNull(KnockWire.decodeLine(byteArrayOf(0xFF.toByte())))
        assertNull(KnockWire.decodeLine(byteArrayOf(0xC3.toByte())))
        assertNull(KnockWire.decodeLine(byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte())))
    }

    @Test
    fun clientRejectsAHelloThatIsNotUtf8() = runBlocking {
        val loopback = InetAddress.getLoopbackAddress()
        ServerSocket(0, 1, loopback).use { server ->
            val phone = thread {
                server.accept().use { s ->
                    val hello = "{\"t\":\"knockHello\",\"v\":1,\"phoneNonce\":\"$phoneNonce\",\"x\":\"".toByteArray() +
                        byteArrayOf(0xFF.toByte()) + "\"}\n".toByteArray()
                    s.getOutputStream().write(hello)
                    s.getInputStream().read()
                }
            }
            val outcome = SocketKnockTransport().knock(loopback.hostAddress!!, server.localPort, loopback.hostAddress!!, tvId, keyId, key) { true }
            phone.join()
            assertEquals(KnockOutcome.NO_HELLO, outcome)
        }
    }

    @Test
    fun clientProvesItselfToAListenerAndReadsKnockOk() = runBlocking {
        val loopback = InetAddress.getLoopbackAddress()
        ServerSocket(0, 1, loopback).use { server ->
            var seen: String? = null
            val phone = thread {
                server.accept().use { s ->
                    s.getOutputStream().write("{\"t\":\"knockHello\",\"v\":1,\"phoneNonce\":\"$phoneNonce\"}\n".toByteArray())
                    val line = BufferedReader(InputStreamReader(s.getInputStream())).readLine()
                    seen = line
                    val o = StrictJson.objectOnly(line)!!
                    val tvNonceSent = (o.fields["tvNonce"] as com.flick.receiver.net.StrictJsonValue.Str).value
                    val proof = (o.fields["proof"] as com.flick.receiver.net.StrictJsonValue.Str).value
                    val expected = KnockWire.proof(key, tvId, keyId, phoneNonce, tvNonceSent, loopback.hostAddress!!, server.localPort)
                    if (expected == proof) s.getOutputStream().write("{\"t\":\"knockOk\",\"v\":1}\n".toByteArray())
                }
            }
            val outcome = SocketKnockTransport().knock(loopback.hostAddress!!, server.localPort, loopback.hostAddress!!, tvId, keyId, key) { true }
            phone.join()
            assertEquals(KnockOutcome.ANSWERED, outcome)
            assertTrue(seen!!.contains("\"phoneNonce\":\"$phoneNonce\""))
        }
    }

    @Test
    fun clientReadsAnAskAndADecline() = runBlocking {
        val loopback = InetAddress.getLoopbackAddress()
        listOf(
            "{\"t\":\"knockOk\",\"v\":1,\"ask\":true}" to KnockOutcome.ASKED,
            "{\"t\":\"knockOk\",\"v\":1,\"declined\":true}" to KnockOutcome.DECLINED,
        ).forEach { (reply, expected) ->
            ServerSocket(0, 1, loopback).use { server ->
                val phone = thread {
                    server.accept().use { s ->
                        s.getOutputStream().write("{\"t\":\"knockHello\",\"v\":1,\"phoneNonce\":\"$phoneNonce\"}\n".toByteArray())
                        BufferedReader(InputStreamReader(s.getInputStream())).readLine()
                        s.getOutputStream().write("$reply\n".toByteArray())
                    }
                }
                val outcome = SocketKnockTransport().knock(loopback.hostAddress!!, server.localPort, loopback.hostAddress!!, tvId, keyId, key) { true }
                phone.join()
                assertEquals(expected, outcome)
            }
        }
    }

    @Test
    fun clientSendsNoProofWhenNoLongerStartedAndReportsSilence() = runBlocking {
        val loopback = InetAddress.getLoopbackAddress()
        ServerSocket(0, 1, loopback).use { server ->
            var received: String? = "unset"
            val phone = thread {
                server.accept().use { s ->
                    s.getOutputStream().write("{\"t\":\"knockHello\",\"v\":1,\"phoneNonce\":\"$phoneNonce\"}\n".toByteArray())
                    received = BufferedReader(InputStreamReader(s.getInputStream())).readLine()
                }
            }
            val outcome = SocketKnockTransport().knock(loopback.hostAddress!!, server.localPort, loopback.hostAddress!!, tvId, keyId, key) { false }
            phone.join()
            assertEquals(KnockOutcome.NOT_STARTED, outcome)
            assertNull(received)
        }
    }

    @Test
    fun clientGivesUpOnAListenerThatNeverSaysHello() = runBlocking {
        val loopback = InetAddress.getLoopbackAddress()
        ServerSocket(0, 1, loopback).use { server ->
            val phone = thread { runCatching { server.accept().use { Thread.sleep(2_500) } } }
            val outcome = SocketKnockTransport().knock(loopback.hostAddress!!, server.localPort, loopback.hostAddress!!, tvId, keyId, key) { true }
            assertEquals(KnockOutcome.NO_HELLO, outcome)
            phone.join()
        }
    }

    @Test
    fun clientReportsSilenceWhenTheProofIsRefused() = runBlocking {
        val loopback = InetAddress.getLoopbackAddress()
        ServerSocket(0, 1, loopback).use { server ->
            val phone = thread {
                server.accept().use { s ->
                    s.getOutputStream().write("{\"t\":\"knockHello\",\"v\":1,\"phoneNonce\":\"$phoneNonce\"}\n".toByteArray())
                    BufferedReader(InputStreamReader(s.getInputStream())).readLine()
                }
            }
            val outcome = SocketKnockTransport().knock(loopback.hostAddress!!, server.localPort, loopback.hostAddress!!, tvId, keyId, key) { true }
            phone.join()
            assertEquals(KnockOutcome.SILENT, outcome)
        }
    }

    @Test
    fun aKeyThatIsNotBase64IsNoKeyAndDialsNothing() = runBlocking {
        val loopback = InetAddress.getLoopbackAddress()
        ServerSocket(0, 1, loopback).use { server ->
            server.soTimeout = 300
            var connected = false
            val outcome = SocketKnockTransport().knock(
                loopback.hostAddress!!, server.localPort, loopback.hostAddress!!, tvId, keyId, "not base64!",
                connected = { connected = true },
            ) { true }
            assertEquals(KnockOutcome.NO_KEY, outcome)
            assertFalse(connected)
            assertTrue(runCatching { server.accept().close() }.isFailure)
        }
    }

    @Test
    fun aConnectionCutBeforeTheProofIsNoHello() = runBlocking {
        val loopback = InetAddress.getLoopbackAddress()
        ServerSocket(0, 1, loopback).use { server ->
            val phone = thread {
                server.accept().use { s ->
                    // A reset, not a clean close: the read fails with an I/O error, not end-of-stream.
                    s.setSoLinger(true, 0)
                }
            }
            var connected = false
            val outcome = SocketKnockTransport().knock(
                loopback.hostAddress!!, server.localPort, loopback.hostAddress!!, tvId, keyId, key,
                connected = { connected = true },
            ) { true }
            phone.join()
            assertEquals(KnockOutcome.NO_HELLO, outcome)
            assertTrue(connected)
        }
    }

    private class MemoryStorage(val values: MutableMap<String, String> = mutableMapOf()) : KnockAddressStorage {
        override fun loadAll(): Map<String, String> = HashMap(values)
        override fun save(keyId: String, encoded: String) { values[keyId] = encoded }
        override fun remove(keyId: String) { values.remove(keyId) }
        override fun clear() = values.clear()
    }
}
