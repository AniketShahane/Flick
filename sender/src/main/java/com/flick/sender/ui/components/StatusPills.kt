package com.flick.sender.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.flick.sender.ui.theme.FlickIcons
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.PillShape
import com.flick.sender.ui.theme.rememberReduceMotion

/** A status dot; when [pulsing] it breathes 0.45 → 1.0 (design `fkPulse`). */
@Composable
fun LiveDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 6.dp,
    pulsing: Boolean = false,
) {
    val reduceMotion = rememberReduceMotion()
    val alpha = if (pulsing && !reduceMotion) {
        val t = rememberInfiniteTransition(label = "dot")
        t.animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
            label = "dotAlpha",
        ).value
    } else {
        1f
    }
    androidx.compose.foundation.Canvas(modifier.size(size)) {
        drawCircle(color = color.copy(alpha = color.alpha * alpha))
    }
}

enum class StatusKind { LIVE, CONNECTING, TROUBLE, CAUTION }

/** Full-pill status: `Serving · live`, `Connecting…`, `TV unreachable`, etc. */
@Composable
fun StatusPill(text: String, kind: StatusKind, modifier: Modifier = Modifier) {
    val colors = LocalFlickColors.current
    val accent = when (kind) {
        StatusKind.LIVE -> colors.live
        StatusKind.CONNECTING -> colors.link
        StatusKind.TROUBLE -> colors.trouble
        StatusKind.CAUTION -> colors.caution
    }
    Row(
        modifier = modifier
            .clip(PillShape)
            .background(accent.copy(alpha = 0.12f))
            .border(1.dp, accent.copy(alpha = 0.35f), PillShape)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiveDot(
            color = accent,
            pulsing = kind == StatusKind.LIVE || kind == StatusKind.CONNECTING,
            modifier = Modifier.padding(end = 6.dp),
        )
        Text(
            text = text,
            style = FlickText.caption.copy(fontWeight = FontWeight.SemiBold, color = accent),
        )
    }
}

/** The cyan "connected TV" chip (Link is connection only). */
@Composable
fun ConnectChip(name: String, modifier: Modifier = Modifier) {
    val colors = LocalFlickColors.current
    Row(
        modifier = modifier
            .clip(PillShape)
            .background(colors.link.copy(alpha = 0.10f))
            .border(1.dp, colors.link.copy(alpha = 0.30f), PillShape)
            .padding(horizontal = 11.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiveDot(color = colors.link, pulsing = true, modifier = Modifier.padding(end = 6.dp))
        Text(
            text = name,
            style = FlickText.caption.copy(fontWeight = FontWeight.SemiBold, color = colors.link),
        )
    }
}

/** Signal chip → `61 Mb/s · 5 GHz`, mono tabular, expands to the quality sheet. */
@Composable
fun SignalChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    healthy: Boolean = true,
) {
    val colors = LocalFlickColors.current
    val glyphTint = if (healthy) colors.live else colors.caution
    Row(
        modifier = modifier
            .clip(PillShape)
            .background(colors.surfaceRaised)
            .border(1.dp, colors.outlineHairline, PillShape)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = FlickIcons.Wifi,
            contentDescription = null,
            tint = glyphTint,
            modifier = Modifier.size(14.dp).padding(end = 0.dp),
        )
        Text(
            text = text,
            style = FlickText.mono.copy(fontWeight = FontWeight.SemiBold, color = colors.onSurface),
            modifier = Modifier.padding(start = 7.dp),
        )
    }
}
