package com.flick.receiver.net

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Resolves the TV's own LAN IPv4 address. The control server binds THIS address
 * (never 0.0.0.0) per control-channel.md §1 so it is reachable only on the
 * subnet, and the bound host is what the anti-rebinding Host check compares to.
 */
object LanAddress {

    /** The first site-local IPv4 on an up, non-loopback interface, or null. */
    fun current(): String? = ipv4Addresses().firstOrNull { it.isSiteLocalAddress }?.hostAddress

    /**
     * Whether this TV has an IPv4 at all, site-local or not.
     *
     * The one fact that separates "no network yet" from "a network Flick cannot use" —
     * a carrier-grade or link-local address is online and is not a home network, and
     * telling that TV to connect to Wi-Fi is advice it has already taken.
     */
    fun hasAnyIpv4(): Boolean = ipv4Addresses().isNotEmpty()

    // Materialised inside the runCatching rather than returned lazily: `isUp` throws,
    // and a sequence would throw it at the caller's iteration, past the catch.
    private fun ipv4Addresses(): List<Inet4Address> = runCatching {
        NetworkInterface.getNetworkInterfaces()
            .toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
    }.getOrNull().orEmpty()
}
