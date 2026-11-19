package com.flick.sender.library

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The phone's half of library channel v1. `docs/design/library-channel.md` is the contract;
 * the TV's `LibraryFrames` is the other half, and both are pinned by the §11 fixtures.
 */
internal object LibraryProtocol {
    const val VERSION = 1
    const val PATH = "/library"
    const val MAX_FRAME_BYTES = 16 * 1024
    const val MAX_ITEMS = 20_000
    const val MAX_PARTS = 4_000
    const val PATCH_MAX_ITEMS = 64
    const val THUMB_MAGIC = "FLT1"
    const val THUMB_HEADER_BYTES = 4 + 11

    private val nonceShape = Regex("[A-Za-z0-9_-]{22}")
    private val proofShape = Regex("[A-Za-z0-9_-]{43}")
    private val itemIdShape = Regex("[A-Za-z0-9_-]{11}")
    private val showKeyShape = Regex("^(?:tv:[1-9][0-9]{0,11}|name:.{1,200})$")

    fun nonce(value: String?): Boolean = value != null && nonceShape.matches(value)
    fun proofValue(value: String?): Boolean = value != null && proofShape.matches(value)
    fun itemIdValue(value: String?): Boolean = value != null && itemIdShape.matches(value)
    fun showKeyValue(value: String?): Boolean = value != null && showKeyShape.matches(value)

    fun randomNonce(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(16).also(random::nextBytes)
        return base64(bytes)
    }

    /** §4: each field as a four-byte big-endian length, then its UTF-8 bytes. */
    fun transcript(
        role: String, tvId: String, keyId: String, clientNonce: String, serverNonce: String,
        peerIp: String, serverHost: String, serverPort: Int,
    ): ByteArray {
        val fields = listOf(
            "Flick-Library-V1", role, VERSION.toString(), tvId, keyId, clientNonce, serverNonce,
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

    /** [role] is `client` for `libProof` and `server` for `libReady`. */
    fun proof(
        key: String, role: String, tvId: String, keyId: String, clientNonce: String, serverNonce: String,
        peerIp: String, serverHost: String, serverPort: Int,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(Base64.getUrlDecoder().decode(key), "HmacSHA256"))
        return base64(mac.doFinal(transcript(role, tvId, keyId, clientNonce, serverNonce, peerIp, serverHost, serverPort)))
    }

    fun constantTimeEquals(expected: String, actual: String): Boolean =
        MessageDigest.isEqual(expected.toByteArray(StandardCharsets.US_ASCII), actual.toByteArray(StandardCharsets.US_ASCII))

    /** §5.1: the item id is derived from `PlaybackMediaFingerprint`, so it changes when the file does. */
    fun itemId(fingerprint: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("Flick-Library-Item-V1|$fingerprint".toByteArray(StandardCharsets.UTF_8))
        return base64(digest.copyOf(8))
    }

    /** §5.2: first 16 bytes of SHA-256 over `[item,item,…]` exactly as the items serialize. */
    fun rev(itemJson: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(LibraryJsonWriter.array(itemJson).toByteArray(StandardCharsets.UTF_8))
        return base64(digest.digest().copyOf(16))
    }

    fun utf8Size(text: String): Int = text.toByteArray(StandardCharsets.UTF_8).size

    internal fun base64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/** One row of the catalog before the wire rules are applied. Built by the catalog builder. */
internal data class CatalogItem(
    val id: String,
    val name: String,
    val title: String,
    val sortKey: String,
    val find: String,
    val kind: Kind,
    val show: String? = null,
    val showKey: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val year: Int? = null,
    val durationMs: Long = 0L,
    val addedSeconds: Long = 0L,
    val folder: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val hdr: Hdr? = null,
    val resumeMs: Long? = null,
    val watched: Boolean = false,
    val blocked: Block? = null,
    val art: Art? = null,
) {
    enum class Kind(val wire: String) { FILM("film"), EPISODE("episode"), VIDEO("video") }
    enum class Hdr(val wire: String) { DOLBY_VISION("dv"), HDR10("hdr10"), SDR("sdr") }
    enum class Block(val wire: String) { UNPLAYABLE("unplayable"), SILENT("silent") }

    /** TMDB image paths only; the TV builds every URL itself. */
    data class Art(val tmdbId: Long?, val show: Boolean?, val poster: String?, val backdrop: String?, val logo: String?)
}

/**
 * §5.1's field rules, applied on the phone so the TV never sees an item it must refuse: one
 * refused item fails its whole part, and the same catalog would then fail on every reconnect.
 */
internal object CatalogRules {
    const val NAME_MAX = 255
    const val TITLE_MAX = 200
    const val SHOW_MAX = 200
    const val SORT_KEY_MAX = 800
    const val FIND_MAX = 2_000
    const val FOLDER_MAX = 512
    const val SHOW_NAME_KEY_MAX = 200
    const val MAX_MS = 604_800_000L

    private val imagePath = Regex("^/[A-Za-z0-9_-]+\\.(?:jpg|jpeg|png|webp)$")

    /** The item's canonical JSON, or null when it cannot be sent at all. */
    fun json(item: CatalogItem): String? {
        if (!LibraryProtocol.itemIdValue(item.id)) return null
        val name = label(item.name, NAME_MAX) ?: return null
        val title = label(item.title, TITLE_MAX) ?: label(item.name, TITLE_MAX) ?: return null
        val showLabel = item.show?.let { label(it, SHOW_MAX) }
        val season = item.season?.takeIf { it in 0..999 }
        val episode = item.episode?.takeIf { it in 0..9_999 }
        val showKey = item.showKey?.let(::showKey)
        val episodic = item.kind == CatalogItem.Kind.EPISODE &&
            showLabel != null && showKey != null && season != null && episode != null
        val kind = if (item.kind == CatalogItem.Kind.EPISODE && !episodic) CatalogItem.Kind.VIDEO else item.kind

        val w = LibraryJsonWriter()
            .str("id", item.id)
            .str("name", name)
            .str("title", title)
            .str("sortKey", truncate(item.sortKey, SORT_KEY_MAX))
            .str("find", truncate(item.find, FIND_MAX))
            .str("kind", kind.wire)
        if (episodic) {
            w.str("show", showLabel!!).str("showKey", showKey!!).num("s", season!!).num("e", episode!!)
        }
        item.year?.takeIf { it in 1870..2100 }?.let { w.num("year", it) }
        w.num("durMs", item.durationMs.takeIf { it in 0L..MAX_MS } ?: 0L)
        w.num("added", item.addedSeconds.coerceAtLeast(0L))
        item.folder?.takeIf { it.length <= FOLDER_MAX && it.none(Char::isISOControl) }?.let { w.str("folder", it) }
        w.num("w", item.width.coerceIn(0, 100_000))
        w.num("h", item.height.coerceIn(0, 100_000))
        item.hdr?.let { w.str("hdr", it.wire) }
        item.resumeMs?.takeIf { it in 0L..MAX_MS }?.let { w.num("resumeMs", it) }
        if (item.watched) w.bool("watched", true)
        item.blocked?.let { w.str("blocked", it.wire) }
        item.art?.let(::artJson)?.let { w.raw("art", it) }
        return w.build()
    }

    /** `tv:<id>` passes through; anything else is `name:` plus a folded title cut to fit. */
    fun showKey(value: String): String? {
        if (value.startsWith("tv:")) return value.takeIf(LibraryProtocol::showKeyValue)
        if (!value.startsWith("name:")) return null
        val body = truncate(value.removePrefix("name:").filterNot { it == '\n' || it == '\r' || it == '\u0085' || it == ' ' || it == ' ' }, SHOW_NAME_KEY_MAX)
        return "name:$body".takeIf(LibraryProtocol::showKeyValue)
    }

    private fun artJson(art: CatalogItem.Art): String? {
        val poster = art.poster?.takeIf(imagePath::matches)
        val backdrop = art.backdrop?.takeIf(imagePath::matches)
        val logo = art.logo?.takeIf(imagePath::matches)
        if (poster == null && backdrop == null && logo == null) return null
        val w = LibraryJsonWriter()
        art.tmdbId?.takeIf { it > 0L }?.let { w.num("tmdb", it) }
        art.show?.let { w.bool("tv", it) }
        poster?.let { w.str("poster", it) }
        backdrop?.let { w.str("backdrop", it) }
        logo?.let { w.str("logo", it) }
        return w.build()
    }

    /**
     * Display text as the TV's label rule accepts it: no control or format characters, runs of
     * whitespace as one space, trimmed, at most [max] code points. Null when nothing is left.
     */
    fun label(value: String, max: Int): String? {
        val out = StringBuilder()
        var pendingSpace = false
        var index = 0
        var count = 0
        while (index < value.length && count < max) {
            val codePoint = value.codePointAt(index)
            index += Character.charCount(codePoint)
            if (Character.getType(codePoint) == Character.FORMAT.toInt()) continue
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                if (out.isNotEmpty()) pendingSpace = true
                continue
            }
            if (Character.isISOControl(codePoint)) continue
            if (Character.getType(codePoint) == Character.SURROGATE.toInt()) continue
            if (pendingSpace) {
                if (count + 1 >= max) break
                out.append(' ')
                count++
            }
            out.appendCodePoint(codePoint)
            count++
            pendingSpace = false
        }
        return out.toString().takeIf { it.isNotBlank() }
    }

    /** At most [max] UTF-16 units, never splitting a surrogate pair. */
    fun truncate(value: String, max: Int): String {
        if (value.length <= max) return value
        var end = max
        if (Character.isHighSurrogate(value[end - 1])) end--
        return value.substring(0, end)
    }
}

/** A catalog as it goes on the wire: canonical items in library order, and its rev. */
internal class EncodedCatalog private constructor(
    val rev: String,
    val ids: List<String>,
    val itemJson: List<String>,
    /** Items [CatalogRules] refused or duplicate ids, counted for the log; never sent. */
    val dropped: Int,
) {
    private val byId: Map<String, String> by lazy { ids.zip(itemJson).toMap() }

    fun json(id: String): String? = byId[id]

    val size: Int get() = ids.size

    companion object {
        fun of(items: List<CatalogItem>): EncodedCatalog {
            val ids = ArrayList<String>(items.size)
            val json = ArrayList<String>(items.size)
            val seen = HashSet<String>()
            var dropped = 0
            for (item in items) {
                if (ids.size >= LibraryProtocol.MAX_ITEMS) { dropped++; continue }
                val encoded = CatalogRules.json(item)
                if (encoded == null || !seen.add(item.id) || !LibraryFrames.fitsAlone(encoded)) { dropped++; continue }
                ids += item.id
                json += encoded
            }
            return EncodedCatalog(LibraryProtocol.rev(json), ids, json, dropped)
        }
    }
}

/** Frames the phone sends. Every builder returns text that is at most 16 KiB in UTF-8. */
internal object LibraryFrames {

    private const val V = LibraryProtocol.VERSION

    fun hello(tvId: String, keyId: String, clientNonce: String): String = LibraryJsonWriter()
        .str("t", "libHello").num("v", V).str("tvId", tvId).str("keyId", keyId).str("clientNonce", clientNonce).build()

    fun proof(clientNonce: String, serverNonce: String, proof: String): String = LibraryJsonWriter()
        .str("t", "libProof").num("v", V).str("clientNonce", clientNonce).str("serverNonce", serverNonce).str("proof", proof).build()

    /** The whole catalog: `catBegin`, every `catPart` in index order, then `catEnd`. */
    fun full(catalog: EncodedCatalog): List<String> {
        val parts = packParts(catalog.rev, catalog.itemJson)
        val frames = ArrayList<String>(parts.size + 2)
        frames += LibraryJsonWriter().str("t", "catBegin").num("v", V).str("rev", catalog.rev)
            .num("count", catalog.size).num("parts", parts.size).build()
        frames += parts
        frames += LibraryJsonWriter().str("t", "catEnd").num("v", V).str("rev", catalog.rev).build()
        return frames
    }

    /**
     * A `catPatch` taking the TV from [base] to [next], or null when the full catalog must go
     * instead: more than [LibraryProtocol.PATCH_MAX_ITEMS] changes, a frame over 16 KiB, or an
     * order the TV's patch rule (§5.2: new items first, the rest in their old order) would not
     * reproduce. The TV sorts stably over this order, so it has to match the phone's exactly.
     */
    fun patch(base: EncodedCatalog, next: EncodedCatalog): String? {
        if (base.rev == next.rev) return null
        val nextIds = next.ids.toHashSet()
        val baseIds = base.ids.toHashSet()
        val remove = base.ids.filter { it !in nextIds }
        val upsert = next.ids.filter { it !in baseIds || base.json(it) != next.json(it) }
        if (upsert.size + remove.size > LibraryProtocol.PATCH_MAX_ITEMS) return null
        val added = next.ids.filter { it !in baseIds }
        val kept = base.ids.filter { it in nextIds }
        if (added + kept != next.ids) return null
        val frame = LibraryJsonWriter().str("t", "catPatch").num("v", V).str("base", base.rev).str("rev", next.rev)
            .raw("upsert", LibraryJsonWriter.array(upsert.map { next.json(it)!! }))
            .strings("remove", remove)
            .build()
        return frame.takeIf { LibraryProtocol.utf8Size(it) <= LibraryProtocol.MAX_FRAME_BYTES }
    }

    /** True when [itemJson] fits in a `catPart` by itself, which every valid item does. */
    fun fitsAlone(itemJson: String): Boolean =
        partOverhead(LibraryProtocol.MAX_PARTS) + LibraryProtocol.utf8Size(itemJson) <= LibraryProtocol.MAX_FRAME_BYTES

    /** Greedy packing into `catPart` frames, each at most 16 KiB. */
    fun packParts(rev: String, itemJson: List<String>): List<String> {
        val parts = ArrayList<String>()
        var batch = ArrayList<String>()
        var batchBytes = 0
        fun flush() {
            parts += part(rev, parts.size, batch)
            batch = ArrayList()
            batchBytes = 0
        }
        for (json in itemJson) {
            val size = LibraryProtocol.utf8Size(json)
            val separator = if (batch.isEmpty()) 0 else 1
            if (batch.isNotEmpty() && partOverhead(parts.size) + batchBytes + separator + size > LibraryProtocol.MAX_FRAME_BYTES) flush()
            batchBytes += (if (batch.isEmpty()) 0 else 1) + size
            batch += json
        }
        if (batch.isNotEmpty()) flush()
        return parts
    }

    private fun part(rev: String, index: Int, items: List<String>): String = LibraryJsonWriter()
        .str("t", "catPart").num("v", V).str("rev", rev).num("index", index).raw("items", LibraryJsonWriter.array(items)).build()

    /** Bytes of a `catPart` with an empty item list at [index]. */
    private fun partOverhead(index: Int): Int = LibraryProtocol.utf8Size(part("A".repeat(22), index, emptyList()))

    fun playAck(req: String, ok: Boolean, code: PlayRefusal? = null): String {
        val w = LibraryJsonWriter().str("t", "playAck").num("v", V).str("req", req).bool("ok", ok)
        if (!ok) w.str("code", (code ?: PlayRefusal.UNAVAILABLE).wire)
        return w.build()
    }

    /** §6.6: which catalog item the cast [castId] is playing, for the TV's Up next. */
    fun casting(castId: String, id: String): String = LibraryJsonWriter()
        .str("t", "casting").num("v", V).str("castId", castId).str("id", id).build()

    fun detail(id: String, overview: String?, tagline: String?, hdr: CatalogItem.Hdr?, subtitle: String?): String {
        val w = LibraryJsonWriter().str("t", "detail").num("v", V).str("id", id)
        overview?.let { CatalogRules.label(it, 1_200) }?.let { w.str("overview", it) }
        tagline?.let { CatalogRules.label(it, 200) }?.let { w.str("tagline", it) }
        hdr?.let { w.str("hdr", it.wire) }
        subtitle?.let { CatalogRules.label(it, 200) }?.let { w.str("subtitle", it) }
        return w.build()
    }

    data class EpisodeInfo(val episode: Int, val name: String?, val overview: String?, val still: String?)

    /** §6.3: one season's TMDB episode data, split into frames of at most 16 KiB. */
    fun season(showKey: String, season: Int, episodes: List<EpisodeInfo>): List<String> {
        val imagePath = Regex("^/[A-Za-z0-9_-]+\\.(?:jpg|jpeg|png|webp)$")
        val encoded = episodes.filter { it.episode in 0..9_999 }.map { info ->
            val w = LibraryJsonWriter().num("e", info.episode)
            info.name?.let { CatalogRules.label(it, 200) }?.let { w.str("name", it) }
            info.overview?.let { CatalogRules.label(it, 400) }?.let { w.str("overview", it) }
            info.still?.takeIf(imagePath::matches)?.let { w.str("still", it) }
            w.build()
        }
        fun frame(part: Int, parts: Int, items: List<String>) = LibraryJsonWriter()
            .str("t", "season").num("v", V).str("showKey", showKey).num("s", season)
            .num("part", part).num("parts", parts).raw("episodes", LibraryJsonWriter.array(items)).build()
        val overhead = LibraryProtocol.utf8Size(frame(9_999, 9_999, emptyList()))
        val batches = ArrayList<List<String>>()
        var batch = ArrayList<String>()
        var bytes = 0
        for (json in encoded) {
            val size = LibraryProtocol.utf8Size(json) + if (batch.isEmpty()) 0 else 1
            if (batch.isNotEmpty() && overhead + bytes + size > LibraryProtocol.MAX_FRAME_BYTES) {
                batches += batch
                batch = ArrayList()
                bytes = 0
            }
            bytes += LibraryProtocol.utf8Size(json) + if (batch.isEmpty()) 0 else 1
            batch += json
        }
        if (batch.isNotEmpty() || batches.isEmpty()) batches += batch
        return batches.mapIndexed { index, items -> frame(index, batches.size, items) }
    }

    fun thumbMiss(id: String): String = LibraryJsonWriter().str("t", "thumbMiss").num("v", V).str("id", id).build()

    /** §6.4: `FLT1`, the item id, then the JPEG. Null when the whole frame would pass 16 KiB. */
    fun thumb(id: String, jpeg: ByteArray): ByteArray? {
        if (!LibraryProtocol.itemIdValue(id)) return null
        if (LibraryProtocol.THUMB_HEADER_BYTES + jpeg.size > LibraryProtocol.MAX_FRAME_BYTES) return null
        return (LibraryProtocol.THUMB_MAGIC + id).toByteArray(StandardCharsets.US_ASCII) + jpeg
    }

    /** The largest JPEG a thumbnail frame can carry. */
    const val THUMB_MAX_JPEG_BYTES = LibraryProtocol.MAX_FRAME_BYTES - LibraryProtocol.THUMB_HEADER_BYTES
}

/** §6.1's refusal codes. */
internal enum class PlayRefusal(val wire: String) {
    MISSING("missing"),
    DISABLED("disabled"),
    BUSY("busy"),
    UNAVAILABLE("unavailable"),
}

/** Frames the TV sends, as the phone reads them. */
internal sealed interface TvFrame {
    data class Challenge(
        val tvId: String, val keyId: String, val clientNonce: String, val serverNonce: String,
        val peerIp: String, val serverHost: String, val serverPort: Int,
    ) : TvFrame
    data class Ready(val serverNonce: String, val proof: String) : TvFrame
    data object Denied : TvFrame
    /** [have] is the rev the TV still holds, or null when it holds none. */
    data class CatWant(val have: String?) : TvFrame
    data class Play(val req: String, val id: String, val fromStart: Boolean) : TvFrame
    data class DetailWant(val id: String) : TvFrame
    data class SeasonWant(val showKey: String, val season: Int) : TvFrame
    data class ThumbWant(val id: String) : TvFrame
    data object Refresh : TvFrame
    data object Browsing : TvFrame
    /** A frame type this phone does not know: ignored, never fatal (§7). */
    data class Unknown(val type: String) : TvFrame
    /** A known type with a bad field, a wrong `v`, or not a JSON object: closes the library socket. */
    data object Malformed : TvFrame

    companion object {
        private val ipv4 = Regex("^(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])(?:\\.(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])){3}$")

        fun parse(text: String): TvFrame {
            if (LibraryProtocol.utf8Size(text) > LibraryProtocol.MAX_FRAME_BYTES) return Malformed
            val o = LibraryJsonReader.objectOnly(text) ?: return Malformed
            val type = o.str("t") ?: return Malformed
            val known = type in KnownTypes
            if (o.long("v") != LibraryProtocol.VERSION.toLong()) return if (known) Malformed else Unknown(type)
            return when (type) {
                "libChallenge" -> {
                    val tvId = o.str("tvId")?.takeIf(LibraryProtocol::nonce)
                    val keyId = o.str("keyId")?.takeIf(LibraryProtocol::nonce)
                    val client = o.str("clientNonce")?.takeIf(LibraryProtocol::nonce)
                    val server = o.str("serverNonce")?.takeIf(LibraryProtocol::nonce)
                    val peerIp = o.str("peerIp")?.takeIf(ipv4::matches)
                    val host = o.str("serverHost")?.takeIf(ipv4::matches)
                    val port = o.long("serverPort")?.takeIf { it in 1L..65_535L }?.toInt()
                    if (tvId == null || keyId == null || client == null || server == null || peerIp == null || host == null || port == null) Malformed
                    else Challenge(tvId, keyId, client, server, peerIp, host, port)
                }
                "libReady" -> {
                    val server = o.str("serverNonce")?.takeIf(LibraryProtocol::nonce)
                    val proof = o.str("proof")?.takeIf(LibraryProtocol::proofValue)
                    if (server == null || proof == null) Malformed else Ready(server, proof)
                }
                "libDenied" -> Denied
                "catWant" -> when (val have = o.fields["have"]) {
                    null -> CatWant(null)
                    else -> (have as? LibraryJsonValue.Str)?.value?.takeIf(LibraryProtocol::nonce)?.let(::CatWant) ?: Malformed
                }
                "play" -> {
                    val req = o.str("req")?.takeIf(LibraryProtocol::nonce)
                    val id = o.str("id")?.takeIf(LibraryProtocol::itemIdValue)
                    val from = o.str("from")?.takeIf { it == "resume" || it == "start" }
                    if (req == null || id == null || from == null) Malformed else Play(req, id, from == "start")
                }
                "detailWant" -> o.str("id")?.takeIf(LibraryProtocol::itemIdValue)?.let(::DetailWant) ?: Malformed
                "seasonWant" -> {
                    val key = o.str("showKey")?.takeIf(LibraryProtocol::showKeyValue)
                    val season = o.long("s")?.takeIf { it in 0L..999L }?.toInt()
                    if (key == null || season == null) Malformed else SeasonWant(key, season)
                }
                "thumbWant" -> o.str("id")?.takeIf(LibraryProtocol::itemIdValue)?.let(::ThumbWant) ?: Malformed
                "refresh" -> Refresh
                "browsing" -> Browsing
                else -> Unknown(type)
            }
        }

        private val KnownTypes = setOf(
            "libChallenge", "libReady", "libDenied", "catWant", "play", "detailWant", "seasonWant",
            "thumbWant", "refresh", "browsing",
        )
    }
}
