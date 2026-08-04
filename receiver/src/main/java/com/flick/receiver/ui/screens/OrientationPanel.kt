package com.flick.receiver.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.flick.receiver.R
import com.flick.receiver.player.TurnNote
import com.flick.receiver.player.VideoRotation
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
 * Panel width. The header sets it, not the rows: "Orientation" at 22 sp Bricolage
 * measures around 116 dp against the 10.5 dp/glyph the metrics panel's own header
 * was sized from, and beside it sit a 10 dp gap, the 19 dp close button and
 * 2 × 17 dp of panel padding — 179 dp. The widest row is far narrower (a 14 dp
 * mark, a 10 dp gap, `270°` at 16 sp, 2 × 12 dp of row inset), and the Auto
 * readout `AUTO · 270°` is 11 glyphs of 14 sp mono at 0.1 em tracking, 108 dp
 * inside the same padding. 200 dp keeps 21 dp off the binding measurement, which
 * is the title — the one line here that would ellipsise rather than wrap.
 */
val OrientationPanelWidth: Dp = 200.dp

/**
 * The picture-orientation panel — left-anchored above the transport, summoned by
 * the square tile in the control row.
 *
 * Five choices rather than four: [VideoRotation.AsFiled] is what a viewer presses
 * when Auto read their file wrong, and without it the only way back to the
 * container's own answer would be the choice that just overruled it.
 *
 * The eyebrow states what Auto decided, and only while Auto is chosen: every other
 * row already says what it applied by being the selected one.
 *
 * Focus enters on the current choice so the panel opens where the viewer left off;
 * `Back` calls [onDismiss], which is also what the close button does. Opening this
 * panel takes the transport bar off screen, so these rows are the only focusables
 * left — the request is retried across frames rather than made once, because a
 * `FocusRequester` whose node is not yet placed throws and would leave the remote
 * steering nothing at all.
 */
@Composable
fun OrientationPanel(
    rotation: VideoRotation,
    /** What [VideoRotation.Auto] resolved to for this film, in degrees. */
    autoRotationDegrees: Int,
    onSelectRotation: (VideoRotation) -> Unit,
    onDismiss: () -> Unit,
    /** What the turn in force cost the picture, or could not do; null when it cost nothing. */
    turnNote: TurnNote? = null,
    /** Changes for every open, including a reopen while an exit is retained. */
    entryKey: Int = 0,
    modifier: Modifier = Modifier,
) {
    val closeFocus = remember { FocusRequester() }
    // One requester per choice, minted above the rows: the panel wires up/down
    // between siblings and picks its entry target before any row composes. The
    // choices are fixed and never re-read, so a list parallel to
    // [VideoRotation.ALL] is enough — nothing here needs the subtitles panel's
    // identity map.
    val optionFocus = remember { VideoRotation.ALL.map { FocusRequester() } }
    val selectedIndex = VideoRotation.ALL.indexOf(rotation).coerceAtLeast(0)
    val entered = remember { mutableStateOf(false) }
    LaunchedEffect(entryKey, selectedIndex) {
        landTvFocus(optionFocus[selectedIndex], closeFocus) { entered.value }
    }

    // The panel is one beacon group: the close key and the five rows share ONE
    // ring that glides between them.
    FocusBeaconHost(modifier = modifier) {
        GlassPanel(
            modifier = Modifier
                .width(OrientationPanelWidth)
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
            // The playback chrome owns this panel's enter AND exit (spec B7); a
            // second entrance latch here would double the parent's motion.
            animateEntrance = false,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FlickSpace.Sm),
            ) {
                Text(
                    text = stringResource(R.string.video_rotation_panel_title),
                    style = FlickType.display(sizeSp = 22),
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                FlickTvIconButton(
                    imageVector = FlickIcons.Close,
                    contentDescription = stringResource(R.string.video_rotation_close),
                    onClick = onDismiss,
                )
            }

            // One eyebrow slot, and the note outranks the Auto readout in it.
            // What Auto settled on is already legible from the selected row; what
            // a turn could not do to the picture is legible from nowhere else,
            // and it is the answer to the question the viewer just asked.
            val noteRes = turnNoteLabelRes(turnNote)
            if (noteRes != null) {
                Text(
                    text = stringResource(noteRes),
                    style = FlickType.monoEyebrow(trackingEm = 0.1f),
                    color = FlickColor.OnSurfaceFaint,
                    maxLines = 2,
                )
            } else if (rotation == VideoRotation.Auto) {
                Text(
                    text = stringResource(
                        R.string.video_rotation_auto_applied,
                        stringResource(rotationLabelRes(shownVideoRotation(rotation, autoRotationDegrees))),
                    ),
                    style = FlickType.monoEyebrow(trackingEm = 0.1f),
                    color = FlickColor.OnSurfaceFaint,
                    maxLines = 1,
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(FlickSpace.Xs),
            ) {
                VideoRotation.ALL.forEachIndexed { index, option ->
                    RotationRow(
                        label = stringResource(rotationLabelRes(option)),
                        selected = option == rotation,
                        focusRequester = optionFocus[index],
                        // Up off the first row is left unset so ordinary search
                        // reaches the close key; down off the last may not leave
                        // this modal at all.
                        upFocusRequester = optionFocus.getOrNull(index - 1),
                        downFocusRequester = optionFocus.getOrNull(index + 1)
                            ?: FocusRequester.Cancel,
                        onClick = { onSelectRotation(option) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RotationRow(
    label: String,
    selected: Boolean,
    focusRequester: FocusRequester,
    upFocusRequester: FocusRequester?,
    downFocusRequester: FocusRequester,
    onClick: () -> Unit,
) {
    FlickTvRow(
        onClick = onClick,
        modifier = Modifier
            .focusProperties {
                if (upFocusRequester != null) up = upFocusRequester
                down = downFocusRequester
            }
            .fillMaxWidth(),
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
        Text(
            text = label,
            style = FlickType.body(sizeSp = 16, weight = FontWeight.Bold),
            color = if (selected) FlickColor.SparkLight else FlickColor.OnChrome,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The turn currently on screen: the viewer's own choice, or — under
 * [VideoRotation.Auto] — the turn Auto actually applied.
 *
 * This is what the transport tile wears and what the hint over the film names, so
 * the sign and the control can never disagree. A verdict off the quarter-turn grid
 * cannot be produced by the policy and reads as the file's own.
 */
internal fun shownVideoRotation(choice: VideoRotation, autoDegrees: Int): VideoRotation =
    if (choice != VideoRotation.Auto) {
        choice
    } else {
        VideoRotation.forExtraDegrees(autoDegrees) ?: VideoRotation.AsFiled
    }

/**
 * The line for a turn that could not be given intact, or null when there is
 * nothing to say.
 *
 * Both lines describe the PICTURE rather than the mechanism: a viewer who has
 * just pressed 90° wants to know what they are looking at, not that a GL output
 * surface has no Dolby Vision dataspace.
 */
internal fun turnNoteLabelRes(note: TurnNote?): Int? = when (note) {
    null -> null
    TurnNote.NotOnThisTv -> R.string.video_rotation_note_locked
    TurnNote.ShownInSdr -> R.string.video_rotation_note_tone_mapped
}

/** The label for each choice; the player enum carries no user-facing text. */
@StringRes
internal fun rotationLabelRes(rotation: VideoRotation): Int = when (rotation) {
    VideoRotation.Auto -> R.string.video_rotation_auto
    VideoRotation.AsFiled -> R.string.video_rotation_as_filed
    VideoRotation.Quarter -> R.string.video_rotation_quarter
    VideoRotation.Half -> R.string.video_rotation_half
    VideoRotation.ThreeQuarter -> R.string.video_rotation_three_quarter
}
