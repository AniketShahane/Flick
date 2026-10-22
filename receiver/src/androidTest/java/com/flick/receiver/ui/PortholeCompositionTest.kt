package com.flick.receiver.ui

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.flick.receiver.ui.components.DisplayCadenceSource
import com.flick.receiver.ui.components.FilmStaging
import com.flick.receiver.ui.components.HouseLights
import com.flick.receiver.ui.components.HouseStage
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
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The porthole as the display composes it, read from a screenshot of the whole screen
 * rather than of the window: the film's surface sits beneath the window and a texture
 * inside it, and both must show through the porthole in the colour they were filled with.
 */
class PortholeCompositionTest {

    // Full motion even when the device's animator scale is 0.
    @get:Rule
    val composeRule = createComposeRule(
        effectContext = object : MotionDurationScale {
            override val scaleFactor = 1f
        },
    )

    private var stage by mutableStateOf(HouseStage.Room)
    private var up = 0

    @Before
    fun freezeClock() {
        composeRule.mainClock.autoAdvance = false
    }

    @Test
    fun aFilmOnASurfaceShowsThroughThePorthole() = showOver { context ->
        SurfaceView(context).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) = Unit
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    val canvas = holder.lockCanvas()
                    canvas.drawColor(FILM)
                    holder.unlockCanvasAndPost(canvas)
                }
                override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
            })
        }
    }

    @Test
    fun aTurnedFilmOnATextureShowsThroughThePorthole() = showOver { context ->
        TextureView(context).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                    val surface = Surface(texture)
                    val canvas = surface.lockCanvas(null)
                    canvas.drawColor(FILM)
                    surface.unlockCanvasAndPost(canvas)
                    surface.release()
                }
                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture) = true
                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
            }
        }
    }

    private fun showOver(film: (android.content.Context) -> View) {
        composeRule.setContent {
            FlickTvTheme {
                CompositionLocalProvider(LocalReducedMotion provides false) {
                    Box(Modifier.fillMaxSize()) {
                        if (stage != HouseStage.Room) AndroidView(modifier = Modifier.fillMaxSize(), factory = film)
                        HouseLights(
                            stage = stage,
                            deviceLabel = "Phone",
                            title = "Film",
                            cadence = Cadence,
                            keyPresses = MutableSharedFlow(),
                            filmStaging = FilmStaging(),
                            onLightsDown = {},
                            onPictureUp = { up++ },
                            onPictureSettled = {},
                            requestedRefreshRate = 60f,
                            freshRate = { 60f },
                            now = { composeRule.mainClock.currentTime },
                        )
                    }
                }
            }
        }
        advance(700)
        composeRule.runOnIdle { stage = HouseStage.Handshake }
        advance(1_600)
        // Before the film is up, the porthole's lit pane hides whatever is behind it.
        assertTrue("the film showed before its first frame", !shows(screen("pane"), FILM))
        composeRule.runOnIdle { stage = HouseStage.Film }
        var frames = 0
        while (up == 0 && frames++ < 60) composeRule.mainClock.advanceTimeByFrame()
        // Into the dwell, past the pane's crossfade.
        advance(FlickMotion.TUNNEL_FILM_IN_MS + 300L)
        val dwell = screen("dwell")
        assertTrue("the film does not show through the porthole", shows(dwell, FILM))
        assertTrue("the tunnel does not surround the porthole", !shows(dwell, FILM, fx = 0.05f, fy = 0.05f))
        // Mid-expansion the film still shows through the opening porthole.
        advance(FlickMotion.TUNNEL_DWELL_MS - 300L + FlickMotion.TUNNEL_FLASH_MS + 60L)
        assertTrue("the film went dark in the expanding porthole", shows(screen("expanding"), FILM))
    }

    private fun advance(ms: Long) = composeRule.mainClock.advanceTimeBy(ms, ignoreFrameDuration = true)

    /** The display as composed, surfaces and all. */
    private fun screen(name: String): Bitmap {
        composeRule.waitForIdle()
        Thread.sleep(250)
        val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
            .copy(Bitmap.Config.ARGB_8888, false)
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        File(dir, "porthole_${javaClass.simpleName}_$name.png").outputStream().use {
            shot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return shot
    }

    private fun shows(shot: Bitmap, colour: Int, fx: Float = 0.5f, fy: Float = TUNNEL_VANISH_Y): Boolean {
        val c = shot.getPixel((fx * shot.width).roundToInt(), (fy * shot.height).roundToInt())
        return abs(android.graphics.Color.red(c) - android.graphics.Color.red(colour)) < 12 &&
            abs(android.graphics.Color.green(c) - android.graphics.Color.green(colour)) < 12 &&
            abs(android.graphics.Color.blue(c) - android.graphics.Color.blue(colour)) < 12
    }

    private object Cadence : DisplayCadenceSource {
        override val physicalHz = 60f
        override val restHz = 60f
        override val lastChangeAtMs: Long? = null
        override val changes: Flow<Long> = MutableSharedFlow()
        override fun switchWouldBlank(requestedHz: Float) = false
    }

    private companion object {
        /** A green nothing in the tunnel comes near. */
        val FILM = android.graphics.Color.rgb(0x22, 0xCC, 0x55)
    }
}
