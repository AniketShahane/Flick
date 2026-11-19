package com.flick.receiver.ui.library

import com.flick.receiver.library.LibraryFold
import com.flick.receiver.library.LibraryFolderTree
import com.flick.receiver.library.LibraryItem
import com.flick.receiver.library.LibraryPlay
import com.flick.receiver.library.LibraryPlayFailure
import com.flick.receiver.library.LibrarySearch
import com.flick.receiver.library.LibraryShelves
import com.flick.receiver.library.LibraryShow

/** The resolution class a tile or chip names; the words themselves are string resources. */
internal enum class LibraryResolution { UHD, FHD, HD, SD }

/**
 * Which message a failed Play shows. One per [LibraryPlayFailure] today; kept as its own type
 * so the copy can merge two causes without the hub's vocabulary changing.
 */
internal enum class PlayFailureCopy {
    NOT_ON_PHONE,
    SETTING_OFF,
    PHONE_BUSY,
    CANT_OPEN,
    PHONE_IN_BACKGROUND,
    NO_ANSWER,
    DID_NOT_START,
    OFFLINE,
}

/** The home hero: the first Continue watching item, else the newest one. */
internal data class HeroPick(val item: LibraryItem, val continuing: Boolean)

internal object LibraryFormat {

    private const val MINUTE_MS = 60_000L

    /**
     * Whole minutes still to watch, rounded UP: twenty seconds left must never read
     * "0 MIN LEFT". Null when there is no resume point or the running time is unknown.
     */
    fun minutesLeft(item: LibraryItem): Int? {
        val resume = item.resumeMs ?: return null
        if (item.durationMs <= 0L) return null
        val left = item.durationMs - resume
        if (left <= 0L) return null
        return ((left + MINUTE_MS - 1) / MINUTE_MS).toInt()
    }

    /** How far through [item] the resume point is, 0..1; null with no resume point or no running time. */
    fun progress(item: LibraryItem): Float? {
        val resume = item.resumeMs ?: return null
        if (item.durationMs <= 0L) return null
        return (resume.toDouble() / item.durationMs).toFloat().coerceIn(0f, 1f)
    }

    /**
     * A running time as whole minutes — "12 MIN" for 12:14, as the phone's tiles read — and
     * never 0 for a known one. Null when unknown (`durMs` 0).
     */
    fun runtimeMinutes(durationMs: Long): Int? =
        if (durationMs <= 0L) null else (durationMs / MINUTE_MS).coerceAtLeast(1L).toInt()

    /** "2:26", "52:10", "1:02:03". */
    fun clock(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0L) / 1_000L)
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            "$hours:${pad(minutes)}:${pad(seconds)}"
        } else {
            "$minutes:${pad(seconds)}"
        }
    }

    /** "S2:E5". */
    fun episodeLabel(season: Int, episode: Int): String = "S$season:E$episode"

    fun episodeLabel(item: LibraryItem): String? =
        if (item.isEpisode) episodeLabel(item.season!!, item.episode!!) else null

    /**
     * Classed by the frame's SHORT side, so a portrait phone clip shot in 4K still reads 4K.
     * The long-side terms catch scope crops (3840 × 1600 is 4K, 1920 × 800 is 1080p).
     */
    fun resolution(width: Int, height: Int): LibraryResolution? {
        if (width <= 0 || height <= 0) return null
        val short = minOf(width, height)
        val long = maxOf(width, height)
        return when {
            short >= 2_000 || long >= 3_200 -> LibraryResolution.UHD
            short >= 1_000 || long >= 1_800 -> LibraryResolution.FHD
            short >= 700 || long >= 1_200 -> LibraryResolution.HD
            else -> LibraryResolution.SD
        }
    }

    /** The last segment of a MediaStore relative path: "Movies/Shows/Lantern Coast/" → "Lantern Coast". */
    fun folderName(folder: String?): String? =
        LibraryFolderTree.normalized(folder)?.substringAfterLast('/')

    fun heroPick(shelves: LibraryShelves): HeroPick? =
        shelves.continueWatching.firstOrNull()?.let { HeroPick(it, continuing = true) }
            ?: shelves.recentlyAdded.firstOrNull()?.let { HeroPick(it, continuing = false) }

    /** True while the phone is being asked to play [id], or has said yes and the cast is starting. */
    fun asking(play: LibraryPlay, id: String): Boolean = when (play) {
        is LibraryPlay.Asking -> play.id == id
        is LibraryPlay.Starting -> play.id == id
        else -> false
    }

    fun failureCopy(reason: LibraryPlayFailure): PlayFailureCopy = when (reason) {
        LibraryPlayFailure.MISSING -> PlayFailureCopy.NOT_ON_PHONE
        LibraryPlayFailure.DISABLED -> PlayFailureCopy.SETTING_OFF
        LibraryPlayFailure.BUSY -> PlayFailureCopy.PHONE_BUSY
        LibraryPlayFailure.UNAVAILABLE -> PlayFailureCopy.CANT_OPEN
        LibraryPlayFailure.BACKGROUND -> PlayFailureCopy.PHONE_IN_BACKGROUND
        LibraryPlayFailure.NO_ANSWER -> PlayFailureCopy.NO_ANSWER
        LibraryPlayFailure.NOT_STARTED -> PlayFailureCopy.DID_NOT_START
        LibraryPlayFailure.OFFLINE -> PlayFailureCopy.OFFLINE
    }

    /**
     * Whole seconds until [autoplayAtMs], rounded up so the ring reads 10 at the start of a
     * ten-second window and 1 in its last second; 0 once it has passed.
     */
    fun secondsUntil(autoplayAtMs: Long, nowMs: Long): Int {
        val left = autoplayAtMs - nowMs
        if (left <= 0L) return 0
        return ((left + 999L) / 1_000L).toInt()
    }

    private fun pad(value: Long): String = if (value < 10L) "0$value" else value.toString()
}

/** A search result: a video, or a whole show when the query names the show itself. */
internal sealed interface SearchHit {
    val key: String

    data class Video(val item: LibraryItem) : SearchHit {
        override val key: String get() = "v:" + item.id
    }

    data class Show(val show: LibraryShow) : SearchHit {
        override val key: String get() = "s:" + show.key
    }
}

internal object LibrarySearchModel {

    /** The on-screen keyboard's character keys, in reading order. */
    const val KEYS = "abcdefghijklmnopqrstuvwxyz1234567890"

    const val KEY_COLUMNS = 7

    /** Long enough for any title anyone types with a remote; short enough to stay on one line. */
    const val MAX_QUERY = 40

    /**
     * The phone's search ([LibrarySearch.matching], every word a substring of `find`), in
     * catalog order. Episodes collapse into one show result when every word also names the
     * show — "lantern" finds The Lantern Coast once, not eighteen times — while a query that
     * only an episode's own file name carries still lists that episode. An empty query lists
     * nothing: the whole catalog is not a search result.
     */
    fun hits(items: List<LibraryItem>, shows: List<LibraryShow>, query: String): List<SearchHit> {
        val words = LibraryFold.words(query)
        if (words.isEmpty()) return emptyList()
        val matches = LibrarySearch.matching(items, query)
        if (matches.isEmpty()) return emptyList()
        val showsByKey = shows.associateBy { it.key }
        val namedShows = HashMap<String, Boolean>()
        val emitted = HashSet<String>()
        val hits = ArrayList<SearchHit>(matches.size)
        for (item in matches) {
            val show = if (item.isEpisode) showsByKey[item.showKey] else null
            val named = show != null && namedShows.getOrPut(show.key) {
                val compact = LibraryFold.fold(show.title).replace(" ", "")
                words.all { it in compact }
            }
            if (named) {
                if (emitted.add(show!!.key)) hits += SearchHit.Show(show)
            } else {
                hits += SearchHit.Video(item)
            }
        }
        return hits
    }

    /** [query] with [key] typed: no leading or doubled space, and nothing past [MAX_QUERY]. */
    fun typed(query: String, key: Char): String {
        if (query.length >= MAX_QUERY) return query
        if (key == ' ' && (query.isEmpty() || query.endsWith(' '))) return query
        return query + key
    }

    fun deleted(query: String): String = query.dropLast(1)
}
