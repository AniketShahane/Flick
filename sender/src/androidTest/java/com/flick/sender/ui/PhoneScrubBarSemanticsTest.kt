package com.flick.sender.ui

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flick.sender.ui.components.PhoneScrubBar
import com.flick.sender.ui.theme.FlickTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneScrubBarSemanticsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun adjustableActionStartsScrubsAndFinishesExactlyOnce() {
        val target = mutableFloatStateOf(0.25f)
        var starts = 0
        var ends = 0
        val values = mutableListOf<Float>()

        composeRule.setContent {
            FlickTheme(darkTheme = true, dynamicColor = false) {
                PhoneScrubBar(
                    targetFraction = { target.floatValue },
                    ghostFraction = { 0.20f },
                    syncing = false,
                    framePreview = { null },
                    previewLabel = { null },
                    onScrubStart = { starts++ },
                    onScrub = { fraction ->
                        target.floatValue = fraction
                        values += fraction
                    },
                    onScrubEnd = { ends++ },
                    // Exercise the compact-mode relocation path without changing the
                    // one-start/one-end accessibility contract.
                    reservePreviewSpace = true,
                    targetLabel = "Seek target",
                    confirmedLabel = "TV confirmed at 00:12",
                    stateLabel = "Following",
                    adjustableActionLabel = "Adjust seek target",
                )
            }
        }

        composeRule.onNodeWithContentDescription("Seek target")
            .assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .performSemanticsAction(SemanticsActions.SetProgress) { action ->
                action(0.75f)
            }

        composeRule.runOnIdle {
            assertEquals(1, starts)
            assertEquals(listOf(0.75f), values)
            assertEquals(1, ends)
            assertEquals(0.75f, target.floatValue)
        }
    }

    @Test
    fun disposingAnActivePointerScrubFinishesExactlyOnce() {
        val visible = mutableStateOf(true)
        var starts = 0
        var ends = 0

        composeRule.setContent {
            FlickTheme(darkTheme = true, dynamicColor = false) {
                if (visible.value) {
                    PhoneScrubBar(
                        targetFraction = { 0.25f },
                        ghostFraction = { null },
                        syncing = false,
                        framePreview = { null },
                        previewLabel = { null },
                        onScrubStart = { starts++ },
                        onScrub = {},
                        onScrubEnd = { ends++ },
                        targetLabel = "Seek target",
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("Seek target")
            .performTouchInput { down(center) }
        composeRule.waitForIdle()
        composeRule.runOnUiThread { visible.value = false }
        composeRule.waitForIdle()

        assertEquals(1, starts)
        assertEquals(1, ends)
    }

    @Test
    fun draggingAcrossTheBarTracksTheFingerAndTerminatesOnce() {
        val target = mutableFloatStateOf(0f)
        var starts = 0
        var ends = 0
        val values = mutableListOf<Float>()

        composeRule.setContent {
            FlickTheme(darkTheme = true, dynamicColor = false) {
                PhoneScrubBar(
                    targetFraction = { target.floatValue },
                    ghostFraction = { 0.10f },
                    // Deliberately off: the sync chip mounts an ungated shimmer loop,
                    // and an animation that never ends keeps waitForIdle from returning
                    // on any device whose animator scale is not zero.
                    syncing = false,
                    framePreview = { null },
                    previewLabel = { "0:30" },
                    onScrubStart = { starts++ },
                    onScrub = { fraction ->
                        target.floatValue = fraction
                        values += fraction
                    },
                    onScrubEnd = { ends++ },
                    bufferedFraction = { 0.55f },
                    positionMs = { 65_000L },
                    durationMs = { 125_000L },
                    targetLabel = "Seek target",
                )
            }
        }

        composeRule.onNodeWithContentDescription("Seek target")
            .performTouchInput {
                down(centerLeft)
                moveTo(centerRight)
                up()
            }

        composeRule.runOnIdle {
            assertEquals(1, starts)
            assertEquals(1, ends)
            assertTrue("expected at least a down and a move", values.size >= 2)
            assertTrue("drag should start at the left edge", values.first() < 0.05f)
            assertTrue("drag should end at the right edge", values.last() > 0.9f)
        }
    }
}
