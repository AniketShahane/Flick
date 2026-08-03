package com.flick.receiver.ui

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.input.key.Key
import com.flick.receiver.ui.components.RenameLabelDialog
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickTvTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RenameLabelDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun currentNameIsSelectedAndImeDoneSubmitsReplacement() {
        val saved = mutableListOf<String>()
        composeRule.setContent {
            FlickTvTheme {
                RenameLabelDialog(
                    title = "Rename this TV",
                    currentName = "Living Room TV",
                    onCommit = { saved += it; true },
                    onDismiss = {},
                )
            }
        }

        val field = composeRule.onNodeWithTag("rename-name-field")
        field.assertIsFocused()
        field.assertTextEquals("Living Room TV")
        field.performTextInput("Den TV")
        field.assertTextEquals("Den TV")
        field.performImeAction()

        composeRule.runOnIdle { assertEquals(listOf("Den TV"), saved) }
    }

    @Test
    fun blankInputDisablesSaveAndCancelNeverCommits() {
        var commits = 0
        var dismisses = 0
        composeRule.setContent {
            FlickTvTheme {
                RenameLabelDialog(
                    title = "Rename paired phone",
                    currentName = "Pixel",
                    onCommit = { commits++; true },
                    onDismiss = { dismisses++ },
                )
            }
        }

        val field = composeRule.onNodeWithTag("rename-name-field")
        field.performTextClearance()
        field.performTextInput("   ")
        composeRule.onNodeWithTag("rename-save").assertIsNotEnabled()
        composeRule.onNodeWithTag("rename-cancel").assertIsEnabled().performClick()

        composeRule.runOnIdle {
            assertEquals(0, commits)
            assertEquals(1, dismisses)
        }
    }

    @Test
    fun dpadMovesFromTheEditorAcrossCancelAndSave() {
        composeRule.setContent {
            FlickTvTheme {
                RenameLabelDialog(
                    title = "Rename this TV",
                    currentName = "Living Room TV",
                    onCommit = { true },
                    onDismiss = {},
                )
            }
        }

        val field = composeRule.onNodeWithTag("rename-name-field").assertIsFocused()
        field.performKeyInput {
            keyDown(Key.DirectionDown)
            keyUp(Key.DirectionDown)
        }
        val cancel = composeRule.onNodeWithTag("rename-cancel").assertIsFocused()
        cancel.performKeyInput {
            keyDown(Key.DirectionRight)
            keyUp(Key.DirectionRight)
        }
        composeRule.onNodeWithTag("rename-save").assertIsFocused()
    }

    @Test
    fun backDismissesWithoutCommitting() {
        var commits = 0
        var dismisses = 0
        composeRule.setContent {
            FlickTvTheme {
                RenameLabelDialog(
                    title = "Rename this TV",
                    currentName = "Living Room TV",
                    onCommit = { commits++; true },
                    onDismiss = { dismisses++ },
                )
            }
        }

        composeRule.onNodeWithTag("rename-name-field").performKeyInput {
            keyDown(Key.Back)
            keyUp(Key.Back)
        }
        composeRule.runOnIdle {
            assertEquals(0, commits)
            assertEquals(1, dismisses)
        }
    }

    /**
     * The field is M3's but the label slot is filled with tv-material3's `Text`,
     * which reads tv-material3's `LocalContentColor` — black, and nothing here
     * provides it. `focusedLabelColor` cannot reach that Text, so only the pixels
     * say which colour actually won. The same capture covers the second half: a
     * field container of its own put the floating label's upper half on the card
     * and its lower half on the container, and that seam is gone only if the
     * container's navy is drawn nowhere in the field.
     */
    @Test
    fun theFieldLabelIsDrawnInSparkOverOneUniformBackdrop() {
        composeRule.setContent {
            FlickTvTheme {
                RenameLabelDialog(
                    title = "Rename this TV",
                    currentName = "Living Room TV",
                    onCommit = { true },
                    onDismiss = {},
                )
            }
        }

        val label = composeRule.onNodeWithText("Name", useUnmergedTree = true)
            .captureToImage()
            .toPixelMap()
        var spark = 0
        for (y in 0 until label.height) {
            for (x in 0 until label.width) {
                if (label[x, y] == FlickColor.Spark) spark++
            }
        }
        // Exact equality, not a tolerance: antialiasing only blends the glyph
        // EDGES, so a 14 sp label at density 2 still leaves unblended stem cores
        // — 836 of the 102 × 47 box on the verified TV. The floor is well under
        // that and well over the handful of pixels a stray tint could leave, so
        // it fails on a black label rather than on a hinting change.
        assertTrue("the label drew $spark Spark pixels", spark > 200)

        val field = composeRule.onNodeWithTag("rename-name-field").captureToImage().toPixelMap()
        var containerFill = 0
        for (y in 0 until field.height) {
            for (x in 0 until field.width) {
                if (field[x, y] == FlickColor.SurfaceRaised) containerFill++
            }
        }
        assertEquals("the field still paints a second navy behind its label", 0, containerFill)
    }

    @Test
    fun failedSaveKeepsTheDialogOpenAndExplainsTheFailure() {
        var dismisses = 0
        composeRule.setContent {
            FlickTvTheme {
                RenameLabelDialog(
                    title = "Rename paired phone",
                    currentName = "Pixel",
                    onCommit = { false },
                    onDismiss = { dismisses++ },
                )
            }
        }

        composeRule.onNodeWithTag("rename-save").performClick()
        composeRule.onNodeWithText("Couldn’t save the name. Try again.").assertIsEnabled()
        composeRule.onNodeWithTag("rename-name-field").assertIsFocused()
        composeRule.runOnIdle { assertEquals(0, dismisses) }
    }
}
