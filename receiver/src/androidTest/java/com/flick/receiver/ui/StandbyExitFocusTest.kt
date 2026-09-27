package com.flick.receiver.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.Text
import com.flick.receiver.net.PairNetworkFace
import com.flick.receiver.ui.components.FlickTvButton
import com.flick.receiver.ui.components.LocalShellInteractive
import com.flick.receiver.ui.components.ShellGate
import com.flick.receiver.ui.components.standbySurfaceInteractive
import com.flick.receiver.ui.screens.IdleScreen
import com.flick.receiver.ui.screens.PairScreen
import com.flick.receiver.ui.theme.FlickTvTheme
import com.flick.receiver.ui.theme.LocalReducedMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * An exiting standby surface is inert for the whole of its exit, under a host that is
 * itself live. Focus gates may only clear `canFocus`: the outermost assignment wins, so
 * a live ancestor that wrote `true` would re-arm the controls still fading out.
 *
 * The host and walk use ReceiverApp's own [ShellGate] and [standbySurfaceInteractive],
 * with the real Idle and Pair screens inside and a slow crossfade so the exit is still
 * running when the keys arrive.
 */
class StandbyExitFocusTest {

    @get:Rule
    val composeRule = createComposeRule(
        effectContext = object : MotionDurationScale {
            override val scaleFactor = 1f
        },
    )

    private enum class Surface { Idle, Pair }

    private var surface by mutableStateOf(Surface.Idle)
    private var confirmLabel by mutableStateOf<String?>(null)

    private var idlePairAnother = 0
    private var idleSettings = 0
    private var pairRename = 0
    private var pairSettings = 0

    /** Every frame on which a surface held focus while it was the one leaving. */
    private val exitingFocus = mutableListOf<Surface>()

    @Before
    fun freezeClock() {
        composeRule.mainClock.autoAdvance = false
    }

    @Composable
    private fun LiveHost(content: @Composable () -> Unit) {
        ShellGate(true) {
            CompositionLocalProvider(LocalReducedMotion provides false, content = content)
        }
    }

    @Composable
    private fun StandbyFace(rendered: Surface) {
        when (rendered) {
            Surface.Idle -> IdleScreen(
                pairedLabel = "Pixel",
                onPairAnother = { idlePairAnother++ },
                onOpenSettings = { idleSettings++ },
            )
            Surface.Pair -> PairScreen(
                tvName = "Living Room TV",
                code = "1234",
                qrPayload = null,
                host = "192.0.2.12",
                port = 8472,
                networkFace = PairNetworkFace.READY,
                onRename = { pairRename++ },
                onOpenSettings = { pairSettings++ },
            )
        }
    }

    private fun showWalk() {
        composeRule.setContent {
            FlickTvTheme {
                LiveHost {
                    AnimatedContent(
                        targetState = surface,
                        transitionSpec = { fadeIn(tween(EXIT_MS)) togetherWith fadeOut(tween(EXIT_MS)) },
                        label = "standbySurface",
                    ) { rendered ->
                        val interactive = standbySurfaceInteractive(rendered, surface, hostInteractive = true)
                        ShellGate(
                            interactive,
                            Modifier.onFocusChanged { if (it.hasFocus && !interactive) exitingFocus += rendered },
                        ) { StandbyFace(rendered) }
                    }
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(SETTLE_MS)
    }

    private fun press(key: Key) {
        composeRule.onRoot().performKeyInput { pressKey(key) }
        composeRule.mainClock.advanceTimeByFrame()
    }

    /** Walks the D-pad through every direction that could reach the leaving surface. */
    private fun pokeDuringExit() {
        repeat(2) {
            press(Key.DirectionUp)
            press(Key.DirectionDown)
            press(Key.DirectionRight)
            press(Key.DirectionRight)
            press(Key.DirectionCenter)
        }
    }

    @Test
    fun idleToPairLeavesIdleInert() {
        showWalk()
        composeRule.runOnIdle { surface = Surface.Pair }
        composeRule.mainClock.advanceTimeByFrame()

        pokeDuringExit()

        assertEquals("the leaving Idle took focus", emptyList<Surface>(), exitingFocus)
        assertEquals("the leaving Idle's Pair key fired", 0, idlePairAnother)
        assertEquals("the leaving Idle's Settings key fired", 0, idleSettings)
    }

    @Test
    fun pairToIdleLeavesPairInert() {
        surface = Surface.Pair
        showWalk()
        composeRule.runOnIdle { surface = Surface.Idle }
        composeRule.mainClock.advanceTimeByFrame()

        pokeDuringExit()

        assertEquals("the leaving Pair took focus", emptyList<Surface>(), exitingFocus)
        assertEquals("the leaving Pair's Rename key fired", 0, pairRename)
        assertEquals("the leaving Pair's Settings key fired", 0, pairSettings)
    }

    /**
     * Manual → Confirm inside Pair's own action row: the outgoing Rename / Settings row
     * is still composed and fading, and nothing may reach it.
     */
    @Test
    fun pairActionsOutgoingRowCannotTakeFocus() {
        composeRule.setContent {
            FlickTvTheme {
                LiveHost {
                    PairScreen(
                        tvName = "Living Room TV",
                        code = "1234",
                        qrPayload = null,
                        host = "192.0.2.12",
                        port = 8472,
                        networkFace = PairNetworkFace.READY,
                        onRename = { pairRename++ },
                        onOpenSettings = { pairSettings++ },
                        confirmDeviceLabel = confirmLabel,
                    )
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(SETTLE_MS)

        composeRule.runOnIdle { confirmLabel = "Pixel 9" }
        composeRule.mainClock.advanceTimeByFrame()

        // From Deny, Right reaches Allow, the incoming row's last control; further
        // Rights must not walk on into the outgoing Rename / Settings.
        repeat(3) { press(Key.DirectionRight) }
        press(Key.DirectionUp)
        press(Key.DirectionDown)
        repeat(3) { press(Key.DirectionRight) }
        press(Key.DirectionCenter)

        assertEquals("the outgoing row's Rename fired", 0, pairRename)
        assertEquals("the outgoing row's Settings fired", 0, pairSettings)
    }

    /** A requester on a leaving FlickTvButton cannot pull focus back into the exit. */
    @Test
    fun aRequesterOnALeavingButtonFails() {
        var key by mutableStateOf(0)
        var leavingFocused = false
        var leavingClicks = 0
        val requesters = List(2) { FocusRequester() }
        composeRule.setContent {
            FlickTvTheme {
                LiveHost {
                    AnimatedContent(
                        targetState = key,
                        transitionSpec = { fadeIn(tween(EXIT_MS)) togetherWith fadeOut(tween(EXIT_MS)) },
                        label = "swap",
                    ) { k ->
                        val interactive = k == key &&
                            transition.targetState == EnterExitState.Visible
                        ShellGate(interactive) {
                            val requester = remember(k) { requesters[k] }
                            FlickTvButton(
                                onClick = { if (k == 0) leavingClicks++ },
                                focusRequester = requester,
                                modifier = Modifier.onFocusChanged {
                                    if (k == 0 && it.isFocused && !interactive) leavingFocused = true
                                },
                            ) { Text("Key $k") }
                        }
                    }
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(SETTLE_MS)
        composeRule.runOnIdle { requesters[0].requestFocus() }
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.runOnIdle { key = 1 }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.runOnIdle { runCatching { requesters[0].requestFocus() } }
        composeRule.mainClock.advanceTimeByFrame()
        press(Key.DirectionCenter)

        assertFalse("a requester re-focused the leaving button", leavingFocused)
        assertEquals("the leaving button fired", 0, leavingClicks)
    }

    /**
     * [LocalShellInteractive] alone, with no gate between it and the buttons: every
     * FlickTvButton must still refuse focus and clicks by itself.
     */
    private fun assertInertUnderTheLocalAlone(rendered: Surface) {
        var focused = false
        composeRule.setContent {
            FlickTvTheme {
                LiveHost {
                    CompositionLocalProvider(LocalShellInteractive provides false) {
                        Box(Modifier.fillMaxSize().onFocusChanged { if (it.hasFocus) focused = true }) {
                            StandbyFace(rendered)
                        }
                    }
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(SETTLE_MS)
        pokeDuringExit()
        repeat(3) { press(Key.DirectionLeft) }
        press(Key.DirectionCenter)

        assertFalse("a $rendered button took focus under an inert shell", focused)
        assertEquals("a $rendered button fired under an inert shell",
            0, idlePairAnother + idleSettings + pairRename + pairSettings)
    }

    @Test
    fun idleButtonsHonourTheInertLocalAlone() = assertInertUnderTheLocalAlone(Surface.Idle)

    @Test
    fun pairButtonsHonourTheInertLocalAlone() = assertInertUnderTheLocalAlone(Surface.Pair)

    private companion object {
        /** Long enough that every key above lands while the exit is still running. */
        const val EXIT_MS = 2_000
        const val SETTLE_MS = 3_000L
    }
}
