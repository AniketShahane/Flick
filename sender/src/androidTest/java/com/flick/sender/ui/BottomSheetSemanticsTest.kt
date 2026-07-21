package com.flick.sender.ui

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flick.sender.ui.screens.BottomSheet
import com.flick.sender.ui.theme.FlickTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BottomSheetSemanticsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun sheetExposesModalPaneAndScrimDismissesOnce() {
        var dismissals = 0

        composeRule.setContent {
            FlickTheme(darkTheme = true, dynamicColor = false) {
                BottomSheet(onDismiss = { dismissals++ }) {
                    Text("Sheet contents")
                }
            }
        }

        composeRule.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Flick sheet"),
        ).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Dismiss sheet")
            .assertHasClickAction()
            .performClick()

        composeRule.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test
    fun clickInsideSheetIsSwallowedAndDoesNotDismiss() {
        var dismissals = 0

        composeRule.setContent {
            FlickTheme(darkTheme = true, dynamicColor = false) {
                BottomSheet(onDismiss = { dismissals++ }) {
                    Text("Sheet contents")
                }
            }
        }

        composeRule.onNodeWithText("Sheet contents")
            .assertHasClickAction()
            .performClick()

        composeRule.runOnIdle { assertEquals(0, dismissals) }
    }

    @Test
    fun systemBackDismissesSheetExactlyOnce() {
        var dismissals = 0
        lateinit var backDispatcher: OnBackPressedDispatcher

        composeRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            FlickTheme(darkTheme = true, dynamicColor = false) {
                BottomSheet(onDismiss = { dismissals++ }) {
                    Text("Sheet contents")
                }
            }
        }

        composeRule.runOnIdle {
            backDispatcher.onBackPressed()
        }

        composeRule.runOnIdle { assertEquals(1, dismissals) }
    }
}
