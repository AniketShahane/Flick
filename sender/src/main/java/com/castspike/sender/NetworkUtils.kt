package com.castspike.sender

import java.net.Inet4Address
import java.net.NetworkInterface

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
}
