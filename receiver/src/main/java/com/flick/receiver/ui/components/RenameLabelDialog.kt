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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
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
    val keyboard = LocalSoftwareKeyboardController.current
    var input by remember(currentName) {
        mutableStateOf(
            TextFieldValue(
                text = currentName,
                selection = TextRange(0, currentName.length),
            ),
        )
    }
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
                        val limited = limitRenameLabelInput(next.text)
                        input = if (limited == next.text) {
                            next
                        } else {
                            TextFieldValue(limited, TextRange(limited.length))
                        }
                        saveFailed = false
                    },
                    modifier = Modifier
                        .fillMaxWidth()
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
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(FlickSpace.Md, Alignment.End),
                ) {
                    FlickTvButton(
                        onClick = onDismiss,
                        contentPadding = FlickDimens.ControlPadding,
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
