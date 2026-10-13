package com.flick.sender.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * Puts each kind of bitmap draw the library makes on the GPU once, for one frame, a few seconds
 * after the grid first settles: software and hardware bitmaps, opaque and translucent, at full
 * and modulated alpha, on and off the pixel grid, straight onto the window and inside an
 * offscreen layer.
 *
 * The render thread compiles a pipeline the first time it meets each of those in each kind of
 * target, inside the frame that needs it. The nav's glass is an offscreen layer that every row
 * passes under at a third of its size, so otherwise the first tile of a kind not yet seen there
 * stalls a fling for the compile. Every sample is [ground] drawn over [ground], so it shows
 * nothing; [ready] holds the frame back while the grid is moving.
 */
@Composable
internal fun ArtPipelineWarmup(ground: Color, ready: () -> Boolean, modifier: Modifier = Modifier) {
    if (ArtPipelineWarmth.done) return
    var drawing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(WarmupDelayMs)
        snapshotFlow { ready() }.first { it }
        drawing = true
        // The first frame draws the samples; by the second that frame has gone to the GPU.
        withFrameNanos { }
        withFrameNanos { }
        ArtPipelineWarmth.done = true
        drawing = false
    }
    if (!drawing) return
    val samples = remember(ground) { warmupSamples(ground) }
    val side = with(LocalDensity.current) { SamplePx.toDp() }
    Box(modifier.size(side)) {
        Canvas(Modifier.matchParentSize()) { drawSamples(samples) }
        Canvas(
            Modifier
                .matchParentSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        ) { drawSamples(samples) }
    }
}

/** Once per process: the compiled pipelines outlive any one composition of the grid. */
private object ArtPipelineWarmth {
    @Volatile var done = false
}

private fun warmupSamples(ground: Color): List<ImageBitmap> {
    val argb = ground.toArgb()
    val opaque = Bitmap.createBitmap(SamplePx, SamplePx, Bitmap.Config.ARGB_8888).apply {
        eraseColor(argb)
        setHasAlpha(false)
    }
    val translucent = Bitmap.createBitmap(SamplePx, SamplePx, Bitmap.Config.ARGB_8888).apply {
        eraseColor(ground.copy(alpha = 0.5f).toArgb())
    }
    val software = listOf(opaque, translucent)
    val hardware = software.mapNotNull { runCatching { it.copy(Bitmap.Config.HARDWARE, false) }.getOrNull() }
    return (software + hardware).map { it.asImageBitmap() }
}

private fun DrawScope.drawSamples(samples: List<ImageBitmap>) {
    for (image in samples) {
        for (alpha in SampleAlphas) {
            // Scaled in both, so the sampler filters as a tile's does.
            drawImage(image, dstSize = IntSize(SamplePx / 2, SamplePx / 2), alpha = alpha)
            translate(left = 0.5f, top = 0.5f) {
                scale(GlassInputScaleFactor, pivot = Offset.Zero) {
                    drawImage(image, dstSize = IntSize(SamplePx, SamplePx), alpha = alpha)
                }
            }
        }
    }
}

private const val SamplePx = 4
private val SampleAlphas = floatArrayOf(1f, 0.5f)
private const val WarmupDelayMs = 1_500L
