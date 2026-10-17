package com.flick.receiver.ui

import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.flick.receiver.ui.components.DisplayCadenceSource
import com.flick.receiver.ui.components.FILM_FLOWING_WAIT_MS
import com.flick.receiver.ui.components.FilmStaging
import com.flick.receiver.ui.components.awaitFramesFlowing
import com.flick.receiver.ui.components.HouseLights
import com.flick.receiver.ui.components.HouseStage
import com.flick.receiver.ui.components.RATE_KNOWN_WAIT_MS
import com.flick.receiver.ui.components.RESYNC_GRACE_MS
import com.flick.receiver.ui.components.TUNNEL_TAG
import com.flick.receiver.ui.components.TUNNEL_VANISH_Y
import com.flick.receiver.ui.components.TUNNEL_WINDOW_FRACTION
import com.flick.receiver.ui.components.VEIL_DENSITY
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickMotion
import com.flick.receiver.ui.theme.FlickTvTheme
import com.flick.receiver.ui.theme.LocalReducedMotion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The house lights at full motion on a paused clock, read back as pixels.
 *
 * The underlay stands in for the lit room (a bright amber-white ramp), and a plain view
 * with a bright blue-white ramp stands in for the film's view at Handshake and Film,
 * posed by the same [FilmPose] the player's view is. Both are the worst backdrop a dark
 * curtain can be judged over.
 *
 * The tunnel's loop and its fade over the room run on the RenderThread, off this clock.
 * With the device's animator scale at 0, as the suite runs, the loop is drawn fully in
 * and still from its first frame; what is asserted here is the main-thread choreography
 * around it: the holds, the callbacks, the film's pose and the curtain. Every case leaves
 * a filmstrip behind, `<test>_<ms>.png`.
 */
class StageMotionTest {

    // Full motion even when the device's animator scale is 0.
    @get:Rule
    val composeRule = createComposeRule(
        effectContext = object : MotionDurationScale {
            override val scaleFactor = 1f
        },
    )

    private class FakeDisplayCadence : DisplayCadenceSource {
        override var physicalHz: Float = 60f
        override var restHz: Float = 60f
        override var lastChangeAtMs: Long? = null
        private val events = MutableSharedFlow<Long>(extraBufferCapacity = 8)
        override val changes: Flow<Long> = events
        var blank = false

        override fun switchWouldBlank(requestedHz: Float): Boolean = blank

        fun emitChange(atMs: Long) {
            lastChangeAtMs = atMs
            events.tryEmit(atMs)
        }
    }

    private var stage by mutableStateOf(HouseStage.Room)
    private var filmDim by mutableFloatStateOf(0f)
    private var rate by mutableFloatStateOf(0f)

    // The pin the window is asked for at the seam; 0 models a pin released there.
    private var pin by mutableFloatStateOf(0f)
    private val cadence = FakeDisplayCadence()
    private val keys = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    private val staging = FilmStaging()
    private var filmView: View? = null

    // The retained face over the film's view: up until onLightsDown, like the real shell.
    private var roomShown by mutableStateOf(true)
    /** The film's rendered-frame count, as the player's decoder counters would report it. */
    @Volatile private var renderedFrames = 0
    private var lightsDown = 0
    private var up = 0
    private var settled = 0

    @Before
    fun freezeClock() {
        composeRule.mainClock.autoAdvance = false
    }

    private fun launch(
        initial: HouseStage = HouseStage.Room,
        reduced: Boolean = false,
        settle: Boolean = true,
        offstage: Boolean = false,
        leastShownMs: Long = FlickMotion.TUNNEL_MIN_SHOWN_MS,
        gateOnFrames: Boolean = false,
    ) {
        stage = initial
        composeRule.setContent {
            FlickTvTheme {
                CompositionLocalProvider(LocalReducedMotion provides reduced) {
                    Box(Modifier.fillMaxSize()) {
                        if (stage == HouseStage.Handshake || stage == HouseStage.Film) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { context ->
                                    View(context).apply {
                                        background = GradientDrawable(
                                            GradientDrawable.Orientation.TOP_BOTTOM,
                                            intArrayOf(FILM[0].toArgb(), FILM[1].toArgb()),
                                        )
                                        filmView = this
                                    }
                                },
                                onRelease = { if (filmView === it) filmView = null },
                            )
                        }
                        if (roomShown) Box(Modifier.fillMaxSize().drawBehind { drawRect(Brush.verticalGradient(STANDBY)) })
                        HouseLights(
                            stage = stage,
                            filmDim = filmDim,
                            freshRate = { rate },
                            pinnedRate = { pin },
                            deviceLabel = DEVICE,
                            title = TITLE,
                            cadence = cadence,
                            keyPresses = keys,
                            filmStaging = staging,
                            onLightsDown = {
                                lightsDown++
                                roomShown = false
                            },
                            onPictureUp = { up++ },
                            onPictureSettled = { settled++ },
                            now = { composeRule.mainClock.currentTime },
                            offstage = offstage,
                            leastShownMs = leastShownMs,
                            awaitFilmFlowing = {
                                if (gateOnFrames) {
                                    awaitFramesFlowing({ renderedFrames }, now = { composeRule.mainClock.currentTime })
                                }
                            },
                        )
                    }
                }
            }
        }
        if (settle) advance(LAUNCH_SETTLED_MS)
    }

    /** Exactly [ms] of virtual time, not rounded up to a whole test-clock frame. */
    private fun advance(ms: Long) = composeRule.mainClock.advanceTimeBy(ms, ignoreFrameDuration = true)

    /** One harness frame; the film's view must be whole and untouched after every one. */
    private fun frame() {
        composeRule.mainClock.advanceTimeByFrame()
        assertFilmUntouched()
    }

    private fun assertFilmUntouched() = composeRule.runOnIdle {
        filmView?.let {
            assertTrue(
                "the film's view was transformed: scale ${it.scaleX}x${it.scaleY} " +
                    "at ${it.translationX},${it.translationY} alpha ${it.alpha}",
                it.scaleX == 1f && it.scaleY == 1f && it.translationX == 0f && it.translationY == 0f && it.alpha == 1f,
            )
        }
    }

    /** Sets the stage and returns the seam's time; the write lands on the next frame. */
    private fun cut(to: HouseStage): Long {
        composeRule.runOnIdle {
            stage = to
            if (to == HouseStage.Room || to == HouseStage.Fault) roomShown = true
        }
        return composeRule.mainClock.currentTime
    }

    private fun since(seam: Long): Long = composeRule.mainClock.currentTime - seam

    private fun shot(test: String? = null, seam: Long = 0L): PixelMap {
        val image: ImageBitmap = composeRule.onRoot().captureToImage()
        if (test != null) {
            val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
            File(dir, "${test}_${since(seam)}.png").outputStream().use {
                image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        return image.toPixelMap()
    }

    /** Mean luma of a 9 × 9 patch, which also averages out the curtain's dither. */
    private fun PixelMap.luma(fx: Float, fy: Float): Float = lumaAt((fx * width).roundToInt(), (fy * height).roundToInt())

    private fun PixelMap.lumaAt(cx: Int, cy: Int): Float {
        var sum = 0f
        var n = 0
        for (y in (cy - 4)..(cy + 4)) {
            for (x in (cx - 4)..(cx + 4)) {
                if (x !in 0 until width || y !in 0 until height) continue
                sum += lumaOf(this[x, y])
                n++
            }
        }
        return sum / n
    }

    /**
     * Total variation along the headline's middle row. Flat ground sums to a handful;
     * glyphs at any visible alpha add a sharp step per stroke.
     */
    private fun PixelMap.glyphEnergy(row: Rect): Float {
        val y = row.center.y.roundToInt().coerceIn(0, height - 1)
        val from = row.left.roundToInt().coerceIn(0, width - 1)
        val to = row.right.roundToInt().coerceIn(0, width - 1)
        var total = 0f
        for (x in from until to) total += abs(lumaOf(this[x + 1, y]) - lumaOf(this[x, y]))
        return total
    }

    private fun headlineBounds(): Rect =
        composeRule.onNodeWithText(HEADLINE).fetchSemanticsNode().boundsInRoot

    private fun tunnelExists(): Boolean =
        composeRule.onAllNodesWithTag(TUNNEL_TAG).fetchSemanticsNodes().isNotEmpty()


    /**
     * Whether the film shows here. Through the porthole that is the hole the tunnel punches
     * through the window's buffer down to the film's surface, which a capture of the window
     * reads as transparent (a 35 % flash over it stays well under half). Once the tunnel is gone it is
     * the film stand-in's own deep red, which nothing of the tunnel, the room or the flash
     * reads as.
     */
    private fun PixelMap.filmAt(x: Int, y: Int): Boolean {
        val c = this[x.coerceIn(0, width - 1), y.coerceIn(0, height - 1)]
        val hole = c.alpha < 0.5f
        // Red over green rules out the amber ring and streaks, even under the flash.
        val film = c.red > 0.6f && c.red - c.blue > 0.25f && c.red > 1.8f * c.green
        return hole || film
    }

    private fun PixelMap.windowShowsFilm(): Boolean =
        filmAt(width / 2, (TUNNEL_VANISH_Y * height).roundToInt())

    /** The porthole's lit pane: a pale blue, well above the wash. */
    private fun PixelMap.windowGlows(): Boolean {
        val c = this[width / 2, (TUNNEL_VANISH_Y * height).roundToInt()]
        return c.alpha > 0.9f && c.blue - c.red > 0.2f && lumaOf(c) > 0.45f
    }

    /** The porthole's left edge on the vanishing point's row: the first film pixel, or null if none shows. */
    private fun PixelMap.portholeLeft(): Int? {
        val y = (TUNNEL_VANISH_Y * height).roundToInt()
        return (0..width / 2).firstOrNull { filmAt(it, y) }
    }

    /** The porthole's left edge with the window at [approach] of its full size. */
    private fun windowLeft(px: PixelMap, approach: Float = 1f): Float =
        px.width * (1f - TUNNEL_WINDOW_FRACTION * approach) / 2f

    // Past the tunnel's least time on screen, so a reveal waits on the display alone.
    private fun settleHandshake() {
        cut(HouseStage.Handshake)
        advance(1_600)
    }

    // The whole show: the rate wait, the film's dwell in the window and the expansion.
    private fun settleFilm() {
        settleHandshake()
        cut(HouseStage.Film)
        advance(RATE_KNOWN_WAIT_MS + 2_600)
    }

    @Test
    fun theTunnelRisesOverTheRoom() {
        launch()
        val room = shot().luma(0.05f, 0.05f)
        val seam = cut(HouseStage.Handshake)
        var captionSeenAt: Long? = null
        var lightsDownAt: Long? = null
        while (since(seam) < 900) {
            frame()
            val t = since(seam)
            val px = shot("theTunnelRisesOverTheRoom", seam)
            assertTrue("the tunnel's overlay must be composed from the seam's first frame (t=$t)", tunnelExists())
            assertTrue("the film showed through the porthole before its first frame (t=$t)", !px.windowShowsFilm())
            if (captionSeenAt == null && px.glyphEnergy(headlineBounds()) > GLYPHS_VISIBLE) captionSeenAt = t
            if (lightsDownAt == null && lightsDown == 1) lightsDownAt = t
        }
        // Two frames of dispatch and one to stamp the tunnel's first frame sit before its fade.
        val fade = FlickMotion.TUNNEL_FADE_IN_MS
        assertTrue("onLightsDown ran at $lightsDownAt ms, not about $fade", lightsDownAt != null && lightsDownAt in fade..fade + 80)
        assertEquals(1, lightsDown)
        val captionFrom = FlickMotion.TUNNEL_CAPTION_DELAY_MS
        assertTrue("the copy showed at $captionSeenAt ms, not soon after $captionFrom",
            captionSeenAt != null && captionSeenAt in captionFrom..captionFrom + 300)
        val px = shot("theTunnelRisesOverTheRoom", seam)
        assertTrue("the room still showed at the corner: $room → ${px.luma(0.05f, 0.05f)}", px.luma(0.05f, 0.05f) < WASH_DARK)
        assertTrue("the porthole does not glow", px.windowGlows())
        // The window's approach never grows it past its full size, which the copy sits under.
        val windowBottom = (TUNNEL_VANISH_Y + TUNNEL_WINDOW_FRACTION / 2f) * px.height
        assertTrue("the copy is under the window's reach", headlineBounds().top > windowBottom)
    }

    @Test
    fun theFilmPlaysInTheWindowThenTakesTheScreen() {
        rate = 60f
        cadence.physicalHz = 60f
        cadence.blank = false
        launch()
        settleHandshake()
        val seam = cut(HouseStage.Film)
        var upAt: Long? = null
        var filmInAt: Long? = null
        var crossfading = false
        var settledAt: Long? = null
        var lastLeft = Int.MAX_VALUE / 2
        var expandFrom: Long? = null
        val lefts = mutableListOf<Pair<Long, Int>>()
        while (since(seam) < 2_400) {
            frame()
            val t = since(seam)
            val px = shot("theFilmPlaysInTheWindowThenTakesTheScreen", seam)
            if (upAt == null && up == 1) upAt = t
            val windowY = (TUNNEL_VANISH_Y * px.height).roundToInt()
            val centre = px[px.width / 2, windowY]
            // The lit pane part-way gone over the porthole: the two mid-crossfade.
            if (upAt != null && filmInAt == null && centre.alpha in 0.1f..0.9f) crossfading = true
            if (filmInAt == null && px.windowShowsFilm()) filmInAt = t
            val left = px.portholeLeft()
            if (left != null) {
                assertTrue("the porthole narrowed at $t ms: $lastLeft → $left", left <= lastLeft + 2)
                lastLeft = left
                if (expandFrom == null && left < windowLeft(px) - 4f) expandFrom = t
            }
            if (left != null) lefts += t to left
            if (filmInAt != null && expandFrom == null && t - filmInAt in 40L until FlickMotion.TUNNEL_DWELL_MS - 300) {
                // In the porthole: the full-size film's own centre, the tunnel all round it,
                // and the window still on its approach.
                assertTrue("the porthole is not the approaching window at $t ms (centre $centre): $left",
                    left!! >= windowLeft(px) - 3f && left <= windowLeft(px, FlickMotion.TUNNEL_APPROACH_FROM) + 3f)
                assertTrue("the tunnel is not around the porthole at $t ms", px.luma(0.05f, 0.05f) < WASH_DARK)
                assertEquals("onPictureSettled during the dwell", 0, settled)
            }
            if (settledAt == null && settled == 1) settledAt = t
        }
        assertEquals("onPictureUp exactly once", 1, up)
        assertTrue("the picture came up at $upAt ms, not within three frames", upAt != null && upAt <= 3 * HARNESS_FRAME_MS.toLong() + 2)
        assertTrue("the pane cut to the film instead of crossfading", crossfading)
        assertTrue("the film showed through at $filmInAt ms", filmInAt != null && filmInAt - upAt!! <= FlickMotion.TUNNEL_FILM_IN_MS + 40)
        val show = FlickMotion.TUNNEL_DWELL_MS + FlickMotion.TUNNEL_EXPAND_MS + FlickMotion.TUNNEL_SETTLE_MS
        val took = settledAt!! - upAt!!
        assertTrue("the dwell and expansion took $took ms, not about $show", took in show..show + 120)
        // The expansion leaves at the approach's own speed: no frame around its start jumps.
        val expandAt = upAt!! + FlickMotion.TUNNEL_DWELL_MS
        lefts.zipWithNext().filter { (a, _) -> a.first in expandAt - 48..expandAt + 48 }.forEach { (a, b) ->
            assertTrue("the porthole jumped ${a.second - b.second} px at ${b.first} ms", a.second - b.second <= 3)
        }
        assertTrue("the tunnel outlived the expansion", !tunnelExists())
        val film = shot("theFilmPlaysInTheWindowThenTakesTheScreen", seam)
        assertEquals("the porthole did not open onto the whole screen", 0, film.portholeLeft())
        assertEquals("the film does not fill the screen", lumaOf(filmAt(0.05f)), film.luma(0.5f, 0.05f), 0.03f)
    }


    @Test
    fun theWindowTakesTheScreenWithAFlash() {
        rate = 60f
        cadence.physicalHz = 60f
        cadence.blank = false
        launch()
        settleHandshake()
        val seam = cut(HouseStage.Film)
        while (up == 0) frame()
        advance(FlickMotion.TUNNEL_DWELL_MS - 40)
        val before = shot("theWindowTakesTheScreenWithAFlash", seam).luma(0.05f, 0.05f)
        var peak = before
        while (since(seam) < FlickMotion.TUNNEL_DWELL_MS + 200) {
            frame()
            peak = maxOf(peak, shot("theWindowTakesTheScreenWithAFlash", seam).luma(0.05f, 0.05f))
        }
        assertTrue("no flash at the corner: $before → $peak", peak - before > 0.2f)
    }

    @Test
    fun aKeyEndsTheDwell() {
        rate = 60f
        cadence.physicalHz = 60f
        cadence.blank = false
        launch()
        settleHandshake()
        cut(HouseStage.Film)
        while (up == 0) frame()
        advance(400)
        val before = shot().portholeLeft()!!
        val key = composeRule.mainClock.currentTime
        keys.tryEmit(Unit)
        // The expansion leaves slowly by design; ten frames in it has clearly gone.
        repeat(10) { frame() }
        val after = shot().portholeLeft()!!
        assertTrue("a key must start the expansion at once: $before → $after", before - after > 8)
        while (settled == 0 && since(key) < 1_400) frame()
        assertTrue("settled ${since(key)} ms after the key",
            since(key) <= FlickMotion.TUNNEL_EXPAND_MS + FlickMotion.TUNNEL_SETTLE_MS + 80)
    }


    /** A re-cast landing while the last film still dwells in the window. */
    @Test
    fun aReCastDuringTheDwellDrawsTheFilmBackWithoutGrowingIt() {
        rate = 60f
        cadence.physicalHz = 60f
        cadence.blank = false
        launch()
        settleHandshake()
        cut(HouseStage.Film)
        while (up == 0) frame()
        advance(500)
        val seam = cut(HouseStage.Handshake)
        var captionSeenAt: Long? = null
        while (since(seam) < 1_000) {
            frame()
            val t = since(seam)
            val px = shot("aReCastDuringTheDwellDrawsTheFilmBackWithoutGrowingIt", seam)
            px.portholeLeft()?.let {
                assertTrue("the porthole widened at $t ms: $it", it >= windowLeft(px) - 3f)
            }
            if (captionSeenAt == null && px.glyphEnergy(headlineBounds()) > GLYPHS_VISIBLE) captionSeenAt = t
        }
        assertTrue("the headline never came back", captionSeenAt != null)
        val px = shot()
        assertTrue("the old film still shows in the porthole", !px.windowShowsFilm())
        assertTrue("the tunnel is not around the porthole", px.luma(0.05f, 0.05f) < WASH_DARK)
        assertTrue("the porthole does not glow", px.windowGlows())
    }


    /** Stop then loadMedia while the last film's window is still taking the screen. */
    @Test
    fun aStopAndReCastMidExpansionStartsTheTunnelWhole() {
        rate = 60f
        cadence.physicalHz = 60f
        cadence.blank = false
        launch()
        settleHandshake()
        cut(HouseStage.Film)
        while (up == 0) frame()
        advance(FlickMotion.TUNNEL_DWELL_MS + 300)
        val mid = shot()
        val midLeft = mid.portholeLeft()
        assertTrue("not mid-expansion: $midLeft", midLeft != null && midLeft in 1 until windowLeft(mid).toInt())
        cut(HouseStage.Room)
        advance(100)
        val seam = cut(HouseStage.Handshake)
        var captionSeenAt: Long? = null
        while (since(seam) < 900) {
            frame()
            val t = since(seam)
            val px = shot("aStopAndReCastMidExpansionStartsTheTunnelWhole", seam)
            if (captionSeenAt == null && px.glyphEnergy(headlineBounds()) > GLYPHS_VISIBLE) captionSeenAt = t
            if (t > 40) assertTrue("the film showed through the new handshake at $t ms", !px.windowShowsFilm())
        }
        assertTrue("the headline never came back", captionSeenAt != null)
        val px = shot()
        // The tunnel back at full strength and scale, and no flash left over it.
        assertTrue("the tunnel did not come back over the corner: ${px.luma(0.05f, 0.05f)}", px.luma(0.05f, 0.05f) < WASH_DARK)
        assertTrue("the porthole does not glow", px.windowGlows())
        assertTrue("the tunnel was not left up for the handshake", tunnelExists())
    }


    /** The tunnel is drawn in the window's own draw pass: no views of its own, and so no view layers or drawables. */
    @Test
    fun theTunnelDrawsWithoutViewsOrLayers() {
        rate = 60f
        cadence.physicalHz = 60f
        cadence.blank = false
        launch()
        settleHandshake()
        assertNoTunnelViews()
        cut(HouseStage.Film)
        while (up == 0) frame()
        advance(FlickMotion.TUNNEL_DWELL_MS + 300)
        assertNoTunnelViews()
    }

    private fun assertNoTunnelViews() = composeRule.runOnIdle {
        val all = generateSequence(listOf(filmView!!.rootView)) { level ->
            level.flatMap { v -> if (v is android.view.ViewGroup) (0 until v.childCount).map(v::getChildAt) else emptyList() }
                .ifEmpty { null }
        }.flatten().toList()
        assertTrue("a view draws a drawable in the show", all.none { it is android.widget.ImageView })
        assertTrue("a view in the show has a layer of its own", all.all { it.layerType == View.LAYER_TYPE_NONE })
    }

    /** The porthole opens only once the released film's frames are reaching the screen. */
    @Test
    fun thePaneWaitsForTheFilmsFramesToFlow() {
        rate = 60f
        cadence.physicalHz = 60f
        cadence.blank = false
        launch(gateOnFrames = true)
        settleHandshake()
        renderedFrames = 1
        val seam = cut(HouseStage.Film)
        while (up == 0) frame()
        advance(250)
        assertTrue("the porthole opened before the film's frames flowed", shot().windowGlows())
        renderedFrames = 2
        repeat(3) { frame() }
        assertTrue("the porthole opened on one frame", shot().windowGlows())
        renderedFrames = 3
        repeat(3) { frame() }
        advance(FlickMotion.TUNNEL_FILM_IN_MS + 40L)
        assertTrue("the film did not show through once its frames flowed (${since(seam)} ms)", shot().windowShowsFilm())
    }

    /** A film that stalls at once is still shown, once the wait for its frames runs out. */
    @Test
    fun thePaneGivesWayToAStalledFilmAfterItsWait() {
        rate = 60f
        cadence.physicalHz = 60f
        cadence.blank = false
        launch(gateOnFrames = true)
        settleHandshake()
        cut(HouseStage.Film)
        while (up == 0) frame()
        advance(FILM_FLOWING_WAIT_MS - 60)
        assertTrue("the porthole opened before the wait ran out", shot().windowGlows())
        advance(60 + FlickMotion.TUNNEL_FILM_IN_MS + 80L)
        assertTrue("the porthole never opened onto a stalled film", shot().windowShowsFilm())
    }

    @Test
    fun revealWaitsForTheModeSwitch() {
        rate = 23.976f
        cadence.physicalHz = 60f
        cadence.blank = true
        launch(leastShownMs = 0L)
        settleHandshake()
        cut(HouseStage.Film)
        advance(400)
        assertEquals(0, up)
        assertTrue("the film showed inside the hold", !shot().windowShowsFilm())
        cadence.physicalHz = 23.976f
        cadence.emitChange(composeRule.mainClock.currentTime)
        advance(499)
        assertEquals("the picture came up inside the resync grace", 0, up)
        assertTrue("the film showed inside the resync grace", !shot().windowShowsFilm())
        frame()
        frame()
        assertEquals("the picture did not come up once the grace ran out", 1, up)
        advance(FlickMotion.TUNNEL_FILM_IN_MS + 40L)
        assertTrue("the film did not show through once the picture came up", shot().windowShowsFilm())
    }

    @Test
    fun revealWaitsForALateRate() {
        // The film's rate reaches the stage up to one 2 Hz snapshot after Active.
        rate = 0f
        cadence.physicalHz = 60f
        cadence.blank = true
        launch(leastShownMs = 0L)
        settleHandshake()
        val seam = cut(HouseStage.Film)
        advance(300)
        assertEquals(0, up)
        composeRule.runOnIdle { rate = 23.976f }
        advance(150)
        assertEquals("the picture came up before the late rate's switch", 0, up)
        cadence.physicalHz = 23.976f
        val change = composeRule.mainClock.currentTime
        cadence.emitChange(change)
        while (composeRule.mainClock.currentTime - change < 484) {
            frame()
            assertEquals("the picture came up inside the resync grace at ${since(seam)} ms", 0, up)
        }
        advance(499 - (composeRule.mainClock.currentTime - change))
        assertEquals("the picture came up inside the resync grace", 0, up)
        frame()
        frame()
        assertEquals("the picture did not come up once the grace ran out", 1, up)
    }

    @Test
    fun reCastWaitsForTheSecondSwitch() {
        // A re-cast of a 23.976 film: the panel is already pinned to it, the seam
        // releases the pin (to 60), and the pin is re-requested once the rate lands.
        rate = 23.976f
        cadence.physicalHz = 23.976f
        cadence.blank = true
        launch(leastShownMs = 0L)
        settleHandshake()
        cadence.physicalHz = 60f
        val release = composeRule.mainClock.currentTime
        cadence.emitChange(release)
        val seam = cut(HouseStage.Film)
        while (composeRule.mainClock.currentTime - release < 600) {
            frame()
            assertEquals("the picture came up between the two switches at ${since(seam)} ms", 0, up)
        }
        cadence.physicalHz = 23.976f
        val repin = composeRule.mainClock.currentTime
        cadence.emitChange(repin)
        while (composeRule.mainClock.currentTime - repin < 484) {
            frame()
            assertEquals("the picture came up inside the second resync grace at ${since(seam)} ms", 0, up)
        }
        advance(499 - (composeRule.mainClock.currentTime - repin))
        assertEquals("the picture came up inside the second resync grace", 0, up)
        frame()
        frame()
        assertEquals("the picture did not come up once the second grace ran out", 1, up)
    }

    @Test
    fun reCastWaitsForAReleaseReportedAfterTheRate() {
        // A re-cast of a 23.976 film whose rate reaches the stage before the release
        // the seam made is reported: the release and the re-pin both still land.
        rate = 0f
        cadence.physicalHz = 23.976f
        cadence.restHz = 60f
        cadence.blank = true
        launch(leastShownMs = 0L)
        settleHandshake()
        val seam = cut(HouseStage.Film)
        advance(200)
        composeRule.runOnIdle { rate = 23.976f }
        frame()
        assertEquals("the picture came up when the rate landed", 0, up)
        while (since(seam) < 400) {
            frame()
            assertEquals("the picture came up before the release at ${since(seam)} ms", 0, up)
        }
        cadence.physicalHz = 60f
        cadence.emitChange(composeRule.mainClock.currentTime)
        while (since(seam) < 1_000) {
            frame()
            assertEquals("the picture came up between the two switches at ${since(seam)} ms", 0, up)
        }
        cadence.physicalHz = 23.976f
        val repin = composeRule.mainClock.currentTime
        cadence.emitChange(repin)
        while (composeRule.mainClock.currentTime - repin < 484) {
            frame()
            assertEquals("the picture came up inside the re-pin grace at ${since(seam)} ms", 0, up)
        }
        advance(499 - (composeRule.mainClock.currentTime - repin))
        assertEquals("the picture came up inside the re-pin grace", 0, up)
        frame()
        frame()
        assertEquals("the picture did not come up once the re-pin grace ran out", 1, up)
    }

    @Test
    fun sameCadenceReCastWithAHeldPinDoesNotHold() {
        // A re-cast at the cadence the window is still pinned to: nothing switches, so
        // the rate landing late must not start a wait for a release that never comes.
        pin = 23.976f
        rate = 0f
        cadence.physicalHz = 23.976f
        cadence.restHz = 60f
        cadence.blank = true
        launch()
        settleHandshake()
        val seam = cut(HouseStage.Film)
        advance(300)
        assertEquals("the picture came up before the rate landed", 0, up)
        composeRule.runOnIdle { rate = 23.976f }
        frame()
        frame()
        assertEquals("the picture did not come up within two frames of the rate (${since(seam)} ms)", 1, up)
        assertTrue("the picture came up at ${since(seam)} ms, not well before 800", since(seam) < 800)
    }

    @Test
    fun rateLessReCastWaitsForAPinReleasedAfterTheSeam() {
        // The seam's snapshot still holds the old film's pin; the new film reports no
        // rate, so the pin is given up a tick later and its switch is reported late.
        pin = 23.976f
        rate = 0f
        cadence.physicalHz = 23.976f
        cadence.restHz = 60f
        cadence.blank = true
        launch(leastShownMs = 0L)
        settleHandshake()
        val seam = cut(HouseStage.Film)
        advance(200)
        composeRule.runOnIdle { pin = 0f }
        advance(750 - since(seam))
        assertEquals("the picture came up before the release was reported", 0, up)
        cadence.physicalHz = 60f
        val change = composeRule.mainClock.currentTime
        cadence.emitChange(change)
        while (composeRule.mainClock.currentTime - change < 484) {
            frame()
            assertEquals("the picture came up inside the resync grace at ${since(seam)} ms", 0, up)
        }
        advance(499 - (composeRule.mainClock.currentTime - change))
        assertEquals("the picture came up inside the resync grace", 0, up)
        frame()
        frame()
        assertEquals("the picture did not come up once the grace ran out", 1, up)
    }

    @Test
    fun aCastDuringAFaultDissolveHandsOverToTheTunnel() {
        launch()
        settleFilm()
        cadence.physicalHz = 24f
        cadence.restHz = 60f
        val fault = cut(HouseStage.Fault)
        advance(200)
        cadence.physicalHz = 60f
        cadence.emitChange(composeRule.mainClock.currentTime)
        // The fault's curtain is flat, so any point reads its density.
        var d = 1f
        while (d > 0.5f) {
            frame()
            assertTrue("the fault never dissolved to half (${since(fault)} ms)", since(fault) < 2_500)
            d = shot().densityAt(fx = 0.5f, fy = 0.1f)
        }
        val lightsDownBefore = lightsDown
        val seam = cut(HouseStage.Handshake)
        // The seam's own frame still ticks the dissolve before the new move seeds from it.
        frame()
        val half = shot().luma(0.05f, 0.05f)
        var last = half
        while (since(seam) < 700) {
            frame()
            val now = shot("aCastDuringAFaultDissolveHandsOverToTheTunnel", seam).luma(0.05f, 0.05f)
            // The half-dissolved fault never shows brighter than it was at the seam.
            assertTrue("the fault brightened from $half to $now at ${since(seam)} ms", now <= half + NOISE)
            if (since(seam) < FlickMotion.TUNNEL_FADE_IN_MS) {
                assertEquals("onLightsDown before the tunnel was in", lightsDownBefore, lightsDown)
            }
            last = now
        }
        assertEquals("onLightsDown must fire exactly once", lightsDownBefore + 1, lightsDown)
        assertTrue("the tunnel did not cover the fault: $last", last < WASH_DARK)
    }

    /** The density of a flat curtain over the room at a point, from the room's luma there. */
    private fun PixelMap.densityAt(fx: Float, fy: Float): Float {
        val under = lumaOf(STANDBY[0]) + (lumaOf(STANDBY[1]) - lumaOf(STANDBY[0])) * fy
        return (under - luma(fx, fy)) / (under - lumaOf(FlickColor.Canvas))
    }

    /** A re-cast arrives as stop then loadMedia, so the Room lasts only a moment. */
    @Test
    fun aStopThenCastStillRunsTheTunnel() {
        launch()
        settleFilm()
        cut(HouseStage.Room)
        advance(250)
        val lightsDownBefore = lightsDown
        val seam = cut(HouseStage.Handshake)
        while (since(seam) < 700) {
            frame()
            shot("aStopThenCastStillRunsTheTunnel", seam)
        }
        val px = shot()
        assertTrue("the tunnel did not rise", px.luma(0.05f, 0.05f) < WASH_DARK)
        assertTrue("the porthole does not glow", px.windowGlows())
        assertEquals(lightsDownBefore + 1, lightsDown)
        assertTrue("the tunnel was not left up for the handshake", tunnelExists())
    }

    /** An Activity created for a cast composes straight into the handshake. */
    @Test
    fun aFirstCompositionIntoAHandshakeRunsTheTunnel() {
        launch(initial = HouseStage.Handshake, settle = false)
        val start = composeRule.mainClock.currentTime
        while (since(start) < 700) {
            frame()
            val px = shot("aFirstCompositionIntoAHandshakeRunsTheTunnel", start)
            assertTrue("the film showed through before its first frame", !px.windowShowsFilm())
            if (since(start) in 40L until FlickMotion.TUNNEL_FADE_IN_MS) assertEquals("onLightsDown must fire at once", 1, lightsDown)
        }
        assertEquals(1, lightsDown)
        val px = shot()
        assertTrue("the tunnel did not rise", px.luma(0.05f, 0.05f) < WASH_DARK)
        assertTrue("the porthole does not glow", px.windowGlows())
        assertTrue("the tunnel was not left up for the handshake", tunnelExists())
    }

    /** A re-cast whose first frame lands 400 ms into the handshake, while the old film is still being drawn back. */
    @Test
    fun aQuickFirstFrameWaitsForTheTunnelsLeastTime() {
        rate = 60f
        cadence.physicalHz = 60f
        cadence.restHz = 60f
        cadence.blank = false
        launch()
        settleFilm()
        val upBefore = up
        val handshake = cut(HouseStage.Handshake)
        advance(400)
        cut(HouseStage.Film)
        // The tunnel starts a frame or two into the handshake and owes 1100 ms from there.
        while (since(handshake) < FlickMotion.TUNNEL_MIN_SHOWN_MS) {
            frame()
            val t = since(handshake)
            val px = shot("aQuickFirstFrameWaitsForTheTunnelsLeastTime", handshake)
            assertEquals("the picture came up before the tunnel's least time at $t ms", upBefore, up)
            if (t > 400 + 2 * FlickMotion.TUNNEL_FILM_IN_MS + 40) {
                assertTrue("the held first frame showed in the porthole at $t ms", !px.windowShowsFilm())
                assertTrue("the porthole does not glow at $t ms", px.windowGlows())
            }
        }
        while (up == upBefore && since(handshake) < 2_000) frame()
        assertEquals("the picture never came up once the tunnel had its time", upBefore + 1, up)
        assertTrue("the picture came up at ${since(handshake)} ms, not soon after its least time",
            since(handshake) < FlickMotion.TUNNEL_MIN_SHOWN_MS + 100)
    }

    /**
     * The pin at the first frame blanks the panel 200 ms into the reveal. The tunnel had
     * long since had its time, but the blank hid it, so it owes its least time again from
     * the switch's settle before the film appears in it.
     */
    @Test
    fun aModeSwitchRestartsTheTunnelsLeastTime() {
        rate = 23.976f
        cadence.physicalHz = 60f
        cadence.blank = true
        launch()
        settleHandshake()
        val seam = cut(HouseStage.Film)
        advance(200)
        cadence.physicalHz = 23.976f
        val change = composeRule.mainClock.currentTime
        cadence.emitChange(change)
        val owed = RESYNC_GRACE_MS + FlickMotion.TUNNEL_MIN_SHOWN_MS
        while (composeRule.mainClock.currentTime - change < owed - 20) {
            frame()
            assertEquals("the picture came up inside the tunnel's restarted time at ${since(seam)} ms", 0, up)
            assertTrue("the tunnel left before the film appeared at ${since(seam)} ms", tunnelExists())
        }
        advance(owed - (composeRule.mainClock.currentTime - change))
        frame()
        frame()
        assertEquals("the picture did not come up once the tunnel had its time after the switch", 1, up)
    }

    @Test
    fun aCastPlannedOffstageOpensDark() {
        launch(offstage = true)
        val seam = cut(HouseStage.Handshake)
        frame()
        val first = shot("aCastPlannedOffstageOpensDark", seam)
        val canvas = lumaOf(FlickColor.Canvas)
        assertEquals("the corner was not opaque Canvas on the first frame", canvas, first.luma(0.05f, 0.05f), NOISE)
        assertEquals("the top was not opaque Canvas on the first frame", canvas, first.luma(0.5f, 0.1f), NOISE)
        assertEquals("onLightsDown must fire at once", 1, lightsDown)
        assertTrue("the tunnel's overlay was not composed", tunnelExists())
    }

    @Test
    fun revealStopsWaitingForARateThatNeverComes() {
        rate = 0f
        cadence.physicalHz = 60f
        cadence.blank = true
        launch()
        settleHandshake()
        val seam = cut(HouseStage.Film)
        advance(RATE_KNOWN_WAIT_MS - 100)
        assertEquals("the picture came up before the rate wait ran out", 0, up)
        advance(100)
        frame()
        frame()
        assertEquals("onPictureUp by the rate wait plus two frames (${since(seam)} ms)", 1, up)
    }

    @Test
    fun aFaultMidHandshakeDismissesTheTunnel() {
        launch()
        cut(HouseStage.Handshake)
        advance(250)
        frame()
        val seam = cut(HouseStage.Fault)
        while (since(seam) < 600) {
            frame()
            shot("aFaultMidHandshakeDismissesTheTunnel", seam)
        }
        assertTrue("the tunnel outlived the fault", !tunnelExists())
        val px = shot()
        val room = lumaOf(STANDBY[0]) + (lumaOf(STANDBY[1]) - lumaOf(STANDBY[0])) * 0.05f
        assertEquals("the fault face is not uncovered", room, px.luma(0.5f, 0.05f), 0.03f)
    }

    @Test
    fun aKeyEndsTheHold() {
        rate = 23.976f
        cadence.physicalHz = 60f
        cadence.blank = true
        launch()
        settleHandshake()
        cut(HouseStage.Film)
        advance(200)
        assertEquals(0, up)
        keys.tryEmit(Unit)
        frame()
        frame()
        assertEquals("a key must end the hold at once", 1, up)
    }

    @Test
    fun reCastDrawsTheOldFilmBackIntoTheWindow() {
        launch()
        settleFilm()
        val film = shot().luma(0.5f, 0.1f)
        val canvas = lumaOf(FlickColor.Canvas)
        composeRule.runOnIdle { filmDim = 0.34f }
        frame()
        val seam = cut(HouseStage.Handshake)
        frame()
        val first = shot("reCastDrawsTheOldFilmBackIntoTheWindow", seam).luma(0.5f, 0.1f)
        assertEquals("the dim must start at the paused film's", film + (canvas - film) * 0.34f, first, 0.03f)
        var lastLeft = 0
        var closedOnWindow = false
        while (since(seam) < FlickMotion.TUNNEL_SHRINK_MS + 2 * FlickMotion.TUNNEL_FILM_IN_MS + 100) {
            frame()
            val px = shot("reCastDrawsTheOldFilmBackIntoTheWindow", seam)
            val left = px.portholeLeft() ?: continue
            assertTrue("the porthole widened at ${since(seam)} ms: $lastLeft → $left", left >= lastLeft - 2)
            lastLeft = left
            if (left >= windowLeft(px) - 3f) closedOnWindow = true
        }
        assertTrue("the porthole never closed onto the window", closedOnWindow)
        val px = shot()
        assertTrue("the old film still shows in the porthole", !px.windowShowsFilm())
        assertTrue("the tunnel did not rise around the window", px.luma(0.05f, 0.05f) < WASH_DARK)
        assertTrue("the porthole does not glow", px.windowGlows())
    }

    @Test
    fun lightsUpHoldsThenOpensFromTheCentre() {
        launch()
        settleFilm()
        cadence.physicalHz = 24f
        cadence.restHz = 60f
        val seam = cut(HouseStage.Room)
        val canvas = lumaOf(FlickColor.Canvas)
        while (since(seam) < 400) {
            frame()
            val px = shot("lightsUpHoldsThenOpensFromTheCentre", seam)
            for ((fx, fy) in listOf(0.05f to 0.05f, 0.5f to 0.45f, 0.95f to 0.95f)) {
                assertEquals("uncovered at ($fx, $fy) during the hold", canvas, px.luma(fx, fy), 0.02f)
            }
        }
        cadence.physicalHz = 60f
        cadence.emitChange(composeRule.mainClock.currentTime)
        var centreLitAt: Long? = null
        var cornerLitAt: Long? = null
        while (since(seam) < 1_800) {
            frame()
            val t = since(seam)
            val px = shot("lightsUpHoldsThenOpensFromTheCentre", seam)
            if (centreLitAt == null && px.luma(0.5f, 0.45f) > canvas + 0.05f) centreLitAt = t
            if (cornerLitAt == null && px.luma(0.05f, 0.05f) > canvas + 0.05f) cornerLitAt = t
        }
        assertTrue("the room lit before the resync grace ran out ($centreLitAt ms)", centreLitAt != null && centreLitAt >= 900)
        assertTrue("centre lit at $centreLitAt, not before the corner at $cornerLitAt",
            cornerLitAt != null && centreLitAt!! < cornerLitAt)
    }

    @Test
    fun launchOpensFromTheCentre() {
        launch(settle = false)
        val seam = composeRule.mainClock.currentTime
        val canvas = lumaOf(FlickColor.Canvas)
        val first = shot("launchOpensFromTheCentre", seam)
        assertEquals("the first frame must match the window background", canvas, first.luma(0.5f, 0.45f), 0.02f)
        assertEquals(canvas, first.luma(0.05f, 0.05f), 0.02f)
        var centreLitAt: Long? = null
        var cornerLitAt: Long? = null
        while (since(seam) < 800) {
            frame()
            val t = since(seam)
            val px = shot("launchOpensFromTheCentre", seam)
            if (centreLitAt == null && px.luma(0.5f, 0.45f) > canvas + 0.05f) centreLitAt = t
            if (cornerLitAt == null && px.luma(0.05f, 0.05f) > canvas + 0.05f) cornerLitAt = t
        }
        assertTrue("centre lit at $centreLitAt, not before the corner at $cornerLitAt",
            centreLitAt != null && cornerLitAt != null && centreLitAt < cornerLitAt)
    }

    @Test
    fun faultDissolvesUniformly() {
        launch()
        settleHandshake()
        val seam = cut(HouseStage.Fault)
        val canvas = lumaOf(FlickColor.Canvas)
        val corner = mutableListOf<Float>()
        val centre = mutableListOf<Float>()
        while (since(seam) < 700) {
            frame()
            val px = shot("faultDissolvesUniformly", seam)
            // The outgoing tunnel's overlay sits over the curtain for its short exit;
            // only the curtain is being judged.
            if (tunnelExists()) continue
            corner += px.luma(0.05f, 0.05f)
            centre += px.luma(0.5f, 0.45f)
        }
        assertTrue("no frames after the tunnel left", corner.size > 4)
        // The two points sit on different parts of the ramp, so each is read as its
        // progress from Canvas to its own lit value.
        val cornerLit = corner.last()
        val centreLit = centre.last()
        corner.indices.forEach { i ->
            val a = (corner[i] - canvas) / (cornerLit - canvas)
            val b = (centre[i] - canvas) / (centreLit - canvas)
            assertEquals("corner and centre parted at sample $i", a, b, 0.05f)
        }
    }

    @Test
    fun handshakeSemanticsLeaveOnTheFirstExitFrame() {
        launch()
        settleHandshake()
        composeRule.onNodeWithText(HEADLINE).assertExists()
        cut(HouseStage.Film)
        frame()
        composeRule.onNodeWithText(HEADLINE).assertDoesNotExist()
        assertTrue("the tunnel must still be drawing its exit", tunnelExists())
    }

    @Test
    fun reducedMotionReachesEachEndStateInOneStep() {
        rate = 23.976f
        cadence.physicalHz = 60f
        cadence.blank = true
        launch(reduced = true, settle = false)
        oneStep()
        val standby = shot("reduced", 0L)
        assertTrue("launch must not cover the room", standby.luma(0.5f, 0.45f) > 0.5f)

        cut(HouseStage.Handshake)
        oneStep()
        assertEquals(1, lightsDown)
        assertTrue(tunnelExists())
        composeRule.onNodeWithText(HEADLINE).assertExists()

        cut(HouseStage.Film)
        oneStep()
        assertEquals("the pending switch must not hold a reduced-motion reveal", 1, up)
        assertEquals(1, settled)
        assertTrue(!tunnelExists())
        assertTrue(shot().windowShowsFilm())
        val film = shot().luma(0.5f, 0.1f)

        composeRule.runOnIdle { filmDim = 0.5f }
        cut(HouseStage.Handshake)
        oneStep()
        val veil = shot().luma(0.5f, 0.1f)
        val canvas = lumaOf(FlickColor.Canvas)
        assertEquals(film + (canvas - film) * VEIL_DENSITY, veil, 0.03f)

        cut(HouseStage.Fault)
        oneStep()
        assertTrue(!tunnelExists())
        assertTrue("a fault must not stay covered", shot().luma(0.05f, 0.05f) > 0.5f)

        cut(HouseStage.Room)
        oneStep()
        assertTrue(shot().luma(0.5f, 0.45f) > 0.5f)
    }

    /** The frame that composes a change launches its effect; the next one draws what it snapped. */
    private fun oneStep() {
        frame()
        frame()
    }

    /** The film stand-in's colour [fy] of the way down it. */
    private fun filmAt(fy: Float): Color = lerpColor(FILM[0], FILM[1], fy)

    private fun lerpColor(a: Color, b: Color, t: Float) = Color(
        red = a.red + (b.red - a.red) * t,
        green = a.green + (b.green - a.green) * t,
        blue = a.blue + (b.blue - a.blue) * t,
    )

    private companion object {
        const val DEVICE = "Phone"
        const val TITLE = "Film"
        const val HEADLINE = "Phone is flicking Film"
        const val LAUNCH_SETTLED_MS = 700L
        const val HARNESS_FRAME_MS = 16f
        const val NOISE = 2f / 255f
        const val GLYPHS_VISIBLE = 8f

        /** The tunnel's wash at the corner is near the house dark; the room and the film are far above it. */
        const val WASH_DARK = 0.12f

        val STANDBY = listOf(Color.White, Color(0xFFFFB61E))
        val FILM = listOf(Color(0xFFE0204A), Color(0xFFC01070))

        fun lumaOf(c: Color): Float = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue
    }
}
