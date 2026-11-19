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

    /**
     * The episode to offer after [show]'s most recently finished one: the first after it by
     * `(season, episode)` that is a different episode (a second file of the finished one is
     * skipped). Null when nothing in the show has been watched yet, when the show is finished,
     * or when that episode is watched or already in progress (it is in Continue watching
     * instead) — a later episode is never offered in its place.
     */
    fun nextEpisode(show: LibraryShow): LibraryItem? {
        val ordered = show.all
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

    /** What "Play" on a show starts: the in-progress episode, else the next one, else the first. */
    fun showPick(show: LibraryShow): LibraryItem? =
        show.all.firstOrNull { it.resumeMs != null } ?: nextEpisode(show) ?: show.all.firstOrNull { !it.watched } ?: show.all.firstOrNull()

    fun shelves(catalog: LibraryCatalog): LibraryShelves {
        val items = catalog.items
        val shows = shows(items)
        val recent = LibrarySorting.sorted(items, LibrarySortOrder.RECENT)
        return LibraryShelves(
            continueWatching = recent.filter { it.resumeMs != null }.take(ROW_LIMIT),
            nextEpisodes = shows.mapNotNull(::nextEpisode)
                .sortedByDescending { next -> shows.first { it.key == next.showKey }.all.filter { it.watched }.maxOfOrNull { it.addedSeconds } ?: 0L }
                .take(ROW_LIMIT),
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
