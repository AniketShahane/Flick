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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
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
import com.flick.receiver.player.SubtitleTrackFocusIdentity
import com.flick.receiver.player.SubtitleTrackInfo
import com.flick.receiver.player.VideoRotation
import com.flick.receiver.ui.components.FlickTvButton
import com.flick.receiver.ui.components.FlickTvIconButton
import com.flick.receiver.ui.components.FlickTvRow
import com.flick.receiver.ui.components.FocusBeaconHost
import com.flick.receiver.ui.components.GlassPanel
import com.flick.receiver.ui.components.GlassPanelTone
import com.flick.receiver.ui.components.landTvFocus
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickDimens
import com.flick.receiver.ui.theme.FlickIcons
import com.flick.receiver.ui.theme.FlickShape
import com.flick.receiver.ui.theme.FlickSpace
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
 * Panel width. The spec draws 310 dp. What actually sets the number is the track
 * meta line, the widest fixed string the panel can be asked to draw: Geist Mono
 * advances 0.6 em and `trackMeta` adds 0.1 em of tracking, so the longest label
 * the format mapper emits — `CEA-608 · EMBEDDED`, 18 glyphs — measures
 * 18 × 0.7 × 14 sp = 176.4 dp. Around it sit 2 × 17 dp of panel padding,
 * 2 × [FocusRingBleed], the row's 2 × 12 dp inset, a 14 dp mark and a 10 dp gap,
 * i.e. 112 dp of chrome. 292 dp leaves the text column 180 dp.
 */
val SubtitlesPanelWidth: Dp = 292.dp

/**
 * Horizontal inset on each track row. `Modifier.verticalScroll` clips hard at
 * `0..width` on the cross axis, and a focused row's ring is drawn 4.5 dp outside
 * its bounds with a 2 dp stroke *inside* the same layer that scales the row by
 * 1.06 — so the ring's outer edge sits `(rowHalfWidth + 5.5 dp) × 1.06` from the
 * row's centre. The scroll box is 258 dp wide here, so the inset must satisfy
 * `(129 + 5.5 − b) × 1.06 ≤ 129`, i.e. b ≥ 13 dp; 15 dp keeps a margin for the
 * row border and rounding.
 */
private val FocusRingBleed: Dp = 15.dp

/**
 * The scroll container inflates its clip by the max supported elevation on the
 * scroll axis, so the first and last rings survive on their own; these spacers
 * only stop a ring from painting over the header or the size selector.
 */
private val FocusRingBleedVertical: Dp = FlickSpace.Xs

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
 * Opening this panel takes the transport bar off screen, so these rows are the
 * only focusables left — the request is retried across frames rather than made
 * once, because a `FocusRequester` whose node is not yet placed throws and would
 * leave the remote steering nothing at all.
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
    rotation: VideoRotation = VideoRotation.Auto,
    /** What [VideoRotation.Auto] resolved to for this film, in degrees. */
    autoRotationDegrees: Int = 0,
    onSelectRotation: (VideoRotation) -> Unit = {},
    /** Changes for every open, including a reopen while an exit is retained. */
    entryKey: Int = 0,
    modifier: Modifier = Modifier,
) {
    val selectedIndex = tracks.indexOfFirst { it.isSelected }
    val offSelected = selectedIndex < 0
    val offFocus = remember { FocusRequester() }
    // Directional search has to cross the scrolling list's boundary to reach size.
    // Media3 IDs are positional; the immutable TrackGroup owns focus identity.
    val trackIdentities = tracks.map { it.focusIdentity }
    val trackFocusByIdentity = remember {
        mutableMapOf<SubtitleTrackFocusIdentity, FocusRequester>()
    }
    val trackFocuses = trackIdentities.map { identity ->
        trackFocusByIdentity.getOrPut(identity) { FocusRequester() }
    }
    val sizeFocus = remember { FocusRequester() }
    val rotationFocus = remember { FocusRequester() }
    val selectedChoiceFocus = if (offSelected) offFocus else trackFocuses[selectedIndex]
    val selectedChoiceKey: Any = if (offSelected) OffChoice else trackIdentities[selectedIndex]
    val lastChoiceFocus = trackFocuses.lastOrNull() ?: offFocus
    val entered = remember { mutableStateOf(false) }
    val choiceFocus = remember { ChoiceFocus() }
    // Evaluated HERE, during the composition that drops the row, because this is
    // the last moment the answer still exists: once the node goes, Compose parks
    // focus on another row, and "parked because the row died" and "the viewer
    // walked here" are the same state. The effect below runs after that, so it
    // cannot tell them apart — it can only be told.
    val displaced = choiceFocus.displacedBy(trackIdentities)
    LaunchedEffect(entryKey, selectedChoiceFocus, trackIdentities) {
        // Two different questions, and only one of them is "has the panel got
        // focus at all". A displaced viewer is already holding a row — the wrong
        // one — so the entry test would report success without moving anything.
        landTvFocus(selectedChoiceFocus, selectedChoiceFocus) {
            if (displaced) choiceFocus.current == selectedChoiceKey else entered.value
        }
    }

    // The panel is one beacon group: the close button, the track rows and the
    // three size cells share ONE ring that glides between them. The host sits
    // outside the track list's scroll clip, so the ring is no longer something the
    // scroll container can cut in half.
    FocusBeaconHost(modifier = modifier) {
    GlassPanel(
        modifier = Modifier
            .width(SubtitlesPanelWidth)
            .onFocusChanged { entered.value = it.hasFocus }
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
        verticalArrangement = Arrangement.spacedBy(FlickSpace.Sm),
        // The playback chrome owns this panel's enter AND exit (spec B7); a second
        // entrance latch here would double the parent's motion.
        animateEntrance = false,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.subtitles_panel_title),
                style = FlickType.display(sizeSp = 22),
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
            verticalArrangement = Arrangement.spacedBy(FlickSpace.Xs),
        ) {
            Spacer(Modifier.height(FocusRingBleedVertical))
            TrackRow(
                label = stringResource(R.string.subtitles_off),
                meta = null,
                selected = offSelected,
                focusRequester = offFocus,
                downFocusRequester = trackFocuses.firstOrNull() ?: sizeFocus,
                onClick = { onSelectTrack(null) },
                modifier = Modifier.reportChoiceFocus(choiceFocus, OffChoice),
            )
            tracks.forEachIndexed { index, track ->
                key(trackIdentities[index]) {
                    TrackRow(
                        label = track.label
                            ?: stringResource(R.string.subtitles_track_fallback, track.trackNumber),
                        meta = trackMeta(track),
                        selected = track.isSelected,
                        focusRequester = trackFocuses[index],
                        upFocusRequester = trackFocuses.getOrNull(index - 1) ?: offFocus,
                        downFocusRequester = trackFocuses.getOrNull(index + 1) ?: sizeFocus,
                        onClick = { onSelectTrack(track.id) },
                        modifier = Modifier.reportChoiceFocus(choiceFocus, trackIdentities[index]),
                    )
                }
            }
            Spacer(Modifier.height(FocusRingBleedVertical))
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(FlickSpace.Xs),
        ) {
            Text(
                text = stringResource(R.string.subtitles_size_label),
                style = FlickType.monoEyebrow(trackingEm = 0.18f),
                color = FlickColor.OnPanelLabel,
                maxLines = 1,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .focusProperties {
                        // Return to the immediately preceding subtitle choice;
                        // down continues into the orientation row below.
                        up = lastChoiceFocus
                        down = rotationFocus
                    },
                horizontalArrangement = Arrangement.spacedBy(FlickSpace.Sm),
            ) {
                SubtitleSize.ALL.forEach { option ->
                    val on = option == size
                    FlickTvButton(
                        onClick = { onSelectSize(option) },
                        modifier = Modifier.weight(1f),
                        focusRequester = if (on) sizeFocus else null,
                        selected = on,
                        shape = FlickShape.Sm,
                        containerColor = if (on) FlickColor.OnSurface else null,
                        borderColor = if (on) Color.Transparent else null,
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = stringResource(option.labelRes),
                            style = FlickType.body(sizeSp = 16),
                            color = if (on) FlickColor.OnLight else FlickColor.OnSurfaceDim,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        OrientationSection(
            rotation = rotation,
            autoRotationDegrees = autoRotationDegrees,
            onSelectRotation = onSelectRotation,
            focusRequester = rotationFocus,
            upFocusRequester = sizeFocus,
        )
    }
    }
}

/**
 * The picture's orientation, in the same vocabulary as the size row above it.
 *
 * Five cells rather than four: [VideoRotation.AsFiled] is what a viewer presses
 * when Auto read their file wrong, and without it the only way back to the
 * container's own answer would be the choice that just overruled it. They are
 * one type step down from the size cells because five labels have to fit the
 * width three do — which is the panel's own mono-meta size, not a new one.
 *
 * The eyebrow states what Auto decided, and only while Auto is chosen: every
 * other row already says what it applied by being the selected cell.
 */
@Composable
private fun OrientationSection(
    rotation: VideoRotation,
    autoRotationDegrees: Int,
    onSelectRotation: (VideoRotation) -> Unit,
    focusRequester: FocusRequester,
    upFocusRequester: FocusRequester,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(FlickSpace.Xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FlickSpace.Xs),
        ) {
            Text(
                text = stringResource(R.string.video_rotation_label),
                style = FlickType.monoEyebrow(trackingEm = 0.18f),
                color = FlickColor.OnPanelLabel,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (rotation == VideoRotation.Auto) {
                Text(
                    text = stringResource(
                        R.string.video_rotation_auto_applied,
                        stringResource(rotationLabelResForDegrees(autoRotationDegrees)),
                    ),
                    style = FlickType.monoEyebrow(trackingEm = 0.1f),
                    color = FlickColor.OnSurfaceFaint,
                    maxLines = 1,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .focusProperties {
                    // The final rank in this modal: never leave through the
                    // bottom edge, and return to the size row above.
                    up = upFocusRequester
                    down = FocusRequester.Cancel
                },
            horizontalArrangement = Arrangement.spacedBy(FlickSpace.Xs),
        ) {
            VideoRotation.ALL.forEach { option ->
                val on = option == rotation
                FlickTvButton(
                    onClick = { onSelectRotation(option) },
                    modifier = Modifier.weight(1f),
                    focusRequester = if (on) focusRequester else null,
                    selected = on,
                    shape = FlickShape.Sm,
                    containerColor = if (on) FlickColor.OnSurface else null,
                    borderColor = if (on) Color.Transparent else null,
                    contentPadding = PaddingValues(horizontal = 3.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = stringResource(rotationLabelRes(option)),
                        style = FlickType.body(sizeSp = 14),
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

/** The cell label for each choice; the player enum carries no user-facing text. */
@StringRes
private fun rotationLabelRes(rotation: VideoRotation): Int = when (rotation) {
    VideoRotation.Auto -> R.string.video_rotation_auto
    VideoRotation.AsFiled -> R.string.video_rotation_as_filed
    VideoRotation.Quarter -> R.string.video_rotation_quarter
    VideoRotation.Half -> R.string.video_rotation_half
    VideoRotation.ThreeQuarter -> R.string.video_rotation_three_quarter
}

/**
 * The same label, reached by degrees, for the Auto readout. A verdict off the
 * quarter-turn grid cannot be produced by the policy and reads as the file's own.
 */
@StringRes
private fun rotationLabelResForDegrees(degrees: Int): Int =
    rotationLabelRes(VideoRotation.forExtraDegrees(degrees) ?: VideoRotation.AsFiled)

/** The OFF row's key in [ChoiceFocus]. It has no media track to name it. */
internal object OffChoice

/**
 * Which choice row — OFF or one track — holds focus, so a live track refresh can
 * tell a viewer it DISPLACED from one it merely changed the list around.
 *
 * Media3 re-reads the text tracks twice a second, and a track can vanish
 * mid-film. When the vanished row is the focused one, Compose parks focus on the
 * first row in the scroll group; the panel still reports `hasFocus`, so the entry
 * latch sees a landed panel and leaves the viewer sitting on OFF. Recording which
 * row that was is the only way to distinguish it afterwards — see [displacedBy]
 * for why the answer must be taken during composition.
 *
 * Deliberately NOT snapshot state. It is written from focus callbacks and read
 * during composition; making it observable would recompose the whole panel on
 * every D-pad step over a live decoder, which is the cost `FlickTvButton`'s
 * painted focus states exist to avoid. Nothing observes it, so a write can never
 * schedule a recomposition and the read can never loop.
 */
internal class ChoiceFocus {

    /** The focused choice row, or null while focus is on size or the close key. */
    var current: Any? = null
        private set

    fun report(key: Any, focused: Boolean) {
        if (focused) current = key else if (current == key) current = null
    }

    /**
     * True only when focus sits on a TRACK row that [identities] no longer
     * carries.
     *
     * The two exclusions are the whole point. Focus on OFF, on a size cell or on
     * the close key is never displaced — the viewer put it there, and a track
     * appearing or disappearing around them must not move it. Neither is focus on
     * a track that survived the refresh: that is the "preserves the focused
     * logical track when it survives" half of the same contract.
     */
    fun displacedBy(identities: List<SubtitleTrackFocusIdentity>): Boolean {
        val held = current
        return held is SubtitleTrackFocusIdentity && held !in identities
    }
}

/** Reports this row's focus to [choiceFocus]; sits above the row's focus target. */
private fun Modifier.reportChoiceFocus(choiceFocus: ChoiceFocus, key: Any): Modifier =
    this.onFocusChanged { choiceFocus.report(key, it.isFocused) }

/**
 * The design puts the meta beside the label; even at 14 sp mono
 * `CEA-608 · EMBEDDED` claims 176 dp of the row's 180 dp text column, so it sits
 * on a second line inside the row instead.
 */
@Composable
private fun TrackRow(
    label: String,
    meta: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester,
) {
    FlickTvRow(
        onClick = onClick,
        modifier = modifier
            .focusProperties {
                if (upFocusRequester != null) up = upFocusRequester
                down = downFocusRequester
            }
            .fillMaxWidth()
            .padding(horizontal = FocusRingBleed),
        focusRequester = focusRequester,
        selected = selected,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(FlickSpace.Sm),
    ) {
        Icon(
            imageVector = if (selected) FlickIcons.CheckCircle else FlickIcons.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (selected) FlickColor.Spark else FlickColor.OnSurfaceFaint,
            modifier = Modifier.size(FlickDimens.GlyphSmall),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = FlickType.body(sizeSp = 16, weight = FontWeight.Bold),
                color = if (selected) FlickColor.SparkLight else FlickColor.OnChrome,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta != null) {
                Text(
                    text = meta,
                    style = FlickType.monoEyebrow(trackingEm = 0.1f),
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
