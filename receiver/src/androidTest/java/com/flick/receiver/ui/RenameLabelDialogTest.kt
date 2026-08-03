package com.flick.receiver.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
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
import com.flick.receiver.ui.theme.FlickTvTheme
import org.junit.Assert.assertEquals
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
