package com.flick.sender.library

import com.flick.sender.net.PairLaunch
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

/**
 * The phone's half of the TV doorbell (`docs/design/library-channel.md` §13): a TV that holds
 * a pairing key knocks on the phone's listener, and the phone then dials that TV over control
 * v2 exactly as it would for a tap. Pinned by the §13 fixtures on both modules.
 */
internal object KnockProtocol {
    const val VERSION = 1
    const val MAX_FRAME_BYTES = 512
    const val DEADLINE_MS = 2_000L

    /** Tried in order; the platform's ephemeral choice when all ten are taken. */
    val PORTS: List<Int> = (47670..47679).toList()
    const val EPHEMERAL = 0

    /** §13: each field as a four-byte big-endian length, then its UTF-8 bytes. */
    fun transcript(
        tvId: String, keyId: String, phoneNonce: String, tvNonce: String, phoneHost: String, phonePort: Int,
    ): ByteArray {
        val fields = listOf(
            "Flick-Knock-V1", "tv", VERSION.toString(), tvId, keyId, phoneNonce, tvNonce, phoneHost,
            phonePort.toString(),
        )
        val out = ByteArrayOutputStream()
        fields.forEach { field ->
            val bytes = field.toByteArray(StandardCharsets.UTF_8)
            out.write(ByteBuffer.allocate(4).putInt(bytes.size).array())
            out.write(bytes)
        }
        return out.toByteArray()
    }

    fun proof(
        key: String, tvId: String, keyId: String, phoneNonce: String, tvNonce: String, phoneHost: String, phonePort: Int,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(Base64.getUrlDecoder().decode(key), "HmacSHA256"))
        return LibraryProtocol.base64(mac.doFinal(transcript(tvId, keyId, phoneNonce, tvNonce, phoneHost, phonePort)))
    }

    fun hello(phoneNonce: String): String =
        LibraryJsonWriter().str("t", "knockHello").num("v", VERSION).str("phoneNonce", phoneNonce).build()

    /** §13.5: never both flags; a TV that predates them reads every form as the plain one. */
    fun ok(reply: KnockReply = KnockReply.CONNECT): String {
        val frame = LibraryJsonWriter().str("t", "knockOk").num("v", VERSION)
        when (reply) {
            KnockReply.CONNECT -> Unit
            KnockReply.ASK -> frame.bool("ask", true)
            KnockReply.DECLINED -> frame.bool("declined", true)
        }
        return frame.build()
    }

    data class Knock(val tvId: String, val keyId: String, val phoneNonce: String, val tvNonce: String, val proof: String)

    /** The TV's `knock`, or null for anything else: wrong type, wrong `v`, a bad field, or not one strict object. */
    fun parseKnock(text: String): Knock? {
        if (LibraryProtocol.utf8Size(text) > MAX_FRAME_BYTES) return null
        val o = LibraryJsonReader.objectOnly(text) ?: return null
        if (o.str("t") != "knock" || o.long("v") != VERSION.toLong()) return null
        val tvId = o.str("tvId")?.takeIf(LibraryProtocol::nonce) ?: return null
        val keyId = o.str("keyId")?.takeIf(LibraryProtocol::nonce) ?: return null
        val phoneNonce = o.str("phoneNonce")?.takeIf(LibraryProtocol::nonce) ?: return null
        val tvNonce = o.str("tvNonce")?.takeIf(LibraryProtocol::nonce) ?: return null
        val proof = o.str("proof")?.takeIf(LibraryProtocol::proofValue) ?: return null
        return Knock(tvId, keyId, phoneNonce, tvNonce, proof)
    }

    /** Whether a TV knocked with a pairing this phone holds. Never a reason to touch the pairing. */
    sealed interface Verdict {
        data class Valid(val tvId: String, val keyId: String) : Verdict
        data object Invalid : Verdict
    }

    /**
     * [keyFor] answers the pairing key for exactly (tvId, keyId), or null when no usable
     * pairing has both. The proof binds [phoneHost]:[phonePort], the address the TV dialed.
     */
    fun verify(
        frame: String,
        phoneNonce: String,
        phoneHost: String,
        phonePort: Int,
        keyFor: (tvId: String, keyId: String) -> String?,
    ): Verdict {
        val knock = parseKnock(frame) ?: return Verdict.Invalid
        if (knock.phoneNonce != phoneNonce || knock.tvNonce == phoneNonce) return Verdict.Invalid
        val key = keyFor(knock.tvId, knock.keyId) ?: return Verdict.Invalid
        val expected = runCatching {
            proof(key, knock.tvId, knock.keyId, phoneNonce, knock.tvNonce, phoneHost, phonePort)
        }.getOrNull() ?: return Verdict.Invalid
        return if (LibraryProtocol.constantTimeEquals(expected, knock.proof)) Verdict.Valid(knock.tvId, knock.keyId)
        else Verdict.Invalid
    }

    /**
     * One LF-terminated line of strict UTF-8, at most [MAX_FRAME_BYTES] before the LF; null on
     * end of stream, an overlong line, or bytes that are not UTF-8. [beforeRead] runs before
     * every blocking read so the caller can shrink the socket timeout to its deadline, and
     * throws to give up.
     */
    fun readLine(input: InputStream, beforeRead: () -> Unit = {}): String? {
        val out = ByteArrayOutputStream(64)
        while (true) {
            beforeRead()
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) break
            if (out.size() >= MAX_FRAME_BYTES) return null
            out.write(b)
        }
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(out.toByteArray())).toString()
        } catch (e: CharacterCodingException) {
            null
        }
    }
}

/**
 * One kind of listener line (a rate refusal, a refused proof, a dropped connection) is logged
 * once per [windowMs], the next carrying how many went unlogged, so admitted or not, noisy
 * devices cannot flush the diagnostics ring.
 */
internal class ThrottledLog(private val windowMs: Long = KnockPolicy.MINUTE_MS) {
    private var loggedAtMs: Long? = null
    private var unlogged = 0

    /** [text] to log at [nowMs], or null when this window has logged one. */
    @Synchronized
    fun line(nowMs: Long, text: String): String? {
        val last = loggedAtMs
        if (last != null && nowMs - last < windowMs) {
            unlogged += 1
            return null
        }
        val line = if (unlogged > 0) "$text unlogged=$unlogged" else text
        loggedAtMs = nowMs
        unlogged = 0
        return line
    }
}

/** §13's admission rules for the listener, kept apart from the socket so they are testable. */
internal object KnockPolicy {
    const val MIN_INTERVAL_MS = 5_000L
    const val PER_MINUTE = 6
    const val GLOBAL_PER_MINUTE = 30
    const val MINUTE_MS = 60_000L
    const val ACCEPT_BACKOFF_FIRST_MS = 100L
    const val ACCEPT_BACKOFF_MAX_MS = 1_000L

    /** A private IPv4 peer in canonical dotted form: 10/8, 172.16/12 or 192.168/16. */
    fun peerAllowed(address: String?): Boolean = address != null && PairLaunch.isCanonicalIpv4(address)

    /** The port to send in `knockAt`, or null when there is none worth sending. */
    fun knockAtPort(bound: Int?, alreadySent: Int?): Int? =
        bound?.takeIf { it in 1..65_535 && it != alreadySent }

    /** The pause after the accept that failed following one that waited [previousMs] (0: the last one succeeded). */
    fun acceptBackoffMs(previousMs: Long): Long =
        if (previousMs <= 0L) ACCEPT_BACKOFF_FIRST_MS else (previousMs * 2).coerceAtMost(ACCEPT_BACKOFF_MAX_MS)
}

/**
 * §13: per peer address, at most one admitted handshake per [minIntervalMs] and [perWindow]
 * per [windowMs]; across all peers, at most [globalPerWindow] per [windowMs]. A refused
 * connection is closed unanswered and counts against nothing.
 */
internal class KnockGate(
    private val minIntervalMs: Long = KnockPolicy.MIN_INTERVAL_MS,
    private val perWindow: Int = KnockPolicy.PER_MINUTE,
    private val globalPerWindow: Int = KnockPolicy.GLOBAL_PER_MINUTE,
    private val windowMs: Long = KnockPolicy.MINUTE_MS,
) {
    private val all = ArrayDeque<Long>()
    private val byPeer = HashMap<String, ArrayDeque<Long>>()

    @Synchronized
    fun tryAdmit(peer: String, nowMs: Long): Boolean {
        all.prune(nowMs)
        byPeer.values.forEach { it.prune(nowMs) }
        byPeer.values.removeAll { it.isEmpty() }
        if (all.size >= globalPerWindow) return false
        val mine = byPeer[peer]
        val last = mine?.lastOrNull()
        if (last != null && nowMs - last < minIntervalMs) return false
        if (mine != null && mine.size >= perWindow) return false
        all.addLast(nowMs)
        byPeer.getOrPut(peer) { ArrayDeque() }.addLast(nowMs)
        return true
    }

    private fun ArrayDeque<Long>.prune(nowMs: Long) {
        while (isNotEmpty() && nowMs - first() >= windowMs) removeFirst()
    }
}

/**
 * §13.1: where the listener may bind. Only on a Wi-Fi network whose own IPv4 subnet holds the
 * stored address of a verified pairing. A heuristic, not proof of being home: another network
 * reusing that private subnet (a common router default) qualifies too.
 */
internal object DoorbellBinding {
    /** One IPv4 address of a network with its prefix length, as `LinkProperties` lists it. */
    data class Address(val host: String, val prefix: Int)

    data class Choice<N>(val network: N, val host: String)

    private const val MIN_PREFIX = 8

    fun ipv4(host: String): Int? {
        if (!PairLaunch.isCanonicalIpv4(host)) return null
        return host.split('.').fold(0) { acc, part -> (acc shl 8) or part.toInt() }
    }

    fun sameSubnet(address: Address, other: String): Boolean {
        if (address.prefix !in MIN_PREFIX..32) return false
        val a = ipv4(address.host) ?: return false
        val b = ipv4(other) ?: return false
        val mask = if (address.prefix == 32) -1 else (-1 shl (32 - address.prefix))
        return (a and mask) == (b and mask)
    }

    /** The address of one network to listen on, or null when none of its subnets holds a paired TV. */
    fun listenAddress(addresses: List<Address>, pairedHosts: Collection<String>): String? =
        addresses.firstOrNull { address -> pairedHosts.any { sameSubnet(address, it) } }?.host

    /**
     * [candidates] maps each Wi-Fi network up now, in the order it appeared, to its
     * [listenAddress]. The network bound now stays while it still qualifies; otherwise the
     * first that does takes over, which is how a make-before-break switch keeps a listener.
     */
    fun <N> choose(bound: Choice<N>?, candidates: Map<N, String?>): Choice<N>? {
        if (bound != null) candidates[bound.network]?.let { return Choice(bound.network, it) }
        return candidates.entries.firstOrNull { it.value != null }?.let { Choice(it.key, it.value!!) }
    }
}

/** What a valid knock makes the phone do (§13). */
internal enum class KnockAction(val journal: String) {
    /** A cast is live: its own control session is the TV's link already. */
    CAST_LIVE("cast_live"),

    /**
     * Control to that TV answered and the phone thinks its library socket is open. The TV
     * knocks only without one, so that socket is dead: close it and dial the library again.
     */
    LINKED("linked"),

    /** Control to that TV answered but the library socket is down: dial the library now. */
    LIBRARY("library"),

    /**
     * Something the person started, a live control line to another TV, or an earlier knock's
     * resume to another TV owns the dial.
     */
    BUSY("busy"),

    /** An earlier knock's resume to the same TV is still dialing: it answers this knock too. */
    RESUMING("resuming"),

    /** The ordinary non-user resume to the stored endpoint. */
    RESUME("resume"),
}

/** Where control v2 is authenticated when a knock arrives. */
internal enum class KnockControl { NONE, THAT_TV, OTHER_TV }

internal object KnockResponse {
    /**
     * Whether the answer turns on the control line still carrying anything: a TV knocks only
     * when it holds no library socket from this phone, so a line the phone believes up may be
     * one a powered-off TV or a sleeping phone never got to report dead.
     */
    fun needsLivenessCheck(
        castLive: Boolean,
        knockResumingTv: String?,
        control: KnockControl,
        visible: Boolean,
    ): Boolean = !castLive && knockResumingTv == null && when (control) {
        KnockControl.NONE -> false
        KnockControl.THAT_TV -> true
        KnockControl.OTHER_TV -> !visible
    }

    /**
     * [tvId] is the knocking TV; [knockResumingTv] the TV an earlier knock's resume is still
     * dialing, or null. That resume answers a knock from its own TV only: nothing would dial
     * another, so that one is busy.
     * [controlAlive] is the liveness check's answer, and is read only when control is up.
     * [dialing] counts an earlier knock's own resume too.
     * With the app in front of the person, control on another TV is theirs whatever a ping
     * said: one lost pong must not switch their TV under them.
     */
    fun action(
        tvId: String,
        castLive: Boolean,
        knockResumingTv: String?,
        control: KnockControl,
        libraryOpen: Boolean,
        dialing: Boolean,
        visible: Boolean,
        controlAlive: Boolean,
    ): KnockAction = when {
        castLive -> KnockAction.CAST_LIVE
        knockResumingTv != null -> if (knockResumingTv == tvId) KnockAction.RESUMING else KnockAction.BUSY
        control == KnockControl.THAT_TV && controlAlive -> if (libraryOpen) KnockAction.LINKED else KnockAction.LIBRARY
        dialing -> KnockAction.BUSY
        control == KnockControl.OTHER_TV && (controlAlive || visible) -> KnockAction.BUSY
        else -> KnockAction.RESUME
    }
}

/**
 * §8 ready mode: whether `TvReadyService` may run with no time limit and the doorbell
 * listening. Without the battery exemption a background start would be refused anyway, so
 * the phone keeps the 30-minute window instead.
 */
internal object TvReadyMode {
    fun holds(
        enabled: Boolean,
        verifiedPairing: Boolean,
        mediaAccess: Boolean,
        batteryExempt: Boolean,
        stoppedSinceOpened: Boolean,
    ): Boolean = enabled && verifiedPairing && mediaAccess && batteryExempt && !stoppedSinceOpened
}
