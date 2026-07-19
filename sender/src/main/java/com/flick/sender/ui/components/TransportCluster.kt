package com.flick.sender.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.flick.sender.ui.theme.FlickGradients
import com.flick.sender.ui.theme.FlickIcons
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.Motion

/**
 * Play/pause that **morphs** (triangle ↔ bars) via flickSettle — never a hard
 * swap (design §7). The two half-shapes interpolate corner-for-corner between the
 * play triangle and the two pause bars as [playing] flips.
 */
@Composable
fun PlayPauseMorph(
    playing: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val f by animateFloatAsState(
        targetValue = if (playing) 1f else 0f,
        animationSpec = tween(Motion.FlickSettleMs, easing = Motion.FlickSettle),
        label = "morph",
    )
    Canvas(modifier) {
        val u = size.minDimension / 24f
        fun blend(ax: Float, ay: Float, bx: Float, by: Float) =
            Offset(lerp(ax, bx, f) * u, lerp(ay, by, f) * u)

        // Left half: play quad → left pause bar.
        val left = Path().apply {
            val a = blend(8f, 5f, 7f, 5f)
            val b = blend(13.5f, 8.5f, 10f, 5f)
            val c = blend(13.5f, 15.5f, 10f, 19f)
            val d = blend(8f, 19f, 7f, 19f)
            moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); lineTo(d.x, d.y); close()
        }
        // Right half: play tip (degenerate quad) → right pause bar.
        val right = Path().apply {
            val a = blend(13.5f, 8.5f, 14f, 5f)
            val b = blend(19f, 12f, 17f, 5f)
            val c = blend(19f, 12f, 17f, 19f)
            val d = blend(13.5f, 15.5f, 14f, 19f)
            moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); lineTo(d.x, d.y); close()
        }
        drawPath(left, color)
        drawPath(right, color)
    }
}

/** Primary play control — a Spark-gradient circle wrapping the white morph. */
@Composable
fun PrimaryPlayButton(
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 58.dp,
) {
    val colors = LocalFlickColors.current
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(FlickGradients.spark(dark = !colors.isLight), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        PlayPauseMorph(
            playing = playing,
            color = Color.White,
            modifier = Modifier.size(size * 0.36f),
        )
    }
}

/** ±10s skip — the arc glyph with the "10" overlaid (the vector can't carry text). */
@Composable
fun SeekButton(
    forward: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    tint: Color = LocalFlickColors.current.onSurface,
) {
    val glyph: ImageVector = if (forward) FlickIcons.Fwd10 else FlickIcons.Back10
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .border(1.5.dp, tint.copy(alpha = 0.22f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = glyph, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
        Text(
            text = "10",
            style = FlickText.mono.copy(fontSize = 8.sp, fontWeight = FontWeight.Bold, color = tint),
            modifier = Modifier.offset(y = size * 0.11f),
        )
    }
}

/** back-10 · play/pause · fwd-10 (design transport cluster). */
@Composable
fun TransportCluster(
    playing: Boolean,
    onBack10: () -> Unit,
    onPlayPause: () -> Unit,
    onFwd10: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LocalFlickColors.current.onSurface,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SeekButton(forward = false, onClick = onBack10, tint = tint)
        PrimaryPlayButton(playing = playing, onClick = onPlayPause)
        SeekButton(forward = true, onClick = onFwd10, tint = tint)
    }
}
