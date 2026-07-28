package com.flick.sender.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.flick.sender.R
import com.flick.sender.net.FlickController
import com.flick.sender.ui.components.NowPlayingDockClearance
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors

/**
 * S11 as a nav peer of the library and the devices list, not as a modal. Everything on
 * it is optional: the advisories name a condition and its exact fix, and casting is
 * never blocked on one of them being answered.
 */
@Composable
fun PhoneSettingsScreen(
    controller: FlickController,
    batteryExempt: Boolean,
    onOpenWifiSettings: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
) {
    val colors = LocalFlickColors.current
    val castingItem by controller.castingItem.collectAsState()

    // The dock floats over this surface too, above the nav, so the foot of the scroll has
    // to clear both of them while a cast is live — otherwise the diagnostics row sits
    // under a bar that answers taps meant for it.
    val bottomClearance = 116.dp + if (castingItem != null) NowPlayingDockClearance else 0.dp

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = bottomClearance),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = stringResource(R.string.settings_heading),
                style = FlickText.displayLarge.copy(color = colors.onSurface),
            )
            Text(
                text = stringResource(R.string.advisories_sub),
                style = FlickText.bodyMedium.copy(color = colors.onSurfaceDim),
            )
        }
        // One node, not a run of siblings: the advisories carry their own spacing between
        // the cards, and this screen's 22 dp rhythm must not be inserted between them.
        Advisories(
            batteryExempt = batteryExempt,
            onOpenWifiSettings = onOpenWifiSettings,
            onRequestBatteryExemption = onRequestBatteryExemption,
            onOpenDiagnostics = { controller.toggleDiagnostics(true) },
        )
    }
}
