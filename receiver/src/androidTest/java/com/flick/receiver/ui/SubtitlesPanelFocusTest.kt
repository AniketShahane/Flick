package com.flick.receiver.ui

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import com.flick.receiver.player.HdrType
import com.flick.receiver.player.PlaybackPhase
import com.flick.receiver.player.SubtitleTrackFocusIdentity
import com.flick.receiver.player.SubtitleTrackInfo
import com.flick.receiver.ui.screens.PlaybackPanel
import com.flick.receiver.ui.screens.PlaybackScreen
import com.flick.receiver.ui.screens.SubtitleSize
import com.flick.receiver.ui.screens.SubtitlesPanel
import com.flick.receiver.ui.theme.FlickTvTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
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

        // Size is the last rank again, now that the orientation cells have a panel
        // of their own: it cannot leak through the bottom of the modal.
        large.press(Key.DirectionDown)
        large.assertIsFocused()

        large.press(Key.Back)
        val subtitlesCard = composeRule.onNodeWithText("SUBS").assertIsFocused()
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

    @Test
    fun repeated_down_traverses_a_scrolling_track_list_and_reaches_size() {
        val tracks = (1..10).map { number ->
            subtitle(
                id = "0:$number",
                label = "Track $number",
                selected = false,
                number = number,
            )
        }
        setSubtitlesPanel(tracks = { tracks })

        var focused = composeRule.onNodeWithText("Off").assertIsFocused()
        tracks.indices.forEach { index ->
            focused.press(Key.DirectionDown)
            focused = composeRule.onNodeWithText("Track ${index + 1}").assertIsFocused()
        }
        focused.press(Key.DirectionDown)
        val medium = composeRule.onNodeWithText("Medium").assertIsFocused()
        medium.press(Key.DirectionUp)
        composeRule.onNodeWithText("Track 10").assertIsFocused()
    }

    /**
     * The panel standalone is not the panel on the screen. `PlaybackSidePanel`
     * wraps it in the modal focus gate, and a gate that leaked onto the track
     * list's own scroll focus group vetoed every move out of the list — with the
     * size cells fully on screen the whole time. Both halves are asserted for
     * that reason: displayed and reachable fail differently here.
     *
     * Six tracks is the count at which the list scrolls at 1080p / density 2, so
     * this also covers a size row sitting below a list that does not fit.
     */
    @Test
    fun size_cells_are_displayed_and_reachable_below_a_scrolling_list_on_the_real_screen() {
        val tracks = (1..6).map { number ->
            subtitle(id = "0:$number", label = "Track $number", selected = false, number = number)
        }
        setPlaybackWithOpenSubtitles(tracks)

        composeRule.onNodeWithText("Small").assertIsDisplayed()
        composeRule.onNodeWithText("Medium").assertIsDisplayed()
        composeRule.onNodeWithText("Large").assertIsDisplayed()

        var focused = composeRule.onNodeWithText("Off").assertIsFocused()
        tracks.indices.forEach { index ->
            focused.press(Key.DirectionDown)
            focused = composeRule.onNodeWithText("Track ${index + 1}").assertIsFocused()
        }
        focused.press(Key.DirectionDown)
        val medium = composeRule.onNodeWithText("Medium").assertIsFocused()
        medium.press(Key.DirectionUp)
        composeRule.onNodeWithText("Track 6").assertIsFocused()
    }

    /**
     * The other half of the same gate: scoping it to the panel's own boundary may
     * not cost the modal guarantee. The close button is the panel's topmost
     * control, so UP from it is the move that would otherwise escape.
     */
    @Test
    fun the_open_panel_still_refuses_to_let_focus_leave_through_its_top_edge() {
        setPlaybackWithOpenSubtitles(
            tracks = listOf(subtitle(id = "0:0", label = "English", selected = true, number = 1)),
        )

        val close = composeRule.onNodeWithContentDescription("Close subtitles")
        close.performSemanticsAction(SemanticsActions.RequestFocus)
        close.assertIsFocused()
        close.press(Key.DirectionUp)
        close.assertIsFocused()
    }

    @Test
    fun identical_tracks_keep_focus_by_media_identity_when_position_and_id_change() {
        val firstIdentity = focusIdentity()
        val secondIdentity = focusIdentity()
        assertNotSame(firstIdentity.mediaTrackGroup, secondIdentity.mediaTrackGroup)
        assertEquals(firstIdentity.mediaTrackGroup, secondIdentity.mediaTrackGroup)
        val first = subtitle("0:0", "Same", selected = true, number = 1, identity = firstIdentity)
        val second = subtitle("0:1", "Same", selected = false, number = 2, identity = secondIdentity)
        var tracks by mutableStateOf(listOf(first, second))
        var selectedId: String? = null
        setSubtitlesPanel(tracks = { tracks }, onSelectTrack = { selectedId = it })

        val matchingRows = composeRule.onAllNodesWithText("Same")
        matchingRows[0].assertIsFocused().press(Key.DirectionDown)
        matchingRows[1].assertIsFocused()

        composeRule.runOnIdle {
            tracks = listOf(
                second.copy(id = "1:0", trackNumber = 1),
                first.copy(id = "1:1", trackNumber = 2),
            )
        }
        matchingRows[0].assertIsFocused().performClick()
        composeRule.runOnIdle { assertEquals("1:0", selectedId) }
        matchingRows[0].press(Key.DirectionDown)
        matchingRows[1].assertIsFocused()
    }

    @Test
    fun removing_a_focused_unselected_track_returns_to_the_selected_track() {
        val alpha = subtitle("0:0", "Alpha", selected = true, number = 1)
        val bravo = subtitle("0:1", "Bravo", selected = false, number = 2)
        val charlie = subtitle("0:2", "Charlie", selected = false, number = 3)
        var tracks by mutableStateOf(listOf(alpha, bravo, charlie))
        setSubtitlesPanel(tracks = { tracks })

        composeRule.onNodeWithText("Alpha").assertIsFocused().press(Key.DirectionDown)
        composeRule.onNodeWithText("Bravo").assertIsFocused()
        composeRule.runOnIdle {
            tracks = listOf(alpha, charlie.copy(trackNumber = 2))
        }
        composeRule.onNodeWithText("Alpha").assertIsFocused()
    }

    @Test
    fun removing_the_entry_track_lands_on_the_new_selected_track() {
        val alpha = subtitle("0:0", "Alpha", selected = true, number = 1)
        val bravo = subtitle("0:1", "Bravo", selected = false, number = 2)
        var tracks by mutableStateOf(listOf(alpha, bravo))
        setSubtitlesPanel(tracks = { tracks })

        composeRule.onNodeWithText("Alpha").assertIsFocused()
        composeRule.runOnIdle {
            tracks = listOf(bravo.copy(isSelected = true))
        }
        composeRule.onNodeWithText("Bravo").assertIsFocused()
    }

    /**
     * The refresh that re-lands a displaced viewer must not touch one who simply
     * is not on a track row. Focus here is on a size cell, so the vanishing track
     * is not the one being held and nothing may move.
     */
    @Test
    fun focus_on_the_size_cells_survives_an_unrelated_track_disappearing() {
        val alpha = subtitle("0:0", "Alpha", selected = true, number = 1)
        val bravo = subtitle("0:1", "Bravo", selected = false, number = 2)
        var tracks by mutableStateOf(listOf(alpha, bravo))
        setSubtitlesPanel(tracks = { tracks })

        composeRule.onNodeWithText("Alpha").assertIsFocused().press(Key.DirectionDown)
        composeRule.onNodeWithText("Bravo").assertIsFocused().press(Key.DirectionDown)
        composeRule.onNodeWithText("Medium").assertIsFocused()

        composeRule.runOnIdle { tracks = listOf(alpha) }
        composeRule.onNodeWithText("Medium").assertIsFocused()
    }

    /** The same exclusion for OFF, which is a choice row but never a track. */
    @Test
    fun focus_on_off_survives_an_unrelated_track_appearing() {
        val alpha = subtitle("0:0", "Alpha", selected = false, number = 1)
        val bravo = subtitle("0:1", "Bravo", selected = false, number = 2)
        var tracks by mutableStateOf(listOf(alpha))
        setSubtitlesPanel(tracks = { tracks })

        composeRule.onNodeWithText("Off").assertIsFocused()
        composeRule.runOnIdle { tracks = listOf(alpha, bravo) }
        composeRule.onNodeWithText("Off").assertIsFocused()
    }

    /**
     * Media3's tracks are re-read twice a second and the mapper builds fresh
     * [SubtitleTrackInfo] objects each time. Identity is carried by the immutable
     * TrackGroup, so an unchanged list must not restart the entry latch at all —
     * this is the case that runs continuously for the whole film.
     */
    @Test
    fun an_equal_track_refresh_moves_nothing() {
        val alpha = subtitle("0:0", "Alpha", selected = true, number = 1)
        val bravo = subtitle("0:1", "Bravo", selected = false, number = 2)
        var tracks by mutableStateOf(listOf(alpha, bravo))
        setSubtitlesPanel(tracks = { tracks })

        composeRule.onNodeWithText("Alpha").assertIsFocused().press(Key.DirectionDown)
        composeRule.onNodeWithText("Bravo").assertIsFocused()

        repeat(3) {
            composeRule.runOnIdle { tracks = listOf(alpha.copy(), bravo.copy()) }
            composeRule.onNodeWithText("Bravo").assertIsFocused()
        }
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

    private fun setSubtitlesPanel(
        tracks: () -> List<SubtitleTrackInfo>,
        onSelectTrack: (String?) -> Unit = {},
    ) {
        composeRule.setContent {
            FlickTvTheme {
                SubtitlesPanel(
                    tracks = tracks(),
                    size = SubtitleSize.Medium,
                    onSelectTrack = onSelectTrack,
                    onSelectSize = {},
                    onDismiss = {},
                    modifier = Modifier.height(360.dp),
                )
            }
        }
    }

    private fun subtitle(
        id: String,
        label: String,
        selected: Boolean,
        number: Int,
        identity: SubtitleTrackFocusIdentity = focusIdentity(),
    ) = SubtitleTrackInfo(
        id = id,
        focusIdentity = identity,
        label = label,
        mimeType = "application/x-subrip",
        isSelected = selected,
        trackNumber = number,
    )

    private fun focusIdentity() = SubtitleTrackFocusIdentity(
        mediaTrackGroup = TrackGroup(
            Format.Builder()
                .setSampleMimeType("application/x-subrip")
                .build(),
        ),
        trackIndexWithinGroup = 0,
    )

    private fun SemanticsNodeInteraction.press(key: Key) {
        performKeyInput {
            keyDown(key)
            keyUp(key)
        }
    }
}
