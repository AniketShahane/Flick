package com.flick.receiver.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.flick.receiver.player.HdrType
import com.flick.receiver.player.PlaybackPhase
import com.flick.receiver.ui.screens.PlaybackScreen
import com.flick.receiver.ui.theme.FlickTvTheme
import org.junit.Rule
import org.junit.Test

class PlaybackChromeSemanticsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun hidden_chrome_is_excluded_then_visible_chrome_reclaims_play_focus() {
        lateinit var setChromeVisible: (Boolean) -> Unit

        composeRule.setContent {
            var chromeVisible by remember { mutableStateOf(false) }
            val playFocusRequester = remember { FocusRequester() }
            setChromeVisible = { chromeVisible = it }

            FlickTvTheme {
                PlaybackScreen(
                    playing = false,
                    phase = PlaybackPhase.Paused,
                    positionMs = 20_000L,
                    durationMs = 120_000L,
                    bufferedMs = 30_000L,
                    targetMs = 20_000L,
                    seeking = false,
                    volume = 0.5f,
                    title = "A Film",
                    deviceLabel = "Pixel",
                    hdr = HdrType.NONE,
                    chromeVisible = chromeVisible,
                    quality = null,
                    onBack10 = {},
                    onPlayPause = {},
                    onForward10 = {},
                    onSetVolume = {},
                    playFocusRequester = playFocusRequester,
                    videoContent = {
                        Box(Modifier.semantics { contentDescription = "Film surface" })
                    },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Film surface").assertExists()
        composeRule.onNodeWithContentDescription("Play").assertDoesNotExist()

        composeRule.runOnIdle { setChromeVisible(true) }
        composeRule.onNodeWithContentDescription("Play").assertExists().assertIsFocused()
        composeRule.onNodeWithContentDescription("Skip back 10 seconds").assertExists()
        composeRule.onNodeWithContentDescription("Skip forward 10 seconds").assertExists()
        composeRule.onNodeWithContentDescription("Volume").assertExists()

        // Freeze the clock before hiding so these assertions run during the
        // retained 500 ms exit subtree, not after AnimatedVisibility disposes it.
        // With Compose test JUnit v2 the state write is scheduled on the test
        // dispatcher, so advance one frame to commit it before inspecting the
        // retained exit subtree.
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnIdle { setChromeVisible(false) }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithContentDescription("Play").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Skip back 10 seconds").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Skip forward 10 seconds").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Volume").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Film surface").assertExists()
        composeRule.mainClock.autoAdvance = true
    }
}
