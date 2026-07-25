package com.flick.sender.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.flick.sender.ui.theme.FlickIcons
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors

/**
 * Continuous TV volume. A 13dp track with a bright fill and the amber blade thumb;
 * the trailing [percentLabel] is fixed-width so the digits never shift the track.
 * The visual band is short, but the gesture/semantics box stays a legal target.
 */
@Composable
fun VolumeSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LocalFlickColors.current.onSurfaceDim,
    percentLabel: String? = null,
    accessibilityLabel: String? = null,
    valueDescription: String? = null,
    adjustableActionLabel: String? = null,
) {
    val colors = LocalFlickColors.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = FlickIcons.Volume,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Box(
            Modifier
                .weight(1f)
                .height(48.dp)
                .semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(0f, 1f), 0f..1f)
                    accessibilityLabel?.let { contentDescription = it }
                    valueDescription?.let { stateDescription = it }
                    setProgress(adjustableActionLabel) { target ->
                        onValueChange(target.coerceIn(0f, 1f))
                        true
                    }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val w = { size.width.toFloat().coerceAtLeast(1f) }
                        onValueChange((down.position.x / w()).coerceIn(0f, 1f))
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
                            onValueChange((change.position.x / w()).coerceIn(0f, 1f))
                            change.consume()
                        }
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val cy = size.height / 2f
                val track = TrackHeight.toPx()
                val r = track / 2f
                drawRoundRect(
                    color = colors.fillTrack,
                    topLeft = Offset(0f, cy - r),
                    size = Size(size.width, track),
                    cornerRadius = CornerRadius(r, r),
                )
                val fillW = value.coerceIn(0f, 1f) * size.width
                if (fillW > 0f) {
                    drawRoundRect(
                        color = colors.onSurface,
                        topLeft = Offset(0f, cy - r),
                        size = Size(fillW, track),
                        cornerRadius = CornerRadius(r, r),
                    )
                }
                val bladeW = ThumbWidth.toPx()
                val bladeH = ThumbHeight.toPx()
                val bx = fillW.coerceIn(bladeW / 2f, (size.width - bladeW / 2f).coerceAtLeast(bladeW / 2f))
                drawRoundRect(
                    color = colors.spark,
                    topLeft = Offset(bx - bladeW / 2f, cy - bladeH / 2f),
                    size = Size(bladeW, bladeH),
                    cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                )
            }
        }
        if (percentLabel != null) {
            Spacer(Modifier.width(14.dp))
            Text(
                text = percentLabel,
                style = FlickText.monoSmall.copy(color = tint, textAlign = TextAlign.End),
                modifier = Modifier.width(36.dp),
            )
        }
    }
}

private val TrackHeight = 13.dp
private val ThumbWidth = 6.dp
private val ThumbHeight = 26.dp
