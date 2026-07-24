package com.flick.receiver.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.flick.receiver.R
import com.flick.receiver.player.SubtitleTrackInfo
import com.flick.receiver.ui.components.FlickTvButton
import com.flick.receiver.ui.components.FlickTvIconButton
import com.flick.receiver.ui.components.FlickTvRow
import com.flick.receiver.ui.components.GlassPanel
import com.flick.receiver.ui.components.GlassPanelTone
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickIcons
import com.flick.receiver.ui.theme.FlickShape
import com.flick.receiver.ui.theme.FlickType

/**
 * The three fixed caption sizes offered by the subtitles panel (spec §5.4).
 *
 * [scale] multiplies the receiver's existing viewport-relative caption size —
 * the value ReceiverApp computes through `reducedSubtitleTextSizeSp` — so the
 * platform caption-manager scale and the layout listeners keep working; this is
 * a user preference layered on top, never a replacement.
 */
enum class SubtitleSize(val scale: Float, @param:StringRes val labelRes: Int) {
    Small(0.85f, R.string.subtitles_size_small),
    Medium(1f, R.string.subtitles_size_medium),
    Large(1.25f, R.string.subtitles_size_large),
    ;

    companion object {
        /**
         * The one immutable listing of the three sizes. `values()` clones its
         * array on every call, and the selector below sits in a composable that
         * re-runs whenever the 2 Hz track re-read produces a different list.
         */
        val ALL: List<SubtitleSize> = SubtitleSize.values().asList()
    }
}

/**
 * Panel width. The spec draws 310 dp; the §1a type floors push the three size
 * cells (24 sp reading copy) past that, so the panel is 30 dp wider. Everything
 * else keeps its ÷2 design metrics.
 */
val SubtitlesPanelWidth: Dp = 340.dp

/** Track-row markers are drawn at the ÷2 design size. */
private val TrackMarkSize: Dp = 16.dp

/**
 * Horizontal inset on each track row. `Modifier.verticalScroll` clips hard at
 * `0..width` on the cross axis, and a focused row's ring is drawn 5.5 dp outside
 * its bounds with a 2.5 dp stroke *inside* the same layer that scales the row by
 * 1.06 — so the ring's outer edge sits `(rowHalfWidth + 6.75 dp) × 1.06` from the
 * row's centre. At this panel width that needs ≈ 15.5 dp of inset; 17 dp keeps a
 * margin for the row border and rounding.
 */
private val FocusRingBleed: Dp = 17.dp

/**
 * The scroll container inflates its clip by the max supported elevation on the
 * scroll axis, so the first and last rings survive on their own; these spacers
 * only stop a ring from painting over the header or the size selector.
 */
private val FocusRingBleedVertical: Dp = 6.dp

/**
 * The subtitles panel (receiver-expressive-spec.md §5.4) — left-anchored above
 * the transport panel.
 *
 * Every row is real: [tracks] comes from Media3's live text tracks, and the meta
 * chip names the format the container actually carries. A track the player would
 * not render is never listed, and a format the mapper cannot name simply loses
 * that half of the chip rather than showing a guess.
 *
 * Focus enters on the currently selected row so the panel opens where the user
 * left off; `Back` calls [onDismiss], which is also what the close button does.
 *
 * The panel must be measured with a **bounded height** — the track list claims
 * the space the header and the size selector do not need, and scrolls inside it.
 */
@Composable
fun SubtitlesPanel(
    tracks: List<SubtitleTrackInfo>,
    size: SubtitleSize,
    onSelectTrack: (String?) -> Unit,
    onSelectSize: (SubtitleSize) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { entryFocus.requestFocus() } }

    val selectedIndex = tracks.indexOfFirst { it.isSelected }
    val offSelected = selectedIndex < 0

    GlassPanel(
        modifier = modifier
            .width(SubtitlesPanelWidth)
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
        contentPadding = PaddingValues(horizontal = 17.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.subtitles_panel_title),
                style = FlickType.display(sizeSp = 27, trackingEm = -0.04f, lineHeightRatio = 1.05f),
                color = Color.White,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            FlickTvIconButton(
                imageVector = FlickIcons.Close,
                contentDescription = stringResource(R.string.subtitles_close),
                onClick = onDismiss,
            )
        }

        // The list claims whatever the fixed rows leave. Rows are inset so the
        // detached focus ring survives the scroll container's horizontal clip.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Spacer(Modifier.height(FocusRingBleedVertical))
            TrackRow(
                label = stringResource(R.string.subtitles_off),
                meta = null,
                selected = offSelected,
                focusRequester = if (offSelected) entryFocus else null,
                onClick = { onSelectTrack(null) },
            )
            tracks.forEachIndexed { index, track ->
                TrackRow(
                    label = track.label
                        ?: stringResource(R.string.subtitles_track_fallback, track.trackNumber),
                    meta = trackMeta(track),
                    selected = track.isSelected,
                    focusRequester = if (index == selectedIndex) entryFocus else null,
                    onClick = { onSelectTrack(track.id) },
                )
            }
            Spacer(Modifier.height(FocusRingBleedVertical))
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.subtitles_size_label),
                style = FlickType.monoEyebrow(sizeSp = 16, trackingEm = 0.18f),
                color = FlickColor.OnPanelLabel,
                maxLines = 1,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SubtitleSize.ALL.forEach { option ->
                    val on = option == size
                    FlickTvButton(
                        onClick = { onSelectSize(option) },
                        modifier = Modifier.weight(1f),
                        selected = on,
                        shape = FlickShape.Sm,
                        containerColor = if (on) FlickColor.OnSurface else null,
                        borderColor = if (on) Color.Transparent else null,
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = stringResource(option.labelRes),
                            style = FlickType.body(sizeSp = 24, lineHeightRatio = 1.1f),
                            color = if (on) FlickColor.OnLight else FlickColor.OnSurfaceDim,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The design puts the meta beside the label; at the 16 sp mono floor
 * `SRT · EMBEDDED` is wider than the label would then have left, so it sits on a
 * second line inside the row instead.
 */
@Composable
private fun TrackRow(
    label: String,
    meta: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    FlickTvRow(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = FocusRingBleed),
        focusRequester = focusRequester,
        selected = selected,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = if (selected) FlickIcons.CheckCircle else FlickIcons.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (selected) FlickColor.Spark else FlickColor.OnSurfaceFaint,
            modifier = Modifier.size(TrackMarkSize),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = FlickType.body(sizeSp = 24, weight = FontWeight.Bold, lineHeightRatio = 1.1f),
                color = if (selected) FlickColor.SparkLight else FlickColor.OnChrome,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta != null) {
                Text(
                    text = meta,
                    style = FlickType.monoEyebrow(sizeSp = 16, trackingEm = 0.1f),
                    color = if (selected) FlickColor.SparkLightDim else FlickColor.OnSurfaceFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * `SRT · EMBEDDED` / `PGS · IMAGE`, both halves derived from the container's own
 * sample MIME. An unmapped format drops its half rather than printing a guess.
 */
@Composable
private fun trackMeta(track: SubtitleTrackInfo): String {
    val source = stringResource(
        if (track.isImageBased) R.string.subtitles_source_image else R.string.subtitles_source_embedded,
    )
    val format = track.formatLabel
    return if (format.isEmpty()) source else stringResource(R.string.subtitles_track_meta, format, source)
}
