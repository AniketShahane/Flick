package com.flick.receiver.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import com.flick.receiver.StageFilmLayers
import com.flick.receiver.session.MediaStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The player surface's host across a cast. Moving its view to another parent detaches it
 * from the window, which destroys its surface and hands the decoder a new one just as the
 * film arrives; across Checking → Preparing → Active it must stay one view, attached, on
 * one surface.
 */
class StageFilmHostTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var stage by mutableStateOf<MediaStage>(MediaStage.None)
    private val events = mutableListOf<String>()
    private val views = mutableListOf<View>()

    @Test
    fun theSurfaceIsHostedOnceFromTheHandshakeToTheFilmAndThroughAReCast() {
        composeRule.setContent {
            StageFilmLayers(
                stage = stage,
                surface = {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            SurfaceView(context).apply {
                                views += this
                                holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(holder: SurfaceHolder) { events += "created" }
                                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                                    override fun surfaceDestroyed(holder: SurfaceHolder) { events += "destroyed" }
                                })
                                addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                                    override fun onViewAttachedToWindow(v: View) { events += "attached" }
                                    override fun onViewDetachedFromWindow(v: View) { events += "detached" }
                                })
                            }
                        },
                    )
                },
            ) {
                // The playback screen over the film, as heavy as a Box needs to be here.
                Box(Modifier.fillMaxSize())
            }
        }
        // A cast, then a re-cast straight over the playing film.
        for (next in listOf(
            MediaStage.Checking("cast", 1L), MediaStage.Preparing("cast", 1L), MediaStage.Active("cast", 1L),
            MediaStage.Checking("again", 2L), MediaStage.Preparing("again", 2L), MediaStage.Active("again", 2L),
        )) {
            composeRule.runOnIdle { stage = next }
            composeRule.waitForIdle()
            Thread.sleep(300)
            composeRule.waitForIdle()
        }
        composeRule.runOnIdle {
            assertEquals("one view for the whole cast", 1, views.size)
            assertSame(views.first(), views.last())
            assertTrue("the surface's view is not attached at Active", views.first().isAttachedToWindow)
            assertEquals("the surface was not made exactly once and kept", listOf("attached", "created"), events)
        }
    }
}
