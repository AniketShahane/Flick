package com.flick.receiver.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import com.flick.receiver.ui.components.TransportCluster
import com.flick.receiver.ui.components.VolumeCells
import com.flick.receiver.ui.theme.FlickTvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TransportAndVolumeInteractionTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun transport_ordered_actions_invoke_only_their_own_callbacks() {
        var backCalls = 0
        var playPauseCalls = 0
        var forwardCalls = 0

        composeRule.setContent {
            FlickTvTheme {
                TransportCluster(
                    playing = false,
                    onBack10 = { backCalls++ },
                    onPlayPause = { playPauseCalls++ },
                    onForward10 = { forwardCalls++ },
                    back10ContentDescription = "Skip back 10 seconds",
                    playPauseContentDescription = "Play",
                    forward10ContentDescription = "Skip forward 10 seconds",
                )
            }
        }

        composeRule.onNodeWithContentDescription("Skip back 10 seconds").performClick()
        composeRule.onNodeWithContentDescription("Play").performClick()
        composeRule.onNodeWithContentDescription("Skip forward 10 seconds").performClick()

        composeRule.runOnIdle {
            assertEquals(1, backCalls)
            assertEquals(1, playPauseCalls)
            assertEquals(1, forwardCalls)
        }
    }

    @Test
    fun transportTargetsStayAtLeast48DpAfterTheCompactRedesign() {
        composeRule.setContent {
            FlickTvTheme {
                TransportCluster(
                    playing = true,
                    onBack10 = {},
                    onPlayPause = {},
                    onForward10 = {},
                    back10ContentDescription = "Skip back 10 seconds",
                    playPauseContentDescription = "Pause",
                    forward10ContentDescription = "Skip forward 10 seconds",
                )
            }
        }

        listOf("Skip back 10 seconds", "Pause", "Skip forward 10 seconds").forEach { label ->
            composeRule.onNodeWithContentDescription(label)
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
        }
    }

    @Test
    fun volume_dpad_steps_only_while_engaged_and_back_disengages() {
        var observedVolume = -1f

        composeRule.setContent {
            var volume by remember { mutableFloatStateOf(0.5f) }
            FlickTvTheme {
                VolumeCells(
                    level = volume,
                    onChange = {
                        volume = it
                        observedVolume = it
                    },
                    contentDescription = "Volume",
                    stateDescription = "50 percent",
                )
            }
        }

        val volume = composeRule.onNodeWithContentDescription("Volume")
        // A remote sends D-pad events to its focused target. Request focus
        // explicitly (semantic click alone does not establish it), then let the
        // v2 test dispatcher commit the engage state before sending Right.
        volume.assertExists().requestFocus().assertIsFocused().performClick()
        composeRule.runOnIdle { }
        volume.performKeyInput {
            keyDown(Key.DirectionRight)
            keyUp(Key.DirectionRight)
        }
        composeRule.runOnIdle { assertEquals(0.6f, observedVolume, 0.001f) }

        volume.performKeyInput {
            keyDown(Key.Back)
            keyUp(Key.Back)
            keyDown(Key.DirectionRight)
            keyUp(Key.DirectionRight)
        }
        composeRule.runOnIdle { assertEquals(0.6f, observedVolume, 0.001f) }
    }
}
