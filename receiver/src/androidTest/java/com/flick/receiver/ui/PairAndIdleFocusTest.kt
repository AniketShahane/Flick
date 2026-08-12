package com.flick.receiver.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import com.flick.receiver.net.PairNetworkFace
import com.flick.receiver.ui.screens.IdleScreen
import com.flick.receiver.ui.screens.PairScreen
import com.flick.receiver.ui.theme.FlickTvTheme
import org.junit.Assert.assertEquals
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

    /**
     * The pair screen's whole action row. "Show code bigger" and the enlarged-code
     * mode it drove are gone; exactly one control still takes focus on entry, and
     * the D-pad has to reach the other one and come back.
     */
    @Test
    fun pair_lands_on_rename_and_walks_to_settings() {
        composeRule.setContent {
            FlickTvTheme {
                PairScreen(
                    tvName = "Living Room TV",
                    code = "1234",
                    qrPayload = null,
                    host = "192.0.2.12",
                    port = 8472,
                    networkFace = PairNetworkFace.READY,
                    onRename = {},
                    onOpenSettings = {},
                )
            }
        }

        val rename = composeRule.onNodeWithText("Rename TV")
        val settings = composeRule.onNodeWithText("Settings")
        rename.assertIsEnabled().assertIsFocused()
        rename.performKeyInput { keyDown(Key.DirectionRight); keyUp(Key.DirectionRight) }
        settings.assertIsEnabled().assertIsFocused()
        settings.performKeyInput { keyDown(Key.DirectionLeft); keyUp(Key.DirectionLeft) }
        rename.assertIsFocused()
    }

    /**
     * Settings is unreachable from anywhere else while no phone is paired — the
     * router sends a factory-fresh TV to this screen and never to Idle — so this
     * key is the only route in, and it must fire the caller's handler rather than
     * the rename beside it.
     */
    @Test
    fun pair_actions_fire_their_own_handlers() {
        var renames = 0
        var settingsOpens = 0
        composeRule.setContent {
            FlickTvTheme {
                PairScreen(
                    tvName = "Living Room TV",
                    code = "1234",
                    qrPayload = null,
                    host = "192.0.2.12",
                    port = 8472,
                    networkFace = PairNetworkFace.READY,
                    onRename = { renames++ },
                    onOpenSettings = { settingsOpens++ },
                )
            }
        }

        composeRule.onNodeWithText("Settings").performClick()
        composeRule.runOnIdle {
            assertEquals(1, settingsOpens)
            assertEquals(0, renames)
        }

        composeRule.onNodeWithText("Rename TV").performClick()
        composeRule.runOnIdle {
            assertEquals(1, settingsOpens)
            assertEquals(1, renames)
        }
    }
}
