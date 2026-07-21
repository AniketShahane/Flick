package com.flick.receiver.ui

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.flick.receiver.session.ErrorKind
import com.flick.receiver.ui.screens.ErrorScreen
import com.flick.receiver.ui.theme.FlickTvTheme
import org.junit.Rule
import org.junit.Test

class ErrorScreenFocusTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun secondary_action_receives_initial_focus_when_primary_is_absent() {
        composeRule.setContent {
            FlickTvTheme {
                ErrorScreen(
                    kind = ErrorKind.Unreachable,
                    deviceLabel = "Pixel",
                    onPrimary = null,
                    onSecondary = {},
                )
            }
        }

        composeRule.onNodeWithText("End session").assertIsFocused()
    }
}
