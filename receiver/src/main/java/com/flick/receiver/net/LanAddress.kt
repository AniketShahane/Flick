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
    fun current(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()
            .toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()
}
