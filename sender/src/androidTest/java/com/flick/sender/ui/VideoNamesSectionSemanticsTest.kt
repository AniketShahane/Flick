package com.flick.sender.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flick.sender.R
import com.flick.sender.ui.screens.VideoNamesSection
import com.flick.sender.ui.theme.FlickTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoNamesSectionSemanticsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun readableNamesIsOneSwitchThatAnnouncesItsSafetySummaryAndToggles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val title = context.getString(R.string.settings_video_names_title)
        val summary = context.getString(R.string.settings_video_names_summary)
        val description = context.getString(R.string.settings_video_names_a11y, title, summary)
        var selected: Boolean? = null

        composeRule.setContent {
            var simplified by remember { mutableStateOf(true) }
            FlickTheme(darkTheme = false, dynamicColor = false) {
                VideoNamesSection(simplified = simplified) { value ->
                    selected = value
                    simplified = value
                }
            }
        }

        val switchRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
        composeRule.onAllNodes(switchRole, useUnmergedTree = true).assertCountEquals(1)
        composeRule.onNodeWithContentDescription(description)
            .assertHasClickAction()
            .assert(switchRole)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "On"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
            .performClick()

        composeRule.runOnIdle { assertEquals(false, selected) }
        composeRule.onNodeWithContentDescription(description)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Off"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
    }
}
