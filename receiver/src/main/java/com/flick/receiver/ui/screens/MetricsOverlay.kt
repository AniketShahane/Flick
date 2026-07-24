package com.flick.receiver.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.flick.receiver.R
import com.flick.receiver.player.DiagnosticsSnapshot
import com.flick.receiver.player.SubtitleCueKind
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickShape
import com.flick.receiver.ui.theme.FlickType
import java.util.Locale

/** Fixed label gutter — the whole point of the HUD is that values line up. */
private val LabelGutter = 84.dp

/**
 * T10b · the opt-in developer HUD (default OFF; toggled from settings). This is
 * deliberately the *power-user* layer and stays subordinate to the Stream metrics
 * panel: no glass gloss, no rounded generosity — a dense mono readout on a dark
 * plate with a brand-blue rule down its edge, pinned inside the safe area by its
 * caller.
 *
 * Amber marks the one number a tuner watches (throughput); green/amber carry the
 * existing health semantics. Purely presentational — it never takes D-pad focus,
 * and it reads the [DiagnosticsSnapshot] telemetry unchanged.
 */
@Composable
fun MetricsOverlay(
    snapshot: DiagnosticsSnapshot,
    modifier: Modifier = Modifier,
) {
    val shape = FlickShape.Sm
    Column(
        modifier = modifier
            .widthIn(min = 320.dp, max = 470.dp)
            .clip(shape)
            .background(FlickColor.ScrimVeil)
            .border(1.dp, FlickColor.OutlineHairline, shape)
            .drawBehind {
                // 3dp brand rule along the leading edge. Blue, never amber: amber
                // means focus everywhere else and this surface is never focusable.
                drawRect(
                    color = FlickColor.PrimaryOnDark,
                    topLeft = Offset(0f, 0f),
                    size = Size(3.dp.toPx(), size.height),
                )
            }
            .padding(start = 17.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        MetricRow(stringResource(R.string.metrics_net), netLine(snapshot), FlickColor.Live)
        MetricRow(
            stringResource(R.string.metrics_buf),
            stringResource(
                R.string.metrics_buffer_value,
                seconds(snapshot.bufferedAheadMs),
                snapshot.rebufferCount,
            ),
            if (snapshot.rebufferCount == 0) FlickColor.Live else FlickColor.Caution,
        )
        MetricRow(stringResource(R.string.metrics_vid), vidLine(snapshot), FlickColor.OnChrome)
        MetricRow(stringResource(R.string.metrics_dec), decLine(snapshot), FlickColor.OnChrome)
        MetricRow(stringResource(R.string.metrics_sub), subtitleLine(snapshot), FlickColor.OnChrome)
        MetricRow(stringResource(R.string.metrics_bitrate), mbps(snapshot.bitrateEstimateBps), FlickColor.Spark)
        MetricRow(
            stringResource(R.string.metrics_dropped),
            stringResource(R.string.metrics_dropped_value, snapshot.droppedFrames, clock(snapshot.positionMs)),
            if (snapshot.droppedFrames == 0L) FlickColor.Live else FlickColor.Caution,
        )
        Box(
            modifier = Modifier
                .padding(top = 3.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(FlickColor.OutlineHairline),
        )
        Text(
            text = stringResource(R.string.metrics_footer),
            style = FlickType.monoEyebrow(trackingEm = 0.12f),
            color = FlickColor.OnSurfaceFaint,
        )
    }
}

@Composable
private fun MetricRow(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            style = FlickType.monoEyebrow(trackingEm = 0.14f),
            color = FlickColor.OnPanelLabel,
            modifier = Modifier
                .width(LabelGutter)
                .padding(top = 3.dp),
            maxLines = 1,
        )
        Text(
            text = value,
            style = FlickType.monoTabular(sizeSp = 20, weight = FontWeight.Medium),
            color = valueColor,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
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

@Composable
private fun decLine(s: DiagnosticsSnapshot): String =
    stringResource(R.string.metrics_decoder_value, s.decoderName ?: stringResource(R.string.metrics_unavailable))

@Composable
private fun subtitleLine(s: DiagnosticsSnapshot): String {
    if (!s.subtitleTrackSelected) return stringResource(R.string.metrics_subtitle_none)
    val cueKind = when (s.subtitleCueKind) {
        SubtitleCueKind.NONE -> stringResource(R.string.metrics_subtitle_cue_idle)
        SubtitleCueKind.TEXT -> stringResource(R.string.metrics_subtitle_cue_text)
        SubtitleCueKind.BITMAP -> stringResource(R.string.metrics_subtitle_cue_bitmap)
        SubtitleCueKind.MIXED -> stringResource(R.string.metrics_subtitle_cue_mixed)
    }
    return stringResource(
        R.string.metrics_subtitle_value,
        s.subtitleTrackMimeType ?: stringResource(R.string.metrics_unavailable),
        cueKind,
    )
}

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
