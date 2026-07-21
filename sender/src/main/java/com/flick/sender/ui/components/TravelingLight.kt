package com.flick.sender.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.flick.sender.ui.theme.Link
import com.flick.sender.ui.theme.rememberReduceMotion

/**
 * The traveling light (design §6) — a cyan mote sliding along the live link. Shown
 * at connect, at seek release, and whenever a cross-surface command lands. Cyan is
 * the link; it never carries content.
 */
@Composable
fun TravelingLight(
    modifier: Modifier,
    color: Color = Link,
) {
    val reduceMotion = rememberReduceMotion()
    val phase = if (reduceMotion) {
        0.5f
    } else {
        val transition = rememberInfiniteTransition(label = "link")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
            label = "phase",
        ).value
    }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val cy = h / 2f
        val p1y = cy - h * 0.35f
        val p2y = cy + h * 0.35f
        val path = Path().apply {
            moveTo(0f, cy)
            cubicTo(w * 0.3f, p1y, w * 0.7f, p2y, w, cy)
        }
        drawPath(
            path = path,
            color = color.copy(alpha = 0.5f),
            style = Stroke(
                width = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(4.dp.toPx(), 10.dp.toPx()),
                    -28f * phase,
                ),
            ),
        )
        val t = phase
        val mt = 1f - t
        val x = w * t
        val y = mt * mt * mt * cy +
            3f * mt * mt * t * p1y +
            3f * mt * t * t * p2y +
            t * t * t * cy
        drawCircle(color = color, radius = 4.dp.toPx(), center = Offset(x, y))
        drawCircle(
            color = color.copy(alpha = 0.4f),
            radius = 7.dp.toPx(),
            style = Stroke(1.dp.toPx()),
            center = Offset(x, y),
        )
    }
}
