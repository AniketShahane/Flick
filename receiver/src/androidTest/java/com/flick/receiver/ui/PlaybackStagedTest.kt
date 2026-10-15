package com.flick.receiver.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.flick.receiver.R
import com.flick.receiver.player.HdrType
import com.flick.receiver.player.PlaybackPhase
import com.flick.receiver.ui.components.FilmDimReading
import com.flick.receiver.ui.screens.PlaybackScreen
import com.flick.receiver.ui.theme.FlickTvTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** While the house lights stage the film in the tunnel's window, the playback screen draws the film alone. */
class PlaybackStagedTest {

    // Full motion even when the device's animator scale is 0.
    @get:Rule
    val composeRule = createComposeRule(
        effectContext = object : MotionDurationScale {
            override val scaleFactor = 1f
        },
    )

    private var staged by mutableStateOf(true)
    private var phase by mutableStateOf(PlaybackPhase.Buffering)
    private val dim = FilmDimReading()

    private fun launch() {
        composeRule.setContent {
            FlickTvTheme {
                PlaybackScreen(
                    playing = true,
                    phase = phase,
                    positionMs = 0L,
                    durationMs = 120_000L,
                    bufferedMs = 0L,
                    targetMs = 0L,
                    seeking = false,
                    volume = 0.5f,
                    title = "A Film",
                    deviceLabel = "Pixel",
                    hdr = HdrType.NONE,
                    chromeVisible = false,
                    quality = null,
                    remoteSeekDeltaMs = 10_000L,
                    remoteSeekVisible = true,
                    onBack10 = {},
                    onPlayPause = {},
                    onForward10 = {},
                    onSetVolume = {},
                    playFocusRequester = remember { FocusRequester() },
                    dimReading = dim,
                    staged = staged,
                    videoContent = { Box {} },
                )
            }
        }
    }

    private fun bufferingShown(): Boolean {
        val words = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.buffering_title)
        return composeRule.onAllNodesWithText(words).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun aStagedFilmDrawsNoBufferingPlateNoSeekBurstAndNoDim() {
        launch()
        composeRule.mainClock.advanceTimeBy(1_000)
        assertTrue("the buffering plate drew over the tunnel", !bufferingShown())
        assertTrue("the seek burst drew over the tunnel",
            composeRule.onAllNodesWithText("+10", substring = true).fetchSemanticsNodes().isEmpty())
        assertEquals("the dim drew over the tunnel", 0f, dim.drawn, 0f)

        composeRule.runOnIdle { staged = false }
        composeRule.mainClock.advanceTimeBy(1_000)
        assertTrue("the buffering plate never came once the film had the screen", bufferingShown())
        assertTrue("the seek burst never came once the film had the screen",
            composeRule.onAllNodesWithText("+10", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun theDimEasesInOnceTheFilmHasTheScreen() {
        phase = PlaybackPhase.Paused
        launch()
        composeRule.mainClock.autoAdvance = false
        composeRule.mainClock.advanceTimeBy(500)
        assertEquals(0f, dim.drawn, 0f)
        composeRule.runOnIdle { staged = false }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeByFrame()
        val first = dim.drawn
        assertTrue("the dim jumped to $first in two frames", first < 0.2f)
        composeRule.mainClock.advanceTimeBy(600)
        assertEquals("the dim never reached the paused film's", 0.34f, dim.drawn, 0.01f)
    }
}
