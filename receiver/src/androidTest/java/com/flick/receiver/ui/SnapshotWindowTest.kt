package com.flick.receiver.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.flick.receiver.ui.components.DisplayCadenceSource
import com.flick.receiver.ui.components.FilmFrames
import com.flick.receiver.ui.components.FilmSnapshots
import com.flick.receiver.ui.components.FilmStaging
import com.flick.receiver.ui.components.HouseLights
import com.flick.receiver.ui.components.HouseStage
import com.flick.receiver.ui.components.TUNNEL_TAG
import com.flick.receiver.ui.components.TUNNEL_VANISH_Y
import com.flick.receiver.ui.theme.FlickMotion
import com.flick.receiver.ui.theme.FlickTvTheme
import com.flick.receiver.ui.theme.LocalReducedMotion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt

/**
 * The window showing copies of the film: the whole frame, letterbox and all, shrunk, read
 * from screenshots of the composed display. The film stand-in is a letterboxed surface whose
 * left half is red and right half green, inside a black frame as the player's view is.
 */
class SnapshotWindowTest {

    // Full motion even when the device's animator scale is 0.
    @get:Rule
    val composeRule = createComposeRule(
        effectContext = object : MotionDurationScale {
            override val scaleFactor = 1f
        },
    )

    private var stage by mutableStateOf(HouseStage.Room)
    private var up = 0
    private var settled = 0
    private var surfaceView: SurfaceView? = null
    private var frameView: FrameLayout? = null
    private val surface get() = surfaceView!!
    private val frame get() = frameView!!

    @Volatile private var right = GREEN

    @Before
    fun freezeClock() {
        composeRule.mainClock.autoAdvance = false
    }

    /** [frames] is given readers of the stand-in's views, which exist only once the handshake composes them. */
    private fun launch(frames: (() -> FrameLayout?, () -> SurfaceView?) -> FilmFrames) {
        val made = frames({ frameView }, { surfaceView })
        composeRule.setContent {
            FlickTvTheme {
                CompositionLocalProvider(LocalReducedMotion provides false) {
                    Box(Modifier.fillMaxSize()) {
                        if (stage != HouseStage.Room) {
                            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                                FrameLayout(context).apply {
                                    setBackgroundColor(android.graphics.Color.BLACK)
                                    val video = SurfaceView(context).apply { holder.addCallback(painter) }
                                    // A scope film: 1920 × 800, letterboxed in the 1080 frame.
                                    addView(video, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FILM_HEIGHT, Gravity.CENTER))
                                    surfaceView = video
                                    frameView = this
                                }
                            })
                        }
                        HouseLights(
                            stage = stage,
                            deviceLabel = "Phone",
                            title = "Film",
                            cadence = Cadence,
                            keyPresses = MutableSharedFlow(),
                            filmStaging = FilmStaging(),
                            onLightsDown = {},
                            onPictureUp = { up++ },
                            onPictureSettled = { settled++ },
                            requestedRefreshRate = 60f,
                            freshRate = { 60f },
                            now = { composeRule.mainClock.currentTime },
                            filmFrames = made,
                        )
                    }
                }
            }
        }
        advance(700)
        composeRule.runOnIdle { stage = HouseStage.Handshake }
        advance(1_600)
    }

    private val painter = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) = Unit
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = paint(holder)
        override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
    }

    private fun paint(holder: SurfaceHolder) {
        val canvas: Canvas = holder.lockCanvas() ?: return
        val w = canvas.width.toFloat()
        val p = Paint()
        p.color = RED
        canvas.drawRect(0f, 0f, w / 2f, canvas.height.toFloat(), p)
        p.color = right
        canvas.drawRect(w / 2f, 0f, w, canvas.height.toFloat(), p)
        holder.unlockCanvasAndPost(canvas)
    }

    /** Cuts to the film and waits, in real time, for the window's first copy. */
    private fun releaseFilm() {
        composeRule.runOnIdle { stage = HouseStage.Film }
        var frames = 0
        while (up == 0 && frames++ < 60) composeRule.mainClock.advanceTimeByFrame()
        // The copies run on their own thread in real time; the stage's clock is paused.
        repeat(6) {
            Thread.sleep(100)
            composeRule.mainClock.advanceTimeByFrame()
        }
    }

    @Test
    fun theWindowShowsTheWholeFrameShrunkThenHandsOverToTheLiveFilm() {
        val snapshots = arrayOfNulls<FilmSnapshots>(1)
        launch { f, s -> FilmSnapshots(frame = f, video = s).also { snapshots[0] = it } }
        releaseFilm()
        advance(FlickMotion.TUNNEL_FILM_IN_MS + 100L)
        val dwell = screen("dwell")
        val cx = dwell.width / 2
        val cy = (TUNNEL_VANISH_Y * dwell.height).roundToInt()
        assertTrue("no red in the window's left half", isColour(dwell.getPixel(cx - 60, cy), RED))
        assertTrue("no green in the window's right half", isColour(dwell.getPixel(cx + 60, cy), GREEN))
        // The whole frame: above the picture, inside the window, the frame's letterbox.
        val band = letterboxAbove(dwell, cx - 60, cy)
        assertTrue("no letterbox above the picture: the window shows a crop ($band px)", band >= 6)
        val dwellWidth = redRun(dwell, cy)
        assertTrue("the snapshot thread is not running in the dwell", snapshots[0]!!.running)

        // Mid-expansion the window, and the frame in it, is larger.
        // The dwell counts from the film shown, about 150 ms of stage time before this point.
        advance(FlickMotion.TUNNEL_DWELL_MS - FlickMotion.TUNNEL_FILM_IN_MS - 250L + FlickMotion.TUNNEL_EXPAND_MS / 2L)
        val mid = screen("expanding")
        assertTrue("the tunnel landed before mid-expansion", composeRule.onAllNodesWithTag(TUNNEL_TAG).fetchSemanticsNodes().isNotEmpty())
        assertTrue("mid-expansion the window lost its letterbox", letterboxAbove(mid, cx - 60, cy) >= 6)
        val midWidth = redRun(mid, cy)
        assertTrue("the window did not grow: $dwellWidth → $midWidth px", midWidth > dwellWidth + 40)

        // Landed: the overlay is gone and the copying has stopped.
        advance(FlickMotion.TUNNEL_EXPAND_MS / 2L + FlickMotion.TUNNEL_SETTLE_MS + 150L)
        assertTrue("the tunnel outlived the landing", composeRule.onAllNodesWithTag(TUNNEL_TAG).fetchSemanticsNodes().isEmpty())
        assertTrue("onPictureSettled did not run", settled == 1)
        assertTrue("the snapshot thread outlived the show", !snapshots[0]!!.running)
        val live = screen("live")
        assertTrue("the live film does not fill the screen", isColour(live.getPixel(live.width / 4, live.height / 2), RED))
    }

    @Test
    fun theWindowFollowsTheFilmAsItChanges() {
        launch { f, s -> FilmSnapshots(frame = f, video = s) }
        releaseFilm()
        advance(FlickMotion.TUNNEL_FILM_IN_MS + 100L)
        val cx = composeRule.runOnIdle { frame.width / 2 }
        val cy = composeRule.runOnIdle { (TUNNEL_VANISH_Y * frame.height).roundToInt() }
        assertTrue(isColour(screen("before").getPixel(cx + 60, cy), GREEN))
        right = BLUE
        paint(surface.holder)
        repeat(4) {
            Thread.sleep(100)
            composeRule.mainClock.advanceTimeByFrame()
        }
        assertTrue("the window kept showing an old copy", isColour(screen("after").getPixel(cx + 60, cy), BLUE))
    }

    @Test
    fun aFilmThatCannotBeCopiedFallsBackToThePorthole() {
        var stopped = 0
        launch { _, _ ->
            object : FilmFrames {
                override suspend fun start() = false
                override fun stop() { stopped++ }
                override fun clear() = Unit
                override fun draw(canvas: Canvas, l: Float, t: Float, r: Float, b: Float) = false
            }
        }
        releaseFilm()
        advance(FlickMotion.TUNNEL_FILM_IN_MS + 100L)
        val shot = screen("porthole")
        val cx = shot.width / 2
        val cy = (TUNNEL_VANISH_Y * shot.height).roundToInt()
        // A porthole onto the live film's centre: no letterbox inside it, the picture to its edge.
        assertTrue("no film in the porthole", isColour(shot.getPixel(cx - 60, cy), RED))
        assertTrue("the porthole shows a letterbox: it is not the live film", letterboxAbove(shot, cx - 60, cy) < 3)
        assertTrue("a refused copier was not stopped", stopped > 0)
    }

    @Test
    fun aSeamDuringTheDwellStopsTheCopying() {
        val snapshots = arrayOfNulls<FilmSnapshots>(1)
        launch { f, s -> FilmSnapshots(frame = f, video = s).also { snapshots[0] = it } }
        releaseFilm()
        advance(300)
        assertTrue(snapshots[0]!!.running)
        composeRule.runOnIdle { stage = HouseStage.Room }
        repeat(3) { composeRule.mainClock.advanceTimeByFrame() }
        assertTrue("the snapshot thread outlived the cast", !snapshots[0]!!.running)
    }

    private fun advance(ms: Long) = composeRule.mainClock.advanceTimeBy(ms, ignoreFrameDuration = true)

    private fun screen(name: String): Bitmap {
        composeRule.waitForIdle()
        Thread.sleep(250)
        val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
            .copy(Bitmap.Config.ARGB_8888, false)
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        File(dir, "snapshot_${name}.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return shot
    }

    /** Dark rows directly above the picture, walking up from inside it. */
    private fun letterboxAbove(shot: Bitmap, x: Int, fromY: Int): Int {
        var y = fromY
        while (y > 0 && isColour(shot.getPixel(x, y), RED)) y--
        var band = 0
        while (y > 0 && isDark(shot.getPixel(x, y))) {
            band++
            y--
        }
        return band
    }

    /** The width of the red run through the window's centre row. */
    private fun redRun(shot: Bitmap, y: Int): Int = (0 until shot.width).count { isColour(shot.getPixel(it, y), RED) }

    /** The letterbox's near-black, also as the expansion's flash greys it. */
    private fun isDark(c: Int) =
        android.graphics.Color.red(c) < 48 && android.graphics.Color.green(c) < 48 && android.graphics.Color.blue(c) < 56

    private fun isColour(c: Int, want: Int): Boolean =
        kotlin.math.abs(android.graphics.Color.red(c) - android.graphics.Color.red(want)) < 40 &&
            kotlin.math.abs(android.graphics.Color.green(c) - android.graphics.Color.green(want)) < 40 &&
            kotlin.math.abs(android.graphics.Color.blue(c) - android.graphics.Color.blue(want)) < 40

    private object Cadence : DisplayCadenceSource {
        override val physicalHz = 60f
        override val restHz = 60f
        override val lastChangeAtMs: Long? = null
        override val changes: Flow<Long> = MutableSharedFlow()
        override fun switchWouldBlank(requestedHz: Float) = false
    }

    private companion object {
        const val FILM_HEIGHT = 800
        val RED = android.graphics.Color.rgb(0xE0, 0x20, 0x30)
        val GREEN = android.graphics.Color.rgb(0x20, 0xC0, 0x40)
        val BLUE = android.graphics.Color.rgb(0x30, 0x40, 0xE0)
    }
}
