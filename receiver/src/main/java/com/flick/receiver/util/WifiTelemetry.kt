package com.flick.receiver.util

import android.content.Context
import android.net.wifi.WifiManager

/**
 * Cheap, best-effort read of the TV's OWN Wi-Fi link for the diagnostics overlay.
 * Both ends' band + RSSI is what turns "the stream stalled" into a fast "which
 * end / which layer" diagnosis, so the receiver samples its own radio each tick.
 *
 * Constraint: [WifiManager.getConnectionInfo] is deprecated at API 31 in favour
 * of a ConnectivityManager `NetworkCapabilities` transport-info read, but that
 * path needs a registered live Network callback. A single synchronous poll of the
 * legacy getter is a cheap per-tick binder call that works uniformly from
 * minSdk 26 — the right trade for a diagnostics-only readout. Frequency, link
 * speed and RSSI do not require location permission (only SSID/BSSID do, and this
 * intentionally reads neither).
 */
object WifiTelemetry {

    /** A TV Wi-Fi link sample: [band] is a human label, speed in Mb/s, rssi in dBm. */
    data class Link(val band: String, val linkSpeedMbps: Int, val rssiDbm: Int)

    /** Null on Ethernet / not associated / unavailable (frequency <= 0). */
    fun read(context: Context): Link? {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        @Suppress("DEPRECATION")
        val info = runCatching { wifiManager.connectionInfo }.getOrNull() ?: return null
        val frequencyMhz = info.frequency
        if (frequencyMhz <= 0) return null
        val band = when {
            frequencyMhz >= 5925 -> "6 GHz"
            frequencyMhz >= 4900 -> "5 GHz"
            else -> "2.4 GHz"
        }
        return Link(band, info.linkSpeed, info.rssi)
    }
}
