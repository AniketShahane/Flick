package com.flick.sender.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.flick.sender.ui.theme.FlickIcons

/** Continuous volume slider (phone). Stepped cells are the TV variant. */
@Composable
fun VolumeSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = FlickIcons.Volume,
            contentDescription = null,
            tint = tint.copy(alpha = 0.7f),
            modifier = Modifier.size(16.dp).padding(end = 0.dp),
        )
        Box(
            Modifier
                .padding(start = 9.dp)
                .weight(1f)
                .height(24.dp)
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
                val th = 5.dp.toPx()
                val r = th / 2f
                drawRoundRect(
                    color = tint.copy(alpha = 0.16f),
                    topLeft = Offset(0f, cy - r),
                    size = Size(size.width, th),
                    cornerRadius = CornerRadius(r, r),
                )
                val fillW = value.coerceIn(0f, 1f) * size.width
                drawRoundRect(
                    color = tint.copy(alpha = 0.9f),
                    topLeft = Offset(0f, cy - r),
                    size = Size(fillW, th),
                    cornerRadius = CornerRadius(r, r),
                )
                drawCircle(color = Color.White, radius = 8.dp.toPx(), center = Offset(fillW, cy))
            }
        }
    }
}
