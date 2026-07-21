package com.flick.sender.ui

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flick.sender.ui.components.VolumeSlider
import com.flick.sender.ui.theme.FlickTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VolumeSliderSemanticsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun volumeIsAdjustableAndClampsAccessibilityActionsToTheSupportedRange() {
        val value = mutableFloatStateOf(0.30f)
        val received = mutableListOf<Float>()

        composeRule.setContent {
            FlickTheme(darkTheme = true, dynamicColor = false) {
                VolumeSlider(
                    value = value.floatValue,
                    onValueChange = { target ->
                        value.floatValue = target
                        received += target
                    },
                    accessibilityLabel = "TV volume",
                    valueDescription = "30 percent",
                    adjustableActionLabel = "Adjust TV volume",
                )
            }
        }

        composeRule.onNodeWithContentDescription("TV volume")
            .assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .performSemanticsAction(SemanticsActions.SetProgress) { action ->
                action(1.25f)
            }

        composeRule.runOnIdle {
            assertEquals(listOf(1f), received)
            assertEquals(1f, value.floatValue)
        }
    }
}
