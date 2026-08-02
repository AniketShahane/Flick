package com.flick.receiver.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
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
import com.flick.receiver.player.DiagnosticsSnapshot
import com.flick.receiver.player.HdrType
import com.flick.receiver.player.PlaybackPhase
import com.flick.receiver.ui.screens.ErrorScreen
import com.flick.receiver.ui.screens.IdleScreen
import com.flick.receiver.ui.screens.PairScreen
import com.flick.receiver.ui.screens.PlaybackScreen
import com.flick.receiver.session.ErrorKind
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
                    networkReady = true,
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
                    networkReady = true,
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
                    networkReady = false,
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
                        networkReady = true,
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

    @Test
    fun error_unreachable_fits_the_safe_area() {
        composeRule.setContent {
            FlickTvTheme {
                ErrorScreen(
                    kind = ErrorKind.Unreachable,
                    deviceLabel = "Pixel 9 Pro",
                    onDismiss = {},
                )
            }
        }
        assertInsideSafeArea("ErrorScreen · unreachable")
    }

    @Test
    fun error_not_serving_fits_the_safe_area() {
        composeRule.setContent {
            FlickTvTheme {
                ErrorScreen(
                    kind = ErrorKind.NotServing,
                    deviceLabel = "Pixel 9 Pro",
                    onDismiss = {},
                )
            }
        }
        assertInsideSafeArea("ErrorScreen · not serving")
    }

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
                    playFocusRequester = FocusRequester(),
                    diagnostics = DiagnosticsSnapshot.EMPTY,
                    onEndSession = {},
                ) { Box(Modifier.fillMaxSize()) }
            }
        }
        assertInsideSafeArea("PlaybackScreen · chrome visible")
    }
}
