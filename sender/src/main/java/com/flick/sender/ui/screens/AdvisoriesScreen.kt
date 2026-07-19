package com.flick.sender.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.flick.sender.R
import com.flick.sender.ui.components.AdvisoryCard
import com.flick.sender.ui.components.AdvisoryTone
import com.flick.sender.ui.theme.FlickIcons
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors

/** S11 — advisories. Tinted, actionable cards; casting is never blocked. */
@Composable
fun AdvisoriesScreen(
    batteryExempt: Boolean,
    onOpenWifiSettings: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalFlickColors.current
    val signal = rememberSignalInfo()
    val showBand = signal.on24GHz
    val showBattery = !batteryExempt

    BottomSheet(onDismiss = onDismiss) {
        SheetGrabber()
        Text(stringResource(R.string.advisories_title), style = FlickText.heading.copy(color = colors.onSurface))
        Text(
            stringResource(R.string.advisories_sub),
            style = FlickText.caption.copy(color = colors.onSurfaceDim),
            modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
        )

        if (showBand) {
            AdvisoryCard(
                icon = FlickIcons.Wifi,
                title = stringResource(R.string.advisory_band_title),
                body = stringResource(R.string.advisory_band_body),
                tone = AdvisoryTone.CAUTION,
                primaryLabel = stringResource(R.string.advisory_band_primary),
                onPrimary = onOpenWifiSettings,
                secondaryLabel = stringResource(R.string.advisory_band_secondary),
                onSecondary = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(9.dp))
        }
        if (showBattery) {
            AdvisoryCard(
                icon = FlickIcons.Private,
                title = stringResource(R.string.advisory_battery_title),
                body = stringResource(R.string.advisory_battery_body),
                tone = AdvisoryTone.INFO,
                primaryLabel = stringResource(R.string.advisory_battery_primary),
                onPrimary = onRequestBatteryExemption,
                secondaryLabel = stringResource(R.string.advisory_battery_secondary),
                onSecondary = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(9.dp))
        }
        if (!showBand && !showBattery) {
            Text(
                stringResource(R.string.advisories_alltuned),
                style = FlickText.body.copy(color = colors.onSurfaceDim),
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                textAlign = TextAlign.Center,
            )
        }

        Text(
            stringResource(R.string.advisories_footer),
            style = FlickText.caption.copy(color = colors.onSurfaceFaint),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 6.dp),
            textAlign = TextAlign.Center,
        )
    }
}
