package com.flick.sender.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.flick.sender.R
import com.flick.sender.media.MediaProbe
import com.flick.sender.model.HdrType
import com.flick.sender.model.MediaItem
import com.flick.sender.model.PlaybackUiState
import com.flick.sender.net.FlickController
import com.flick.sender.ui.theme.FlickCinematicTheme
import com.flick.sender.ui.theme.FlickCorners
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.PillShape
import com.flick.sender.ui.theme.flickRipple
import com.flick.sender.ui.theme.pressScale
import java.util.Locale

/** Alternating fact-row tint. A one-off wash, deliberately not a palette role. */
private val FactRowTint = Color.White.copy(alpha = 0.05f)

/** Seconds of reserve the buffer gauge treats as a full bar. */
private const val BufferFullSeconds = 12.0

/** The gauge's own rule. Clipped to a pill, so the fill is drawn at the same radius. */
private val GaugeBarHeight = 8.dp

/**
 * S10 — the quality sheet. Two gauges and four facts, all of them this phone's
 * own measurements. Forced cinematic: signal reads as instrumentation, and the
 * sheet must look the same whichever theme the library resolved to.
 */
@Composable
fun QualitySheet(controller: FlickController, onDismiss: () -> Unit) {
    FlickCinematicTheme {
        QualityContent(controller, onDismiss)
    }
}

@Composable
private fun QualityContent(controller: FlickController, onDismiss: () -> Unit) {
    val colors = LocalFlickColors.current
    val context = LocalContext.current
    val signal = rememberSignalInfo()
    // Kept as State: the session clock ticks ~10 Hz and only the buffer gauge reads it,
    // so unwrapping it here would re-run the whole sheet ten times a second.
    val playbackState = controller.playback.collectAsState()
    val item by controller.castingItem.collectAsState()
    // Null until the container has actually been parsed. [HdrType.NONE] is the probe's
    // verdict "no HDR here", which the sheet states as "SDR" — seeding with it would
    // make that claim about every file for the length of the probe.
    val hdr by produceState<HdrType?>(initialValue = null, item?.uri) {
        val uri = item?.uri
        value = if (uri != null) MediaProbe.detectHdr(context, uri) else null
    }

    val neededMbps = when (item?.resolutionLabel) {
        "4K" -> 41
        "1080p" -> 18
        "HD" -> 10
        else -> 8
    }
    val throughputMbps = signal.throughputBitsPerSec / 1_000_000.0
    // TransferTelemetry only counts bytes this phone's server actually wrote, and it
    // is reset per cast — zero means "nothing is being served", never "no bandwidth".
    val serving = signal.serving
    val throughputFraction = (throughputMbps / neededMbps).coerceIn(0.0, 1.0).toFloat()

    val unknown = stringResource(R.string.media_unknown)
    val signalColor = if (signal.healthy) colors.link else colors.caution
    val networkStatus = stringResource(R.string.a11y_network_status, signal.chipText())

    BottomSheet(
        onDismiss = onDismiss,
        contentPadding = PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 26.dp),
    ) {
        SheetGrabber()
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.quality_title), style = FlickText.headlineMedium.copy(color = colors.onSurface))
        Spacer(Modifier.height(5.dp))
        Text(
            text = item?.name?.let { stringResource(R.string.quality_sub, it, signal.bandLabel()) }
                ?: stringResource(R.string.quality_sub_idle),
            style = FlickText.bodyMedium.copy(color = colors.onSurfaceDim),
        )

        Spacer(Modifier.height(18.dp))
        Row(
            // The two gauges are one instrument pair: the taller card's content sets the
            // height and the shorter one grows into it, so a longer eyebrow or a reading
            // that had to wrap can never leave one bar sitting above the other.
            Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            GaugeCard(
                eyebrow = stringResource(R.string.quality_throughput),
                value = if (serving) gaugeReading(throughputMbps) else unknown,
                unit = stringResource(R.string.quality_unit_mbps),
                known = serving,
                fraction = { if (serving) throughputFraction else 0f },
                barColor = signalColor,
            )
            BufferGauge(playbackState = playbackState, casting = item != null)
        }

        Spacer(Modifier.height(18.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(FlickCorners.qualityCard))
                .background(colors.fillCard),
        ) {
            FactRow(
                label = stringResource(R.string.quality_fact_resolution),
                value = item?.let { resolutionValue(it) } ?: unknown,
                valueColor = if (item == null) colors.onSurfaceFaint else colors.onSurface,
            )
            FactRow(
                label = stringResource(R.string.quality_fact_range),
                value = hdr?.let { hdrLabelFor(it) } ?: unknown,
                valueColor = if (hdr == null) colors.onSurfaceFaint else colors.sparkBright,
                tinted = true,
            )
            // The decoder is chosen by Media3 on the TV and no control frame carries
            // it back, so this row has no live source and must not invent one.
            FactRow(
                label = stringResource(R.string.quality_fact_decoder),
                value = unknown,
                valueColor = colors.onSurfaceFaint,
                valueStyle = FlickText.monoSmall,
            )
            FactRow(
                label = stringResource(R.string.quality_fact_wifi),
                value = signal.linkLabel(),
                valueColor = if (signal.hasLink) signalColor else colors.onSurfaceFaint,
                tinted = true,
                modifier = Modifier.semantics { contentDescription = networkStatus },
            )
        }

        Spacer(Modifier.height(18.dp))
        val doneInteraction = remember { MutableInteractionSource() }
        // Read inside the sheet that provides it. Flipping the host's state here instead
        // would leave the removal to the shell's overlay fade — animated, but a dissolve
        // in place rather than the travel the scrim, Back and a drag all play, so one
        // sheet would leave two different ways depending on how it was dismissed.
        val done = LocalSheetDismiss.current
        Text(
            text = stringResource(R.string.quality_done),
            style = FlickText.titleSmall.copy(color = colors.onInverseSurface),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .pressScale(doneInteraction)
                .clip(PillShape)
                .background(colors.inverseSurface)
                .clickable(
                    interactionSource = doneInteraction,
                    // The pill is pale against the cinematic sheet; only the ink that
                    // sits on it is visible as a ripple.
                    indication = flickRipple(colors.onInverseSurface),
                    role = Role.Button,
                    onClick = done,
                )
                .heightIn(min = 48.dp)
                .padding(vertical = 17.dp),
        )
    }
}

/**
 * The one gauge fed by the session clock, isolated so the ~10 Hz tick stops here. The
 * TV reports an absolute buffered position, so the honest reserve is the difference
 * ahead of the confirmed playhead — `bufferedMs` alone would climb with the film.
 */
@Composable
private fun RowScope.BufferGauge(playbackState: State<PlaybackUiState>, casting: Boolean) {
    val colors = LocalFlickColors.current
    // Null is "there is no reserve to report", which is not the same claim as zero.
    val reserve = remember(playbackState, casting) {
        derivedStateOf {
            val state = playbackState.value
            if (!casting || state.durationMs <= 0L) {
                null
            } else {
                (state.bufferedMs - state.confirmedMs).coerceAtLeast(0L) / 1000.0
            }
        }
    }
    // The clock ticks ten times a second and the reading has a tenth of a second of
    // resolution, so the label is derived: the card recomposes when the printed figure
    // moves, not when the clock does.
    val reading by remember(reserve) { derivedStateOf { reserve.value?.let(::gaugeReading) } }
    GaugeCard(
        eyebrow = stringResource(R.string.quality_buffer),
        value = reading ?: stringResource(R.string.media_unknown),
        unit = stringResource(R.string.quality_unit_seconds),
        known = reading != null,
        // A lambda: the bar is the only thing here that genuinely follows the clock, and
        // it follows it in the draw phase.
        fraction = { ((reserve.value ?: 0.0) / BufferFullSeconds).coerceIn(0.0, 1.0).toFloat() },
        barColor = colors.link,
    )
}

/**
 * Pinned to [Locale.US] the way every other reading in the app is (see `Format`). A
 * string resource would have formatted against the device locale, which puts "61,4" in
 * this gauge while the pill beside it still reads "61.4 Mb/s" from `Format.megabits` —
 * one measurement, two decimal marks, in one session.
 */
internal fun gaugeReading(value: Double): String = String.format(Locale.US, "%.1f", value)

/**
 * One instrument: eyebrow, reading, unit, bar. The reading carries no unit of its own
 * because "61.4 Mb/s" is wider than this card on a 360 dp frame — it wrapped at the
 * space, which both broke the number in half and made the throughput card a whole line
 * taller than its pair. The bar is pushed to the foot of the card so that whatever the
 * lines above cost, the two bars still read as one scale.
 */
@Composable
private fun RowScope.GaugeCard(
    eyebrow: String,
    value: String,
    unit: String,
    known: Boolean,
    fraction: () -> Float,
    barColor: Color,
) {
    val colors = LocalFlickColors.current
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(RoundedCornerShape(FlickCorners.qualityCard))
            .background(colors.fillCard)
            .padding(17.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(eyebrow, style = FlickText.monoEyebrow.copy(color = colors.onSurfaceFaint))
        // Weighted, so the card that came up short spends the difference here and the
        // bar below it stays on the line its pair's bar is on.
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = value,
                style = FlickText.monoGauge.copy(color = if (known) colors.onSurface else colors.onSurfaceFaint),
            )
            // Drawn even for an unknown reading: the unit says what the gauge measures,
            // which is true whether or not there is a number for it yet.
            Text(unit, style = FlickText.monoSmall.copy(color = colors.onSurfaceFaint))
        }
        GaugeBar(fraction = fraction, barColor = barColor)
    }
}

/**
 * The filled span is drawn rather than laid out. A width the layout owns put the buffer
 * gauge's ~10 Hz reading through a measure pass of the whole sheet — including every
 * frame the sheet's own radial reveal was still travelling on.
 */
@Composable
private fun GaugeBar(fraction: () -> Float, barColor: Color) {
    val colors = LocalFlickColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(GaugeBarHeight)
            .clip(PillShape)
            .background(colors.fillTrack)
            .drawBehind {
                val filled = size.width * fraction().coerceIn(0f, 1f)
                if (filled > 0f) {
                    drawRoundRect(
                        color = barColor,
                        size = Size(filled, size.height),
                        cornerRadius = CornerRadius(size.height / 2f),
                    )
                }
            },
    )
}

@Composable
private fun FactRow(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
    tinted: Boolean = false,
    valueStyle: TextStyle = FlickText.bodyMedium,
) {
    val colors = LocalFlickColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (tinted) FactRowTint else Color.Transparent)
            .padding(horizontal = 17.dp, vertical = 15.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = FlickText.bodyMedium.copy(color = colors.onSurfaceFaint))
        Text(value, style = valueStyle.copy(color = valueColor), textAlign = TextAlign.End)
    }
}

@Composable
private fun resolutionValue(item: MediaItem): String =
    if (item.width > 0 && item.height > 0) {
        stringResource(R.string.sheet_resolution_pixels, item.resolutionLabel, item.width, item.height)
    } else {
        item.resolutionLabel
    }

@Composable
private fun hdrLabelFor(hdr: HdrType): String = when (hdr) {
    HdrType.DOLBY_VISION -> stringResource(R.string.media_hdr_dolby_vision)
    HdrType.HDR10 -> stringResource(R.string.media_hdr10)
    HdrType.NONE -> stringResource(R.string.media_sdr)
}
