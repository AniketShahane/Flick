package com.flick.receiver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.flick.receiver.player.DiagnosticsSnapshot
import com.flick.receiver.player.PlaybackStatus
import java.util.Locale

private val LabelColor = Color(0xFF90A4AE)
private val ValueColor = Color(0xFFECEFF1)
private val GoodColor = Color(0xFF00E676)
private val WarnColor = Color(0xFFFFC107)
private val BadColor = Color(0xFFFF5252)
private val IdleColor = Color(0xFFB0BEC5)

/**
 * The live debug overlay — the core deliverable. Renders every direct-play
 * metric and a big legible PASS / WARN / ERROR banner so a stall is impossible
 * to miss. Purely presentational; it never takes D-pad focus.
 */
@Composable
fun DebugOverlay(
    snapshot: DiagnosticsSnapshot,
    url: String,
    modifier: Modifier = Modifier,
) {
    val statusColor = snapshot.status.color()

    Column(
        modifier = modifier
            .widthIn(min = 340.dp, max = 480.dp)
            .background(Color(0xD9000000), RoundedCornerShape(12.dp))
            .border(2.dp, statusColor, RoundedCornerShape(12.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = snapshot.status.headline(),
            color = statusColor,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "pass = 0 rebuffers • ~0 dropped • buffer > 0",
            color = LabelColor,
            fontSize = 12.sp,
        )

        Spacer(Modifier.height(6.dp))

        MetricLine("State", snapshot.stateLabel)

        val resolution = if (snapshot.width > 0) "${snapshot.width} x ${snapshot.height}" else "—"
        MetricLine("Resolution", resolution)
        MetricLine(
            label = "4K UHD (>= 3840x2160)",
            value = if (snapshot.is4k) "YES" else "NO",
            valueColor = if (snapshot.is4k) GoodColor else IdleColor,
        )
        MetricLine("Frame rate", formatFps(snapshot.frameRate))

        Spacer(Modifier.height(6.dp))

        MetricLine(
            label = "Rebuffers",
            value = snapshot.rebufferCount.toString() +
                if (snapshot.currentlyRebuffering) "  (stalling…)" else "",
            valueColor = if (snapshot.rebufferCount == 0) GoodColor else BadColor,
        )
        MetricLine(
            label = "Rebuffer time",
            value = "${snapshot.cumulativeRebufferMs} ms",
            valueColor = if (snapshot.cumulativeRebufferMs == 0L) GoodColor else WarnColor,
        )
        MetricLine(
            label = "Dropped frames",
            value = snapshot.droppedFrames.toString(),
            valueColor = if (snapshot.droppedFrames == 0L) GoodColor else BadColor,
        )
        MetricLine(
            label = "Buffered ahead",
            value = formatSeconds(snapshot.bufferedAheadMs),
            valueColor = if (snapshot.bufferedAheadMs > 0L) GoodColor else IdleColor,
        )

        Spacer(Modifier.height(6.dp))

        MetricLine("Est. bandwidth", formatMbps(snapshot.bitrateEstimateBps))
        MetricLine("Decoder", snapshot.decoderName ?: "—")
        if (snapshot.wifiBand != null) {
            MetricLine(
                label = "TV Wi-Fi",
                // Some drivers report linkSpeed -1 (LINK_SPEED_UNKNOWN) / rssi 0 even
                // with a valid band; omit those rather than render "-1 Mb/s" / "0 dBm".
                value = buildString {
                    append(snapshot.wifiBand)
                    if (snapshot.wifiLinkSpeedMbps > 0) append(" - ${snapshot.wifiLinkSpeedMbps} Mb/s")
                    if (snapshot.wifiRssiDbm < 0) append(" - ${snapshot.wifiRssiDbm} dBm")
                },
            )
        } else {
            MetricLine("TV net", "wired/unknown")
        }
        MetricLine(
            label = "Auto-recoveries",
            value = snapshot.autoRecoveryCount.toString(),
            valueColor = if (snapshot.autoRecoveryCount == 0) IdleColor else WarnColor,
        )
        MetricLine("Pre-flight probe", formatProbe(snapshot.probeLatencyMs))
        MetricLine(
            "Position",
            "${formatClock(snapshot.positionMs)} / ${formatClock(snapshot.durationMs)}",
        )
        MetricLine("Source", url)

        if (snapshot.errorMessage != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "ERROR ${snapshot.errorCode} " +
                    (snapshot.errorCodeName ?: ""),
                color = BadColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = snapshot.errorMessage,
                color = BadColor,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun MetricLine(
    label: String,
    value: String,
    valueColor: Color = ValueColor,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            color = LabelColor,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            color = valueColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun PlaybackStatus.color(): Color = when (this) {
    PlaybackStatus.PASS -> GoodColor
    PlaybackStatus.WARN -> WarnColor
    PlaybackStatus.ERROR -> BadColor
    PlaybackStatus.IDLE -> IdleColor
}

private fun PlaybackStatus.headline(): String = when (this) {
    PlaybackStatus.PASS -> "DIRECT-PLAY OK"
    PlaybackStatus.WARN -> "PLAYBACK DEGRADED"
    PlaybackStatus.ERROR -> "PLAYBACK ERROR"
    PlaybackStatus.IDLE -> "WAITING FOR STREAM"
}

private fun formatFps(fps: Float): String =
    if (fps <= 0f) "n/a" else String.format(Locale.US, "%.3f fps", fps)

private fun formatMbps(bps: Long): String =
    if (bps <= 0L) "n/a" else String.format(Locale.US, "%.1f Mbps", bps / 1_000_000.0)

private fun formatProbe(ms: Long): String =
    if (ms <= 0L) "n/a" else "$ms ms"

private fun formatSeconds(ms: Long): String =
    String.format(Locale.US, "%.1f s", ms / 1000.0)

private fun formatClock(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}
