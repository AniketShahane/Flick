package com.flick.receiver.library

/** One show: its episodes grouped by season, each season in episode order. */
data class LibraryShow(
    val key: String,
    val title: String,
    val art: LibraryArt?,
    val seasons: List<Int>,
    val episodes: Map<Int, List<LibraryItem>>,
    /** Newest `added` among its episodes, for ordering shows under Recently added. */
    val addedSeconds: Long,
) {
    val episodeCount: Int get() = episodes.values.sumOf { it.size }
    val watchedCount: Int get() = episodes.values.sumOf { list -> list.count { it.watched } }
    val all: List<LibraryItem> get() = seasons.flatMap { episodes[it].orEmpty() }
}

/** The home screen's rows and the Shows/Films tabs, derived from one catalog. */
data class LibraryShelves(
    val continueWatching: List<LibraryItem>,
    val nextEpisodes: List<LibraryItem>,
    val recentlyAdded: List<LibraryItem>,
    val shows: List<LibraryShow>,
    val films: List<LibraryItem>,
    val homeVideos: List<LibraryItem>,
)

object LibraryShelving {

    const val ROW_LIMIT = 30

    /** Episodes by `(season, episode)`, then name order so two files of one episode sit together. */
    val EpisodeOrder: Comparator<LibraryItem> =
        compareBy<LibraryItem>({ it.season ?: 0 }, { it.episode ?: 0 })
            .then { a, b -> LibraryNameOrder.compare(a.sortKey, b.sortKey) }

    fun shows(items: List<LibraryItem>): List<LibraryShow> {
        val grouped = LinkedHashMap<String, MutableList<LibraryItem>>()
        for (item in items) {
            if (!item.isEpisode) continue
            grouped.getOrPut(item.showKey!!) { ArrayList() } += item
        }
        return grouped.map { (key, episodes) ->
            val sorted = episodes.sortedWith(EpisodeOrder)
            val bySeason = LinkedHashMap<Int, List<LibraryItem>>()
            sorted.groupBy { it.season!! }.toSortedMap().forEach { (season, list) -> bySeason[season] = list }
            LibraryShow(
                key = key,
                title = sorted.firstNotNullOfOrNull { it.show } ?: sorted.first().title,
                art = sorted.firstNotNullOfOrNull { it.art },
                seasons = bySeason.keys.toList(),
                episodes = bySeason,
                addedSeconds = sorted.maxOf { it.addedSeconds },
            )
        }.sortedWith { a, b -> LibraryNameOrder.compare(LibraryFold.fold(a.title), LibraryFold.fold(b.title)) }
    }

    /** A resume point shorter than this is a sampling, not a watch: it never moves a show's anchor. */
    const val ANCHOR_MIN_RESUME_MS = 120_000L

    /** When the viewer last really watched [item], in epoch seconds, or null. */
    fun touchedAt(item: LibraryItem): Long? {
        val watched = item.watchedAtSeconds?.takeIf { item.watched }
        val played = item.playedAtSeconds?.takeIf { (item.resumeMs ?: 0L) >= ANCHOR_MIN_RESUME_MS }
        return when {
            watched == null -> played
            played == null -> watched
            else -> maxOf(watched, played)
        }
    }

    /** No episode carries a time: the phone predates `playedAt` / `watchedAt`. */
    fun isLegacy(show: LibraryShow): Boolean = show.all.none { touchedAt(it) != null }

    /**
     * The episode the viewer is at in [show]: the most recently touched (a tie goes to the
     * later episode), or for a legacy phone the last watched by `(season, episode)`.
     */
    fun anchor(show: LibraryShow): LibraryItem? = anchorIn(show.all)

    private fun anchorIn(ordered: List<LibraryItem>): LibraryItem? {
        var best: LibraryItem? = null
        var bestAt = Long.MIN_VALUE
        for (item in ordered) {
            val at = touchedAt(item) ?: continue
            if (at >= bestAt) {
                best = item
                bestAt = at
            }
        }
        return best ?: ordered.lastOrNull { it.watched }
    }

    /** The first unwatched episode after [anchor] that is a different episode, in [ordered]. */
    private fun unwatchedAfter(ordered: List<LibraryItem>, anchor: LibraryItem): LibraryItem? {
        val at = ordered.indexOfFirst { it.id == anchor.id }
        if (at < 0) return null
        return ordered.subList(at + 1, ordered.size)
            .firstOrNull { (it.season != anchor.season || it.episode != anchor.episode) && !it.watched }
    }

    /**
     * The episode Next episodes offers for [show]: the first unwatched one after its anchor
     * that is a different episode. Null when nothing has been watched, when the anchor is in
     * progress or the show is finished, or when that episode is in progress (both are in
     * Continue watching) — a later episode is never offered in its place.
     */
    fun nextEpisode(show: LibraryShow): LibraryItem? {
        val ordered = show.all
        if (isLegacy(show)) return legacyNextEpisode(ordered)
        val anchor = anchorIn(ordered) ?: return null
        if (anchor.resumeMs != null) return null
        return unwatchedAfter(ordered, anchor)?.takeIf { it.resumeMs == null }
    }

    private fun legacyNextEpisode(ordered: List<LibraryItem>): LibraryItem? {
        val lastWatched = ordered.indexOfLast { it.watched }
        if (lastWatched < 0) return null
        val last = ordered[lastWatched]
        val candidate = ordered.drop(lastWatched + 1)
            .firstOrNull { it.season != last.season || it.episode != last.episode } ?: return null
        return candidate.takeIf { !it.watched && it.resumeMs == null }
    }

    /**
     * The episode that follows [item] in its show, watched or not — what Up next plays. A
     * second file of the same `(season, episode)` is skipped, so a duplicate never plays twice.
     */
    fun following(item: LibraryItem, catalog: LibraryCatalog): LibraryItem? {
        if (!item.isEpisode) return null
        val ordered = catalog.items.filter { it.isEpisode && it.showKey == item.showKey }.sortedWith(EpisodeOrder)
        val at = ordered.indexOfFirst { it.id == item.id }
        if (at < 0) return null
        return ordered.drop(at + 1).firstOrNull { it.season != item.season || it.episode != item.episode }
    }

    /**
     * What "Play" on a show starts: the anchor if it is in progress, else the next unwatched
     * episode after it (resumed if started), else the first unwatched, else the first.
     */
    fun showPick(show: LibraryShow): LibraryItem? {
        val ordered = show.all
        if (isLegacy(show)) {
            return ordered.firstOrNull { it.resumeMs != null } ?: legacyNextEpisode(ordered)
                ?: ordered.firstOrNull { !it.watched } ?: ordered.firstOrNull()
        }
        val anchor = anchorIn(ordered)
        if (anchor?.resumeMs != null) return anchor
        return anchor?.let { unwatchedAfter(ordered, it) } ?: ordered.firstOrNull { !it.watched } ?: ordered.firstOrNull()
    }

    /**
     * The one in-progress episode of a non-legacy [show] that Continue watching holds: the
     * episode Play on the show would resume, which is the anchor whenever the anchor is in
     * progress. Other in-progress episodes are ones the viewer moved past.
     */
    private fun heldInProgress(show: LibraryShow): LibraryItem? = showPick(show)?.takeIf { it.resumeMs != null }

    fun shelves(catalog: LibraryCatalog): LibraryShelves {
        val items = catalog.items
        val shows = shows(items)
        val recent = LibrarySorting.sorted(items, LibrarySortOrder.RECENT)

        // A legacy show keeps every in-progress episode in Continue watching, as before.
        val heldByShow = HashMap<String, String?>()
        val legacyShows = HashSet<String>()
        val nexts = ArrayList<Triple<LibraryItem, Long?, Long>>()
        for (show in shows) {
            val legacy = isLegacy(show)
            if (legacy) legacyShows += show.key
            else heldByShow[show.key] = heldInProgress(show)?.id
            val next = nextEpisode(show) ?: continue
            val anchorAt = if (legacy) null else anchor(show)?.let(::touchedAt)
            val lastWatchedAdded = show.all.filter { it.watched }.maxOfOrNull { it.addedSeconds } ?: 0L
            nexts += Triple(next, anchorAt, lastWatchedAdded)
        }

        val continuing = recent.filter { item ->
            when {
                item.resumeMs == null -> false
                !item.isEpisode -> true
                else -> item.showKey!! in legacyShows || heldByShow[item.showKey!!] == item.id
            }
        }
        // Stable: items with no `playedAt` keep their Recently added order, after the rest.
        val continueWatching = continuing.sortedWith(compareByDescending<LibraryItem, Long?>(nullsFirst<Long>()) { it.playedAtSeconds })
        val nextEpisodes = nexts
            .sortedWith(compareByDescending<Triple<LibraryItem, Long?, Long>, Long?>(nullsFirst<Long>()) { it.second }.thenByDescending { it.third })
            .map { it.first }

        return LibraryShelves(
            continueWatching = continueWatching.take(ROW_LIMIT),
            nextEpisodes = nextEpisodes.take(ROW_LIMIT),
            recentlyAdded = recent.take(ROW_LIMIT),
            shows = shows,
            films = LibrarySorting.sorted(items.filter { it.kind == LibraryItemKind.FILM }, LibrarySortOrder.NAME),
            homeVideos = recent.filter { it.kind == LibraryItemKind.VIDEO && it.art == null },
        )
    }
}

/** The phone's folder tree (`LibraryFolders`), ported for the All videos folder chooser. */
data class LibraryFolderNode(val path: String, val name: String, val depth: Int, val count: Int)

object LibraryFolderTree {

    fun normalized(path: String?): String? {
        if (path == null) return null
        val segments = path.split('/').mapNotNull { segment -> segment.trim().takeIf { it.isNotEmpty() } }
        return segments.takeIf { it.isNotEmpty() }?.joinToString("/")
    }

    fun derive(items: List<LibraryItem>): List<LibraryFolderNode> {
        val counts = HashMap<String, Int>()
        items.forEach { item ->
            val path = normalized(item.folder) ?: return@forEach
            var node = ""
            path.split('/').forEach { segment ->
                node = if (node.isEmpty()) segment else "$node/$segment"
                counts[node] = (counts[node] ?: 0) + 1
            }
        }
        return counts.keys
            .map { it to it.split('/') }
            .sortedWith(compareBy(SegmentOrder) { it.second })
            .map { (path, segments) -> LibraryFolderNode(path, segments.last(), segments.size - 1, counts.getValue(path)) }
    }

    fun scoped(items: List<LibraryItem>, folder: String?): List<LibraryItem> {
        if (folder == null) return items
        return items.filter { holds(folder, normalized(it.folder)) }
    }

    /** Offered only when choosing a folder could change what is shown — the phone's rule. */
    fun chooserOffered(folders: List<LibraryFolderNode>, folder: String?, itemCount: Int): Boolean =
        folder != null || folders.any { it.count < itemCount }

    private fun holds(folder: String, path: String?): Boolean =
        path != null && (path == folder || path.startsWith("$folder/"))

    private val SegmentOrder = Comparator<List<String>> { a, b ->
        var i = 0
        while (i < a.size && i < b.size) {
            val byName = a[i].compareTo(b[i], ignoreCase = true)
            if (byName != 0) return@Comparator byName
            val exact = a[i].compareTo(b[i])
            if (exact != 0) return@Comparator exact
            i++
        }
        a.size - b.size
    }
}

/**
 * When the Up next card may start the next episode by itself.
 *
 * Three autoplays in a row with no key press from the viewer means nobody may be watching,
 * and a phone streaming to an empty room all night is exactly the battery cost Flick spent
 * PR #33 removing; the fourth waits for a press.
 */
class UpNextStreak(private val limit: Int = 3) {
    private var autoplays = 0

    fun mayAutoplay(autoplayEnabled: Boolean): Boolean = autoplayEnabled && autoplays < limit

    fun autoplayed() {
        autoplays++
    }

    fun viewerPressed() {
        autoplays = 0
    }
}
