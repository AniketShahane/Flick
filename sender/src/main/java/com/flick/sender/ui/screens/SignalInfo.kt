package com.flick.sender.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.structuralEqualityPolicy
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

/**
 * What the signal chip / quality sheet show — this phone's own link + throughput.
 *
 * The Wi-Fi reading is carried whole rather than unpacked into fields, because
 * [com.flick.sender.net.LinkCapacityPolicy.preCastAdvisory] takes the record: a surface
 * that had to rebuild one from three properties would be inventing the fourth.
 */
data class SignalInfo(
    val throughputBitsPerSec: Long,
    val link: WifiLinkInfo?,
) {
    val band: WifiBand? get() = link?.band
    val linkSpeedMbps: Int get() = link?.linkSpeedMbps ?: 0
    val rssiDbm: Int get() = link?.rssiDbm ?: 0

    val healthy: Boolean get() = band != WifiBand.GHZ_24
    val on24GHz: Boolean get() = band == WifiBand.GHZ_24

    /** False when Wi-Fi is not the active transport: band and RSSI are then unknown, not zero. */
    val hasLink: Boolean get() = link != null

    /** True only while this phone's server is actually writing bytes. */
    val serving: Boolean get() = throughputBitsPerSec > 0L

    @Composable
    fun bandLabel(): String = wifiBandLabel(band)

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

    /** Band plus signal strength for a fact row; an honest dash when Wi-Fi is not up. */
    @Composable
    fun linkLabel(): String = when {
        band == null -> stringResource(R.string.media_unknown)
        rssiDbm != 0 -> stringResource(R.string.network_rssi, bandLabel(), rssiDbm)
        else -> bandLabel()
    }
}

/**
 * The band a reading was taken on, named. Top-level because the pre-cast advisory holds a
 * [WifiBand] and no [SignalInfo] — it is a verdict about one link, not a live chip — and
 * two spellings of "5 GHz" in one app is one too many.
 */
@Composable
fun wifiBandLabel(band: WifiBand?): String = when (band) {
    WifiBand.GHZ_6 -> stringResource(R.string.wifi_band_6ghz)
    WifiBand.GHZ_5 -> stringResource(R.string.wifi_band_5ghz)
    WifiBand.GHZ_24 -> stringResource(R.string.wifi_band_24ghz)
    null -> stringResource(R.string.wifi_band_generic)
}

/**
 * Polls the phone's Wi-Fi link + the media-server throughput every ~2s and publishes
 * the result as [State] rather than a value. Nothing here is read at composition scope,
 * so the poll invalidates only the leaves that actually read `.value` — screens that
 * unwrap it at their own scope rebuild their whole tree on every tick.
 */
@Composable
fun rememberSignalState(): State<SignalInfo> {
    val context = LocalContext.current
    val stats = TransferTelemetry.stats.collectAsState()
    val wifi = remember { mutableStateOf<WifiLinkInfo?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            // NOT the measurement — CastServerService owns a 1 Hz sampler for that, and it
            // is the only fold alive while nothing is composed. This one is what brings the
            // reading back DOWN: nothing resets the telemetry when a session ends, so with
            // the service's sampler cancelled the last rate served would stand as the
            // current one until the next cast called reset().
            TransferTelemetry.refresh()
            wifi.value = withContext(Dispatchers.IO) { NetworkUtils.getWifiLinkInfo(context) }
            delay(2000L)
        }
    }
    return remember(stats, wifi) {
        derivedStateOf(structuralEqualityPolicy()) {
            SignalInfo(throughputBitsPerSec = stats.value.bitsPerSec, link = wifi.value)
        }
    }
}

/** Convenience for surfaces that genuinely want the whole record in their own scope. */
@Composable
fun rememberSignalInfo(): SignalInfo = rememberSignalState().value
