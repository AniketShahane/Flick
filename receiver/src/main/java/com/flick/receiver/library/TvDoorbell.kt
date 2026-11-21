package com.flick.receiver.library

import com.flick.receiver.net.StrictJson
import com.flick.receiver.net.integer
import com.flick.receiver.net.string
import com.flick.receiver.util.FlickLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The TV's half of the doorbell: the TV knocks on the phone's listener with a proof made
 * from the pairing key, and the phone answers by resuming its ordinary control-v2 and
 * `/library` connection to its stored TV. `docs/design/library-channel.md` §13 is the
 * contract (§6.7 `knockAt`, §11 fixture, §12 privacy); the phone's `TvDoorbell` is the other half.
 */
object KnockWire {
    const val VERSION = 1L

    /** Every line either side sends, newline excluded. */
    const val MAX_LINE_BYTES = 512

    private const val DOMAIN = "Flick-Knock-V1"
    private const val ROLE_TV = "tv"

    /** Each field as a four-byte big-endian length, then its UTF-8 bytes. */
    fun transcript(
        tvId: String, keyId: String, phoneNonce: String, tvNonce: String, phoneHost: String, phonePort: Int,
    ): ByteArray {
        val fields = listOf(DOMAIN, ROLE_TV, VERSION.toString(), tvId, keyId, phoneNonce, tvNonce, phoneHost, phonePort.toString())
        val out = ByteArrayOutputStream()
        fields.forEach { field ->
            val bytes = field.toByteArray(StandardCharsets.UTF_8)
            out.write(ByteBuffer.allocate(4).putInt(bytes.size).array())
            out.write(bytes)
        }
        return out.toByteArray()
    }

    /** Whether [key] is a pairing key a proof can be made from. */
    fun keyUsable(key: String): Boolean =
        try {
            Base64.getUrlDecoder().decode(key).isNotEmpty()
        } catch (_: IllegalArgumentException) {
            false
        }

    fun proof(
        key: String, tvId: String, keyId: String, phoneNonce: String, tvNonce: String, phoneHost: String, phonePort: Int,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(Base64.getUrlDecoder().decode(key), "HmacSHA256"))
        val digest = mac.doFinal(transcript(tvId, keyId, phoneNonce, tvNonce, phoneHost, phonePort))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    /** The phone's `knockHello`: its nonce, or null for anything else. Unknown fields are ignored. */
    fun parseHello(line: String): String? {
        val o = objectOf(line, "knockHello") ?: return null
        return o.string("phoneNonce")?.value?.takeIf(LibraryAuth::nonce)
    }

    fun isOk(line: String): Boolean = objectOf(line, "knockOk") != null

    /** A line's bytes as strict UTF-8, or null: a malformed line is a protocol failure, never repaired. */
    fun decodeLine(bytes: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /** Null when a field is not the shape the phone requires, so nothing malformed is ever sent. */
    fun knock(tvId: String, keyId: String, phoneNonce: String, tvNonce: String, proof: String): String? {
        if (!listOf(tvId, keyId, phoneNonce, tvNonce).all(LibraryAuth::nonce) || !LibraryAuth.proofValue(proof)) return null
        return "{\"t\":\"knock\",\"v\":$VERSION,\"tvId\":\"$tvId\",\"keyId\":\"$keyId\",\"phoneNonce\":\"$phoneNonce\"," +
            "\"tvNonce\":\"$tvNonce\",\"proof\":\"$proof\"}"
    }

    private fun objectOf(line: String, type: String) =
        line.takeIf { it.toByteArray(StandardCharsets.UTF_8).size <= MAX_LINE_BYTES }
            ?.let(StrictJson::objectOnly)
            ?.takeIf { it.string("t")?.value == type && it.integer("v") == VERSION }
}

/**
 * Where a paired phone's listener was last seen. Either half may be unknown. [proven] orders
 * the entries by when their host was last proven from a `/library` socket; 0 is unknown.
 */
data class KnockAddress(val host: String?, val port: Int?, val proven: Long = 0L) {
    val dialable: Boolean get() = host != null && port != null
}

/**
 * The address book's at-rest form: one value per paired phone, `1|host|port`, unknown halves
 * empty, then `|proven` once the host's recency is known.
 */
object KnockAddressCodec {
    private const val FORMAT = "1"
    private val KeyIdShape = Regex("[A-Za-z0-9_-]{22}")

    fun keyId(value: String?): Boolean = value != null && KeyIdShape.matches(value)

    fun port(value: Int?): Boolean = value != null && value in 1..65_535

    /** A canonical dotted quad in 10/8, 172.16/12 or 192.168/16: the only peers a phone listens to. */
    fun privateIpv4(value: String?): Boolean {
        val parts = value?.split('.')?.takeIf { it.size == 4 } ?: return false
        val octets = parts.map { part ->
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' } || (part.length > 1 && part[0] == '0')) return false
            part.toInt().takeIf { it <= 255 } ?: return false
        }
        return octets[0] == 10 || (octets[0] == 172 && octets[1] in 16..31) || (octets[0] == 192 && octets[1] == 168)
    }

    fun encode(address: KnockAddress): String =
        "$FORMAT|${address.host.orEmpty()}|${address.port?.toString().orEmpty()}" +
            (if (address.proven > 0) "|${address.proven}" else "")

    fun decode(value: String?): KnockAddress? {
        val parts = value?.split('|')?.takeIf { (it.size == 3 || it.size == 4) && it[0] == FORMAT } ?: return null
        val host = parts[1].ifEmpty { null }
        val port = parts[2].ifEmpty { null }?.let { number(it, 5) ?: return null }
        val proven = if (parts.size == 4) number(parts[3], 18)?.takeIf { it > 0 } ?: return null else 0L
        if (host != null && !privateIpv4(host)) return null
        if (port != null && !port(port.toInt())) return null
        if (host == null && port == null) return null
        return KnockAddress(host, port?.toInt(), proven)
    }

    private fun number(raw: String, maxDigits: Int): Long? =
        raw.takeIf { it.isNotEmpty() && it.length <= maxDigits && it.all { c -> c in '0'..'9' } && !(it.length > 1 && it[0] == '0') }
            ?.toLong()
}

/**
 * The address book's storage, by key id. Excluded from backup and device transfer. Called
 * under the book's lock, so writes must not wait on the disk; the book's memory is authoritative.
 */
interface KnockAddressStorage {
    fun loadAll(): Map<String, String>
    fun save(keyId: String, encoded: String)
    fun remove(keyId: String)
    fun clear()
}

/**
 * Per paired phone: the peer IPv4 of its last authenticated `/library` socket and the port of
 * its last `knockAt`. Every method takes only this class's own lock and never calls out under
 * it, so it is safe from the library server and the forget path alike.
 */
class KnockAddressBook(private val storage: KnockAddressStorage) {
    private val lock = Any()
    private val entries: HashMap<String, KnockAddress> = HashMap<String, KnockAddress>().apply {
        storage.loadAll().forEach { (keyId, raw) ->
            val address = KnockAddressCodec.decode(raw)
            if (KnockAddressCodec.keyId(keyId) && address != null) put(keyId, address) else storage.remove(keyId)
        }
    }

    private var lastProven = entries.values.maxOfOrNull { it.proven } ?: 0L

    fun snapshot(): Map<String, KnockAddress> = synchronized(lock) { HashMap(entries) }

    /**
     * An address belongs to the last pairing proven from it: a re-paired phone, or another
     * phone given a forgotten one's IP, would otherwise be knocked twice at once under two
     * keyIds, and the phone admits one knock per peer per 5 s, so the stale one can shut out
     * the valid one. Every other entry at [host] is therefore dropped.
     *
     * [stillPaired] is read after the write: a forget that removed the pairing before it
     * finds nothing to delete here later, so the write is undone instead.
     */
    fun recordPeer(keyId: String, host: String, stillPaired: () -> Boolean) {
        if (!KnockAddressCodec.privateIpv4(host) || !KnockAddressCodec.keyId(keyId)) return
        synchronized(lock) {
            entries.filter { (id, address) -> id != keyId && address.host == host }.keys.forEach { stale ->
                entries.remove(stale)
                storage.remove(stale)
            }
            val current = entries[keyId]
            if (current != null && current.host == host && current.proven > 0 && current.proven == lastProven) return@synchronized
            val next = (current ?: KnockAddress(null, null)).copy(host = host, proven = ++lastProven)
            entries[keyId] = next
            storage.save(keyId, KnockAddressCodec.encode(next))
        }
        if (!stillPaired()) forget(keyId)
    }

    fun recordPort(keyId: String, port: Int, stillPaired: () -> Boolean) {
        if (!KnockAddressCodec.port(port)) return
        update(keyId, stillPaired) { it?.copy(port = port) ?: KnockAddress(null, port) }
    }

    fun forget(keyId: String) = synchronized(lock) {
        entries.remove(keyId)
        storage.remove(keyId)
    }

    fun forgetAll() = synchronized(lock) {
        entries.clear()
        storage.clear()
    }

    /** Drops entries for phones this TV no longer has a pairing for. */
    fun retainOnly(pairedKeyIds: Set<String>) = synchronized(lock) {
        (entries.keys - pairedKeyIds).forEach { forget(it) }
    }

    private fun update(keyId: String, stillPaired: () -> Boolean, change: (KnockAddress?) -> KnockAddress) {
        if (!KnockAddressCodec.keyId(keyId)) return
        synchronized(lock) {
            val next = change(entries[keyId])
            if (entries[keyId] == next) return@synchronized
            entries[keyId] = next
            storage.save(keyId, KnockAddressCodec.encode(next))
        }
        if (!stillPaired()) forget(keyId)
    }
}

/** What the idle screen says about a knock. */
enum class DoorbellFace {
    /** Nothing to say: today's idle text. */
    QUIET,

    /** A knock is out, or its phone is connecting. */
    WAKING,

    /** A phone did not answer, or none connected after answering: today's text and the battery hint. */
    UNANSWERED,
}

/**
 * How one knock attempt ended. NO_HELLO covers every failure after the connect and before a
 * proof went out; SILENT only a phone that said nothing after the proof.
 */
enum class KnockOutcome { ANSWERED, NO_LAN, UNREACHABLE, NO_HELLO, SILENT, NO_KEY, NOT_STARTED }

/**
 * The doorbell's clocks (elapsed-realtime ms), held for the life of the process rather than
 * one screen's, so leaving and reopening the app keeps its gaps and its connecting window.
 * Never persisted: elapsed time means nothing after a reboot.
 */
class KnockTimes {
    @Volatile var lastRoundAtMs: Long? = null
    @Volatile var lastAnsweredAtMs: Long? = null
    private val connectedAtMs = HashMap<String, Long>()

    /** When a knock last connected to the phone at [host]: the moment it could have admitted one. */
    fun connectedAt(host: String): Long? = synchronized(connectedAtMs) { connectedAtMs[host] }

    fun onConnected(host: String, nowMs: Long) = synchronized(connectedAtMs) {
        connectedAtMs.values.removeAll { nowMs - it >= KnockPolicy.ADMISSION_GAP_MS }
        connectedAtMs[host] = nowMs
    }

    companion object {
        val process = KnockTimes()
    }
}

/** The doorbell's timing and choices, kept apart from sockets and coroutines. */
object KnockPolicy {
    const val CONNECT_TIMEOUT_MS = 2_500
    const val HELLO_TIMEOUT_MS = 1_500L
    const val OK_TIMEOUT_MS = 2_000L
    const val ATTEMPTS = 2
    const val RETRY_GAP_MS = 1_500L
    const val KEY_GAP_MS = 10_000L

    /** How long after `knockOk` the screen keeps saying it is waking the phone. */
    const val CONNECTING_GRACE_MS = 8_000L

    /**
     * Between the starts of two start rounds. The phone accepts one knock per 5 s, counted when
     * it greets (library-channel §13), so a round sooner than that is refused before its proof.
     */
    const val START_GAP_MS = 6_000L

    /**
     * From a knock's connect to the next knock at the same phone. The phone's 5-s gate counts
     * from accepting the connection, which can come seconds into a round, so [START_GAP_MS]
     * alone does not cover it.
     */
    const val ADMISSION_GAP_MS = 6_000L

    /**
     * Paired phones with a whole stored address and no authenticated library socket, one per
     * host, in the order a round knocks them: most recently proven first. A host a linked
     * phone holds is skipped.
     */
    fun targets(paired: Set<String>, book: Map<String, KnockAddress>, linked: Set<String>): List<String> {
        val linkedHosts = linked.mapNotNullTo(HashSet()) { book[it]?.host }
        val newestFirst = compareByDescending<String> { book.getValue(it).proven }.thenBy { it }
        return paired
            .filter { it !in linked && book[it]?.dialable == true && book.getValue(it).host !in linkedHosts }
            .groupBy { book.getValue(it).host }
            .values
            .map { sameHost -> sameHost.minWith(newestFirst) }
            .sortedWith(newestFirst)
    }

    /**
     * Whether a round moves on to its next phone after [outcome]. The control server holds one
     * controller and a second phone's login displaces the first, so only a phone that never
     * answered `knockOk` makes way; no LAN on the TV, or a stop, ends the round.
     */
    fun triesNext(outcome: KnockOutcome): Boolean =
        outcome == KnockOutcome.UNREACHABLE || outcome == KnockOutcome.NO_HELLO ||
            outcome == KnockOutcome.SILENT || outcome == KnockOutcome.NO_KEY

    /** A remote key on the idle screen knocks only while no phone is linked, at most once per [KEY_GAP_MS]. */
    fun keyMayKnock(anyLinked: Boolean, lastRoundAtMs: Long?, nowMs: Long): Boolean =
        !anyLinked && (lastRoundAtMs == null || nowMs - lastRoundAtMs >= KEY_GAP_MS)

    /** How long a start round must still wait for [START_GAP_MS]; 0 when it may begin now. */
    fun startWaitMs(lastRoundAtMs: Long?, nowMs: Long): Long = waitMs(START_GAP_MS, lastRoundAtMs, nowMs)

    /** How long a knock at a phone last connected to at [connectedAtMs] must still wait; 0 when it may go now. */
    fun admissionWaitMs(connectedAtMs: Long?, nowMs: Long): Long = waitMs(ADMISSION_GAP_MS, connectedAtMs, nowMs)

    private fun waitMs(gapMs: Long, sinceMs: Long?, nowMs: Long): Long =
        if (sinceMs == null) 0L else (gapMs - (nowMs - sinceMs)).coerceAtLeast(0L)

    /** Whether a phone that answered at [answeredAtMs] may still be connecting. */
    fun stillConnecting(answeredAtMs: Long?, nowMs: Long): Boolean =
        answeredAtMs != null && nowMs - answeredAtMs < CONNECTING_GRACE_MS

    /**
     * Whether [outcome] says the phone did not answer, which is what the battery hint is for.
     * No LAN on the TV, or no stored key, is not the phone's battery.
     */
    fun hints(outcome: KnockOutcome): Boolean =
        outcome == KnockOutcome.UNREACHABLE || outcome == KnockOutcome.NO_HELLO || outcome == KnockOutcome.SILENT

    /**
     * Whether [outcome] of attempt [attempt] (from 1) is worth another try. Only a knock that
     * never got a connection through is retried: the phone counts its admission when it
     * accepts one (library-channel §13), so anything 1.5 s after a connect is refused anyway.
     */
    fun retries(outcome: KnockOutcome, attempt: Int): Boolean =
        attempt < ATTEMPTS && (outcome == KnockOutcome.UNREACHABLE || outcome == KnockOutcome.NO_LAN)

    /** [hinted]: some knock of the round ended in an outcome that [hints]. */
    fun face(pending: Int, answered: Boolean, graceOver: Boolean, linked: Boolean, began: Boolean, hinted: Boolean): DoorbellFace =
        when {
            !began || linked -> DoorbellFace.QUIET
            pending > 0 -> DoorbellFace.WAKING
            answered -> if (graceOver) DoorbellFace.UNANSWERED else DoorbellFace.WAKING
            hinted -> DoorbellFace.UNANSWERED
            else -> DoorbellFace.QUIET
        }
}

/** One knock exchange with a phone's listener. */
interface KnockTransport {
    /**
     * [connected] is called once the connect succeeds. [mayProve] is asked just before the
     * proof is sent; false sends nothing.
     */
    suspend fun knock(
        host: String, port: Int, localHost: String, tvId: String, keyId: String, key: String,
        connected: () -> Unit = {}, mayProve: () -> Boolean,
    ): KnockOutcome
}

/** [KnockTransport] over a plain TCP socket bound to the TV's own LAN address. */
class SocketKnockTransport(
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val random: SecureRandom = SecureRandom(),
    private val clock: () -> Long = System::nanoTime,
) : KnockTransport {

    override suspend fun knock(
        host: String, port: Int, localHost: String, tvId: String, keyId: String, key: String,
        connected: () -> Unit, mayProve: () -> Boolean,
    ): KnockOutcome = coroutineScope {
        if (!KnockWire.keyUsable(key)) return@coroutineScope KnockOutcome.NO_KEY
        val socket = Socket()
        val work = async(io) { socket.use { exchange(it, host, port, localHost, tvId, keyId, key, connected, mayProve) } }
        try {
            work.await()
        } catch (e: CancellationException) {
            // Unblocks the read the IO thread is parked in, so a stop never waits out a deadline.
            runCatching { socket.close() }
            throw e
        }
    }

    private fun exchange(
        socket: Socket, host: String, port: Int, localHost: String, tvId: String, keyId: String, key: String,
        connected: () -> Unit, mayProve: () -> Boolean,
    ): KnockOutcome {
        try {
            socket.bind(InetSocketAddress(InetAddress.getByName(localHost), 0))
            socket.connect(InetSocketAddress(InetAddress.getByName(host), port), KnockPolicy.CONNECT_TIMEOUT_MS)
        } catch (_: Exception) {
            return KnockOutcome.UNREACHABLE
        }
        connected()
        var proofSent = false
        return try {
            socket.tcpNoDelay = true
            val input = socket.getInputStream()
            val phoneNonce = readLine(socket, input, KnockPolicy.HELLO_TIMEOUT_MS)?.let(KnockWire::parseHello)
                ?: return KnockOutcome.NO_HELLO
            val tvNonce = LibraryAuth.randomNonce(random)
            val proof = KnockWire.proof(key, tvId, keyId, phoneNonce, tvNonce, host, port)
            val frame = KnockWire.knock(tvId, keyId, phoneNonce, tvNonce, proof) ?: return KnockOutcome.NO_KEY
            if (!mayProve()) return KnockOutcome.NOT_STARTED
            // From the first byte written the phone may have the proof, so nothing may retry it.
            proofSent = true
            socket.getOutputStream().apply {
                write((frame + "\n").toByteArray(StandardCharsets.UTF_8))
                flush()
            }
            val answer = readLine(socket, input, KnockPolicy.OK_TIMEOUT_MS)
            if (answer != null && KnockWire.isOk(answer)) KnockOutcome.ANSWERED else KnockOutcome.SILENT
        } catch (_: Exception) {
            if (proofSent) KnockOutcome.SILENT else KnockOutcome.NO_HELLO
        }
    }

    /** One `\n`-terminated line within [budgetMs] in total, or null when it is late, long or cut off. */
    private fun readLine(socket: Socket, input: InputStream, budgetMs: Long): String? {
        val deadline = clock() + budgetMs * 1_000_000L
        val out = ByteArrayOutputStream()
        while (true) {
            val leftMs = (deadline - clock()) / 1_000_000L
            if (leftMs <= 0) return null
            socket.soTimeout = leftMs.toInt().coerceAtLeast(1)
            val b = try {
                input.read()
            } catch (_: SocketTimeoutException) {
                return null
            }
            when {
                b < 0 -> return null
                b == '\n'.code -> return KnockWire.decodeLine(out.toByteArray())
                out.size() >= KnockWire.MAX_LINE_BYTES -> return null
                else -> out.write(b)
            }
        }
    }
}

/**
 * Decides when the TV knocks and what the idle screen says about it. It knocks only while
 * [onStarted] holds and the control server is listening ([onServer]), since the phone's
 * answer is a control resume to that server: once per start, again after the server
 * re-binds, and on [onIdleKey], always only while no phone is linked. A round wakes one phone
 * at a time, most recently proven first, moving on only when one never answered. Without a
 * LAN address it waits rather than failing.
 *
 * [keyFor] reads the pairing store and is called off the main thread with no lock held.
 * [times] outlives this object, so a reopened screen keeps the gaps of the last one.
 */
class TvDoorbell(
    private val book: KnockAddressBook,
    private val pairedKeyIds: () -> Set<String>,
    private val linkedKeyIds: () -> Set<String>,
    private val keyFor: (keyId: String) -> String?,
    private val tvId: () -> String,
    private val localHost: () -> String?,
    private val transport: KnockTransport,
    private val clock: () -> Long,
    private val scope: CoroutineScope,
    private val work: CoroutineDispatcher = Dispatchers.IO,
    private val times: KnockTimes = KnockTimes(),
) {
    private val lock = Any()
    private var started = false
    private var serverReady = false
    private var startOwed = false
    private var rebindOwed = false
    private var round: Job? = null
    private var grace: Job? = null
    private var later: Job? = null
    private var generation = 0L
    private var began = false
    private var pending = 0
    private var answered = false
    private var graceOver = false
    private var hinted = false
    private var notReady = false
    private var linked = false

    private val _face = MutableStateFlow(DoorbellFace.QUIET)
    val face: StateFlow<DoorbellFace> = _face.asStateFlow()

    fun onStarted() {
        synchronized(lock) {
            started = true
            startOwed = true
        }
        startIfDue()
    }

    fun onStopped() = synchronized(lock) {
        started = false
        serverReady = false
        startOwed = false
        rebindOwed = false
        later?.cancel()
        later = null
        endRoundLocked()
    }

    /** Whether the control server is listening on the LAN; [rebound] when it has just (re)bound. */
    fun onServer(bound: Boolean, rebound: Boolean) {
        synchronized(lock) {
            serverReady = bound
            if (bound && rebound) {
                startOwed = true
                if (started) rebindOwed = true
            }
        }
        startIfDue()
    }

    /** A remote key on the idle screen. Called for every key, auto-repeat included. */
    fun onIdleKey() {
        if (!synchronized(lock) { keyMayKnockLocked() }) return
        val linkedNow = linkedKeyIds()
        val targets = KnockPolicy.targets(pairedKeyIds(), book.snapshot(), linkedNow)
        val local = localHost()
        synchronized(lock) {
            if (!keyMayKnockLocked() || linkedNow.isNotEmpty() || targets.isEmpty() || local == null) return
            beginLocked(targets, linkedNow, isStart = false)
        }
        logBegin("key", targets.size)
    }

    private fun keyMayKnockLocked(): Boolean =
        started && serverReady && round?.isActive != true && KnockPolicy.keyMayKnock(linked, times.lastRoundAtMs, clock())

    /**
     * Whether any phone's library socket is authenticated. A link ends the round: once it
     * drops, the screen says nothing about a knock until a new round begins.
     */
    fun onLinkChanged(anyLinked: Boolean) = synchronized(lock) {
        linked = anyLinked
        if (anyLinked) {
            startOwed = false
            rebindOwed = false
            later?.cancel()
            later = null
            times.lastAnsweredAtMs = null
            endRoundLocked()
        } else {
            publishLocked()
        }
    }

    private fun startIfDue() {
        if (!synchronized(lock) { startMayBeDueLocked() }) return
        val linkedNow = linkedKeyIds()
        val targets = KnockPolicy.targets(pairedKeyIds(), book.snapshot(), linkedNow)
        val local = localHost()
        synchronized(lock) {
            // A round still out keeps the start owed: it is made once that round ends.
            if (!startMayBeDueLocked()) return
            // A second phone's login would displace the linked one's.
            if (targets.isEmpty() || linkedNow.isNotEmpty() || linked) {
                startOwed = false
                rebindOwed = false
                return
            }
            // Still owed: the next server pass, which follows every address change, tries again.
            if (local == null) return
            val now = clock()
            val answeredAt = times.lastAnsweredAtMs
            // A phone answering an address the TV has since re-bound away from may never arrive.
            if (!rebindOwed && answeredAt != null && KnockPolicy.stillConnecting(answeredAt, now)) {
                startOwed = false
                if (!(began && answered)) showConnectingLocked(answeredAt, now)
                return
            }
            val wait = KnockPolicy.startWaitMs(times.lastRoundAtMs, now)
            if (wait > 0) {
                if (later?.isActive != true) {
                    later = scope.launch {
                        delay(wait)
                        synchronized(lock) { later = null }
                        startIfDue()
                    }
                }
                return
            }
            beginLocked(targets, linkedNow, isStart = true)
        }
        logBegin("start", targets.size)
    }

    private fun startMayBeDueLocked(): Boolean = started && serverReady && startOwed && round?.isActive != true

    private fun beginLocked(targets: List<String>, linkedNow: Set<String>, isStart: Boolean) {
        times.lastRoundAtMs = clock()
        // Any round knocks every phone a start would, so it makes an owed start.
        val makesStart = isStart || startOwed
        startOwed = false
        rebindOwed = false
        val ofRound = ++generation
        began = true
        pending = 1
        answered = false
        graceOver = false
        hinted = false
        notReady = false
        linked = linkedNow.isNotEmpty()
        grace?.cancel()
        grace = null
        publishLocked()
        round = scope.launch {
            for (keyId in targets) {
                val outcome = ring(keyId)
                val next = synchronized(lock) {
                    if (!started || generation != ofRound) return@launch
                    recordLocked(outcome, ofRound)
                    KnockPolicy.triesNext(outcome)
                }
                if (!next) break
            }
            synchronized(lock) {
                if (!started || generation != ofRound) return@launch
                pending = 0
                round = null
                // A start that found no LAN address has not been made yet.
                if (makesStart && notReady) startOwed = true
                publishLocked()
            }
            startIfDue()
        }
    }

    private fun recordLocked(outcome: KnockOutcome, ofRound: Long) {
        if (KnockPolicy.hints(outcome)) hinted = true
        if (outcome == KnockOutcome.NO_LAN) notReady = true
        if (outcome == KnockOutcome.ANSWERED) {
            times.lastAnsweredAtMs = clock()
            if (!answered) {
                answered = true
                graceLocked(ofRound, KnockPolicy.CONNECTING_GRACE_MS)
            }
        }
        publishLocked()
    }

    /** The screen of a round whose phone answered at [answeredAtMs] and may still be connecting. */
    private fun showConnectingLocked(answeredAtMs: Long, nowMs: Long) {
        val ofRound = ++generation
        began = true
        pending = 0
        answered = true
        graceOver = false
        hinted = false
        graceLocked(ofRound, KnockPolicy.CONNECTING_GRACE_MS - (nowMs - answeredAtMs))
        publishLocked()
    }

    private fun graceLocked(ofRound: Long, ms: Long) {
        grace?.cancel()
        grace = scope.launch {
            delay(ms)
            synchronized(lock) {
                if (generation != ofRound) return@synchronized
                graceOver = true
                publishLocked()
            }
        }
    }

    private fun endRoundLocked() {
        generation++
        round?.cancel()
        round = null
        grace?.cancel()
        grace = null
        began = false
        pending = 0
        answered = false
        graceOver = false
        hinted = false
        publishLocked()
    }

    private suspend fun ring(keyId: String): KnockOutcome {
        book.snapshot()[keyId]?.host?.let { host ->
            val wait = KnockPolicy.admissionWaitMs(times.connectedAt(host), clock())
            if (wait > 0) delay(wait)
        }
        var outcome = KnockOutcome.UNREACHABLE
        var attempt = 0
        while (true) {
            attempt++
            outcome = attempt(keyId)
            if (!KnockPolicy.retries(outcome, attempt)) break
            delay(KnockPolicy.RETRY_GAP_MS)
        }
        FlickLog.i("doorbell", "knock end outcome=${outcome.name.lowercase()} attempts=$attempt")
        return outcome
    }

    private suspend fun attempt(keyId: String): KnockOutcome {
        if (!synchronized(lock) { started }) return KnockOutcome.NOT_STARTED
        // The key first: a phone forgotten mid-round is gone, not unreachable.
        val key = withContext(work) { keyFor(keyId) } ?: return KnockOutcome.NO_KEY
        val address = book.snapshot()[keyId]
        val host = address?.host
        val port = address?.port
        if (host == null || port == null) return KnockOutcome.UNREACHABLE
        val local = localHost() ?: return KnockOutcome.NO_LAN
        return transport.knock(
            host, port, local, tvId(), keyId, key,
            connected = { times.onConnected(host, clock()) },
        ) { synchronized(lock) { started } }
    }

    private fun logBegin(trigger: String, phones: Int) {
        FlickLog.i("doorbell", "knock begin trigger=$trigger phones=$phones")
    }

    private fun publishLocked() {
        _face.value = KnockPolicy.face(pending, answered, graceOver, linked, began, hinted)
    }
}
