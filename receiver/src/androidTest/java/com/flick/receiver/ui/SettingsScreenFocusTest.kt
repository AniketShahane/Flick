package com.flick.receiver.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import com.flick.receiver.net.PairedPhone
import com.flick.receiver.ui.screens.SettingsScreen
import com.flick.receiver.ui.theme.FlickDimens
import com.flick.receiver.ui.theme.FlickTvTheme
import com.flick.receiver.util.FlickLog
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsScreenFocusTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * Two phones, because "Forget all phones" is only offered at two or more: at
     * one it duplicates that row's own Forget, and at none it is a destructive
     * action with nothing to destroy. Every case below therefore mounts the same
     * column shape — Device name, the paired heading, two phone rows, then the
     * rows this file has always walked. One phone is undated, which is how a
     * pairing written before the TV recorded dates renders.
     */
    private val pairedPhones = listOf(
        PairedPhone(keyId = "test-key-a", label = "Pixel 9 Pro", pairedAtMs = 1_726_000_000_000L),
        PairedPhone(keyId = "test-key-b", label = "Galaxy S24", pairedAtMs = null),
    )

    private val expandedDiagnostics = List(14) { index ->
        FlickLog.Entry(
            atMs = 1_726_000_000_000L + index * 1_000L,
            level = listOf('I', 'D', 'W')[index % 3],
            area = listOf("probe", "player", "http", "lan")[index % 4],
            message = "attempt=${index + 1} bufferedMs=${480 - index * 11} retry=${index % 3}",
        )
    }

    private fun assertTitleInsideSafeArea() {
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        val titleBounds = composeRule.onNodeWithText("Settings").getUnclippedBoundsInRoot()
        val rootBounds = composeRule.onRoot().getUnclippedBoundsInRoot()
        val rootHeight = rootBounds.bottom - rootBounds.top
        val safeTop = rootBounds.top + rootHeight * 0.05f
        val safeBottom = rootBounds.bottom - rootHeight * 0.05f
        assertTrue(
            "Settings title must remain inside the vertical 5% safe area; " +
                "title=$titleBounds, root=$rootBounds",
            titleBounds.top >= safeTop && titleBounds.bottom <= safeBottom,
        )
    }

    private fun assertViewportStartsBelowTitle() {
        val titleBounds = composeRule.onNodeWithText("Settings").getUnclippedBoundsInRoot()
        val viewportBounds = composeRule.onNodeWithTag("settings-scroll-viewport").getUnclippedBoundsInRoot()
        assertTrue(
            "The clipped scroll viewport must begin below the fixed title, so visible rows cannot paint into it; " +
                "title=$titleBounds, viewport=$viewportBounds",
            viewportBounds.top >= titleBounds.bottom,
        )
    }

    private fun assertFocusedTargetIsRingSafe() {
        val viewportBounds = composeRule.onNodeWithTag("settings-scroll-viewport").getUnclippedBoundsInRoot()
        // Every paired-phone row carries one shared tag, so the focused node is
        // resolved by index within its tag rather than by the tag alone.
        var focused: SemanticsNodeInteraction? = null
        for (tag in listOf(
            "settings-first-row",
            "settings-paired-phone-row",
            "settings-metrics-row",
            "settings-forget-row",
            "settings-diagnostics-row",
            "settings-clear-row",
            "settings-done-row",
        )) {
            val nodes = composeRule.onAllNodesWithTag(tag)
            val index = nodes.fetchSemanticsNodes()
                .indexOfFirst { it.config.getOrNull(SemanticsProperties.Focused) == true }
            if (index >= 0) {
                focused = nodes[index]
                break
            }
        }
        assertTrue("Expected one tagged Settings control to own D-pad focus", focused != null)
        if (focused != null) {
            val focusedBounds = focused.getUnclippedBoundsInRoot()
            assertTrue(
                "Focused settings rows need the shared ring reserve within the viewport; " +
                    "row=$focusedBounds, viewport=$viewportBounds",
                focusedBounds.top >= viewportBounds.top + FlickDimens.FocusRingReserve &&
                    focusedBounds.bottom <= viewportBounds.bottom - FlickDimens.FocusRingReserve,
            )
        }
    }

    private fun assertFocusedFirstRowPaintStaysInsideViewport() {
        val rowBounds = composeRule.onNodeWithTag("settings-first-row").getUnclippedBoundsInRoot()
        val viewportBounds = composeRule.onNodeWithTag("settings-scroll-viewport").getUnclippedBoundsInRoot()
        val rowHeight = rowBounds.bottom - rowBounds.top
        // Focus scales a row 6% around center (3% toward each edge), then draws
        // the 4.5dp-offset, 2dp-wide detached ring another 5.5dp outward.
        val focusedTopPaintOutset = rowHeight * 0.03f + 5.5.dp
        assertTrue(
            "Rename's focused scale and ring must remain inside the clipped viewport; " +
                "row=$rowBounds, viewport=$viewportBounds",
            rowBounds.top - focusedTopPaintOutset >= viewportBounds.top,
        )
    }

    private fun assertMetricsPaintStaysInsideHorizontalSafeArea() {
        val metricsBounds = composeRule.onNodeWithTag("settings-metrics-row").getUnclippedBoundsInRoot()
        val rootBounds = composeRule.onRoot().getUnclippedBoundsInRoot()
        val metricsWidth = metricsBounds.right - metricsBounds.left
        val focusedPaintOutset = metricsWidth * 0.03f + 5.5.dp
        assertTrue(
            "A focused full-width settings row, including its scale and ring, must stay " +
                "inside the 48dp 5% horizontal overscan inset; " +
                "metrics=$metricsBounds, root=$rootBounds",
            metricsBounds.left - focusedPaintOutset >= rootBounds.left + 48.dp &&
                metricsBounds.right + focusedPaintOutset <= rootBounds.right - 48.dp,
        )
    }

    @Test
    fun settings_title_stays_inside_safe_area_at_default_font_scale() {
        composeRule.setContent {
            FlickTvTheme {
                SettingsScreen(
                    tvName = "Living Room TV",
                    pairedSummary = "2 paired",
                    pairedPhones = pairedPhones,
                    metricsEnabled = false,
                    onRename = {},
                    onToggleMetrics = {},
                    onForgetAll = {},
                    onDone = {},
                )
            }
        }
        assertTitleInsideSafeArea()
        assertViewportStartsBelowTitle()
        val rename = composeRule.onNodeWithText("Device name")
        rename.assertIsFocused().assertIsDisplayed()
        assertFocusedTargetIsRingSafe()
        assertFocusedFirstRowPaintStaysInsideViewport()
    }

    @Test
    fun settings_title_stays_inside_safe_area_at_supported_max_font_scale_and_after_scrolling() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                FlickTvTheme {
                    SettingsScreen(
                        tvName = "Living Room TV",
                        pairedSummary = "2 paired",
                        pairedPhones = pairedPhones,
                        metricsEnabled = false,
                        onRename = {},
                        onToggleMetrics = {},
                        onForgetAll = {},
                        onDone = {},
                    )
                }
            }
        }
        assertTitleInsideSafeArea()
        assertViewportStartsBelowTitle()
        assertFocusedTargetIsRingSafe()

        val rename = composeRule.onNodeWithText("Device name")
        val firstPhone = composeRule.onNodeWithText("Pixel 9 Pro")
        val secondPhone = composeRule.onNodeWithText("Galaxy S24")
        val metrics = composeRule.onNodeWithText("Playback metrics overlay")
        val forgetAll = composeRule.onNodeWithText("Forget all phones")
        val diagnostics = composeRule.onNodeWithText("Diagnostics")
        val done = composeRule.onNodeWithText("Done")
        rename.assertIsFocused().assertIsDisplayed()
        assertFocusedTargetIsRingSafe()
        rename.performKeyInput { keyDown(Key.DirectionDown); keyUp(Key.DirectionDown) }
        firstPhone.assertIsFocused().assertIsDisplayed()
        assertFocusedTargetIsRingSafe()
        firstPhone.performKeyInput { keyDown(Key.DirectionDown); keyUp(Key.DirectionDown) }
        secondPhone.assertIsFocused().assertIsDisplayed()
        assertFocusedTargetIsRingSafe()
        secondPhone.performKeyInput { keyDown(Key.DirectionDown); keyUp(Key.DirectionDown) }
        metrics.assertIsFocused().assertIsDisplayed()
        assertFocusedTargetIsRingSafe()
        assertMetricsPaintStaysInsideHorizontalSafeArea()
        metrics.performKeyInput { keyDown(Key.DirectionDown); keyUp(Key.DirectionDown) }
        forgetAll.assertIsFocused().assertIsDisplayed()
        assertFocusedTargetIsRingSafe()
        forgetAll.performKeyInput { keyDown(Key.DirectionDown); keyUp(Key.DirectionDown) }
        diagnostics.assertIsFocused().assertIsDisplayed()
        assertFocusedTargetIsRingSafe()
        diagnostics.performKeyInput { keyDown(Key.DirectionDown); keyUp(Key.DirectionDown) }
        done.assertIsFocused().assertIsDisplayed()
        assertFocusedTargetIsRingSafe()
        assertTitleInsideSafeArea()
        assertViewportStartsBelowTitle()

        done.performKeyInput { keyDown(Key.DirectionUp); keyUp(Key.DirectionUp) }
        diagnostics.assertIsFocused().assertIsDisplayed()
        assertFocusedTargetIsRingSafe()
        diagnostics.performKeyInput { keyDown(Key.DirectionUp); keyUp(Key.DirectionUp) }
        forgetAll.assertIsFocused().assertIsDisplayed()
        assertFocusedTargetIsRingSafe()
        forgetAll.performKeyInput { keyDown(Key.DirectionUp); keyUp(Key.DirectionUp) }
        metrics.assertIsFocused().assertIsDisplayed()
        assertFocusedTargetIsRingSafe()
        metrics.performKeyInput { keyDown(Key.DirectionUp); keyUp(Key.DirectionUp) }
        secondPhone.assertIsFocused().assertIsDisplayed()
        assertFocusedTargetIsRingSafe()
        secondPhone.performKeyInput { keyDown(Key.DirectionUp); keyUp(Key.DirectionUp) }
        firstPhone.assertIsFocused().assertIsDisplayed()
        assertFocusedTargetIsRingSafe()
        firstPhone.performKeyInput { keyDown(Key.DirectionUp); keyUp(Key.DirectionUp) }
        composeRule.onNodeWithText("Device name").assertIsFocused()
        assertFocusedTargetIsRingSafe()
        assertFocusedFirstRowPaintStaysInsideViewport()
    }

    private fun setExpandedDiagnosticsContent(fontScale: Float) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = fontScale)) {
                FlickTvTheme {
                    SettingsScreen(
                        tvName = "Living Room TV",
                        pairedSummary = "2 paired",
                        pairedPhones = pairedPhones,
                        metricsEnabled = false,
                        onRename = {},
                        onToggleMetrics = {},
                        onForgetAll = {},
                        onDone = {},
                        diagnosticsVisible = true,
                        diagnostics = expandedDiagnostics,
                    )
                }
            }
        }
    }

    private fun runExpandedDiagnosticsTraversal() {
        val rename = composeRule.onNodeWithText("Device name")
        val firstPhone = composeRule.onNodeWithText("Pixel 9 Pro")
        val secondPhone = composeRule.onNodeWithText("Galaxy S24")
        val metrics = composeRule.onNodeWithText("Playback metrics overlay")
        val forgetAll = composeRule.onNodeWithText("Forget all phones")
        val diagnostics = composeRule.onNodeWithText("Diagnostics")
        val clear = composeRule.onNodeWithText("Clear diagnostics")
        val done = composeRule.onNodeWithText("Done")
        rename.assertIsFocused().assertIsDisplayed()
        assertFocusedTargetIsRingSafe()
        rename.performKeyInput { keyDown(Key.DirectionDown); keyUp(Key.DirectionDown) }
        firstPhone.assertIsFocused().assertIsDisplayed()
        assertFocusedTargetIsRingSafe()
        firstPhone.performKeyInput { keyDown(Key.DirectionDown); keyUp(Key.DirectionDown) }
        secondPhone.assertIsFocused().assertIsDisplayed()
        assertFocusedTargetIsRingSafe()
        secondPhone.performKeyInput { keyDown(Key.DirectionDown); keyUp(Key.DirectionDown) }
        metrics.assertIsFocused().assertIsDisplayed()
        assertFocusedTargetIsRingSafe()
        metrics.performKeyInput { keyDown(Key.DirectionDown); keyUp(Key.DirectionDown) }
        forgetAll.assertIsFocused().assertIsDisplayed()
        assertFocusedTargetIsRingSafe()
        forgetAll.performKeyInput { keyDown(Key.DirectionDown); keyUp(Key.DirectionDown) }
        diagnostics.assertIsFocused().assertIsDisplayed()
        assertFocusedTargetIsRingSafe()
        diagnostics.performKeyInput { keyDown(Key.DirectionDown); keyUp(Key.DirectionDown) }
        clear.assertIsFocused().assertIsDisplayed()
        assertFocusedTargetIsRingSafe()
        clear.performKeyInput { keyDown(Key.DirectionDown); keyUp(Key.DirectionDown) }
        done.assertIsFocused().assertIsDisplayed()
        assertFocusedTargetIsRingSafe()
        done.performKeyInput { keyDown(Key.DirectionUp); keyUp(Key.DirectionUp) }
        clear.assertIsFocused().assertIsDisplayed()
        assertFocusedTargetIsRingSafe()
        clear.performKeyInput { keyDown(Key.DirectionUp); keyUp(Key.DirectionUp) }
        diagnostics.assertIsFocused().assertIsDisplayed()
        assertFocusedTargetIsRingSafe()
    }

    @Test
    fun expanded_fourteen_line_diagnostics_keeps_each_dpad_destination_ring_safe_at_default_font_scale() {
        setExpandedDiagnosticsContent(fontScale = 1f)
        runExpandedDiagnosticsTraversal()
    }

    @Test
    fun expanded_fourteen_line_diagnostics_keeps_each_dpad_destination_ring_safe_at_supported_max_font_scale() {
        setExpandedDiagnosticsContent(fontScale = 2f)
        runExpandedDiagnosticsTraversal()
    }

    private fun runLiveDiagnosticsResizeWhileFocused(fontScale: Float) {
        lateinit var diagnosticsState: MutableState<List<FlickLog.Entry>>
        lateinit var fontScaleState: MutableState<Float>
        composeRule.setContent {
            val density = LocalDensity.current
            diagnosticsState = remember { mutableStateOf(expandedDiagnostics) }
            fontScaleState = remember { mutableStateOf(fontScale) }
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = fontScaleState.value)) {
                FlickTvTheme {
                    SettingsScreen(
                        tvName = "Living Room TV",
                        pairedSummary = "2 paired",
                        pairedPhones = pairedPhones,
                        metricsEnabled = false,
                        onRename = {},
                        onToggleMetrics = {},
                        onForgetAll = {},
                        onDone = {},
                        diagnosticsVisible = true,
                        diagnostics = diagnosticsState.value,
                    )
                }
            }
        }

        val rename = composeRule.onNodeWithText("Device name")
        val firstPhone = composeRule.onNodeWithText("Pixel 9 Pro")
        val secondPhone = composeRule.onNodeWithText("Galaxy S24")
        val metrics = composeRule.onNodeWithText("Playback metrics overlay")
        val forgetAll = composeRule.onNodeWithText("Forget all phones")
        val diagnostics = composeRule.onNodeWithText("Diagnostics")
        val clear = composeRule.onNodeWithText("Clear diagnostics")
        val done = composeRule.onNodeWithText("Done")
        rename.assertIsFocused().assertIsDisplayed()
        assertFocusedTargetIsRingSafe()
        rename.performKeyInput { keyDown(Key.DirectionDown); keyUp(Key.DirectionDown) }
        firstPhone.assertIsFocused().assertIsDisplayed()
        assertFocusedTargetIsRingSafe()
        firstPhone.performKeyInput { keyDown(Key.DirectionDown); keyUp(Key.DirectionDown) }
        secondPhone.assertIsFocused().assertIsDisplayed()
        assertFocusedTargetIsRingSafe()
        secondPhone.performKeyInput { keyDown(Key.DirectionDown); keyUp(Key.DirectionDown) }
        metrics.assertIsFocused().assertIsDisplayed()
        assertFocusedTargetIsRingSafe()
        metrics.performKeyInput { keyDown(Key.DirectionDown); keyUp(Key.DirectionDown) }
        forgetAll.assertIsFocused().assertIsDisplayed()
        assertFocusedTargetIsRingSafe()
        forgetAll.performKeyInput { keyDown(Key.DirectionDown); keyUp(Key.DirectionDown) }
        diagnostics.assertIsFocused().assertIsDisplayed()
        assertFocusedTargetIsRingSafe()
        diagnostics.performKeyInput { keyDown(Key.DirectionDown); keyUp(Key.DirectionDown) }
        clear.assertIsFocused().assertIsDisplayed()
        assertFocusedTargetIsRingSafe()

        composeRule.runOnUiThread { diagnosticsState.value = emptyList() }
        clear.assertIsFocused().assertIsDisplayed()
        assertFocusedTargetIsRingSafe()
        composeRule.runOnUiThread { diagnosticsState.value = expandedDiagnostics }
        clear.assertIsFocused().assertIsDisplayed()
        assertFocusedTargetIsRingSafe()

        clear.performKeyInput { keyDown(Key.DirectionDown); keyUp(Key.DirectionDown) }
        done.assertIsFocused().assertIsDisplayed()
        assertFocusedTargetIsRingSafe()
        composeRule.runOnUiThread { diagnosticsState.value = expandedDiagnostics + FlickLog.Entry(
            atMs = 1_726_000_014_000L,
            level = 'W',
            area = "player",
            message = "attempt=15 bufferedMs=326 retry=2",
        ) }
        done.assertIsFocused().assertIsDisplayed()
        assertFocusedTargetIsRingSafe()
        composeRule.runOnUiThread { fontScaleState.value = if (fontScale == 1f) 2f else 1f }
        done.assertIsFocused().assertIsDisplayed()
        assertFocusedTargetIsRingSafe()
    }

    @Test
    fun populated_diagnostics_resize_keeps_clear_and_done_ring_safe_at_default_font_scale() {
        runLiveDiagnosticsResizeWhileFocused(fontScale = 1f)
    }

    @Test
    fun populated_diagnostics_resize_keeps_clear_and_done_ring_safe_at_supported_max_font_scale() {
        runLiveDiagnosticsResizeWhileFocused(fontScale = 2f)
    }

    @Test
    fun dpad_navigation_reveals_done_at_the_tv_viewport_bottom() {
        composeRule.setContent {
            FlickTvTheme {
                SettingsScreen(
                    tvName = "Living Room TV",
                    pairedSummary = "2 paired",
                    pairedPhones = pairedPhones,
                    metricsEnabled = false,
                    onRename = {},
                    onToggleMetrics = {},
                    onForgetAll = {},
                    onDone = {},
                )
            }
        }

        val rename = composeRule.onNodeWithText("Device name")
        val firstPhone = composeRule.onNodeWithText("Pixel 9 Pro")
        val secondPhone = composeRule.onNodeWithText("Galaxy S24")
        val metrics = composeRule.onNodeWithText("Playback metrics overlay")
        val forgetAll = composeRule.onNodeWithText("Forget all phones")
        val diagnostics = composeRule.onNodeWithText("Diagnostics")
        val done = composeRule.onNodeWithText("Done")

        rename.assertIsFocused().assertIsDisplayed()
        assertFocusedTargetIsRingSafe()
        rename.performKeyInput {
            keyDown(Key.DirectionDown)
            keyUp(Key.DirectionDown)
        }
        firstPhone.assertIsFocused().assertIsDisplayed()
        assertFocusedTargetIsRingSafe()
        firstPhone.performKeyInput {
            keyDown(Key.DirectionDown)
            keyUp(Key.DirectionDown)
        }
        secondPhone.assertIsFocused().assertIsDisplayed()
        assertFocusedTargetIsRingSafe()
        secondPhone.performKeyInput {
            keyDown(Key.DirectionDown)
            keyUp(Key.DirectionDown)
        }
        metrics.assertIsFocused().assertIsDisplayed()
        assertFocusedTargetIsRingSafe()
        assertMetricsPaintStaysInsideHorizontalSafeArea()
        metrics.performKeyInput {
            keyDown(Key.DirectionDown)
            keyUp(Key.DirectionDown)
        }
        forgetAll.assertIsFocused().assertIsDisplayed()
        assertFocusedTargetIsRingSafe()
        forgetAll.performKeyInput {
            keyDown(Key.DirectionDown)
            keyUp(Key.DirectionDown)
        }
        diagnostics.assertIsFocused().assertIsDisplayed()
        assertFocusedTargetIsRingSafe()
        diagnostics.performKeyInput {
            keyDown(Key.DirectionDown)
            keyUp(Key.DirectionDown)
        }

        done.assertIsFocused().assertIsDisplayed()
        assertTitleInsideSafeArea()
        assertFocusedTargetIsRingSafe()

    }
}
