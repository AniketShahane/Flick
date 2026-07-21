package com.flick.receiver.ui

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.flick.receiver.ui.components.TvScrubBar
import com.flick.receiver.ui.theme.FlickTvTheme
import org.junit.Rule
import org.junit.Test

class TvScrubBarSemanticsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun seeking_exposes_target_confirmed_and_syncing_state() {
        composeRule.setContent {
            FlickTvTheme {
                TvScrubBar(
                    durationMs = 120_000L,
                    confirmedMs = 42_000L,
                    bufferedMs = 80_000L,
                    targetMs = 55_000L,
                    seeking = true,
                )
            }
        }

        composeRule.onNodeWithContentDescription(
            "target 0:55 · snap on release, confirmed 0:42",
        ).assert(hasStateDescription("SYNCING…"))
    }

    @Test
    fun healthy_state_announces_confirmed_position_not_pending_target() {
        composeRule.setContent {
            FlickTvTheme {
                TvScrubBar(
                    durationMs = 120_000L,
                    confirmedMs = 42_000L,
                    bufferedMs = 80_000L,
                    targetMs = 55_000L,
                    seeking = false,
                )
            }
        }

        composeRule.onNodeWithContentDescription("confirmed 0:42")
            .assertExists()
        composeRule.onAllNodes(
            hasContentDescription("target 0:55 · snap on release"),
        ).assertCountEquals(0)
        composeRule.onAllNodes(
            hasContentDescription("confirmed 0:42") and hasStateDescription("SYNCING…"),
        ).assertCountEquals(0)
    }
}
