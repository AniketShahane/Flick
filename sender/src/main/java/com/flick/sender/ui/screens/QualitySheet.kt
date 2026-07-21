package com.flick.sender.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flick.sender.R
import com.flick.sender.media.MediaProbe
import com.flick.sender.model.HdrType
import com.flick.sender.net.FlickController
import com.flick.sender.ui.Format
import com.flick.sender.ui.components.LiveDot
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors

/** S10 — the quality sheet. Gauges, not a metrics wall; only this phone's facts. */
@Composable
fun QualitySheet(controller: FlickController, onDismiss: () -> Unit) {
    val colors = LocalFlickColors.current
    val context = LocalContext.current
    val signal = rememberSignalInfo()
    val state by controller.playback.collectAsState()
    val item by controller.castingItem.collectAsState()
    val hdr by produceState(initialValue = HdrType.NONE, item?.uri) {
        val uri = item?.uri
        value = if (uri != null) MediaProbe.detectHdr(context, uri) else HdrType.NONE
    }

    val neededMbps = when (item?.resolutionLabel) {
        "4K" -> 41
        "1080p" -> 18
        "HD" -> 10
        else -> 8
    }
    val throughputMbps = signal.throughputBitsPerSec / 1_000_000.0
    val throughputFraction = (throughputMbps / neededMbps).coerceIn(0.0, 1.0).toFloat()
    val bufferSeconds = state.bufferedMs / 1000.0
    val bufferFraction = (bufferSeconds / 12.0).coerceIn(0.0, 1.0).toFloat()
    val networkStatus = stringResource(R.string.a11y_network_status, signal.chipText())

    BottomSheet(onDismiss = onDismiss) {
        SheetGrabber()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.quality_title), style = FlickText.heading.copy(color = colors.onSurface))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.semantics { contentDescription = networkStatus },
            ) {
                LiveDot(colors.live, size = 5.dp, modifier = Modifier.padding(end = 5.dp))
                Text(
                    stringResource(if (signal.healthy) R.string.quality_healthy else R.string.quality_watch),
                    style = FlickText.caption.copy(color = if (signal.healthy) colors.live else colors.caution),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        GaugeSection(
            label = stringResource(R.string.quality_throughput),
            value = stringResource(
                R.string.quality_needs,
                Format.megabits(signal.throughputBitsPerSec),
                neededMbps,
            ),
            fraction = throughputFraction,
            color = if (signal.healthy) colors.live else colors.caution,
        )

        Spacer(Modifier.height(14.dp))
        Text(
            stringResource(R.string.quality_buffer),
            style = FlickText.mono.copy(color = colors.onSurfaceDim, fontWeight = FontWeight.SemiBold),
        )
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.5.dp)) {
            val filled = (bufferFraction * 10).toInt()
            repeat(10) { i ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (i < filled) colors.live else colors.onSurface.copy(alpha = 0.12f)),
                )
            }
        }
        Text(
            stringResource(R.string.quality_reserve, bufferSeconds),
            style = FlickText.mono.copy(color = colors.onSurface, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(top = 6.dp),
        )

        Spacer(Modifier.height(14.dp))
        Fact(
            stringResource(R.string.quality_playing),
            stringResource(
                R.string.quality_playing_value,
                item?.resolutionLabel ?: stringResource(R.string.media_unknown),
                hdrLabelFor(hdr),
            ),
        )
        Fact(
            stringResource(R.string.quality_network),
            stringResource(R.string.network_rssi, signal.bandLabel(), signal.rssiDbm),
        )

        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.quality_direct),
            style = FlickText.caption.copy(color = colors.onSurfaceFaint),
        )
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun hdrLabelFor(hdr: HdrType): String = when (hdr) {
    HdrType.DOLBY_VISION -> stringResource(R.string.media_hdr_dolby_vision)
    HdrType.HDR10 -> stringResource(R.string.media_hdr10)
    HdrType.NONE -> stringResource(R.string.media_sdr)
}

@Composable
private fun GaugeSection(label: String, value: String, fraction: Float, color: androidx.compose.ui.graphics.Color) {
    val colors = LocalFlickColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = FlickText.mono.copy(color = colors.onSurfaceDim, fontWeight = FontWeight.SemiBold))
        Text(value, style = FlickText.mono.copy(color = colors.onSurface, fontWeight = FontWeight.SemiBold))
    }
    Spacer(Modifier.height(6.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .height(7.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(colors.onSurface.copy(alpha = 0.12f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(7.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(color),
        )
    }
}

@Composable
private fun Fact(label: String, value: String) {
    val colors = LocalFlickColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = FlickText.caption.copy(color = colors.onSurfaceDim))
        Text(value, style = FlickText.caption.copy(color = colors.onSurface, fontWeight = FontWeight.SemiBold))
    }
}
