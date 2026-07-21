package com.flick.sender.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flick.sender.R
import com.flick.sender.ui.components.FlickPrimaryButton
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors
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
        Text(stringResource(R.string.diagnostics_title), style = FlickText.heading.copy(color = colors.onSurface))
        Text(
            stringResource(R.string.diagnostics_sub),
            style = FlickText.caption.copy(color = colors.onSurfaceDim),
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )

        if (entries.isEmpty()) {
            Text(
                stringResource(R.string.diagnostics_empty),
                style = FlickText.caption.copy(color = colors.onSurfaceFaint),
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
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
                        style = FlickText.mono.copy(fontSize = 10.sp, color = colors.onSurfaceDim),
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FlickPrimaryButton(
                text = stringResource(R.string.diagnostics_copy),
                onClick = {
                    copyToClipboard(context, entries.joinToString("\n", transform = ::render))
                    Toast.makeText(context, R.string.diagnostics_copied, Toast.LENGTH_SHORT).show()
                },
                enabled = entries.isNotEmpty(),
                modifier = Modifier.weight(1f),
            )
            FlickPrimaryButton(
                text = stringResource(R.string.diagnostics_clear),
                onClick = { FlickLog.clear() },
                enabled = entries.isNotEmpty(),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
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
