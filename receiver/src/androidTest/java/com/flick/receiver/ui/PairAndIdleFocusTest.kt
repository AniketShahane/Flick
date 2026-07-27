package com.flick.receiver.ui

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.flick.receiver.ui.screens.IdleScreen
import com.flick.receiver.ui.screens.PairScreen
import com.flick.receiver.ui.theme.FlickTvTheme
import org.junit.Rule
import org.junit.Test

class PairAndIdleFocusTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun idle_lands_on_pair_another_phone() {
        composeRule.setContent {
            FlickTvTheme {
                IdleScreen(
                    pairedLabel = "Pixel",
                    onPairAnother = {},
                    onOpenSettings = {},
                )
            }
        }

        composeRule.onNodeWithText("Pair another phone").assertIsFocused()
    }

    @Test
    fun larger_code_modal_excludes_background_and_restores_focus_on_close() {
        composeRule.setContent {
            FlickTvTheme {
                PairScreen(
                    tvName = "Living Room TV",
                    code = "1234",
                    qrPayload = null,
                    host = "192.0.2.12",
                    port = 8472,
                    networkReady = true,
                    onRename = {},
                )
            }
        }

        val showBigger = composeRule.onNodeWithText("Show code bigger")
        showBigger.assertIsFocused().performClick()

        composeRule.onNodeWithText("Done").assertIsFocused()
        composeRule.onNodeWithText("Show code bigger").assertDoesNotExist()
        composeRule.onNodeWithText("Rename TV").assertDoesNotExist()

        composeRule.onNodeWithText("Done").performClick()
        composeRule.onNodeWithText("Show code bigger").assertIsEnabled().assertIsFocused()
    }

    @Test
    fun system_back_closes_larger_code_and_restores_background_focus() {
        lateinit var backDispatcher: OnBackPressedDispatcher

        composeRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            FlickTvTheme {
                PairScreen(
                    tvName = "Living Room TV",
                    code = "1234",
                    qrPayload = null,
                    host = "192.0.2.12",
                    port = 8472,
                    networkReady = true,
                    onRename = {},
                )
            }
        }

        val showBigger = composeRule.onNodeWithText("Show code bigger")
        showBigger.performClick()
        composeRule.onNodeWithText("Done").assertIsFocused()
        composeRule.onNodeWithText("Show code bigger").assertDoesNotExist()

        composeRule.runOnIdle {
            backDispatcher.onBackPressed()
        }

        composeRule.onNodeWithText("Done").assertDoesNotExist()
        composeRule.onNodeWithText("Show code bigger").assertIsEnabled().assertIsFocused()
    }
}
