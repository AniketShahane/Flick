package com.flick.sender.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flick.sender.ui.components.MiniNowPlayingBar
import com.flick.sender.ui.theme.FlickTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MiniNowPlayingBarSemanticsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test fun compactPlayerIsAFullSizeButtonThatRestoresControls() {
        var clicks = 0
        val description = "Open Now Playing controls for Demo Movie"

        composeRule.setContent {
            FlickTheme(darkTheme = true, dynamicColor = false) {
                MiniNowPlayingBar(
                    title = "Demo Movie",
                    status = "PLAYING ON FLICK TV",
                    actionLabel = "Controls",
                    accessibilityLabel = description,
                    playing = true,
                    onClick = { clicks++ },
                )
            }
        }

        composeRule.onNodeWithContentDescription(description)
            .assertHasClickAction()
            .assertHeightIsAtLeast(76.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()

        composeRule.runOnIdle { assertEquals(1, clicks) }
    }
}
