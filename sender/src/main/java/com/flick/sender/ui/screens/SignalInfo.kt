package com.flick.sender.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.flick.sender.R
import com.flick.sender.NetworkUtils
import com.flick.sender.TransferTelemetry
import com.flick.sender.WifiBand
import com.flick.sender.WifiLinkInfo
import com.flick.sender.ui.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** What the signal chip / quality sheet show — this phone's own link + throughput. */
data class SignalInfo(
    val throughputBitsPerSec: Long,
    val band: WifiBand?,
    val linkSpeedMbps: Int,
    val rssiDbm: Int,
) {
    val healthy: Boolean get() = band != WifiBand.GHZ_24
    val on24GHz: Boolean get() = band == WifiBand.GHZ_24

    @Composable
    fun bandLabel(): String = when (band) {
        WifiBand.GHZ_6 -> stringResource(R.string.wifi_band_6ghz)
        WifiBand.GHZ_5 -> stringResource(R.string.wifi_band_5ghz)
        WifiBand.GHZ_24 -> stringResource(R.string.wifi_band_24ghz)
        null -> stringResource(R.string.wifi_band_generic)
    }

    @Composable
    fun chipText(): String = when {
        throughputBitsPerSec > 0L -> stringResource(
            R.string.network_chip_throughput,
            Format.megabits(throughputBitsPerSec),
            bandLabel(),
        )
        linkSpeedMbps > 0 -> stringResource(R.string.network_chip_link_speed, linkSpeedMbps, bandLabel())
        else -> bandLabel()
    }
}

/** Polls the phone's Wi-Fi link + the media-server throughput every ~2s. */
@Composable
fun rememberSignalInfo(): SignalInfo {
    val context = LocalContext.current
    val stats by TransferTelemetry.stats.collectAsState()
    var wifi by remember { mutableStateOf<WifiLinkInfo?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            TransferTelemetry.refresh()
            wifi = withContext(Dispatchers.IO) { NetworkUtils.getWifiLinkInfo(context) }
            delay(2000L)
        }
    }
    return SignalInfo(
        throughputBitsPerSec = stats.bitsPerSec,
        band = wifi?.band,
        linkSpeedMbps = wifi?.linkSpeedMbps ?: 0,
        rssiDbm = wifi?.rssiDbm ?: 0,
    )
}
