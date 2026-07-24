package com.flick.receiver.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.flick.receiver.R
import com.flick.receiver.player.DiagnosticsSnapshot
import com.flick.receiver.player.ThroughputSnapshot
import com.flick.receiver.ui.components.FlickTvIconButton
import com.flick.receiver.ui.components.GlassPanel
import com.flick.receiver.ui.components.GlassPanelTone
import com.flick.receiver.ui.components.LiveDot
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickIcons
import com.flick.receiver.ui.theme.FlickShape
import com.flick.receiver.ui.theme.FlickType

/**
 * Panel width. The spec draws 370 dp, which is the design's 740 px ÷ 2 — a number
 * taken before the §1a type floors tripled the relative size of this panel's mono
 * text, and the two cannot both hold. At the floors the widest row of real content
 * measures:
 *  - stats grid — `DROPPED FRAMES` is 14 chars of 16 sp mono at 0.12 em tracking
 *    ≈ 161 dp, so three equal columns plus two 10 dp gaps plus 2 × 17 dp padding
 *    need ≈ 538 dp;
 *  - header — "Stream metrics" at 27 sp (≈ 181 dp) + the `HEALTHY · DIRECT PLAY`
 *    pill at 16 sp mono (≈ 260 dp) + the 23 dp close button + 20 dp of gaps
 *    ≈ 484 dp of content.
 *
 * 540 dp is therefore the narrowest width at which no measured telemetry
 * ellipsises; anything nearer the spec's 370 dp would have to cut type below the
 * §9 floors. Shortening `metrics_label_dropped` to `DROPPED` and the two
 * `metrics_health_*` strings to their first word would take the panel to ≈ 450 dp
 * — a strings change, and one §5.5 should record.
 */
val StreamMetricsPanelWidth: Dp = 540.dp

/**
 * Histogram bar column height. The design draws 74 px ÷ 2 = 37 dp. The slot above
 * the transport panel is `540 dp − 2 × 27 dp safe inset − the transport panel −
 * 10 dp` ≈ 247 dp, and at the §1a floors this panel's header, throughput block
 * and nine stat cells already claim ≈ 250 dp of it. The bars give up 9 dp rather
 * than the last stat row losing its values to the scroll clip.
 */
private val HistogramHeight: Dp = 28.dp

private val HistogramBarGap: Dp = 2.5.dp

private val HistogramBarShape = RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)

/** Design 20 px / 16 px ÷ 2 — the gaps between and inside the stats grid. */
private val StatsColumnGap: Dp = 10.dp
private val StatsRowGap: Dp = 3.dp

/** A measured bar never collapses to nothing — the design floors it at 6 %. */
private const val MIN_BAR_FRACTION = 0.06f

/** Below half the rolling peak a bar tints [FlickColor.HistogramBarLow]. */
private const val LOW_BAR_THRESHOLD = 0.5f

/**
 * One cell of the 3 × 3 stats grid. [value] is already resolved — an unmeasurable
 * field arrives here as `metrics_unavailable` ("—"), never as a plausible-looking
 * placeholder.
 */
private data class StatCell(val label: String, val value: String, val tint: Color)

/**
 * What the health pill is allowed to claim.
 *
 * `DiagnosticsSnapshot.status` cannot drive it directly: `PASS` additionally
 * requires `isPlaying`, so a merely paused stream would fall to `WARN` and the
 * pill would assert `DEGRADED · RECOVERING` about a stream that is neither — and
 * paused is the state this panel is most often read in, because the chrome
 * auto-hide only arms while playing. The cumulative `rebufferCount` /
 * `droppedFrames` counters are excluded for the same reason: they are past
 * events, they are already reported by the grid, and one of them at startup would
 * otherwise pin the pill to "recovering" for the rest of the film.
 */
private enum class StreamHealth { Healthy, Degraded }

/**
 * The stream metrics panel (receiver-expressive-spec.md §5.5) — right-anchored
 * above the transport panel.
 *
 * Everything drawn here is measured: the histogram is the real
 * `bitrateEstimateBps` ring, the health pill reads only present-tense fields, and
 * every stat is a live field. The design's "18.4 GB" file-size chip is cut
 * because the receiver streams byte ranges and never learns the file's length.
 *
 * This is the tasteful read; the dense `MetricsOverlay` dev HUD stays a separate
 * opt-in Settings toggle.
 */
@Composable
fun StreamMetricsPanel(
    diagnostics: DiagnosticsSnapshot,
    throughput: ThroughputSnapshot,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unavailable = stringResource(R.string.metrics_unavailable)
    val health = streamHealth(diagnostics)
    val healthAccent = if (health == StreamHealth.Healthy) FlickColor.Live else FlickColor.Caution
    val healthWash = if (health == StreamHealth.Healthy) FlickColor.LiveWash else FlickColor.CautionWash

    // Focus enters on the close button, so the panel reads as entered and its own
    // Back handling below is reachable — `onKeyEvent` only sees keys while the
    // subtree holds focus.
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { closeFocus.requestFocus() } }

    GlassPanel(
        modifier = modifier
            .width(StreamMetricsPanelWidth)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Back) {
                    onDismiss()
                    true
                } else {
                    false
                }
            },
        shape = FlickShape.Xl,
        tone = GlassPanelTone.Panel,
        contentPadding = PaddingValues(horizontal = 17.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.metrics_panel_title),
                    style = FlickType.display(sizeSp = 27, trackingEm = -0.04f, lineHeightRatio = 1.05f),
                    color = Color.White,
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Nothing measured yet means no claim at all (§7).
                if (health != null) {
                    HealthPill(
                        text = stringResource(
                            if (health == StreamHealth.Healthy) {
                                R.string.metrics_health_healthy
                            } else {
                                R.string.metrics_health_degraded
                            },
                        ),
                        accent = healthAccent,
                        wash = healthWash,
                    )
                }
            }
            FlickTvIconButton(
                imageVector = FlickIcons.Close,
                contentDescription = stringResource(R.string.metrics_panel_close),
                onClick = onDismiss,
                focusRequester = closeFocus,
            )
        }

        // The body scrolls rather than overdrawing the transport panel if a
        // device's font metrics push it past the slot above the chrome.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ThroughputBlock(
                throughput = throughput,
                liveBitrateBps = diagnostics.bitrateEstimateBps,
                unavailable = unavailable,
            )
            StatsGrid(cells = statCells(diagnostics, unavailable))
        }
    }
}

/**
 * The only two things the pill can honestly say. Playback that has not started
 * yet reports nothing — see [StreamHealth].
 */
private fun streamHealth(s: DiagnosticsSnapshot): StreamHealth? = when {
    s.errorMessage != null -> StreamHealth.Degraded
    !s.playbackStarted -> null
    s.currentlyRebuffering -> StreamHealth.Degraded
    // Playing with no window ahead of the playhead is a stream under pressure;
    // a paused stream with a filled buffer is not.
    s.isPlaying && s.bufferedAheadMs <= 0L -> StreamHealth.Degraded
    else -> StreamHealth.Healthy
}

@Composable
private fun HealthPill(text: String, accent: Color, wash: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(FlickShape.Pill)
            .background(wash)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        LiveDot(color = accent, size = 6.dp)
        Text(
            text = text,
            style = FlickType.monoEyebrow(sizeSp = 16, trackingEm = 0.08f),
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * `THROUGHPUT · LAST 40 s` plus the live readout and the 40-bar histogram. The
 * ring hands out only bars it really measured, so a young session pads the
 * DRAWING with leading empty slots — never the data.
 */
@Composable
private fun ThroughputBlock(
    throughput: ThroughputSnapshot,
    liveBitrateBps: Long,
    unavailable: String,
) {
    val latest = if (throughput.latestBps > 0L) throughput.latestBps else liveBitrateBps
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.metrics_throughput_eyebrow),
                style = FlickType.monoEyebrow(sizeSp = 16, trackingEm = 0.14f).copy(lineHeight = 17.sp),
                color = FlickColor.OnPanelLabel,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (latest > 0L) {
                    stringResource(R.string.metrics_value_mbps, formatMbps(latest))
                } else {
                    unavailable
                },
                style = FlickType.monoTabular(sizeSp = 20, weight = FontWeight.SemiBold)
                    .copy(lineHeight = 22.sp),
                color = FlickColor.Spark,
                maxLines = 1,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(HistogramHeight),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(HistogramBarGap),
        ) {
            val slots = throughput.capacity.coerceAtLeast(1)
            val lead = (slots - throughput.size).coerceAtLeast(0)
            repeat(slots) { slot ->
                val index = slot - lead
                if (index < 0) {
                    // Nothing measured for this slot yet: leave it empty rather
                    // than drawing a zero-height bar that reads as a dropout.
                    Box(Modifier.weight(1f))
                } else {
                    val ratio = throughput.ratioAt(index)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(ratio.coerceAtLeast(MIN_BAR_FRACTION))
                            .clip(HistogramBarShape)
                            .background(
                                if (ratio < LOW_BAR_THRESHOLD) FlickColor.HistogramBarLow
                                else FlickColor.HistogramBar,
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatsGrid(cells: List<StatCell>) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(StatsRowGap),
    ) {
        cells.chunked(STATS_COLUMNS).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(StatsColumnGap),
            ) {
                row.forEach { cell ->
                    StatCellView(cell = cell, modifier = Modifier.weight(1f))
                }
                // Keep the last row's columns aligned with the rows above it.
                repeat(STATS_COLUMNS - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun StatCellView(cell: StatCell, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = cell.label,
            style = FlickType.monoEyebrow(sizeSp = 16, trackingEm = 0.12f).copy(lineHeight = 17.sp),
            color = FlickColor.OnPanelLabel,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = cell.value,
            style = FlickType.monoTabular(sizeSp = 20, weight = FontWeight.SemiBold)
                .copy(lineHeight = 22.sp),
            color = cell.tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val STATS_COLUMNS = 3

/** Buffer window below this reads as pressure rather than headroom. */
private const val BUFFER_WARN_MS = 2_000L

/** Probe round-trips above this are worth flagging on a direct-play LAN. */
private const val LATENCY_WARN_MS = 80L

/**
 * The nine grid cells, in the spec's reading order. Every unmeasurable field
 * resolves to [unavailable] — the panel never invents a number.
 */
@Composable
private fun statCells(s: DiagnosticsSnapshot, unavailable: String): List<StatCell> {
    val ink = FlickColor.OnSurface

    val resolution = if (s.width > 0 && s.height > 0) {
        stringResource(R.string.metrics_value_resolution, s.width, s.height)
    } else {
        unavailable
    }
    val codec = videoCodecRes(s.videoMimeType)?.let { stringResource(it) } ?: unavailable
    val frameRate = if (s.frameRate > 0f) {
        stringResource(R.string.metrics_value_frame_rate, formatFrameRate(s.frameRate))
    } else {
        unavailable
    }
    val bitrate = if (s.bitrateEstimateBps > 0L) {
        stringResource(R.string.metrics_value_mbps, formatMbps(s.bitrateEstimateBps))
    } else {
        unavailable
    }
    val bufferAhead = s.bufferedAheadMs
    val buffer = if (bufferAhead >= 0L) {
        stringResource(R.string.metrics_value_seconds, formatSeconds(bufferAhead))
    } else {
        unavailable
    }
    val latency = if (s.probeLatencyMs > 0L) {
        stringResource(R.string.metrics_value_millis, s.probeLatencyMs)
    } else {
        unavailable
    }
    val transport = s.wifiBand?.let { stringResource(R.string.metrics_value_transport, it) } ?: unavailable

    return listOf(
        StatCell(stringResource(R.string.metrics_label_resolution), resolution, ink),
        StatCell(stringResource(R.string.metrics_label_codec), codec, ink),
        StatCell(stringResource(R.string.metrics_label_frame_rate), frameRate, ink),
        StatCell(stringResource(R.string.metrics_label_bitrate), bitrate, FlickColor.Spark),
        StatCell(
            label = stringResource(R.string.metrics_label_buffer),
            value = buffer,
            tint = if (bufferAhead in 0L until BUFFER_WARN_MS) FlickColor.Caution else ink,
        ),
        StatCell(
            label = stringResource(R.string.metrics_label_latency),
            value = latency,
            tint = if (s.probeLatencyMs > LATENCY_WARN_MS) FlickColor.Caution else ink,
        ),
        StatCell(
            label = stringResource(R.string.metrics_label_dropped),
            value = stringResource(R.string.metrics_value_dropped, s.droppedFrames),
            tint = if (s.droppedFrames == 0L) FlickColor.Live else FlickColor.Caution,
        ),
        StatCell(stringResource(R.string.metrics_label_decoder), s.decoderName ?: unavailable, ink),
        StatCell(stringResource(R.string.metrics_label_transport), transport, ink),
    )
}
