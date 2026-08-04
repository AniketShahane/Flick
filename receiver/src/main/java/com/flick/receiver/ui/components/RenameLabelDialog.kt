package com.flick.receiver.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PlatformImeOptions
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import com.flick.receiver.R
import com.flick.receiver.net.normalizeLabel
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickDimens
import com.flick.receiver.ui.theme.FlickShape
import com.flick.receiver.ui.theme.FlickSpace
import com.flick.receiver.ui.theme.FlickType

internal const val RENAME_LABEL_MAX_CODE_POINTS = 80

internal fun limitRenameLabelInput(
    value: String,
    maxCodePoints: Int = RENAME_LABEL_MAX_CODE_POINTS,
): String {
    if (value.codePointCount(0, value.length) <= maxCodePoints) return value
    return value.substring(0, value.offsetByCodePoints(0, maxCodePoints))
}

internal fun normalizedRenameLabel(value: String): String? =
    normalizeLabel(value, RENAME_LABEL_MAX_CODE_POINTS).ifBlank { null }

/**
 * The first edit to the seeded name, applied as though the whole field were still
 * selected: [next] is what the editor made of [current], and this returns only
 * what changed, as the entire value.
 *
 * Seeding `TextRange(0, length)` is not enough on this hardware. The TV IME
 * collapses that selection to a caret at the end of the name the moment it
 * connects — measured `TextRange(14, 14)` against `TextRange(0, 14)` with every
 * IME disabled — so the first keystroke appends and the viewer has to erase the
 * old name by hand. Emulating select-all at the value level reproduces the
 * intended semantics whatever the IME does to the selection, and it is applied
 * once: after one edit the field is an ordinary editor, so a viewer who wants to
 * amend the name rather than retype it still can. The trade-off is that the value
 * returned here disagrees with the one the editor produced, which restarts the
 * input connection — once, on the first keystroke, and never again.
 *
 * An editor that DID honour the selection has already replaced the name, and its
 * result passes through untouched: re-deriving that from a diff would read the
 * tail two names happen to share as text the viewer meant to keep.
 */
internal fun firstRenameLabelEdit(current: TextFieldValue, next: TextFieldValue): TextFieldValue {
    val seeded = current.text
    val edited = next.text
    if (edited == seeded) return next
    if (current.selection.min == 0 && current.selection.max == seeded.length) return next

    val shared = minOf(seeded.length, edited.length)
    var head = 0
    while (head < shared && seeded[head] == edited[head]) head++
    var tail = 0
    while (tail < shared - head &&
        seeded[seeded.length - 1 - tail] == edited[edited.length - 1 - tail]
    ) {
        tail++
    }
    var start = head
    var end = edited.length - tail
    // Both cuts are moved off a surrogate pair rather than through it: an emoji
    // replaced by another shares its high surrogate with the old one, and a cut
    // between the halves would put a lone surrogate in the saved name.
    if (start in 1 until edited.length && Character.isLowSurrogate(edited[start])) start--
    if (end < edited.length && Character.isLowSurrogate(edited[end])) end++
    val typed = edited.substring(start, end)
    return TextFieldValue(typed, TextRange(typed.length))
}

/**
 * One ordinary text editor for both TV and paired-phone names. Android TV's IME
 * owns speech-to-text, so this field needs no microphone permission or app-side
 * speech recognizer.
 */
@Composable
fun RenameLabelDialog(
    title: String,
    currentName: String,
    onCommit: (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val cancelFocusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var input by remember(currentName) {
        mutableStateOf(
            TextFieldValue(
                text = currentName,
                selection = TextRange(0, currentName.length),
            ),
        )
    }
    // Whether the seeded name still stands as it was handed over — see
    // [firstRenameLabelEdit], which is what makes typing replace it.
    var seededNameIntact by remember(currentName) { mutableStateOf(true) }
    var saveFailed by remember(currentName) { mutableStateOf(false) }
    val normalized = normalizedRenameLabel(input.text)

    fun submit() {
        val next = normalized ?: return
        saveFailed = !onCommit(next)
        if (!saveFailed) onDismiss()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.78f))
                .padding(top = 44.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .width(560.dp)
                    .background(FlickColor.SurfaceRaisedAlt, FlickShape.Hero)
                    .border(1.dp, FlickColor.GlassBorder, FlickShape.Hero)
                    .padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(FlickSpace.Md),
            ) {
                Text(
                    text = title,
                    style = FlickType.display(sizeSp = 28),
                    color = FlickColor.OnSurface,
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { next ->
                        val changed = next.text != input.text
                        val replaced = if (changed && seededNameIntact) {
                            firstRenameLabelEdit(input, next)
                        } else {
                            next
                        }
                        if (changed) seededNameIntact = false
                        val limited = limitRenameLabelInput(replaced.text)
                        input = if (limited == replaced.text) {
                            replaced
                        } else {
                            TextFieldValue(limited, TextRange(limited.length))
                        }
                        // Only a real text change clears the failure. Saving ends
                        // the IME session, and finishing composition arrives here
                        // as a value change carrying the same text — clearing on
                        // that would wipe the message before it could be read.
                        if (changed) saveFailed = false
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        // Previewed, not handled: the editor takes DirectionDown as
                        // a cursor move and consumes it, so neither `focusProperties`
                        // nor the focus system's directional search is ever reached
                        // and the buttons cannot be got at with a remote. Only the
                        // key-down half is claimed — claiming the key-up as well
                        // would move focus twice per press. Left and right are left
                        // to the editor, where they are cursor movement, and so is
                        // up: nothing above this field is focusable.
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            if (event.key != Key.DirectionDown) return@onPreviewKeyEvent false
                            cancelFocusRequester.requestFocus()
                            true
                        }
                        .focusRequester(focusRequester)
                        .testTag("rename-name-field"),
                    // The colour is on the Text, not in `colors` below: this is
                    // tv-material3's `Text`, and M3 tints a label by providing
                    // ITS OWN `LocalContentColor`, which tv-material3 does not
                    // read. `focusedLabelColor` would be silently ignored and the
                    // label would fall back to tv's default content colour —
                    // black, since only a tv `Surface` ever provides that local
                    // and this theme installs none.
                    label = {
                        Text(
                            text = stringResource(R.string.rename_name_label),
                            color = FlickColor.Spark,
                        )
                    },
                    singleLine = true,
                    textStyle = FlickType.body(sizeSp = 18),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done,
                        platformImeOptions = PlatformImeOptions("horizontalAlignment=center"),
                        showKeyboardOnFocus = true,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = FlickColor.OnSurface,
                        unfocusedTextColor = FlickColor.OnSurface,
                        cursorColor = FlickColor.Spark,
                        focusedBorderColor = FlickColor.Spark,
                        unfocusedBorderColor = FlickColor.Outline,
                        // The floating label straddles the top border, so a
                        // container of its own put the word's upper half on the
                        // card and its lower half on the field — two navies
                        // behind one label. An outlined field is drawn by its
                        // border; letting the card show through is what gives the
                        // label a single backdrop.
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    ),
                )
                if (saveFailed) {
                    Text(
                        text = stringResource(R.string.rename_save_failed),
                        style = FlickType.body(sizeSp = 16),
                        color = FlickColor.Caution,
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Inherited by both buttons: up returns to the editor, which
                        // is the one focusable above this row. Left and right inside
                        // the row are ordinary focus search.
                        .focusProperties { up = focusRequester },
                    horizontalArrangement = Arrangement.spacedBy(FlickSpace.Md, Alignment.End),
                ) {
                    FlickTvButton(
                        onClick = onDismiss,
                        contentPadding = FlickDimens.ControlPadding,
                        focusRequester = cancelFocusRequester,
                        modifier = Modifier.testTag("rename-cancel"),
                    ) {
                        Text(
                            text = stringResource(R.string.rename_cancel),
                            style = FlickType.body(sizeSp = 16),
                            color = FlickColor.OnSurfaceDim,
                        )
                    }
                    FlickTvButton(
                        onClick = ::submit,
                        enabled = normalized != null,
                        contentPadding = FlickDimens.ControlPadding,
                        modifier = Modifier.testTag("rename-save"),
                    ) {
                        Text(
                            text = stringResource(R.string.rename_save),
                            style = FlickType.body(sizeSp = 16),
                            color = FlickColor.OnSurface,
                        )
                    }
                }
            }
        }
    }

    LaunchedEffect(currentName) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
}
