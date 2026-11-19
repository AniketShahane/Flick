package com.flick.receiver.library

import com.flick.receiver.net.StrictJsonValue

enum class LibraryItemKind(val wire: String) {
    FILM("film"),
    EPISODE("episode"),
    VIDEO("video"),
    ;

    companion object {
        fun of(wire: String): LibraryItemKind? = entries.firstOrNull { it.wire == wire }
    }
}

enum class LibraryHdr(val wire: String) {
    DOLBY_VISION("dv"),
    HDR10("hdr10"),
    SDR("sdr"),
    ;

    companion object {
        fun of(wire: String): LibraryHdr? = entries.firstOrNull { it.wire == wire }
    }
}

enum class LibraryBlock(val wire: String) {
    UNPLAYABLE("unplayable"),
    SILENT("silent"),
    ;

    companion object {
        fun of(wire: String): LibraryBlock? = entries.firstOrNull { it.wire == wire }
    }
}

/** TMDB image paths only. The TV builds every image URL itself (see [LibraryImages]). */
data class LibraryArt(
    val tmdbId: Long?,
    val show: Boolean?,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
)

/** One video in the phone's library, as `docs/design/library-channel.md` §5.1 defines it. */
data class LibraryItem(
    val id: String,
    val name: String,
    val title: String,
    val sortKey: String,
    val find: String,
    val kind: LibraryItemKind,
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
    val hdr: LibraryHdr? = null,
    val resumeMs: Long? = null,
    val watched: Boolean = false,
    val blocked: LibraryBlock? = null,
    val art: LibraryArt? = null,
) {
    val isEpisode: Boolean get() = kind == LibraryItemKind.EPISODE && showKey != null && season != null && episode != null
}

data class LibraryCatalog(val rev: String, val items: List<LibraryItem>) {
    private val byId: Map<String, LibraryItem> by lazy(LazyThreadSafetyMode.PUBLICATION) { items.associateBy { it.id } }

    operator fun get(id: String): LibraryItem? = byId[id]
}

object LibraryWire {
    const val VERSION = 1L
    const val MAX_ITEMS = 20_000
    const val MAX_PARTS = 4_000

    private val ItemId = Regex("[A-Za-z0-9_-]{11}")
    private val Rev = Regex("[A-Za-z0-9_-]{22}")
    private val ImagePath = Regex("^/[A-Za-z0-9_-]+\\.(?:jpg|jpeg|png|webp)$")
    private val ShowKey = Regex("^(?:tv:[1-9][0-9]{0,11}|name:.{1,200})$")

    fun itemId(value: String?): Boolean = value != null && ItemId.matches(value)

    fun rev(value: String?): Boolean = value != null && Rev.matches(value)

    fun imagePath(value: String?): Boolean = value != null && ImagePath.matches(value)

    /**
     * One catalog item, or null when a required field is missing or out of range. Unknown
     * fields are ignored on purpose (§5.1): a later phone may add display fields without a
     * coordinated release.
     */
    fun item(value: StrictJsonValue): LibraryItem? {
        val o = value as? StrictJsonValue.Obj ?: return null
        val id = o.str("id")?.takeIf(::itemId) ?: return null
        val name = o.label("name", 255) ?: return null
        val title = o.label("title", 200) ?: return null
        val sortKey = o.str("sortKey")?.takeIf { it.length <= 800 } ?: return null
        val find = o.str("find")?.takeIf { it.length <= 2_000 } ?: return null
        val kind = o.str("kind")?.let(LibraryItemKind::of) ?: return null
        val durationMs = o.long("durMs")?.takeIf { it in 0L..604_800_000L } ?: return null
        val added = o.long("added")?.takeIf { it >= 0L } ?: return null
        val width = o.long("w")?.takeIf { it in 0L..100_000L }?.toInt() ?: return null
        val height = o.long("h")?.takeIf { it in 0L..100_000L }?.toInt() ?: return null

        val episodic = kind == LibraryItemKind.EPISODE
        val show = if (episodic) o.label("show", 200) ?: return null else null
        val showKey = if (episodic) o.str("showKey")?.takeIf { ShowKey.matches(it) } ?: return null else null
        val season = if (episodic) o.long("s")?.takeIf { it in 0L..999L }?.toInt() ?: return null else null
        val episode = if (episodic) o.long("e")?.takeIf { it in 0L..9_999L }?.toInt() ?: return null else null

        val year = o.optionalLong("year") { it in 1870L..2100L } ?: return null
        val folder = o.optionalString("folder") { it.length <= 512 } ?: return null
        val hdr = o.optionalString("hdr") { LibraryHdr.of(it) != null } ?: return null
        val resume = o.optionalLong("resumeMs") { it in 0L..604_800_000L } ?: return null
        val blocked = o.optionalString("blocked") { LibraryBlock.of(it) != null } ?: return null
        val watched = when (val raw = o.fields["watched"]) {
            null -> false
            is StrictJsonValue.Bool -> raw.value
            else -> return null
        }
        val art = when (val raw = o.fields["art"]) {
            null -> null
            else -> art(raw) ?: return null
        }
        return LibraryItem(
            id = id,
            name = name,
            title = title,
            sortKey = sortKey,
            find = find,
            kind = kind,
            show = show,
            showKey = showKey,
            season = season,
            episode = episode,
            year = year.value?.toInt(),
            durationMs = durationMs,
            addedSeconds = added,
            folder = folder.value,
            width = width,
            height = height,
            hdr = hdr.value?.let(LibraryHdr::of),
            resumeMs = resume.value,
            watched = watched,
            blocked = blocked.value?.let(LibraryBlock::of),
            art = art,
        )
    }

    private fun art(value: StrictJsonValue): LibraryArt? {
        val o = value as? StrictJsonValue.Obj ?: return null
        val tmdb = o.optionalLong("tmdb") { it > 0L } ?: return null
        val show = when (val raw = o.fields["tv"]) {
            null -> null
            is StrictJsonValue.Bool -> raw.value
            else -> return null
        }
        val poster = o.optionalString("poster", ::imagePath) ?: return null
        val backdrop = o.optionalString("backdrop", ::imagePath) ?: return null
        val logo = o.optionalString("logo", ::imagePath) ?: return null
        if (poster.value == null && backdrop.value == null && logo.value == null) return null
        return LibraryArt(tmdb.value, show, poster.value, backdrop.value, logo.value)
    }

    /** A present-or-absent field: null means "present but invalid", which fails the item. */
    class Optional<T>(val value: T?)

    private fun StrictJsonValue.Obj.str(key: String): String? = (fields[key] as? StrictJsonValue.Str)?.value

    private fun StrictJsonValue.Obj.long(key: String): Long? = (fields[key] as? StrictJsonValue.Num)?.token?.let(::integer)

    private fun StrictJsonValue.Obj.label(key: String, maxCodePoints: Int): String? {
        val value = str(key) ?: return null
        if (value.isBlank() || value.codePointCount(0, value.length) > maxCodePoints) return null
        if (value.any { Character.isISOControl(it) }) return null
        return value
    }

    private fun StrictJsonValue.Obj.optionalString(key: String, valid: (String) -> Boolean): Optional<String>? {
        val raw = fields[key] ?: return Optional(null)
        val value = (raw as? StrictJsonValue.Str)?.value ?: return null
        return if (valid(value)) Optional(value) else null
    }

    private fun StrictJsonValue.Obj.optionalLong(key: String, valid: (Long) -> Boolean): Optional<Long>? {
        val raw = fields[key] ?: return Optional(null)
        val value = (raw as? StrictJsonValue.Num)?.token?.let(::integer) ?: return null
        return if (valid(value)) Optional(value) else null
    }

    /** A JSON integer token, refusing fractions, exponents and anything past Long. */
    fun integer(token: String): Long? {
        if (token.isEmpty() || token.length > 19) return null
        if (token.any { it == '.' || it == 'e' || it == 'E' || it == '+' }) return null
        return token.toLongOrNull()
    }
}

/**
 * Assembles `catBegin` / `catPart` / `catEnd` into a catalog and applies `catPatch`, swapping
 * in only complete, consistent catalogs (§5.2). Not thread-safe; owned by one dispatcher.
 */
class CatalogAssembler {

    sealed interface Outcome {
        data object Pending : Outcome
        data class Swapped(val catalog: LibraryCatalog) : Outcome
        /** The patch's base is not what this TV holds: ask for the whole catalog. */
        data object NeedFull : Outcome
        /** A protocol violation: the socket is closed. */
        data object Violation : Outcome
    }

    var current: LibraryCatalog? = null
        private set

    private var stagingRev: String? = null
    private var stagingParts = 0
    private var stagingCount = 0
    private var nextIndex = 0
    private val staging = ArrayList<LibraryItem>()
    private val stagingIds = HashSet<String>()

    fun begin(rev: String, count: Int, parts: Int): Outcome {
        if (!LibraryWire.rev(rev) || count !in 0..LibraryWire.MAX_ITEMS || parts !in 0..LibraryWire.MAX_PARTS) return Outcome.Violation
        if (parts == 0 && count != 0) return Outcome.Violation
        stagingRev = rev
        stagingParts = parts
        stagingCount = count
        nextIndex = 0
        staging.clear()
        stagingIds.clear()
        return Outcome.Pending
    }

    fun part(rev: String, index: Int, items: List<LibraryItem>): Outcome {
        if (rev != stagingRev || index != nextIndex || index >= stagingParts) return Outcome.Violation
        if (staging.size + items.size > stagingCount) return Outcome.Violation
        for (item in items) {
            if (!stagingIds.add(item.id)) return Outcome.Violation
            staging += item
        }
        nextIndex++
        return Outcome.Pending
    }

    fun end(rev: String): Outcome {
        if (rev != stagingRev || nextIndex != stagingParts || staging.size != stagingCount) return Outcome.Violation
        val catalog = LibraryCatalog(rev, staging.toList())
        current = catalog
        stagingRev = null
        staging.clear()
        stagingIds.clear()
        return Outcome.Swapped(catalog)
    }

    fun patch(base: String, rev: String, upsert: List<LibraryItem>, remove: List<String>): Outcome {
        if (!LibraryWire.rev(base) || !LibraryWire.rev(rev)) return Outcome.Violation
        val held = current ?: return Outcome.NeedFull
        if (held.rev != base) return Outcome.NeedFull
        if (upsert.map { it.id }.toSet().size != upsert.size) return Outcome.Violation
        val gone = remove.toHashSet()
        val replacing = upsert.associateBy { it.id }
        val kept = held.items.mapNotNull { item -> if (item.id in gone) null else replacing[item.id] ?: item }
        val keptIds = kept.mapTo(HashSet()) { it.id }
        val added = upsert.filter { it.id !in keptIds && it.id !in gone }
        val items = added + kept
        if (items.size > LibraryWire.MAX_ITEMS) return Outcome.Violation
        val catalog = LibraryCatalog(rev, items)
        current = catalog
        return Outcome.Swapped(catalog)
    }

    fun forget() {
        current = null
        stagingRev = null
        staging.clear()
        stagingIds.clear()
    }
}
