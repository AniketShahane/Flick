package com.flick.receiver.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.flick.receiver.R
import com.flick.receiver.player.DiagnosticsSnapshot
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickType
import com.flick.receiver.ui.theme.glass
import java.util.Locale

/**
 * T10b · the opt-in metrics overlay (default OFF; toggled from settings). The old
 * developer HUD restyled as a well-typed glass card, pinned safely top-left, with
 * a Link accent edge. Purely presentational — it never takes D-pad focus. Reads
 * the existing [DiagnosticsSnapshot] telemetry unchanged.
 */
@Composable
fun MetricsOverlay(
    snapshot: DiagnosticsSnapshot,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = modifier
            .widthIn(min = 320.dp, max = 460.dp)
            .clip(shape)
            .glass(shape)
            .drawBehind {
                // 3dp Link accent along the left edge.
                drawRect(
                    color = FlickColor.Link,
                    topLeft = Offset(0f, 0f),
                    size = Size(3.dp.toPx(), size.height),
                )
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MetricRow(stringResource(R.string.metrics_net), netLine(snapshot), FlickColor.Live)
        MetricRow(
            stringResource(R.string.metrics_buf),
            "${seconds(snapshot.bufferedAheadMs)} · ${snapshot.rebufferCount} stalls",
            if (snapshot.rebufferCount == 0) FlickColor.Live else FlickColor.Caution,
        )
        MetricRow(stringResource(R.string.metrics_vid), vidLine(snapshot), FlickColor.OnSurface)
        MetricRow(stringResource(R.string.metrics_dec), decLine(snapshot), FlickColor.OnSurface)
        MetricRow(stringResource(R.string.metrics_bitrate), mbps(snapshot.bitrateEstimateBps), FlickColor.OnSurface)
        MetricRow(
            stringResource(R.string.metrics_dropped),
            "${snapshot.droppedFrames} · ${clock(snapshot.positionMs)}",
            if (snapshot.droppedFrames == 0L) FlickColor.OnSurface else FlickColor.Caution,
        )
        Text(
            text = stringResource(R.string.metrics_footer),
            style = FlickType.monoTabular(sizeSp = 24, weight = FontWeight.Medium),
            color = FlickColor.OnSurfaceFaint,
        )
    }
}

@Composable
private fun MetricRow(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = FlickType.monoTabular(sizeSp = 24, weight = FontWeight.Bold),
            color = FlickColor.OnSurfaceDim,
        )
        Text(
            text = value,
            style = FlickType.monoTabular(sizeSp = 24, weight = FontWeight.Medium),
            color = valueColor,
        )
    }
}

private fun netLine(s: DiagnosticsSnapshot): String {
    val parts = mutableListOf(mbps(s.bitrateEstimateBps))
    if (s.wifiBand != null) {
        parts += s.wifiBand
        if (s.wifiRssiDbm < 0) parts += "${s.wifiRssiDbm} dBm"
    }
    return parts.joinToString(" · ")
}

private fun vidLine(s: DiagnosticsSnapshot): String {
    val res = if (s.width > 0) "${s.width}×${s.height}" else "—"
    val fps = if (s.frameRate > 0f) String.format(Locale.US, "%.3f", s.frameRate) else "—"
    return "$res · $fps"
}

private fun decLine(s: DiagnosticsSnapshot): String =
    (s.decoderName ?: "—") + " · hw"

private fun mbps(bps: Long): String =
    if (bps <= 0L) "—" else String.format(Locale.US, "%.1f Mb/s", bps / 1_000_000.0)

private fun seconds(ms: Long): String =
    String.format(Locale.US, "%.1f s", ms / 1000.0)

private fun clock(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}
