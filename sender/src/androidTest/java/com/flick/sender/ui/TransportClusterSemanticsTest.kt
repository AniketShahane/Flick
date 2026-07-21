package com.flick.sender.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flick.sender.ui.components.TransportCluster
import com.flick.sender.ui.theme.FlickTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransportClusterSemanticsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun labelledTransportControlsExposeButtonsAndInvokeTheirOwnCallbacks() {
        var backCalls = 0
        var playPauseCalls = 0
        var forwardCalls = 0

        composeRule.setContent {
            FlickTheme(darkTheme = true, dynamicColor = false) {
                TransportCluster(
                    playing = true,
                    onBack10 = { backCalls++ },
                    onPlayPause = { playPauseCalls++ },
                    onFwd10 = { forwardCalls++ },
                    back10Label = "Skip back 10 seconds",
                    playPauseLabel = "Pause on TV",
                    playPauseState = "Playing",
                    forward10Label = "Skip forward 10 seconds",
                )
            }
        }

        composeRule.onNodeWithContentDescription("Skip back 10 seconds")
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()
        composeRule.onNodeWithContentDescription("Pause on TV")
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Playing"))
            .performClick()
        composeRule.onNodeWithContentDescription("Skip forward 10 seconds")
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()

        composeRule.runOnIdle {
            assertEquals(1, backCalls)
            assertEquals(1, playPauseCalls)
            assertEquals(1, forwardCalls)
        }
    }
}
