package com.flick.receiver.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.unit.dp
import com.flick.receiver.ui.screens.SettingsScreen
import com.flick.receiver.ui.theme.FlickTvTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsScreenFocusTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun dpad_navigation_reveals_done_at_the_tv_viewport_bottom() {
        composeRule.setContent {
            FlickTvTheme {
                SettingsScreen(
                    tvName = "Living Room TV",
                    pairedSummary = "1 phone paired",
                    metricsEnabled = false,
                    onRename = {},
                    onToggleMetrics = {},
                    onForgetAll = {},
                    onDone = {},
                )
            }
        }

        val metrics = composeRule.onNodeWithText("Playback metrics overlay")
        val forgetAll = composeRule.onNodeWithText("Forget all phones")
        val diagnostics = composeRule.onNodeWithText("Diagnostics")
        val done = composeRule.onNodeWithText("Done")

        metrics.assertIsFocused().performKeyInput {
            keyDown(Key.DirectionDown)
            keyUp(Key.DirectionDown)
        }
        forgetAll.assertIsFocused().performKeyInput {
            keyDown(Key.DirectionDown)
            keyUp(Key.DirectionDown)
        }
        diagnostics.assertIsFocused().performKeyInput {
            keyDown(Key.DirectionDown)
            keyUp(Key.DirectionDown)
        }

        // The list must leave clearance for the focused button's scale and 16dp
        // glow, not merely place a clipped target somewhere in the viewport.
        done.assertIsFocused().assertIsDisplayed()
        val doneBounds = done.getUnclippedBoundsInRoot()
        val rootBounds = composeRule.onRoot().getUnclippedBoundsInRoot()
        assertTrue(
            "Done must retain 18dp of bottom clearance for its focus glow; " +
                "done=$doneBounds, root=$rootBounds",
            doneBounds.bottom <= rootBounds.bottom - 18.dp,
        )
    }
}
