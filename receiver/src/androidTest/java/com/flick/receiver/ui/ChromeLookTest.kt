package com.flick.receiver.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.test.platform.app.InstrumentationRegistry
import com.flick.receiver.player.HdrType
import com.flick.receiver.player.PlaybackPhase
import com.flick.receiver.player.SubtitleTrackFocusIdentity
import com.flick.receiver.player.SubtitleTrackInfo
import com.flick.receiver.player.VideoRotation
import com.flick.receiver.ui.screens.PlaybackScreen
import com.flick.receiver.ui.theme.FlickTvTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * The playback chrome, rendered over a deliberately BRIGHT frame — the worst
 * backdrop the receiver can be handed — and written out so a person can look at it.
 *
 * Half harness and half gate, and the split is deliberate. Glass, an optical size
 * and a mark's silhouette are settled by eye and nothing else, so this leaves a PNG
 * behind for every state worth looking at. What it asserts is the two claims an eye
 * cannot settle: that the transport's play key and the resting paused key stand on
 * the same centre line, so summoning the chrome does not slide the key sideways; and
 * that each state's keys SPEAK what they are drawing, which is where a mark wired to
 * the wrong turn would show up.
 */
class ChromeLookTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val group = TrackGroup(
        Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).build(),
    )

    private fun track(index: Int, label: String, selected: Boolean) = SubtitleTrackInfo(
        id = index.toString(),
        focusIdentity = SubtitleTrackFocusIdentity(group, index),
        label = label,
        mimeType = MimeTypes.APPLICATION_SUBRIP,
        isSelected = selected,
        trackNumber = index + 1,
    )

    /** A bright, busy frame — glass judged over near-black proves nothing. */
    @Composable
    private fun Film() {
        Canvas(
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = "Film surface" },
        ) {
            drawRect(
                Brush.linearGradient(
                    colorStops = arrayOf(
                        0f to Color(0xFFFFF3D6),
                        0.45f to Color(0xFFB8CFF0),
                        1f to Color(0xFF2A1B3D),
                    ),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                ),
            )
        }
    }

    private fun shoot(name: String) {
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val dir = InstrumentationRegistry.getInstrumentation()
            .targetContext.getExternalFilesDir(null)!!
        File(dir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Composable
    private fun Screen(
        chromeVisible: Boolean,
        subtitlesOn: Boolean,
        rotation: VideoRotation,
        autoDegrees: Int,
    ) {
        val playFocus = remember { FocusRequester() }
        FlickTvTheme {
            PlaybackScreen(
                playing = false,
                phase = PlaybackPhase.Paused,
                positionMs = 48 * 60_000L,
                durationMs = 142 * 60_000L,
                bufferedMs = 55 * 60_000L,
                targetMs = 48 * 60_000L,
                seeking = false,
                volume = 0.7f,
                title = "Dune: Part Two",
                deviceLabel = "Living Room",
                hdr = HdrType.DOLBY_VISION,
                chromeVisible = chromeVisible,
                quality = null,
                onBack10 = {},
                onPlayPause = {},
                onForward10 = {},
                onSetVolume = {},
                playFocusRequester = playFocus,
                subtitleTracks = listOf(
                    track(0, "English", subtitlesOn),
                    track(1, "Français", false),
                ),
                videoRotation = rotation,
                autoVideoRotationDegrees = autoDegrees,
                videoContent = { Film() },
            )
        }
    }

    /**
     * One still, plus the one thing about it worth gating: the turn key speaks the
     * turn it is standing at. The mark itself can only be judged by eye — hence the
     * PNG — but a mark drawn from a turn the key does not claim would be a wiring
     * fault, and that is checkable here.
     */
    private fun still(
        name: String,
        subtitlesOn: Boolean,
        rotation: VideoRotation,
        spokenTurn: String,
        autoDegrees: Int = 0,
    ) {
        composeRule.setContent {
            Screen(
                chromeVisible = true,
                subtitlesOn = subtitlesOn,
                rotation = rotation,
                autoDegrees = autoDegrees,
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Picture orientation: $spokenTurn")
            .assertExists()
        composeRule.onNodeWithContentDescription(
            if (subtitlesOn) "Subtitles: ON" else "Subtitles: OFF",
        ).assertExists()
        shoot(name)
    }

    @Test
    fun theBarsPlayKeyStandsOnTheSameCentreLineAsTheRestingPausedKey() {
        lateinit var setChromeVisible: (Boolean) -> Unit
        composeRule.setContent {
            var chromeVisible by remember { mutableStateOf(false) }
            setChromeVisible = { chromeVisible = it }
            Screen(
                chromeVisible = chromeVisible,
                subtitlesOn = true,
                rotation = VideoRotation.AsFiled,
                autoDegrees = 0,
            )
        }

        composeRule.waitForIdle()
        val rootWidth = composeRule.onRoot().fetchSemanticsNode().size.width
        val resting = composeRule.onNodeWithContentDescription("Paused")
            .fetchSemanticsNode().boundsInRoot
        val restingCentre = (resting.left + resting.right) / 2f
        shoot("tv_chrome_hidden_paused")

        composeRule.runOnIdle { setChromeVisible(true) }
        composeRule.waitForIdle()
        val play = composeRule.onNodeWithContentDescription("Play")
            .fetchSemanticsNode().boundsInRoot
        val playCentre = (play.left + play.right) / 2f
        shoot("tv_chrome_visible_subs_on")

        // Both keys are measured against the same root, so the screen's own
        // centre is the reference rather than either key's.
        val screenCentre = rootWidth / 2f
        assertTrue(
            "resting key centre $restingCentre is not the screen centre $screenCentre",
            abs(restingCentre - screenCentre) <= 1.5f,
        )
        assertTrue(
            "play key centre $playCentre is not the screen centre $screenCentre",
            abs(playCentre - screenCentre) <= 1.5f,
        )
    }

    @Test
    fun subtitlesOffAtRest() =
        still("tv_chrome_subs_off_turn_0", false, VideoRotation.AsFiled, "0\u00b0")

    @Test
    fun aQuarterTurn() = still("tv_chrome_turn_90", true, VideoRotation.Quarter, "90\u00b0")

    @Test
    fun aHalfTurn() = still("tv_chrome_turn_180", false, VideoRotation.Half, "180\u00b0")

    @Test
    fun threeQuartersUnderAuto() = still(
        "tv_chrome_turn_270_auto",
        subtitlesOn = true,
        rotation = VideoRotation.Auto,
        spokenTurn = "AUTO \u00b7 270\u00b0",
        autoDegrees = 270,
    )
}
