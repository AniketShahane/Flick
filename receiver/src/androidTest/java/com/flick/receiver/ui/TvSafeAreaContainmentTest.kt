package com.flick.receiver.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.flick.receiver.net.PairNetworkFace
import com.flick.receiver.player.DiagnosticsSnapshot
import com.flick.receiver.player.HdrType
import com.flick.receiver.player.PlaybackPhase
import com.flick.receiver.ui.screens.ErrorScreen
import com.flick.receiver.ui.screens.IdleScreen
import com.flick.receiver.ui.screens.PairScreen
import com.flick.receiver.ui.screens.PlaybackScreen
import com.flick.receiver.ui.screens.QualityInfo
import com.flick.receiver.session.ReceiverErrorFace
import com.flick.receiver.ui.theme.FlickTvTheme
import org.junit.Rule
import org.junit.Test

/**
 * Overscan containment, measured rather than asserted by eye.
 *
 * These screens are fixed 10-foot layouts with no scroll container, so a Column
 * that measures taller than the viewport does not clip visibly at the bottom —
 * it starves its LAST child and silently crushes that child's content to nothing.
 * A pair screen shipped that way: "Show code bigger" was still present in the
 * semantics tree (so every focus/text assertion passed) while rendering as an
 * empty pill on a real panel.
 *
 * So this asserts what a text-presence test cannot see: no text and no focusable
 * control escapes the 5 % overscan-safe box. An overflowing column pushes its
 * children past that boundary, which is the starved-last-child failure at its cause.
 *
 * It deliberately does NOT assert element heights. Several components attach their
 * `semantics` modifier inside their own padding, so the reported bounds are the
 * inner content rather than the element — a height floor read that way reports
 * false collapses.
 *
 * SettingsScreen is deliberately absent: it is the one receiver surface that DOES
 * scroll past the viewport by design (see [SettingsScreenFocusTest]), so bounds
 * containment is the wrong assertion for it.
 */
class TvSafeAreaContainmentTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Matches `rememberTvSafeAreaPadding` — 5 % of each axis. */
    private val safeFraction = 0.05f

    private fun assertInsideSafeArea(label: String, allowScrolledContent: Boolean = false) {
        composeRule.waitForIdle()
        val root = composeRule.onRoot().fetchSemanticsNode()
        val rootBounds = root.boundsInRoot
        val insetX = rootBounds.width * safeFraction
        val insetY = rootBounds.height * safeFraction

        val failures = mutableListOf<String>()

        fun walk(node: SemanticsNode) {
            val b = node.boundsInRoot
            // Only readable copy and interactive controls are bound by the safe area.
            // Backgrounds, gradient washes, scrims and the video surface itself are
            // SUPPOSED to bleed to the panel edge, so they are not candidates.
            val isContent = node.config.getOrNull(SemanticsProperties.Text) != null || node.isFocusTarget()
            if (isContent) {
                if (b.width <= 0f || b.height <= 0f) {
                    failures += "collapsed essential node: $b node=${node.describe()}"
                } else if (!allowScrolledContent && (b.bottom > rootBounds.bottom - insetY + 0.5f ||
                    b.top < rootBounds.top + insetY - 0.5f ||
                    b.right > rootBounds.right - insetX + 0.5f ||
                    b.left < rootBounds.left + insetX - 0.5f
                )) {
                    failures += "escapes safe area: $b (safe box inset ${insetX}x${insetY} " +
                        "of $rootBounds) node=${node.describe()}"
                }
            }
            node.children.forEach(::walk)
        }
        root.children.forEach(::walk)

        if (failures.isNotEmpty()) {
            throw AssertionError(
                "$label — ${failures.size} layout violation(s) at " +
                    "${rootBounds.width.toInt()}x${rootBounds.height.toInt()}px:\n" +
                    failures.joinToString("\n"),
            )
        }
    }

    private fun SemanticsNode.describe(): String {
        val text = config.getOrNull(SemanticsProperties.Text)
            ?.joinToString(" ") { it.text }
        val desc = config.getOrNull(SemanticsProperties.ContentDescription)
            ?.joinToString(" ")
        return "id=$id text=${text ?: "-"} desc=${desc ?: "-"}"
    }

    private fun SemanticsNode.isFocusTarget(): Boolean =
        config.getOrNull(SemanticsProperties.Focused) != null

    // --- Pair: all three variants, since they differ in height -----------------

    @Test
    fun pair_with_live_code_fits_the_safe_area() {
        composeRule.setContent {
            FlickTvTheme {
                PairScreen(
                    tvName = "Living Room TV",
                    code = "9742",
                    qrPayload = "flick://192.0.2.2:47654",
                    host = "192.0.2.2",
                    port = 47654,
                    networkFace = PairNetworkFace.READY,
                    bindUptimeSec = 42L,
                    onRename = {},
                    onOpenSettings = {},
                    codeExpiresAtElapsedMs = android.os.SystemClock.elapsedRealtime() + 296_000L,
                )
            }
        }
        assertInsideSafeArea("PairScreen · live code")
    }

    /** The tallest pair variant: the locked notice replaces the one-line timer row. */
    @Test
    fun pair_when_locked_fits_the_safe_area() {
        composeRule.setContent {
            FlickTvTheme {
                PairScreen(
                    tvName = "Living Room TV",
                    code = "—",
                    qrPayload = "flick://192.0.2.2:47654",
                    host = "192.0.2.2",
                    port = 47654,
                    networkFace = PairNetworkFace.READY,
                    onRename = {},
                    onOpenSettings = {},
                    codeExpiresAtElapsedMs = null,
                )
            }
        }
        assertInsideSafeArea("PairScreen · locked")
    }

    @Test
    fun pair_without_network_fits_the_safe_area() {
        composeRule.setContent {
            FlickTvTheme {
                PairScreen(
                    tvName = "Living Room TV",
                    code = "—",
                    qrPayload = null,
                    host = "",
                    port = -1,
                    networkFace = PairNetworkFace.NO_ADDRESS,
                    onRename = {},
                    onOpenSettings = {},
                )
            }
        }
        assertInsideSafeArea("PairScreen · no network")
    }

    @Test
    fun pair_actions_remain_reachable_at_large_font_scale() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.3f)) {
                FlickTvTheme {
                    PairScreen(
                        tvName = "Living Room TV",
                        code = "9742",
                        qrPayload = "flick://192.0.2.2:47654",
                        host = "192.0.2.2",
                        port = 47654,
                        networkFace = PairNetworkFace.READY,
                        onRename = {},
                        onOpenSettings = {},
                    )
                }
            }
        }
        // Both keys of the action row. It is the last child of an unscrolled
        // column, so it is the first thing an over-tall column starves — which is
        // how it once shipped as an empty pill.
        composeRule.onNodeWithText("Rename TV").assertIsDisplayed()
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        // The scrolled background may extend past the viewport; essential nodes
        // must still have real bounds, including at this accessibility scale.
        assertInsideSafeArea("PairScreen · large font", allowScrolledContent = true)
    }

    // --- The other fixed-height surfaces ---------------------------------------

    @Test
    fun idle_fits_the_safe_area() {
        composeRule.setContent {
            FlickTvTheme {
                IdleScreen(pairedLabel = "Pixel 9 Pro", onPairAnother = {}, onOpenSettings = {})
            }
        }
        assertInsideSafeArea("IdleScreen")
    }

    /**
     * One per face, because they differ in height: the bodies run from one sentence to
     * three, and the card is an unscrolled Column inside a fixed viewport — the shape
     * this whole file exists to catch. A composed rule can only take content once, so
     * these cannot be a loop.
     */
    private fun assertErrorFaceFits(face: ReceiverErrorFace, beforeReady: Boolean = true) {
        composeRule.setContent {
            FlickTvTheme {
                ErrorScreen(
                    face = face,
                    deviceLabel = "Pixel 9 Pro",
                    onDismiss = {},
                    beforeReady = beforeReady,
                )
            }
        }
        assertInsideSafeArea("ErrorScreen · $face · beforeReady=$beforeReady")
    }

    @Test fun error_video_codec_fits_the_safe_area() =
        assertErrorFaceFits(ReceiverErrorFace.VIDEO_CODEC_UNSUPPORTED)

    @Test fun error_video_format_fits_the_safe_area() =
        assertErrorFaceFits(ReceiverErrorFace.VIDEO_FORMAT_UNSUPPORTED)

    @Test fun error_hdr_fits_the_safe_area() =
        assertErrorFaceFits(ReceiverErrorFace.HDR_PROFILE_UNSUPPORTED)

    @Test fun error_container_fits_the_safe_area() =
        assertErrorFaceFits(ReceiverErrorFace.CONTAINER_UNSUPPORTED)

    @Test fun error_malformed_fits_the_safe_area() =
        assertErrorFaceFits(ReceiverErrorFace.MEDIA_MALFORMED)

    @Test fun error_decoder_fits_the_safe_area() =
        assertErrorFaceFits(ReceiverErrorFace.DECODER_UNAVAILABLE)

    @Test fun error_decoder_taken_fits_the_safe_area() =
        assertErrorFaceFits(ReceiverErrorFace.DECODER_TAKEN)

    @Test fun error_decoder_taken_mid_film_fits_the_safe_area() =
        assertErrorFaceFits(ReceiverErrorFace.DECODER_TAKEN, beforeReady = false)

    @Test fun error_audio_output_fits_the_safe_area() =
        assertErrorFaceFits(ReceiverErrorFace.AUDIO_OUTPUT_REFUSED)

    @Test fun error_audio_output_mid_film_fits_the_safe_area() =
        assertErrorFaceFits(ReceiverErrorFace.AUDIO_OUTPUT_REFUSED, beforeReady = false)

    @Test fun error_startup_fits_the_safe_area() =
        assertErrorFaceFits(ReceiverErrorFace.STARTUP_TIMEOUT)

    @Test fun error_refused_fits_the_safe_area() =
        assertErrorFaceFits(ReceiverErrorFace.SENDER_REFUSED)

    @Test fun error_not_serving_fits_the_safe_area() =
        assertErrorFaceFits(ReceiverErrorFace.SENDER_NOT_SERVING)

    @Test fun error_not_serving_mid_film_fits_the_safe_area() =
        assertErrorFaceFits(ReceiverErrorFace.SENDER_NOT_SERVING, beforeReady = false)

    @Test fun error_unreachable_before_start_fits_the_safe_area() =
        assertErrorFaceFits(ReceiverErrorFace.PHONE_UNREACHABLE, beforeReady = true)

    @Test fun error_unreachable_mid_film_fits_the_safe_area() =
        assertErrorFaceFits(ReceiverErrorFace.PHONE_UNREACHABLE, beforeReady = false)

    @Test fun error_link_lost_before_start_fits_the_safe_area() =
        assertErrorFaceFits(ReceiverErrorFace.LINK_LOST, beforeReady = true)

    @Test fun error_link_lost_mid_film_fits_the_safe_area() =
        assertErrorFaceFits(ReceiverErrorFace.LINK_LOST, beforeReady = false)

    @Test fun error_tv_network_fits_the_safe_area() =
        assertErrorFaceFits(ReceiverErrorFace.TV_NETWORK_CHANGED)

    @Test fun error_tv_network_mid_film_fits_the_safe_area() =
        assertErrorFaceFits(ReceiverErrorFace.TV_NETWORK_CHANGED, beforeReady = false)

    @Test fun error_picture_stopped_fits_the_safe_area() =
        assertErrorFaceFits(ReceiverErrorFace.PICTURE_STOPPED, beforeReady = false)

    @Test fun error_stopped_fits_the_safe_area() =
        assertErrorFaceFits(ReceiverErrorFace.PLAYBACK_STOPPED)

    @Test
    fun playback_chrome_fits_the_safe_area() {
        composeRule.setContent {
            FlickTvTheme {
                PlaybackScreen(
                    playing = true,
                    phase = PlaybackPhase.Playing,
                    positionMs = 4_312_000L,
                    durationMs = 8_076_000L,
                    bufferedMs = 4_932_000L,
                    targetMs = 4_312_000L,
                    seeking = false,
                    volume = 0.6f,
                    title = "A Long Enough Film Title To Exercise Ellipsis",
                    deviceLabel = "Pixel 9 Pro",
                    hdr = HdrType.DOLBY_VISION,
                    chromeVisible = true,
                    quality = null,
                    onBack10 = {},
                    onPlayPause = {},
                    onForward10 = {},
                    onSetVolume = {},
                    playFocusRequester = remember { FocusRequester() },
                    diagnostics = DiagnosticsSnapshot.EMPTY,
                    onEndSession = {},
                ) { Box(Modifier.fillMaxSize()) }
            }
        }
        assertInsideSafeArea("PlaybackScreen · chrome visible")
    }

    /**
     * The start-of-cast quality flourish is sized by its content, not by the frame.
     *
     * The card had no width of its own while every row inside it is `fillMaxWidth`
     * with `SpaceBetween` arrangement, so the rows distributed a label and a value
     * against the whole band: the card spanned the frame and ran under END SESSION
     * at the far end. Only bounds can see that — it shipped that way for weeks
     * while every text-presence and focus assertion in this module passed.
     *
     * The decoder here is the verified TV's own name, which is the widest real
     * value the card is ever asked to draw.
     */
    @Test
    fun quality_card_hugs_its_content_and_clears_end_session() {
        composeRule.setContent {
            FlickTvTheme {
                PlaybackScreen(
                    playing = true,
                    phase = PlaybackPhase.Playing,
                    positionMs = 4_312_000L,
                    durationMs = 8_076_000L,
                    bufferedMs = 4_932_000L,
                    targetMs = 4_312_000L,
                    seeking = false,
                    volume = 0.6f,
                    title = "A Long Enough Film Title To Exercise Ellipsis",
                    deviceLabel = "Pixel 9 Pro",
                    hdr = HdrType.DOLBY_VISION,
                    chromeVisible = true,
                    quality = QualityInfo(
                        qualityLabel = "4K · DOLBY VISION",
                        decoder = DECODER,
                        throughput = "38.4 Mb/s · 5 GHz",
                        bars = 3,
                    ),
                    onBack10 = {},
                    onPlayPause = {},
                    onForward10 = {},
                    onSetVolume = {},
                    playFocusRequester = remember { FocusRequester() },
                    diagnostics = DiagnosticsSnapshot.EMPTY,
                    onEndSession = {},
                ) { Box(Modifier.fillMaxSize()) }
            }
        }
        composeRule.waitForIdle()

        val rootBounds = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        // Every row in the card is a full-width `SpaceBetween`, so any row label's
        // left edge IS the card's content left edge and the decoder value's right
        // edge is its content right edge. The card is those plus 12 dp of panel
        // padding on each side — no test tag needed to read its width.
        val cardLeft = listOf("decoder", "throughput", "wi-fi health").minOf {
            composeRule.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.left
        }
        val cardRight = composeRule.onNodeWithText(DECODER)
            .fetchSemanticsNode().boundsInRoot.right
        val span = cardRight - cardLeft

        // Full-bleed measured ~90 % of the frame. The cap is 384 dp against a
        // 960 dp Android TV band, so a correct card lands near 37 % — this is a
        // regression fence, not the design's own number.
        val maxSpan = rootBounds.width * 0.45f
        if (span > maxSpan) {
            throw AssertionError(
                "quality card spans ${span.toInt()}px of a ${rootBounds.width.toInt()}px " +
                    "frame; must stay within ${maxSpan.toInt()}px",
            )
        }

        // END SESSION shares the band with it. `FlickTvButton` merges its
        // descendants, so this is the pill's own bounds, padding included.
        val endSession = composeRule.onNodeWithText("END SESSION")
            .fetchSemanticsNode().boundsInRoot
        if (cardLeft <= endSession.right) {
            throw AssertionError(
                "quality card starts at ${cardLeft.toInt()}px, under END SESSION " +
                    "which ends at ${endSession.right.toInt()}px",
            )
        }

        assertInsideSafeArea("PlaybackScreen · quality flourish")
    }

    private companion object {
        /** The verified TV's Dolby Vision decoder — the card's widest real value. */
        const val DECODER = "c2.mtk.dvhe.sth.decoder"
    }
}
