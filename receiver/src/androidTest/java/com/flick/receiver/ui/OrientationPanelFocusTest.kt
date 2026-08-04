package com.flick.receiver.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import com.flick.receiver.player.HdrType
import com.flick.receiver.player.PlaybackPhase
import com.flick.receiver.player.VideoRotation
import com.flick.receiver.ui.screens.OrientationPanel
import com.flick.receiver.ui.screens.PlaybackPanel
import com.flick.receiver.ui.screens.PlaybackScreen
import com.flick.receiver.ui.theme.FlickTvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The orientation control moved out of the subtitles panel and onto a square in
 * the transport row, so both halves of that move are asserted here: the tile
 * reaches its own panel and comes back, and the panel keeps the focus contract the
 * cells had — entry on the current choice, an explicit chain between the rows, and
 * a bottom edge the modal cannot leak through.
 */
class OrientationPanelFocusTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun every_turn_is_listed_and_walks_down_to_the_last_one() {
        var chosen: VideoRotation? = null
        setOrientationPanel(onSelectRotation = { chosen = it })

        composeRule.onNodeWithText("Auto").assertIsDisplayed()
        composeRule.onNodeWithText("0°").assertIsDisplayed()
        composeRule.onNodeWithText("90°").assertIsDisplayed()
        composeRule.onNodeWithText("180°").assertIsDisplayed()
        composeRule.onNodeWithText("270°").assertIsDisplayed()

        // Auto is the entry choice, so focus opens there.
        val auto = composeRule.onNodeWithText("Auto").assertIsFocused()
        auto.press(Key.DirectionDown)
        val asFiled = composeRule.onNodeWithText("0°").assertIsFocused()
        asFiled.press(Key.DirectionUp)
        auto.assertIsFocused()

        asFiled.performSemanticsAction(SemanticsActions.RequestFocus)
        asFiled.press(Key.DirectionDown)
        val quarter = composeRule.onNodeWithText("90°").assertIsFocused()
        quarter.press(Key.DirectionDown)
        composeRule.onNodeWithText("180°").assertIsFocused().press(Key.DirectionDown)
        val threeQuarter = composeRule.onNodeWithText("270°").assertIsFocused()

        // The last rank cannot leak through the bottom of the modal.
        threeQuarter.press(Key.DirectionDown)
        threeQuarter.assertIsFocused()

        quarter.performClick()
        composeRule.runOnIdle { assertEquals(VideoRotation.Quarter, chosen) }
    }

    /** Focus opens on the turn the picture is already wearing, not at the top. */
    @Test
    fun focus_enters_on_the_current_choice() {
        setOrientationPanel(rotation = VideoRotation.Half)
        composeRule.onNodeWithText("180°").assertIsFocused()
    }

    /** Auto is a verdict about the file, so the panel has to say what it decided. */
    @Test
    fun the_panel_states_what_auto_decided_and_only_while_auto_is_chosen() {
        setOrientationPanel(autoRotationDegrees = 90)
        composeRule.onNodeWithText("AUTO · 90°").assertIsDisplayed()
    }

    @Test
    fun an_explicit_choice_replaces_the_auto_readout_with_its_own_row() {
        setOrientationPanel(rotation = VideoRotation.Half, autoRotationDegrees = 90)
        composeRule.onNodeWithText("AUTO · 90°").assertDoesNotExist()
        composeRule.onNodeWithText("180°").assertIsDisplayed()
    }

    /**
     * The tile on the real screen: it is a focus target in the row's leading
     * cluster, it summons the panel, and closing the panel puts the remote back on
     * the square that opened it — which is the only thing to return to, because a
     * panel takes the transport bar off screen.
     */
    @Test
    fun the_tile_opens_the_panel_and_back_returns_to_it() {
        setPlaybackScreen()

        val tile = composeRule.onNodeWithContentDescription(AUTO_AS_FILED)
        tile.performSemanticsAction(SemanticsActions.RequestFocus)
        tile.assertIsFocused().performClick()

        composeRule.onNodeWithText("Orientation").assertIsDisplayed()
        composeRule.onNodeWithText("END SESSION").assertDoesNotExist()
        composeRule.onNodeWithText("Auto").assertIsFocused().press(Key.Back)

        tile.assertIsFocused()
        composeRule.onNodeWithText("END SESSION").assertExists()
    }

    /** The row is traversed the way it is drawn: SUBS, then the square. */
    @Test
    fun the_tile_sits_between_the_subtitles_card_and_the_transport() {
        setPlaybackScreen()

        val subs = composeRule.onNodeWithText("SUBS")
        subs.performSemanticsAction(SemanticsActions.RequestFocus)
        subs.assertIsFocused().press(Key.DirectionRight)
        val tile = composeRule.onNodeWithContentDescription(AUTO_AS_FILED).assertIsFocused()
        tile.press(Key.DirectionLeft)
        subs.assertIsFocused()
    }

    private fun setOrientationPanel(
        rotation: VideoRotation = VideoRotation.Auto,
        autoRotationDegrees: Int = 0,
        onSelectRotation: (VideoRotation) -> Unit = {},
    ) {
        composeRule.setContent {
            FlickTvTheme {
                OrientationPanel(
                    rotation = rotation,
                    autoRotationDegrees = autoRotationDegrees,
                    onSelectRotation = onSelectRotation,
                    onDismiss = {},
                )
            }
        }
    }

    private fun setPlaybackScreen() {
        composeRule.setContent {
            var openPanel by remember { mutableStateOf(PlaybackPanel.None) }
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
                    openPanel = openPanel,
                    onOpenPanel = { openPanel = it },
                    onEndSession = {},
                    videoContent = {},
                )
            }
        }
    }

    private fun SemanticsNodeInteraction.press(key: Key) {
        performKeyInput {
            keyDown(key)
            keyUp(key)
        }
    }

    private companion object {
        /** The tile's spoken form with the screen's defaults: Auto, applying no turn. */
        const val AUTO_AS_FILED = "Picture orientation: AUTO · 0°"
    }
}
