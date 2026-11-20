package com.flick.receiver.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.requestFocus
import androidx.test.platform.app.InstrumentationRegistry
import com.flick.receiver.library.LibraryCatalog
import com.flick.receiver.library.LibraryDetail
import com.flick.receiver.library.LibraryEpisodeInfo
import com.flick.receiver.library.LibraryHdr
import com.flick.receiver.library.LibraryHub
import com.flick.receiver.library.LibraryItem
import com.flick.receiver.library.LibraryItemKind
import com.flick.receiver.library.LibraryLink
import com.flick.receiver.library.LibrarySeasonKey
import com.flick.receiver.library.LibraryShelving
import com.flick.receiver.library.LibraryState
import com.flick.receiver.library.PlayFrom
import com.flick.receiver.library.UpNextOffer
import com.flick.receiver.ui.library.LibraryScreen
import com.flick.receiver.ui.theme.FlickTvTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * The TV library's screens over an invented catalog, written out as PNGs so spacing can be
 * judged by eye on the 960 × 540 dp canvas. A harness, not a gate: nothing is asserted.
 *
 * Every item is art-less, so each picture is a generated still served as a phone thumbnail —
 * no network, no TMDB, the same pixels on every run. Pull the files from the app's external
 * files directory; a Gradle connected run uninstalls the app and takes them with it.
 */
class LibraryLookTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class FakeHub(catalog: LibraryCatalog, thumbs: Map<String, ImageBitmap>, details: Map<String, LibraryDetail>) : LibraryHub {
        override val state: StateFlow<LibraryState> = MutableStateFlow(
            LibraryState(
                link = LibraryLink.CONNECTED,
                phoneName = "Pixel",
                catalog = catalog,
                shelves = LibraryShelving.shelves(catalog),
                epoch = 1L,
            ),
        )
        override val details: StateFlow<Map<String, LibraryDetail>> = MutableStateFlow(details)
        override val seasons: StateFlow<Map<LibrarySeasonKey, List<LibraryEpisodeInfo>>> = MutableStateFlow(emptyMap())
        override val thumbnails: StateFlow<Map<String, ImageBitmap>> = MutableStateFlow(thumbs)
        override val upNext: StateFlow<UpNextOffer?> = MutableStateFlow(null)
        override fun play(id: String, from: PlayFrom) = Unit
        override fun clearPlayResult() = Unit
        override fun wantDetail(id: String) = Unit
        override fun wantSeason(showKey: String, season: Int) = Unit
        override fun wantThumbnail(id: String) = Unit
        override fun refresh() = Unit
        override fun browsing() = Unit
        override fun playUpNext() = Unit
        override fun dismissUpNext() = Unit
    }

    private fun show() {
        val items = catalogItems()
        val catalog = LibraryCatalog(rev = "look", items = items)
        val thumbs = items.withIndex().associate { (index, item) -> item.id to still(index) }
        val synopsis = "A lighthouse keeper's daughter finds a ledger of ships that never reached " +
            "port, and follows it up a coast that keeps rearranging itself behind her."
        val details = items.associate { item ->
            item.id to LibraryDetail(
                overview = synopsis,
                tagline = if (item.kind == LibraryItemKind.FILM) "Some doors only open from the inside." else null,
                hdr = item.hdr,
                subtitle = if (item.id == "glasshouse") "English" else null,
            )
        }
        val hub = FakeHub(catalog, thumbs, details)
        composeRule.setContent {
            FlickTvTheme {
                LibraryScreen(hub = hub, onPairAnother = {}, onOpenSettings = {})
            }
        }
        composeRule.waitForIdle()
    }

    private fun tab(label: String) {
        composeRule.onNode(hasText(label) and hasClickAction()).performClick()
        composeRule.waitForIdle()
    }

    private fun focus(description: String) {
        composeRule.onAllNodes(hasContentDescription(description)).onFirst().requestFocus()
        composeRule.waitForIdle()
    }

    private fun press(key: Key, times: Int = 1) {
        repeat(times) {
            composeRule.onNode(isFocused()).performKeyInput { keyDown(key); keyUp(key) }
            composeRule.waitForIdle()
        }
    }

    private fun shoot(name: String) {
        composeRule.waitForIdle()
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        File(dir, "library-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun home_at_rest() {
        show()
        shoot("01-home-rest")
    }

    @Test
    fun home_rows() {
        show()
        focus("Paper Moons")
        shoot("02-home-row-focused")
        press(Key.DirectionDown, times = 3)
        shoot("03-home-posters-focused")
    }

    @Test
    fun shows_grid() {
        show()
        tab("Shows")
        focus("Northern Lines")
        shoot("04-shows-grid")
    }

    @Test
    fun films_grid() {
        show()
        tab("Films")
        focus("Low Tide")
        shoot("05-films-grid")
    }

    @Test
    fun all_videos() {
        show()
        tab("All videos")
        focus("Northbound")
        shoot("06-all-videos")
    }

    @Test
    fun show_page() {
        show()
        tab("Shows")
        composeRule.onAllNodes(hasContentDescription("The Lantern Coast") and hasClickAction()).onFirst().performClick()
        composeRule.waitForIdle()
        shoot("07-show-page")
        press(Key.DirectionDown, times = 2)
        shoot("08-show-episodes-focused")
    }

    @Test
    fun detail_page() {
        show()
        tab("Films")
        composeRule.onAllNodes(hasContentDescription("Glasshouse") and hasClickAction()).onFirst().performClick()
        composeRule.waitForIdle()
        shoot("09-detail-page")
    }

    @Test
    fun search() {
        show()
        composeRule.onNode(hasContentDescription("Search") and hasClickAction()).performClick()
        composeRule.waitForIdle()
        shoot("10-search")
    }

    // ── The invented catalog ────────────────────────────────────────────────

    private val base = 1_760_000_000L
    private var clock = 0L

    private fun added(): Long = base + (clock++) * 3_600L

    private fun film(id: String, title: String, year: Int, minutes: Int, resumeMinutes: Int? = null, hdr: LibraryHdr = LibraryHdr.HDR10) =
        LibraryItem(
            id = id, name = "$title ($year).mkv", title = title, sortKey = title.lowercase(),
            find = title.lowercase().filter { it.isLetterOrDigit() } + year,
            kind = LibraryItemKind.FILM, year = year, durationMs = minutes * 60_000L, addedSeconds = added(),
            folder = "Movies", width = 3840, height = 2160, hdr = hdr,
            resumeMs = resumeMinutes?.let { it * 60_000L },
        )

    private fun episodes(
        key: String,
        show: String,
        seasons: Map<Int, Int>,
        watchedThrough: Pair<Int, Int>? = null,
        resume: Pair<Int, Int>? = null,
    ): List<LibraryItem> = seasons.flatMap { (season, count) ->
        (1..count).map { episode ->
            val code = "S%02dE%02d".format(season, episode)
            val at = season * 100 + episode
            val watched = watchedThrough != null && at <= watchedThrough.first * 100 + watchedThrough.second
            LibraryItem(
                id = "$key-$code", name = "$show $code.mkv", title = "$show $code", sortKey = "$show $code".lowercase(),
                find = (show + code).lowercase().filter { it.isLetterOrDigit() },
                kind = LibraryItemKind.EPISODE, show = show, showKey = key, season = season, episode = episode,
                durationMs = (44 + abs(at * 7) % 14) * 60_000L, addedSeconds = added(), folder = "TV Shows",
                width = 3840, height = 2160, hdr = if (season % 2 == 0) LibraryHdr.DOLBY_VISION else LibraryHdr.HDR10,
                watched = watched,
                resumeMs = if (resume == season to episode) 21 * 60_000L else null,
            )
        }
    }

    private fun video(id: String, title: String, minutes: Int) = LibraryItem(
        id = id, name = "$title.mp4", title = title, sortKey = title.lowercase(),
        find = title.lowercase().filter { it.isLetterOrDigit() }, kind = LibraryItemKind.VIDEO,
        durationMs = minutes * 60_000L, addedSeconds = added(), folder = "Camera", width = 3840, height = 2160,
        hdr = LibraryHdr.DOLBY_VISION,
    )

    private fun catalogItems(): List<LibraryItem> {
        clock = 0L
        return buildList {
            addAll(episodes("harbour-lights", "Harbour Lights", mapOf(1 to 6, 2 to 6, 3 to 4)))
            addAll(episodes("small-hours", "Small Hours", mapOf(1 to 5), watchedThrough = 1 to 1))
            addAll(episodes("quiet-orbit", "Quiet Orbit", mapOf(1 to 8), watchedThrough = 1 to 2))
            addAll(episodes("northern-lines", "Northern Lines", mapOf(1 to 6, 2 to 6)))
            addAll(episodes("the-understudy", "The Understudy", mapOf(1 to 4)))
            addAll(episodes("salt-and-iron", "Salt and Iron", mapOf(1 to 8, 2 to 8)))
            addAll(episodes("field-notes", "Field Notes", mapOf(1 to 6)))
            addAll(
                episodes(
                    "lantern-coast", "The Lantern Coast", mapOf(1 to 6, 2 to 8),
                    watchedThrough = 2 to 3, resume = 2 to 4,
                ),
            )
            add(film("cartographer", "The Cartographer", 2011, 128))
            add(film("low-tide", "Low Tide", 2014, 101))
            add(film("northbound", "Northbound", 2016, 117, hdr = LibraryHdr.DOLBY_VISION))
            add(film("copper-sky", "Copper Sky", 2017, 109))
            add(film("winter-ferry", "A Winter Ferry", 2018, 96))
            add(film("velvet-static", "Velvet Static", 2020, 122, hdr = LibraryHdr.DOLBY_VISION))
            add(film("long-field", "The Long Field", 2021, 134))
            add(film("signal-fires", "Signal Fires", 2022, 141, hdr = LibraryHdr.DOLBY_VISION))
            add(film("still-water", "Still Water", 2015, 99))
            add(film("blue-hour", "Blue Hour", 2019, 112))
            add(film("paper-moons", "Paper Moons", 2019, 113, resumeMinutes = 64))
            add(film("glasshouse", "Glasshouse", 2023, 126, resumeMinutes = 38, hdr = LibraryHdr.DOLBY_VISION))
            add(video("beach-day", "Beach day", 4))
            add(video("birthday", "Birthday dinner", 12))
            add(video("falls", "Hike to the falls", 7))
            add(video("concert", "Concert clip", 3))
        }
    }

    /** A made-up film still: a sky, a light in it and a horizon, its colour picked by [seed]. */
    private fun still(seed: Int): ImageBitmap {
        val w = 480f
        val h = 270f
        val bitmap = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val hue = (seed * 53f) % 360f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            0f, 0f, 0f, h,
            android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.5f, 0.62f)),
            android.graphics.Color.HSVToColor(floatArrayOf((hue + 35f) % 360f, 0.7f, 0.16f)),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = RadialGradient(
            w * (0.22f + (seed % 5) * 0.14f), h * 0.4f, h * 0.6f,
            intArrayOf(0x99FFF2D6.toInt(), 0x00FFF2D6), null, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        paint.color = android.graphics.Color.HSVToColor(235, floatArrayOf((hue + 190f) % 360f, 0.45f, 0.1f))
        val ridge = Path().apply {
            moveTo(0f, h * 0.72f)
            quadTo(w * 0.3f, h * (0.52f + (seed % 3) * 0.05f), w * 0.55f, h * 0.68f)
            quadTo(w * 0.8f, h * 0.8f, w, h * 0.62f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        canvas.drawPath(ridge, paint)
        return bitmap.asImageBitmap()
    }
}
