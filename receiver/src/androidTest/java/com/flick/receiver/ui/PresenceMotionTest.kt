package com.flick.receiver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tv.material3.Text
import com.flick.receiver.ui.screens.IdleScreen
import java.util.Date
import com.flick.receiver.ui.components.FlickPresence
import com.flick.receiver.ui.components.FlickSwap
import com.flick.receiver.ui.components.InkText
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickTvTheme
import com.flick.receiver.ui.theme.FlickType
import com.flick.receiver.ui.theme.LocalReducedMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The overlay helpers at full motion: an exit keeps drawing after it has left focus
 * and semantics, and reduced motion leaves exactly one of anything.
 */
class PresenceMotionTest {

    @get:Rule
    val composeRule = createComposeRule(
        effectContext = object : MotionDurationScale {
            override val scaleFactor = 1f
        },
    )

    private var chip by mutableStateOf<String?>("Chip")
    private var word by mutableStateOf("Buffering")

    @Before
    fun freezeClock() {
        composeRule.mainClock.autoAdvance = false
    }

    private fun show(reduced: Boolean, content: @Composable () -> Unit) {
        composeRule.setContent {
            FlickTvTheme {
                CompositionLocalProvider(LocalReducedMotion provides reduced) {
                    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                        content()
                    }
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(500)
    }

    @Composable
    private fun Chip() {
        FlickPresence(value = chip, overFilm = true) {
            Box(Modifier.size(120.dp).background(Color.White), contentAlignment = Alignment.Center) {
                Text(it, color = Color.Black)
            }
        }
    }

    /** Luma at the chip's centre, which is the root's centre. */
    private fun centreLuma(): Float {
        val px = composeRule.onRoot().captureToImage().toPixelMap()
        val c = px[px.width / 2, px.height / 2 + 40]
        return 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue
    }

    @Test
    fun anExitLeavesSemanticsFirstAndPixelsLast() {
        show(reduced = false) { Chip() }
        composeRule.onNodeWithText("Chip").assertExists()
        assertTrue(centreLuma() > 0.9f)

        composeRule.runOnIdle { chip = null }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithText("Chip").assertDoesNotExist()
        assertTrue("the chip stopped drawing on its first exit frame", centreLuma() > 0.5f)

        composeRule.mainClock.advanceTimeBy(300)
        composeRule.onNodeWithText("Chip").assertDoesNotExist()
        assertTrue("the chip outlived its 250 ms exit", centreLuma() < 0.05f)
    }

    @Test
    fun reducedMotionRemovesAPresenceInOneStep() {
        show(reduced = true) { Chip() }
        composeRule.onNodeWithText("Chip").assertExists()

        composeRule.runOnIdle { chip = null }
        // The frame that applies the write, and the one that draws its snapped end.
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithText("Chip").assertDoesNotExist()
        assertTrue("a reduced-motion exit is still drawing", centreLuma() < 0.05f)
    }

    /** Whether any pixel on screen is the incoming word's red. */
    private fun anyRed(): Boolean {
        val px = composeRule.onRoot().captureToImage().toPixelMap()
        for (y in 0 until px.height) {
            for (x in 0 until px.width) {
                val c = px[x, y]
                if (c.red > 0.5f && c.green < 0.3f && c.blue < 0.3f) return true
            }
        }
        return false
    }

    @Test
    fun reducedMotionSwapLeavesExactlyOneNode() {
        show(reduced = true) {
            FlickSwap(target = word) {
                Text(
                    it,
                    color = if (it == "Stalled") Color.Red else Color.White,
                    style = FlickType.body(sizeSp = 32),
                )
            }
        }
        assertTrue("the outgoing word is already red", !anyRed())
        composeRule.runOnIdle { word = "Stalled" }
        composeRule.mainClock.advanceTimeByFrame()
        // The one frame where both words are composed: the incoming one must hold at
        // alpha 0, or the two draw over each other.
        assertTrue("the incoming word drew on the frame both were composed", !anyRed())
        composeRule.mainClock.advanceTimeByFrame()
        assertEquals(
            1,
            composeRule.onAllNodes(hasText("Buffering") or hasText("Stalled")).fetchSemanticsNodes().size,
        )
        composeRule.onNodeWithText("Stalled").assertExists()
    }

    @Test
    fun fullMotionSwapLeavesSemanticsOnItsFirstExitFrame() {
        show(reduced = false) {
            FlickSwap(target = word) { Text(it, style = FlickType.body(sizeSp = 32)) }
        }
        composeRule.onNodeWithText("Buffering").assertExists()
        composeRule.runOnIdle { word = "Stalled" }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithText("Buffering").assertDoesNotExist()
        composeRule.onNodeWithText("Stalled").assertExists()
    }

    @Test
    fun inkTextExposesItsText() {
        show(reduced = false) {
            InkText(text = "48:12", color = FlickColor.OnSurface, style = FlickType.body(sizeSp = 16))
        }
        assertEquals(1, composeRule.onAllNodesWithText("48:12").fetchSemanticsNodes().size)
    }

    private class HandLifecycle : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    /**
     * The glyphs the clock showing [value] composes, in order. clearAndSetSemantics drops
     * the cells from both semantics trees, so they are read from the layout nodes under
     * it: each cell is one Text, and a rolling cell carries both its outgoing and
     * incoming glyph, so a roll reads longer than the value.
     *
     * The layout node's children and own semantics are only reachable through the
     * internal SemanticsInfo interface, so they are read reflectively; a Compose upgrade
     * that renames them fails here loudly rather than passing.
     */
    private fun clockGlyphs(value: String): String {
        val root: Any = composeRule.onNodeWithText(value).fetchSemanticsNode().layoutInfo
        val glyphs = StringBuilder()
        fun walk(node: Any) {
            @Suppress("UNCHECKED_CAST")
            val children = node.javaClass.getMethod("getChildrenInfo").invoke(node) as List<Any>
            for (child in children) {
                val info = child as LayoutInfo
                if (!info.isAttached || info.isDeactivated) continue
                val config = child.javaClass.getMethod("getSemanticsConfiguration").invoke(child) as SemanticsConfiguration?
                config?.getOrNull(SemanticsProperties.Text)?.forEach { glyphs.append(it.text) }
                walk(child)
            }
        }
        walk(root)
        return glyphs.toString()
    }

    @Test
    fun aReturnDrawsTheNewTimeOnItsFirstFrame() {
        // A minute boundary, so the ticker's own wake-up is a whole minute away.
        var fakeNowMs = 1_800_000_000_000L
        val owner = HandLifecycle()
        composeRule.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        composeRule.setContent {
            FlickTvTheme {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    IdleScreen(
                        pairedLabel = null,
                        onPairAnother = {},
                        onOpenSettings = {},
                        clockSource = { fakeNowMs },
                    )
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(2_000)
        val formatter = android.text.format.DateFormat.getTimeFormat(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
        val before = formatter.format(Date(fakeNowMs))
        assertEquals(1, composeRule.onAllNodesWithText(before).fetchSemanticsNodes().size)
        assertEquals("the settled clock's cells do not spell its value", before, clockGlyphs(before))

        fakeNowMs += 12 * 60_000L
        val after = formatter.format(Date(fakeNowMs))
        composeRule.runOnUiThread {
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }
        composeRule.mainClock.advanceTimeByFrame()

        assertEquals("the new time was not read on the first frame back",
            1, composeRule.onAllNodesWithText(after).fetchSemanticsNodes().size)
        assertEquals("the stale time was still read on the first frame back",
            0, composeRule.onAllNodesWithText(before).fetchSemanticsNodes().size)
        assertEquals("the clock rolled into the new time instead of drawing it on the first frame back",
            after, clockGlyphs(after))
    }

    /**
     * A return one minute on is the step the clock would otherwise roll, so only the
     * re-key on every return draws it fresh.
     */
    @Test
    fun aOneMinuteReturnIsDrawnWithoutARoll() {
        var fakeNowMs = 1_800_000_000_000L
        val owner = HandLifecycle()
        composeRule.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        composeRule.setContent {
            FlickTvTheme {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    IdleScreen(
                        pairedLabel = null,
                        onPairAnother = {},
                        onOpenSettings = {},
                        clockSource = { fakeNowMs },
                    )
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(2_000)
        val formatter = android.text.format.DateFormat.getTimeFormat(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
        composeRule.runOnUiThread { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP) }
        fakeNowMs += 60_000L
        val after = formatter.format(Date(fakeNowMs))
        composeRule.runOnUiThread { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START) }
        composeRule.mainClock.advanceTimeByFrame()

        assertEquals("the clock rolled into the next minute on return instead of drawing it",
            after, clockGlyphs(after))
    }

    /**
     * A long stop comes back on the current time. It covers only the ON_START re-read:
     * a jump of this size bumps the epoch with or without the `returns` re-key, and
     * HandLifecycle does not pause the frame clock, so an ungated ticker also passes.
     * [aOneMinuteReturnIsDrawnWithoutARoll] is the guard for the re-key.
     */
    @Test
    fun minutesPassedWhileStoppedAreDrawnFreshOnReturn() {
        var fakeNowMs = 1_800_000_000_000L
        val owner = HandLifecycle()
        composeRule.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        composeRule.setContent {
            FlickTvTheme {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    IdleScreen(
                        pairedLabel = null,
                        onPairAnother = {},
                        onOpenSettings = {},
                        clockSource = { fakeNowMs },
                    )
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(2_000)
        val formatter = android.text.format.DateFormat.getTimeFormat(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
        val before = formatter.format(Date(fakeNowMs))
        assertEquals(before, clockGlyphs(before))

        composeRule.runOnUiThread { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP) }
        // Three minute boundaries on the frame clock, then the rest of 25 minutes at once.
        repeat(3) {
            fakeNowMs += 60_000L
            composeRule.mainClock.advanceTimeBy(60_000L)
        }
        fakeNowMs += 22 * 60_000L
        val after = formatter.format(Date(fakeNowMs))
        assertTrue("the test's step did not change the time", after != before)
        composeRule.runOnUiThread { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START) }
        composeRule.mainClock.advanceTimeByFrame()

        assertEquals("the new time was not read on the first frame back",
            1, composeRule.onAllNodesWithText(after).fetchSemanticsNodes().size)
        assertEquals("a stale time was still read on the first frame back",
            0, composeRule.onAllNodesWithText(before).fetchSemanticsNodes().size)
        assertEquals("a cell composed an outgoing glyph on the first frame back", after, clockGlyphs(after))
    }
}
