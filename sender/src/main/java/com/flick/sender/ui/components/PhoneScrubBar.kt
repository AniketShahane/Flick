package com.flick.sender.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flick.sender.R
import com.flick.sender.ui.theme.FlickGradients
import com.flick.sender.ui.theme.FlickCorners
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The phone (tactile) scrub bar — the hero instrument (design §7 / Part 4).
 *
 * 10dp track that swells to 12dp while dragging; the Spark gradient fills to
 * [targetFraction] (the optimistic head). A hollow **ghost ring** shows
 * [ghostFraction] (TV-confirmed) when it trails the head. While the thumb is down,
 * an on-device **frame preview** rides above it, and a cyan **SYNCING…** shimmer
 * appears on a Wi-Fi hiccup. Gestures are unified in one detector so a tap seeks
 * and a drag scrubs identically.
 *
 * The bar reports fractions only; the caller maps them to ms, throttles the seek,
 * and fires the detent / grip / snap haptics.
 */
@Composable
fun PhoneScrubBar(
    targetFraction: () -> Float,
    ghostFraction: () -> Float?,
    syncing: Boolean,
    framePreview: ImageBitmap?,
    previewLabel: () -> String?,
    onScrubStart: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
    modifier: Modifier = Modifier,
    fillColor: Color? = null,
    targetLabel: String? = null,
    confirmedLabel: String? = null,
    stateLabel: String? = null,
    adjustableActionLabel: String? = null,
) {
    val colors = LocalFlickColors.current
    var dragging by remember { mutableStateOf(false) }
    var widthPx by remember { mutableStateOf(1) }
    val endGate = remember { ScrubEndGate() }

    // If the bar is torn out from under a live drag (e.g. a mid-stream rebuffer swaps
    // the screen), the gesture coroutine is cancelled without reaching onScrubEnd —
    // leaving the session stuck scrubbing with the FOLLOWING pill lit and no final
    // seek. Close the gesture on dispose so scrubbing always terminates cleanly.
    val currentOnScrubEnd by rememberUpdatedState(onScrubEnd)
    DisposableEffect(Unit) {
        onDispose { endGate.finish(currentOnScrubEnd) }
    }

    val trackHeight by animateDpAsState(
        targetValue = if (dragging) 12.dp else 10.dp,
        animationSpec = tween(160),
        label = "trackH",
    )
    val thumbDiameter by animateDpAsState(
        targetValue = if (dragging) 26.dp else 24.dp,
        animationSpec = tween(160),
        label = "thumbD",
    )

    // Allocate the Spark brush once per theme, not on every pointer-rate recomposition.
    val brush = remember(colors.isLight) { FlickGradients.spark(dark = !colors.isLight) }
    val solidFill = fillColor

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .onSizeChanged { widthPx = it.width }
            .semantics {
                // This reads through the leaf lambda rather than moving the live
                // playhead into the remote's composition scope.
                progressBarRangeInfo = ProgressBarRangeInfo(targetFraction(), 0f..1f)
                targetLabel?.let { contentDescription = it }
                listOfNotNull(confirmedLabel, stateLabel)
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(separator = ", ")
                    ?.let { stateDescription = it }
                setProgress(adjustableActionLabel) { fraction ->
                    endGate.start()
                    onScrubStart()
                    onScrub(fraction.coerceIn(0f, 1f))
                    endGate.finish(currentOnScrubEnd)
                    true
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val w = { size.width.toFloat().coerceAtLeast(1f) }
                    endGate.start()
                    dragging = true
                    try {
                        onScrubStart()
                        onScrub((down.position.x / w()).coerceIn(0f, 1f))
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: event.changes.firstOrNull()
                            if (change == null) break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            onScrub((change.position.x / w()).coerceIn(0f, 1f))
                            change.consume()
                        }
                    } finally {
                        dragging = false
                        endGate.finish(currentOnScrubEnd)
                    }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // Read the pointer-rate fractions INSIDE the draw scope so a scrub only
            // re-runs the draw phase — never a recomposition of the whole bar.
            val tf = targetFraction().coerceIn(0f, 1f)
            val gf = ghostFraction()
            val cy = size.height / 2f
            val th = trackHeight.toPx()
            val radius = th / 2f
            val w = size.width

            // Bare track.
            drawRoundRect(
                color = colors.onSurface.copy(alpha = 0.16f),
                topLeft = Offset(0f, cy - radius),
                size = Size(w, th),
                cornerRadius = CornerRadius(radius, radius),
            )
            // Spark fill 0..target.
            val fillW = tf * w
            if (fillW > 0f) {
                if (solidFill != null) {
                    drawRoundRect(
                        color = solidFill,
                        topLeft = Offset(0f, cy - radius),
                        size = Size(fillW, th),
                        cornerRadius = CornerRadius(radius, radius),
                    )
                } else {
                    drawRoundRect(
                        brush = brush,
                        topLeft = Offset(0f, cy - radius),
                        size = Size(fillW, th),
                        cornerRadius = CornerRadius(radius, radius),
                    )
                }
            }
            // Ghost ○ (TV-confirmed) when it trails the head.
            gf?.let { g ->
                if (abs(g - tf) > 0.006f) {
                    val ghostColor = if (colors.isLight) colors.onSurfaceDim else Color.White.copy(alpha = 0.6f)
                    drawCircle(
                        color = ghostColor,
                        radius = 6.dp.toPx(),
                        center = Offset(g.coerceIn(0f, 1f) * w, cy),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }
            // Thumb + Spark bloom ring.
            val tx = tf * w
            val half = thumbDiameter.toPx() / 2f
            drawCircle(
                color = colors.spark.copy(alpha = 0.22f),
                radius = half + 8.dp.toPx(),
                center = Offset(tx, cy),
            )
            drawCircle(color = colors.spark, radius = half, center = Offset(tx, cy))
            drawCircle(color = colors.sparkLight, radius = half * 0.55f, center = Offset(tx, cy))
        }

        // SYNCING… shimmer chip.
        AnimatedVisibility(
            visible = syncing,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd).offset(y = (-26).dp),
        ) {
            Text(
                text = stringResource(R.string.syncing),
                style = FlickText.monoLabel.copy(color = colors.link, fontWeight = FontWeight.SemiBold),
                modifier = Modifier
                    .background(colors.link.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }

        // Frame preview riding above the thumb. The offset reads targetFraction in the
        // layout phase (lambda form) so tracking the thumb doesn't recompose the card.
        AnimatedVisibility(
            visible = dragging && framePreview != null,
            enter = fadeIn() + scaleIn(initialScale = 0.85f),
            exit = fadeOut() + scaleOut(targetScale = 0.85f),
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset {
                    val previewX = targetFraction().coerceIn(0f, 1f) * widthPx
                    val cardHalf = 52.dp.toPx()
                    val cardWidth = 104.dp.toPx()
                    val maxX = (widthPx - cardWidth).coerceAtLeast(0f)
                    val x = (previewX - cardHalf).coerceIn(0f, maxX)
                    IntOffset(x.roundToInt(), (-84).dp.toPx().roundToInt())
                },
        ) {
            FramePreviewCard(framePreview, previewLabel)
        }
    }
}

@Composable
private fun FramePreviewCard(bitmap: ImageBitmap?, label: () -> String?) {
    if (bitmap == null) return
    val shape = RoundedCornerShape(FlickCorners.md)
    Column(
        Modifier
            .width(104.dp)
            .border(1.dp, LocalFlickColors.current.outlineHairline, shape)
            .background(LocalFlickColors.current.surfaceRaised, shape),
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp),
        )
        Text(
            text = label() ?: "",
            style = FlickText.mono.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = LocalFlickColors.current.onSurface),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** Keeps release, pointer cancellation, and composition disposal to one terminal callback. */
private class ScrubEndGate {
    private var active = false

    fun start() {
        active = true
    }

    fun finish(onScrubEnd: () -> Unit) {
        if (active) {
            active = false
            onScrubEnd()
        }
    }
}
