package com.flick.sender.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flick.sender.media.FilmDetails
import com.flick.sender.ui.components.SynopsisReveal
import com.flick.sender.ui.components.rememberSynopsisReveal
import com.flick.sender.ui.screens.SynopsisIntertitle
import com.flick.sender.ui.theme.FlickCinematicTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The open story under more than one finger: whatever happens, it ends settled. */
@RunWith(AndroidJUnit4::class)
class SynopsisGestureTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val details = FilmDetails(overview = "A hacker learns the truth.", tagline = "Believe.", year = 1999)

    private fun showStory(): SynopsisReveal {
        lateinit var reveal: SynopsisReveal
        composeRule.setContent {
            FlickCinematicTheme {
                // Reduce motion: the reel and its flicker stay still, so the test clock can idle.
                val r = rememberSynopsisReveal("film", reduceMotion = true)
                reveal = r
                SideEffect { r.widthPx = 1000f }
                Box(Modifier.fillMaxSize().testTag("window")) {
                    SynopsisIntertitle(r, IntertitleInks(), details, "The Matrix", reduceMotion = true)
                }
            }
        }
        composeRule.runOnIdle { reveal.show() }
        composeRule.waitForIdle()
        assertTrue(reveal.open)
        return reveal
    }

    private fun assertSettled(reveal: SynopsisReveal) {
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertFalse("not moving", reveal.moving)
            assertEquals("at rest where it was sent", if (reveal.open) 1f else 0f, reveal.value, 0f)
        }
    }

    @Test
    fun aSecondFingerOnTheCloseDiscMidDragStillSettles() {
        val reveal = showStory()
        val close = composeRule.onNodeWithContentDescription("Close the synopsis").fetchSemanticsNode().boundsInRoot.center
        composeRule.onNodeWithTag("window").performTouchInput {
            down(0, Offset(width * 0.5f, height * 0.85f))
            moveBy(0, Offset(150f, 0f))
            moveBy(0, Offset(150f, 0f))
            down(1, close)
            up(1)
            moveBy(0, Offset(-200f, 0f))
            moveBy(0, Offset(80f, 0f))
            up(0)
        }
        assertSettled(reveal)
    }

    @Test
    fun aCloseDragThatIsCancelledStillSettles() {
        val reveal = showStory()
        composeRule.onNodeWithTag("window").performTouchInput {
            down(0, Offset(width * 0.5f, height * 0.85f))
            moveBy(0, Offset(200f, 0f))
            moveBy(0, Offset(200f, 0f))
            cancel()
        }
        assertSettled(reveal)
    }
}
