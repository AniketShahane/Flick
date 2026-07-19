package com.flick.sender

import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface

/** Wi-Fi band classified from the link frequency (MHz). */
enum class WifiBand { GHZ_6, GHZ_5, GHZ_24 }

/**
 * Snapshot of THIS phone's own Wi-Fi link, for the serving diagnostics.
 * Deliberately carries no SSID/BSSID (reading those needs a location permission
 * and is privacy-sensitive).
 */
data class WifiLinkInfo(
    val band: WifiBand,
    val frequencyMhz: Int,
    val linkSpeedMbps: Int,
    val rssiDbm: Int,
)

/**
 * LAN address discovery via [NetworkInterface] enumeration. Deliberately avoids
 * the deprecated `WifiManager.getIpAddress()` and works for Wi-Fi, Ethernet
 * dongles and hotspot interfaces alike.
 */
object NetworkUtils {

    /**
     * Returns this device's site-local IPv4 address (RFC 1918: 10/8, 172.16/12,
     * 192.168/16) that a TV on the same LAN can reach, or `null` if the phone
     * has no usable LAN connection.
     *
     * Loopback and link-local (169.254/16) addresses are skipped. When several
     * candidates exist, a Wi-Fi (`wlan*`) interface is preferred.
     */
    fun getSiteLocalIpv4(): String? {
        var fallback: String? = null
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (nif in interfaces) {
                // Skip interfaces that cannot carry LAN traffic to the TV.
                if (!nif.isUp || nif.isLoopback || nif.isVirtual) continue

                for (address in nif.inetAddresses) {
                    if (address !is Inet4Address) continue
                    if (address.isLoopbackAddress || address.isLinkLocalAddress) continue
                    if (!address.isSiteLocalAddress) continue

                    val ip = address.hostAddress ?: continue
                    val name = nif.name?.lowercase().orEmpty()
                    if (name.startsWith("wlan") || name.startsWith("ap")) {
                        return ip
                    }
                    if (fallback == null) fallback = ip
                }
            }
        } catch (_: Exception) {
            // Enumeration can fail transiently while the network changes; treat
            // that the same as "no LAN available".
            return fallback
        }
        return fallback
    }

    /**
     * Reads this phone's own Wi-Fi link (band / link speed / RSSI), or `null` when
     * Wi-Fi isn't the active link or its frequency can't be read.
     */
    @Suppress("DEPRECATION")
    fun getWifiLinkInfo(context: Context): WifiLinkInfo? {
        // WifiManager.connectionInfo is deprecated since API 31, but it is the
        // only permission-cheap way (ACCESS_WIFI_STATE, already held) to read our
        // own link's band/speed/RSSI; the API 31+ replacement routes through a
        // NetworkCallback and surfaces no extra data we need here.
        val wifiManager = context.applicationContext
            .getSystemService(WifiManager::class.java) ?: return null
        val info = wifiManager.connectionInfo ?: return null
        val freq = info.frequency
        if (freq <= 0) return null
        return WifiLinkInfo(
            band = bandOf(freq),
            frequencyMhz = freq,
            linkSpeedMbps = info.linkSpeed,
            rssiDbm = info.rssi,
        )
    }

    private fun bandOf(frequencyMhz: Int): WifiBand = when {
        frequencyMhz >= 5925 -> WifiBand.GHZ_6
        frequencyMhz >= 4900 -> WifiBand.GHZ_5
        else -> WifiBand.GHZ_24
    }
}
