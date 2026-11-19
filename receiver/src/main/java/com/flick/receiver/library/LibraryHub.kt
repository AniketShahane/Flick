package com.flick.receiver.library

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.StateFlow

/** How this TV's library socket to the phone stands. */
enum class LibraryLink {
    /** No phone has sent a library since this process started; the standby screen stays Idle. */
    NONE,

    /** Authenticated and current. */
    CONNECTED,

    /** Was connected; the catalog is kept so a Wi-Fi blip does not blank the screen (§5.2). */
    LOST,
}

/** Why a Play pressed on the TV did not start a cast. */
enum class LibraryPlayFailure {
    MISSING, DISABLED, BUSY, UNAVAILABLE,

    /** The phone cannot start a cast while Flick is in its background and battery-optimised. */
    BACKGROUND,

    /** No `playAck` within 8 s. */
    NO_ANSWER,

    /** `playAck ok:true` but no `loadMedia` within 30 s. */
    NOT_STARTED,

    /** The library socket is not connected. */
    OFFLINE,
}

/** The one outstanding Play request (§6.1: at most one per socket). */
sealed interface LibraryPlay {
    data object Idle : LibraryPlay

    /** Sent; waiting for the phone's `playAck`. */
    data class Asking(val id: String) : LibraryPlay

    /** The phone accepted; waiting for its control-v2 `loadMedia`. */
    data class Starting(val id: String) : LibraryPlay

    /** Shown on the screen that asked until [LibraryHub.clearPlayResult]. */
    data class Failed(val id: String, val reason: LibraryPlayFailure) : LibraryPlay
}

/** What the library screens draw. */
data class LibraryState(
    val link: LibraryLink = LibraryLink.NONE,
    /** This TV's own label for the phone whose library this is. */
    val phoneName: String? = null,
    val catalog: LibraryCatalog? = null,
    /** Derived from [catalog] off the main thread; null exactly when [catalog] is. */
    val shelves: LibraryShelves? = null,
    val play: LibraryPlay = LibraryPlay.Idle,
)

/** A `detail` frame's fields (§6.2). */
data class LibraryDetail(val overview: String?, val tagline: String?, val hdr: LibraryHdr?, val subtitle: String?)

data class LibrarySeasonKey(val showKey: String, val season: Int)

/**
 * The Up next card over an ended episode (§10). [autoplayAtMs] is on
 * `SystemClock.elapsedRealtime()`; null when autoplay is off or [stillWatching] is asking.
 */
data class UpNextOffer(
    val ended: LibraryItem,
    val next: LibraryItem,
    val show: LibraryShow?,
    val autoplayAtMs: Long?,
    val stillWatching: Boolean,
)

/**
 * The TV library's single source of truth, implemented by the library server and read by the
 * library screens. Every request method is safe to call repeatedly from composition: the hub
 * de-duplicates, rate-limits (§6.4, §6.5) and drops requests while the link is down.
 */
interface LibraryHub {
    val state: StateFlow<LibraryState>

    /** By item id. */
    val details: StateFlow<Map<String, LibraryDetail>>

    /** TMDB episode data by show and season; absent until the phone answers. */
    val seasons: StateFlow<Map<LibrarySeasonKey, List<LibraryEpisodeInfo>>>

    /** Phone thumbnails by item id, decoded; only for items that have no TMDB art. */
    val thumbnails: StateFlow<Map<String, ImageBitmap>>

    val upNext: StateFlow<UpNextOffer?>

    fun play(id: String, from: PlayFrom)

    fun clearPlayResult()

    fun wantDetail(id: String)

    fun wantSeason(showKey: String, season: Int)

    /** For a tile on screen with no TMDB art. At most 6 are outstanding; the rest wait. */
    fun wantThumbnail(id: String)

    fun refresh()

    /** Called while a library screen is visible; sent at most once a minute. */
    fun browsing()

    fun playUpNext()

    fun dismissUpNext()
}
