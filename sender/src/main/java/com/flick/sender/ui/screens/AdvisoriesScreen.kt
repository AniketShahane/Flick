package com.flick.sender.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.flick.sender.R
import com.flick.sender.ui.components.AdvisoryCard
import com.flick.sender.ui.components.AdvisoryTone
import com.flick.sender.ui.theme.FlickCorners
import com.flick.sender.ui.theme.FlickIcons
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.PillShape
import com.flick.sender.ui.theme.flickRipple
import com.flick.sender.ui.theme.pressScale

/** S11 — advisories. Tinted, actionable cards; casting is never blocked. */
@Composable
fun AdvisoriesScreen(
    batteryExempt: Boolean,
    onOpenWifiSettings: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalFlickColors.current
    val signal = rememberSignalInfo()
    val diagnosticsDescription = stringResource(R.string.a11y_diagnostics)
    // `on24GHz` and not `!healthy`: an unknown band must not raise a band advisory.
    val showBand = signal.on24GHz
    val showBattery = !batteryExempt

    BottomSheet(onDismiss = onDismiss) {
        SheetGrabber()
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.advisories_title), style = FlickText.headlineMedium.copy(color = colors.onSurface))
        Text(
            stringResource(R.string.advisories_sub),
            style = FlickText.bodyMedium.copy(color = colors.onSurfaceDim),
            modifier = Modifier.padding(top = 5.dp, bottom = 18.dp),
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
            Spacer(Modifier.height(11.dp))
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
            Spacer(Modifier.height(11.dp))
        }
        if (!showBand && !showBattery) {
            Text(
                stringResource(R.string.advisories_alltuned),
                style = FlickText.titleSmall.copy(color = colors.onSurface),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(FlickCorners.warning))
                    .background(colors.primaryContainer)
                    .padding(vertical = 26.dp),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(11.dp))
        }

        Text(
            stringResource(R.string.advisories_footer),
            style = FlickText.bodyMedium.copy(color = colors.onSurfaceDim),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(6.dp))
        val diagnosticsInteraction = remember { MutableInteractionSource() }
        Text(
            stringResource(R.string.advisory_diagnostics_row),
            style = FlickText.labelMedium.copy(
                color = colors.onSurfaceDim,
                textDecoration = TextDecoration.Underline,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .pressScale(diagnosticsInteraction)
                .clip(PillShape)
                .semantics { contentDescription = diagnosticsDescription }
                .clickable(
                    interactionSource = diagnosticsInteraction,
                    // This sheet follows the system palette rather than forcing the
                    // cinematic one, so the ripple takes the role that inverts with it.
                    indication = flickRipple(colors.onSurface),
                    role = Role.Button,
                    onClick = onOpenDiagnostics,
                )
                .heightIn(min = 48.dp)
                .padding(vertical = 15.dp),
            textAlign = TextAlign.Center,
        )
    }
}
