package com.flick.receiver.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import com.flick.receiver.player.HdrType
import com.flick.receiver.player.PlaybackPhase
import com.flick.receiver.player.SubtitleTrackInfo
import com.flick.receiver.ui.screens.PlaybackPanel
import com.flick.receiver.ui.screens.PlaybackScreen
import com.flick.receiver.ui.screens.SubtitleSize
import com.flick.receiver.ui.theme.FlickTvTheme
import org.junit.Rule
import org.junit.Test

class SubtitlesPanelFocusTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun size_returns_to_last_track_and_modal_restores_focus_on_back_and_reentry() {
        setPlaybackWithOpenSubtitles(
            tracks = listOf(
                subtitle(id = "0:0", label = "English", selected = true, number = 1),
                subtitle(id = "0:1", label = "Spanish", selected = false, number = 2),
            ),
        )

        val english = composeRule.onNodeWithText("English").assertIsFocused()
        composeRule.onNodeWithText("END SESSION").assertDoesNotExist()

        english.press(Key.DirectionDown)
        val spanish = composeRule.onNodeWithText("Spanish").assertIsFocused()
        spanish.press(Key.DirectionDown)
        val medium = composeRule.onNodeWithText("Medium").assertIsFocused()
        medium.press(Key.DirectionUp)
        spanish.assertIsFocused()

        spanish.press(Key.DirectionDown)
        medium.press(Key.DirectionLeft)
        val small = composeRule.onNodeWithText("Small").assertIsFocused()
        small.press(Key.DirectionRight)
        medium.assertIsFocused().press(Key.DirectionRight)
        val large = composeRule.onNodeWithText("Large").assertIsFocused()

        // The last rank cannot leak through the bottom of the modal.
        large.press(Key.DirectionDown)
        large.assertIsFocused()

        large.press(Key.Back)
        val subtitlesCard = composeRule.onNodeWithText("Subtitles").assertIsFocused()
        composeRule.onNodeWithText("END SESSION").assertExists()

        subtitlesCard.performClick()
        composeRule.onNodeWithText("English").assertIsFocused()
        composeRule.onNodeWithText("END SESSION").assertDoesNotExist()
    }

    @Test
    fun size_returns_to_off_when_the_video_has_no_subtitle_tracks() {
        setPlaybackWithOpenSubtitles(tracks = emptyList())

        val off = composeRule.onNodeWithText("Off").assertIsFocused()
        off.press(Key.DirectionDown)
        val medium = composeRule.onNodeWithText("Medium").assertIsFocused()
        medium.press(Key.DirectionUp)
        off.assertIsFocused()
    }

    private fun setPlaybackWithOpenSubtitles(tracks: List<SubtitleTrackInfo>) {
        composeRule.setContent {
            var openPanel by remember { mutableStateOf(PlaybackPanel.Subtitles) }
            val playFocusRequester = remember { FocusRequester() }

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
                    deviceLabel = "Phone",
                    hdr = HdrType.NONE,
                    chromeVisible = true,
                    quality = null,
                    onBack10 = {},
                    onPlayPause = {},
                    onForward10 = {},
                    onSetVolume = {},
                    playFocusRequester = playFocusRequester,
                    subtitleTracks = tracks,
                    subtitleSize = SubtitleSize.Medium,
                    openPanel = openPanel,
                    onOpenPanel = { openPanel = it },
                    onEndSession = {},
                    videoContent = {},
                )
            }
        }
    }

    private fun subtitle(
        id: String,
        label: String,
        selected: Boolean,
        number: Int,
    ) = SubtitleTrackInfo(
        id = id,
        label = label,
        mimeType = "application/x-subrip",
        isSelected = selected,
        trackNumber = number,
    )

    private fun SemanticsNodeInteraction.press(key: Key) {
        performKeyInput {
            keyDown(key)
            keyUp(key)
        }
    }
}
