package com.flick.sender.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.flick.sender.R
import com.flick.sender.ui.components.AdvisoryCard
import com.flick.sender.ui.components.AdvisoryTone
import com.flick.sender.ui.components.BatteryOptimizationCard
import com.flick.sender.ui.theme.FlickCorners
import com.flick.sender.ui.theme.FlickIcons
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors

/**
 * S11 — advisories. Tinted, actionable cards; casting is never blocked.
 *
 * A column body rather than a surface of its own: the settings destination that hosts it
 * owns the scroll, the heading and the clearance the floating chrome needs. It was a
 * bottom sheet until Settings became a nav peer, and nothing summons it as one any more.
 *
 * Each card carries its fix and nothing else. The sheet's "Cast anyway" / "Later" were
 * its dismissals; on a destination there is nothing to dismiss, and neither ever
 * silenced the card it sat on — a seat the user can walk onto at any time must not offer
 * a deferral it does not honour.
 */
@Composable
internal fun Advisories(
    batteryExempt: Boolean,
    notificationsGranted: Boolean,
    onOpenWifiSettings: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
) {
    val colors = LocalFlickColors.current
    val signal = rememberSignalInfo()
    // `on24GHz` and not `!healthy`: an unknown band must not raise a band advisory.
    val showBand = signal.on24GHz
    val showBattery = !batteryExempt
    val showNotifications = !notificationsGranted

    Column {
        if (showBand) {
            AdvisoryCard(
                icon = FlickIcons.Wifi,
                title = stringResource(R.string.advisory_band_title),
                titleStyle = FlickText.titleSmall,
                body = stringResource(R.string.advisory_band_body),
                tone = AdvisoryTone.CAUTION,
                primaryLabel = stringResource(R.string.advisory_band_primary),
                onPrimary = onOpenWifiSettings,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(11.dp))
        }
        if (showBattery) {
            BatteryOptimizationCard(
                title = stringResource(R.string.advisory_battery_title),
                body = stringResource(R.string.advisory_battery_body),
                primaryLabel = stringResource(R.string.advisory_battery_primary),
                onPrimary = onRequestBatteryExemption,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(11.dp))
        }
        // INFO and not CAUTION, and on this destination rather than in the library: the
        // band and battery cards set the precedent that only a cast-threatening fact
        // earns a banner, and a denied notification costs the lock-screen controls and
        // nothing else. The second sentence of the body exists to say exactly that.
        if (showNotifications) {
            AdvisoryCard(
                icon = FlickIcons.Warning,
                title = stringResource(R.string.advisory_notifications_title),
                titleStyle = FlickText.titleSmall,
                body = stringResource(R.string.advisory_notifications_body),
                tone = AdvisoryTone.INFO,
                primaryLabel = stringResource(R.string.advisory_notifications_action),
                onPrimary = onOpenNotificationSettings,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(11.dp))
        }
        if (!showBand && !showBattery && !showNotifications) {
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

        // The footer is a claim about the cards above it, and this is a destination the
        // user can walk onto at any time rather than a sheet raised the once: "Both"
        // names a card that is not there when only one advisory shows, and names nothing
        // at all on a phone that is already tuned up.
        if (showBand || showBattery || showNotifications) {
            Text(
                stringResource(
                    if (listOf(showBand, showBattery, showNotifications).count { it } > 1) {
                        R.string.advisories_footer
                    } else {
                        R.string.advisories_footer_one
                    },
                ),
                style = FlickText.bodyMedium.copy(color = colors.onSurfaceDim),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}
