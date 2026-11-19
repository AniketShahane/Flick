package com.flick.receiver.library

import com.flick.receiver.net.StrictJson
import com.flick.receiver.net.StrictJsonValue
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The TV's half of library channel v1. `docs/design/library-channel.md` is the contract; the
 * phone's `LibraryProtocol` is the other half, and both are pinned by the §11 fixtures.
 */
object LibraryAuth {

    private val nonceShape = Regex("[A-Za-z0-9_-]{22}")
    private val proofShape = Regex("[A-Za-z0-9_-]{43}")

    fun nonce(value: String?): Boolean = value != null && nonceShape.matches(value)

    fun proofValue(value: String?): Boolean = value != null && proofShape.matches(value)

    fun randomNonce(random: SecureRandom = SecureRandom()): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16).also(random::nextBytes))

    /** §4: each field as a four-byte big-endian length, then its UTF-8 bytes. */
    fun transcript(
        role: String, tvId: String, keyId: String, clientNonce: String, serverNonce: String,
        peerIp: String, serverHost: String, serverPort: Int,
    ): ByteArray {
        val fields = listOf(
            "Flick-Library-V1", role, LibraryWire.VERSION.toString(), tvId, keyId, clientNonce, serverNonce,
            peerIp, serverHost, serverPort.toString(),
        )
        val out = ByteArrayOutputStream()
        fields.forEach { field ->
            val bytes = field.toByteArray(StandardCharsets.UTF_8)
            out.write(ByteBuffer.allocate(4).putInt(bytes.size).array())
            out.write(bytes)
        }
        return out.toByteArray()
    }

    /** [role] is `client` for the proof the phone sends and `server` for the TV's `libReady`. */
    fun proof(
        key: String, role: String, tvId: String, keyId: String, clientNonce: String, serverNonce: String,
        peerIp: String, serverHost: String, serverPort: Int,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(Base64.getUrlDecoder().decode(key), "HmacSHA256"))
        val digest = mac.doFinal(transcript(role, tvId, keyId, clientNonce, serverNonce, peerIp, serverHost, serverPort))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    fun constantTimeEquals(expected: String, actual: String): Boolean =
        MessageDigest.isEqual(expected.toByteArray(StandardCharsets.US_ASCII), actual.toByteArray(StandardCharsets.US_ASCII))
}

enum class PlayFrom(val wire: String) { RESUME("resume"), START("start") }

enum class PlayRefusal(val wire: String) {
    MISSING("missing"),
    DISABLED("disabled"),
    BUSY("busy"),
    UNAVAILABLE("unavailable"),
    BACKGROUND("background"),
    ;

    companion object {
        /** A code a later phone adds reads as [UNAVAILABLE] rather than failing the socket. */
        fun of(wire: String): PlayRefusal = entries.firstOrNull { it.wire == wire } ?: UNAVAILABLE
    }
}

/** One episode's TMDB data from a `season` frame. */
data class LibraryEpisodeInfo(val episode: Int, val name: String?, val overview: String?, val still: String?)

/** Frames the phone sends, as the TV reads them. */
sealed interface PhoneFrame {
    data class Hello(val tvId: String, val keyId: String, val clientNonce: String) : PhoneFrame
    data class Proof(val clientNonce: String, val serverNonce: String, val proof: String) : PhoneFrame
    data class CatBegin(val rev: String, val count: Int, val parts: Int) : PhoneFrame
    data class CatPart(val rev: String, val index: Int, val items: List<LibraryItem>) : PhoneFrame
    data class CatEnd(val rev: String) : PhoneFrame
    data class CatPatch(val base: String, val rev: String, val upsert: List<LibraryItem>, val remove: List<String>) : PhoneFrame
    data class PlayAck(val req: String, val ok: Boolean, val refusal: PlayRefusal?) : PhoneFrame
    data class Detail(val id: String, val overview: String?, val tagline: String?, val hdr: LibraryHdr?, val subtitle: String?) : PhoneFrame
    data class Season(val showKey: String, val season: Int, val part: Int, val parts: Int, val episodes: List<LibraryEpisodeInfo>) : PhoneFrame
    data class ThumbMiss(val id: String) : PhoneFrame
    /** §6.6: the control-v2 cast [castId] is playing catalog item [id]. */
    data class Casting(val castId: String, val id: String) : PhoneFrame
    /** A type this TV does not know: ignored, never fatal (§7). */
    data class Unknown(val type: String) : PhoneFrame
    /** A known type with a bad field, a wrong `v`, or not a JSON object: closes the library socket. */
    data object Malformed : PhoneFrame

    companion object {
        private val ShowKey = Regex("^(?:tv:[1-9][0-9]{0,11}|name:.{1,200})$")
        private val CastId = Regex("[A-Za-z0-9_-]{22}")
        private const val MAX_SEASON_PARTS = 64

        fun parse(text: String): PhoneFrame {
            if (text.toByteArray(StandardCharsets.UTF_8).size > LibraryFrames.MAX_FRAME_BYTES) return Malformed
            val o = StrictJson.objectOnly(text) ?: return Malformed
            val type = o.text("t") ?: return Malformed
            val known = type in KnownTypes
            if (o.whole("v") != LibraryWire.VERSION) return if (known) Malformed else Unknown(type)
            return when (type) {
                "libHello" -> {
                    val tvId = o.text("tvId")?.takeIf(LibraryAuth::nonce)
                    val keyId = o.text("keyId")?.takeIf(LibraryAuth::nonce)
                    val client = o.text("clientNonce")?.takeIf(LibraryAuth::nonce)
                    if (tvId == null || keyId == null || client == null) Malformed else Hello(tvId, keyId, client)
                }
                "libProof" -> {
                    val client = o.text("clientNonce")?.takeIf(LibraryAuth::nonce)
                    val server = o.text("serverNonce")?.takeIf(LibraryAuth::nonce)
                    val proof = o.text("proof")?.takeIf(LibraryAuth::proofValue)
                    if (client == null || server == null || proof == null) Malformed else Proof(client, server, proof)
                }
                "catBegin" -> {
                    val rev = o.text("rev")?.takeIf(LibraryWire::rev)
                    val count = o.whole("count")?.takeIf { it in 0L..Int.MAX_VALUE }?.toInt()
                    val parts = o.whole("parts")?.takeIf { it in 0L..Int.MAX_VALUE }?.toInt()
                    if (rev == null || count == null || parts == null) Malformed else CatBegin(rev, count, parts)
                }
                "catPart" -> {
                    val rev = o.text("rev")?.takeIf(LibraryWire::rev)
                    val index = o.whole("index")?.takeIf { it in 0L..Int.MAX_VALUE }?.toInt()
                    val items = o.items("items")
                    if (rev == null || index == null || items == null) Malformed else CatPart(rev, index, items)
                }
                "catEnd" -> o.text("rev")?.takeIf(LibraryWire::rev)?.let(::CatEnd) ?: Malformed
                "catPatch" -> {
                    val base = o.text("base")?.takeIf(LibraryWire::rev)
                    val rev = o.text("rev")?.takeIf(LibraryWire::rev)
                    val upsert = o.items("upsert")
                    val remove = (o.fields["remove"] as? StrictJsonValue.Arr)?.values?.map { value ->
                        (value as? StrictJsonValue.Str)?.value?.takeIf(LibraryWire::itemId) ?: return Malformed
                    }
                    if (base == null || rev == null || upsert == null || remove == null) Malformed else CatPatch(base, rev, upsert, remove)
                }
                "playAck" -> {
                    val req = o.text("req")?.takeIf(LibraryAuth::nonce)
                    val ok = (o.fields["ok"] as? StrictJsonValue.Bool)?.value
                    when {
                        req == null || ok == null -> Malformed
                        ok -> PlayAck(req, true, null)
                        else -> o.text("code")?.let { PlayAck(req, false, PlayRefusal.of(it)) } ?: Malformed
                    }
                }
                "detail" -> {
                    val id = o.text("id")?.takeIf(LibraryWire::itemId) ?: return Malformed
                    val overview = o.optionalLabel("overview", 1_200) ?: return Malformed
                    val tagline = o.optionalLabel("tagline", 200) ?: return Malformed
                    val subtitle = o.optionalLabel("subtitle", 200) ?: return Malformed
                    val hdr = when (val raw = o.fields["hdr"]) {
                        null -> null
                        else -> (raw as? StrictJsonValue.Str)?.value?.let(LibraryHdr::of) ?: return Malformed
                    }
                    Detail(id, overview.value, tagline.value, hdr, subtitle.value)
                }
                "season" -> {
                    val showKey = o.text("showKey")?.takeIf { ShowKey.matches(it) } ?: return Malformed
                    val season = o.whole("s")?.takeIf { it in 0L..999L }?.toInt() ?: return Malformed
                    val parts = o.whole("parts")?.takeIf { it in 1L..MAX_SEASON_PARTS.toLong() }?.toInt() ?: return Malformed
                    val part = o.whole("part")?.takeIf { it in 0L until parts.toLong() }?.toInt() ?: return Malformed
                    val raw = (o.fields["episodes"] as? StrictJsonValue.Arr)?.values ?: return Malformed
                    val episodes = raw.map { episode(it) ?: return Malformed }
                    Season(showKey, season, part, parts, episodes)
                }
                "thumbMiss" -> o.text("id")?.takeIf(LibraryWire::itemId)?.let(::ThumbMiss) ?: Malformed
                "casting" -> {
                    val castId = o.text("castId")?.takeIf { CastId.matches(it) }
                    val id = o.text("id")?.takeIf(LibraryWire::itemId)
                    if (castId == null || id == null) Malformed else Casting(castId, id)
                }
                else -> Unknown(type)
            }
        }

        /** §6.4: `FLT1`, an item id, then JPEG bytes. Null when the frame is not that shape. */
        fun thumbnail(bytes: ByteArray): Pair<String, ByteArray>? {
            if (bytes.size <= LibraryFrames.THUMB_HEADER_BYTES || bytes.size > LibraryFrames.MAX_FRAME_BYTES) return null
            val header = String(bytes, 0, LibraryFrames.THUMB_HEADER_BYTES, StandardCharsets.US_ASCII)
            if (!header.startsWith(LibraryFrames.THUMB_MAGIC)) return null
            val id = header.substring(LibraryFrames.THUMB_MAGIC.length)
            if (!LibraryWire.itemId(id)) return null
            return id to bytes.copyOfRange(LibraryFrames.THUMB_HEADER_BYTES, bytes.size)
        }

        private fun episode(value: StrictJsonValue): LibraryEpisodeInfo? {
            val o = value as? StrictJsonValue.Obj ?: return null
            val e = o.whole("e")?.takeIf { it in 0L..9_999L }?.toInt() ?: return null
            val name = o.optionalLabel("name", 200) ?: return null
            val overview = o.optionalLabel("overview", 400) ?: return null
            val still = when (val raw = o.fields["still"]) {
                null -> null
                else -> (raw as? StrictJsonValue.Str)?.value?.takeIf(LibraryWire::imagePath) ?: return null
            }
            return LibraryEpisodeInfo(e, name.value, overview.value, still)
        }

        private val KnownTypes = setOf(
            "libHello", "libProof", "catBegin", "catPart", "catEnd", "catPatch", "playAck", "detail",
            "season", "thumbMiss", "casting",
        )

        private fun StrictJsonValue.Obj.text(key: String): String? = (fields[key] as? StrictJsonValue.Str)?.value

        private fun StrictJsonValue.Obj.whole(key: String): Long? =
            (fields[key] as? StrictJsonValue.Num)?.token?.let(LibraryWire::integer)

        /** Every element must be a valid item; one bad item fails the frame (§5.1). */
        private fun StrictJsonValue.Obj.items(key: String): List<LibraryItem>? {
            val values = (fields[key] as? StrictJsonValue.Arr)?.values ?: return null
            return values.map { LibraryWire.item(it) ?: return null }
        }

        private fun StrictJsonValue.Obj.optionalLabel(key: String, max: Int): LibraryWire.Optional<String>? {
            val raw = fields[key] ?: return LibraryWire.Optional(null)
            val value = (raw as? StrictJsonValue.Str)?.value ?: return null
            if (value.isBlank() || value.codePointCount(0, value.length) > max || value.any { Character.isISOControl(it) }) return null
            return LibraryWire.Optional(value)
        }
    }
}

/** Frames the TV sends. */
object LibraryFrames {
    const val MAX_FRAME_BYTES = 16 * 1024
    const val THUMB_MAGIC = "FLT1"
    const val THUMB_HEADER_BYTES = 4 + 11

    private const val V = LibraryWire.VERSION

    fun challenge(
        tvId: String, keyId: String, clientNonce: String, serverNonce: String,
        peerIp: String, serverHost: String, serverPort: Int,
    ): String = Json().str("t", "libChallenge").num("v", V).str("tvId", tvId).str("keyId", keyId)
        .str("clientNonce", clientNonce).str("serverNonce", serverNonce).str("peerIp", peerIp)
        .str("serverHost", serverHost).num("serverPort", serverPort.toLong()).build()

    fun ready(serverNonce: String, proof: String): String =
        Json().str("t", "libReady").num("v", V).str("serverNonce", serverNonce).str("proof", proof).build()

    fun denied(): String = Json().str("t", "libDenied").num("v", V).build()

    /** [have] is the rev this TV still holds in memory for that phone, or null. */
    fun catWant(have: String?): String = Json().str("t", "catWant").num("v", V).apply { have?.let { str("have", it) } }.build()

    fun play(req: String, id: String, from: PlayFrom): String =
        Json().str("t", "play").num("v", V).str("req", req).str("id", id).str("from", from.wire).build()

    fun detailWant(id: String): String = Json().str("t", "detailWant").num("v", V).str("id", id).build()

    fun seasonWant(showKey: String, season: Int): String =
        Json().str("t", "seasonWant").num("v", V).str("showKey", showKey).num("s", season.toLong()).build()

    fun thumbWant(id: String): String = Json().str("t", "thumbWant").num("v", V).str("id", id).build()

    fun refresh(): String = Json().str("t", "refresh").num("v", V).build()

    fun browsing(): String = Json().str("t", "browsing").num("v", V).build()

    /** One flat object in field order, with JSON string escaping. */
    private class Json {
        private val out = StringBuilder("{")
        private var first = true

        fun str(key: String, value: String): Json = field(key) { quote(value) }

        fun num(key: String, value: Long): Json = field(key) { out.append(value) }

        fun build(): String = out.append('}').toString()

        private inline fun field(key: String, value: () -> Unit): Json {
            if (!first) out.append(',')
            first = false
            quote(key)
            out.append(':')
            value()
            return this
        }

        private fun quote(value: String) {
            out.append('"')
            for (c in value) {
                when {
                    c == '"' -> out.append("\\\"")
                    c == '\\' -> out.append("\\\\")
                    c.code < 0x20 -> out.append("\\u").append(String.format("%04x", c.code))
                    else -> out.append(c)
                }
            }
            out.append('"')
        }
    }
}
