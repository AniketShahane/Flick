package com.flick.sender.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.flick.sender.R
import com.flick.sender.ui.theme.FlickCorners
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.PillShape
import com.flick.sender.ui.theme.flickRipple
import com.flick.sender.ui.theme.pressScale
import com.flick.sender.util.FlickLog
import java.util.Locale

/**
 * The phone-side diagnostics log, rendered in-app because `adb logcat` is not a
 * usable channel on every device. Copying is safe because redaction is enforced
 * where each line is WRITTEN, not where it is exported.
 */
@Composable
fun DiagnosticsSheet(onDismiss: () -> Unit) {
    val colors = LocalFlickColors.current
    val context = LocalContext.current
    val entries by FlickLog.entries.collectAsState()

    BottomSheet(onDismiss = onDismiss) {
        SheetGrabber()
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.diagnostics_title), style = FlickText.headlineMedium.copy(color = colors.onSurface))
        Text(
            stringResource(R.string.diagnostics_sub),
            style = FlickText.bodyMedium.copy(color = colors.onSurfaceDim),
            modifier = Modifier.padding(top = 5.dp, bottom = 16.dp),
        )

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(FlickCorners.statCard))
                .background(colors.fillCard)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            if (entries.isEmpty()) {
                Text(
                    stringResource(R.string.diagnostics_empty),
                    style = FlickText.bodyMedium.copy(color = colors.onSurfaceDim),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                    textAlign = TextAlign.Center,
                )
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    entries.forEach { entry ->
                        Text(
                            text = render(entry),
                            style = FlickText.monoSmall.copy(color = colors.onSurfaceDim),
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            DiagnosticsAction(
                label = stringResource(R.string.diagnostics_copy),
                enabled = entries.isNotEmpty(),
                containerColor = colors.inverseSurface,
                contentColor = colors.onInverseSurface,
                onClick = {
                    copyToClipboard(context, entries.joinToString("\n", transform = ::render))
                    Toast.makeText(context, R.string.diagnostics_copied, Toast.LENGTH_SHORT).show()
                },
            )
            DiagnosticsAction(
                label = stringResource(R.string.diagnostics_clear),
                enabled = entries.isNotEmpty(),
                containerColor = colors.primaryContainer,
                contentColor = colors.onPrimaryContainer,
                onClick = { FlickLog.clear() },
            )
        }
    }
}

/** Pill action. Disabled stays visible but muted — the log fills as Flick runs. */
@Composable
private fun RowScope.DiagnosticsAction(
    label: String,
    enabled: Boolean,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Text(
        text = label,
        style = FlickText.titleSmall.copy(color = if (enabled) contentColor else contentColor.copy(alpha = 0.45f)),
        textAlign = TextAlign.Center,
        modifier = Modifier
            .weight(1f)
            .pressScale(interaction)
            .clip(PillShape)
            .background(if (enabled) containerColor else containerColor.copy(alpha = 0.30f))
            .clickable(
                interactionSource = interaction,
                // The caller's content colour is by definition the one that reads on
                // its container, in either palette.
                indication = flickRipple(contentColor),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .heightIn(min = 48.dp)
            .padding(vertical = 16.dp),
    )
}

private fun render(entry: FlickLog.Entry): String {
    val totalSeconds = entry.timestampMs / 1000L
    val stamp = String.format(
        Locale.US,
        "%02d:%02d.%03d",
        (totalSeconds / 60L) % 60L,
        totalSeconds % 60L,
        entry.timestampMs % 1000L,
    )
    return "$stamp ${entry.level} [${entry.area}] ${entry.message}"
}

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return
    manager.setPrimaryClip(ClipData.newPlainText(FlickLog.TAG, text))
}
