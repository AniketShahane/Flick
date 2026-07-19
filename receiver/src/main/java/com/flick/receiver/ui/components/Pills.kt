package com.flick.receiver.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickShape

/** Semantic tone for a live status pill (§7). */
enum class PillTone(val accent: Color) {
    Live(FlickColor.Live),
    Link(FlickColor.Link),
    Caution(FlickColor.Caution),
    Trouble(FlickColor.Trouble),
}

/**
 * Full-pill status chip (§7): `Serving · live`, `Connecting…`, `TV unreachable`.
 * The leading dot [pulsing] breathes for live/connecting states. Text sits at the
 * 10-ft minimum (24sp).
 */
@Composable
fun StatusPill(
    text: String,
    tone: PillTone,
    modifier: Modifier = Modifier,
    pulsing: Boolean = false,
) {
    val dotAlpha = if (pulsing) {
        val t = rememberInfiniteTransition(label = "pillPulse")
        val a by t.animateFloat(
            initialValue = 0.45f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
            label = "pillDot",
        )
        a
    } else 1f

    Row(
        modifier = modifier
            .clip(FlickShape.Pill)
            .background(tone.accent.copy(alpha = 0.10f))
            .border(1.dp, tone.accent.copy(alpha = 0.45f), FlickShape.Pill)
            .padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .drawBehind {
                    drawCircle(color = tone.accent.copy(alpha = dotAlpha))
                },
        )
        Text(
            text = text,
            color = FlickColor.OnSurface,
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Premium-sheen quality badge (§1.5) — DV/HDR ONLY, never UI chrome. Filled uses
 * the gold sheen gradient; HDR10 uses the outline variant.
 */
@Composable
fun QualityBadge(
    text: String,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
) {
    val shape = RoundedCornerShape(6.dp)
    val base = if (filled) {
        Modifier.background(FlickColor.SheenGradient, shape)
    } else {
        Modifier
            .background(Color.Transparent, shape)
            .border(1.dp, FlickColor.SheenGoldA.copy(alpha = 0.55f), shape)
    }
    Row(
        modifier = modifier
            .clip(shape)
            .then(base)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = if (filled) FlickColor.OnSheen else FlickColor.SheenGoldB,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
