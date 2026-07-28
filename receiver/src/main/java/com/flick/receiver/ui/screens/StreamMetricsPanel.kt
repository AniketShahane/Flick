package com.flick.receiver.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
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
import com.flick.receiver.ui.theme.FlickMotion
import com.flick.receiver.ui.theme.FlickShape
import com.flick.receiver.ui.theme.FlickSpace
import com.flick.receiver.ui.theme.FlickType
import com.flick.receiver.ui.theme.LocalReducedMotion

/**
 * Panel width. The spec draws 370 dp, which is the design's 740 px ÷ 2. The panel
 * cannot reach it: the width is set by the longest stat label, and that is a
 * measurement, not a taste call. Geist Mono advances exactly 0.6 em per glyph and
 * Compose adds `letterSpacing` after every glyph, so at the 14 sp label size:
 *  - stats grid — `DROPPED FRAMES` is 14 × (0.6 + 0.12) × 14 sp = 141.1 dp, and
 *    the three columns are equal weights, so the content box needs
 *    3 × 141.1 + 2 × 10 dp of gap = 443.3 dp, plus 2 × 17 dp of panel padding;
 *  - header — "Stream metrics" at 22 sp Bricolage ≈ 147 dp + the
 *    `DEGRADED · RECOVERING` pill (21 × 0.68 × 14 sp + 18 dp padding + a 6 dp dot
 *    + a 7 dp gap ≈ 231 dp) + the 19 dp close button + 20 dp of gaps ≈ 417 dp.
 *
 * 488 dp gives the grid a 144.7 dp column — 3.6 dp of margin on the binding
 * label — and leaves the header 29 dp spare. Shortening `metrics_label_dropped`
 * to `DROPPED` would take the panel to ≈ 390 dp; that is a strings change, and one
 * §5.5 should record.
 */
val StreamMetricsPanelWidth: Dp = 488.dp

/**
 * Histogram bar column height. The design draws 74 px ÷ 2 = 37 dp; 28 dp is that
 * value minus the ~24 % every other non-text element in the panel gave up, so it
 * stays in proportion with the type around it rather than dominating the block.
 */
private val HistogramHeight: Dp = 28.dp

private val HistogramBarGap: Dp = 2.5.dp

private val HistogramBarShape = RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)

/**
 * Gaps between and inside the stats grid. The design draws 20 px / 16 px ÷ 2; the
 * row gap had been crushed to 3 dp to buy vertical room the smaller type no longer
 * needs, so it goes back to a real gap.
 */
private val StatsColumnGap: Dp = FlickSpace.Sm
private val StatsRowGap: Dp = FlickSpace.Xs

/**
 * The readout stagger — the throughput block, then the rows [packStatRows]
 * produces: four of them, because the DECODER cell takes a row to itself.
 *
 * Four stages, not one per cell: nine leads would leave the last number most of a
 * second behind the first, and a nine-step cascade read from 3 m looks like the
 * panel struggling to fill itself rather than like an order of reading. The lead
 * is clamped in [metricsStageProgress], so the fourth row arrives WITH the third
 * rather than lengthening the tail — and a fifth would too.
 *
 * This is the panel's CONTENTS resolving, not a second arrival: the playback
 * chrome still owns the panel's own enter and exit (`animateEntrance = false`
 * below), and this one-way latch settles at 1 and stays there, so the parent's
 * fade always has a fully-drawn panel to take off screen.
 */
private const val MetricsStageLead = 0.14f
private const val MetricsStageCount = 4

/** Readouts arrive from just below their resting line. */
private val MetricsStageRise: Dp = 8.dp

private fun metricsStageProgress(progress: Float, index: Int): Float {
    val lead = MetricsStageLead * index.coerceIn(0, MetricsStageCount - 1)
    val span = 1f - MetricsStageLead * (MetricsStageCount - 1)
    return ((progress - lead) / span).coerceIn(0f, 1f)
}

/**
 * One readout's entrance. `graphicsLayer` only, and the driver is read inside the
 * layer block so a stat never recomposes to arrive. Alpha takes the CLAMPED stage:
 * the driver is a spatial spring and settles past 1, which geometry may do and an
 * opacity may not.
 *
 * [settled] drops the layer the moment the readout has filled in. This panel sits
 * over a live decoder, so a finished entrance that keeps a render node per staged
 * readout — and, while alpha is under 1, an offscreen buffer per node — is exactly
 * the cost the film cannot afford to keep paying.
 */
private fun Modifier.metricsStage(
    progress: () -> Float,
    index: Int,
    settled: Boolean,
): Modifier = if (settled) {
    this
} else {
    graphicsLayer {
        val stage = metricsStageProgress(progress(), index)
        alpha = stage
        translationY = (1f - stage) * MetricsStageRise.toPx()
    }
}

/** A measured bar never collapses to nothing — the design floors it at 6 %. */
private const val MIN_BAR_FRACTION = 0.06f

/** Below half the rolling peak a bar tints [FlickColor.HistogramBarLow]. */
private const val LOW_BAR_THRESHOLD = 0.5f

/**
 * One cell of the stats grid. [value] is already resolved — an unmeasurable field
 * arrives here as `metrics_unavailable` ("—"), never as a plausible-looking
 * placeholder.
 *
 * [span] is in grid columns. One value on this panel is an identifier rather than
 * a number and cannot be read from its head: see the DECODER cell.
 */
internal data class StatCell(
    val label: String,
    val value: String,
    val tint: Color,
    val span: Int = 1,
)

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
    /** Changes for every open, including a reopen while an exit is retained. */
    entryKey: Int = 0,
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
    LaunchedEffect(entryKey) { runCatching { closeFocus.requestFocus() } }

    // One driver for the whole readout stagger, restarted per open so a reopened
    // panel fills in again rather than appearing already complete. Keyed on
    // [entryKey] rather than on first composition so it holds whether or not the
    // caller also rebuilds this subtree per open.
    val reducedMotion = LocalReducedMotion.current
    val entranceSpec: FiniteAnimationSpec<Float> = FlickMotion.panelSpatial()
    val entrance = remember { Animatable(0f) }
    var entranceSettled by remember { mutableStateOf(false) }
    LaunchedEffect(entryKey, reducedMotion) {
        entranceSettled = false
        if (reducedMotion) {
            entrance.snapTo(1f)
        } else {
            entrance.snapTo(0f)
            entrance.animateTo(1f, entranceSpec)
        }
        entranceSettled = true
    }
    val stage = { entrance.value }

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
        verticalArrangement = Arrangement.spacedBy(FlickSpace.Sm),
        // The playback chrome owns this panel's enter AND exit (spec B7). A second
        // entrance latch here would compound the parent's edge drift with a rise
        // and a fade of its own, and could never produce an exit at all.
        animateEntrance = false,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FlickSpace.Sm),
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FlickSpace.Sm),
            ) {
                Text(
                    text = stringResource(R.string.metrics_panel_title),
                    style = FlickType.display(sizeSp = 22),
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
            verticalArrangement = Arrangement.spacedBy(FlickSpace.Sm),
        ) {
            ThroughputBlock(
                throughput = throughput,
                liveBitrateBps = diagnostics.bitrateEstimateBps,
                unavailable = unavailable,
                modifier = Modifier.metricsStage(stage, index = 0, settled = entranceSettled),
            )
            StatsGrid(
                cells = statCells(diagnostics, unavailable),
                stage = stage,
                settled = entranceSettled,
            )
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
            style = FlickType.monoEyebrow(trackingEm = 0.08f),
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
    modifier: Modifier = Modifier,
) {
    val latest = if (throughput.latestBps > 0L) throughput.latestBps else liveBitrateBps
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.metrics_throughput_eyebrow),
                style = FlickType.monoEyebrow(trackingEm = 0.14f).copy(lineHeight = 15.sp),
                color = FlickColor.OnPanelLabel,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The reading SNAPS. It is a measurement, and a measurement that
            // travels between values is a fabricated one — quite apart from
            // running an animation once a sample, forever, over a live decoder.
            // Only the bounded histogram gauge below animates, and on an effects
            // spec so it can never draw a throughput that was never measured.
            Text(
                text = if (latest > 0L) {
                    stringResource(R.string.metrics_value_mbps, formatMbps(latest))
                } else {
                    unavailable
                },
                style = FlickType.monoTabular(sizeSp = 16, weight = FontWeight.SemiBold)
                    .copy(lineHeight = 18.sp),
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
                    // Each slot animates to the value that has just marched into
                    // it, so the whole row reads as sliding left one sample at a
                    // time and new data enters from the right.
                    HistogramBar(ratio = throughput.ratioAt(index), modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * One measured histogram slot.
 *
 * The height is a gauge fraction, so it takes the EFFECTS spec: a spatial spring
 * would overshoot and draw, for a few frames, a throughput the receiver never
 * measured. A slot's first real sample snaps — `animateFloatAsState` starts at its
 * target — so an empty slot never grows up out of the floor.
 */
@Composable
private fun HistogramBar(ratio: Float, modifier: Modifier = Modifier) {
    val fraction by animateFloatAsState(
        targetValue = ratio.coerceAtLeast(MIN_BAR_FRACTION),
        animationSpec = FlickMotion.stateEffects(),
        label = "histogramBar",
    )
    val tint by animateColorAsState(
        targetValue = if (ratio < LOW_BAR_THRESHOLD) FlickColor.HistogramBarLow
        else FlickColor.HistogramBar,
        animationSpec = FlickMotion.stateEffects(),
        label = "histogramBarTint",
    )
    Box(
        modifier = modifier
            .fillMaxHeight()
            // Scale rather than height: the Row is fixed at HistogramHeight and
            // this panel sits over a live decoder, so a bar may never relayout.
            // The 2 dp cap squashes by the same factor and stays sub-pixel.
            .graphicsLayer {
                scaleY = fraction
                transformOrigin = TransformOrigin(0.5f, 1f)
            }
            .clip(HistogramBarShape)
            .background(tint),
    )
}

@Composable
private fun StatsGrid(cells: List<StatCell>, stage: () -> Float, settled: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(StatsRowGap),
    ) {
        // Staged a ROW at a time. The grid reads left-to-right then down, so the
        // row is the unit the eye already follows; nine independently arriving
        // cells would read as scatter.
        packStatRows(cells).forEachIndexed { index, row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .metricsStage(stage, index = index + 1, settled = settled),
                horizontalArrangement = Arrangement.spacedBy(StatsColumnGap),
            ) {
                row.forEach { cell ->
                    StatCellView(cell = cell, modifier = Modifier.weight(cell.span.toFloat()))
                }
                // Keep the last row's columns aligned with the rows above it.
                repeat(STATS_COLUMNS - row.sumOf { it.span }) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun StatCellView(cell: StatCell, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // Label and value are one size apart, not one weight: 14 sp tracked mono
        // over 16 sp tabular mono, so the number is what the eye lands on.
        Text(
            text = cell.label,
            style = FlickType.monoEyebrow(trackingEm = 0.12f).copy(lineHeight = 15.sp),
            color = FlickColor.OnPanelLabel,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = cell.value,
            style = FlickType.monoTabular(sizeSp = 16, weight = FontWeight.SemiBold)
                .copy(lineHeight = 18.sp),
            color = cell.tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val STATS_COLUMNS = 3

/**
 * Fills rows left to right, breaking before a cell that will not fit the columns
 * left in the current one. A cell wider than the grid would otherwise be packed
 * against cells it cannot share a row with.
 */
internal fun packStatRows(cells: List<StatCell>): List<List<StatCell>> {
    val rows = mutableListOf<List<StatCell>>()
    var row = mutableListOf<StatCell>()
    var filled = 0
    cells.forEach { cell ->
        if (filled > 0 && filled + cell.span > STATS_COLUMNS) {
            rows += row
            row = mutableListOf()
            filled = 0
        }
        row += cell
        filled += cell.span
    }
    if (row.isNotEmpty()) rows += row
    return rows
}

/** Buffer window below this reads as pressure rather than headroom. */
private const val BUFFER_WARN_MS = 2_000L

/** Probe round-trips above this are worth flagging on a direct-play LAN. */
private const val LATENCY_WARN_MS = 80L

/**
 * The nine grid cells, in the spec's reading order but for DECODER, which trades
 * places with TRANSPORT to reach the full-width row it needs. Every unmeasurable
 * field resolves to [unavailable] — the panel never invents a number.
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
        StatCell(stringResource(R.string.metrics_label_transport), transport, ink),
        // Last, and the full width of the grid. This is the one stat whose value is
        // an identifier rather than a number, and the identifying half is its TAIL:
        // the decoders this project ships against are `c2.mtk.avc.decoder` (18) and
        // `c2.mtk.dvhe.sth.decoder` (23), while a 144.7 dp column holds about 15
        // glyphs of the 16 sp mono advance — every one of them ellipsised away the
        // codec and kept the vendor prefix.
        StatCell(
            label = stringResource(R.string.metrics_label_decoder),
            value = s.decoderName ?: unavailable,
            tint = ink,
            span = STATS_COLUMNS,
        ),
    )
}
