package com.flick.sender.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.system.ErrnoException
import android.system.OsConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * One echo probe of the TV: a TCP connect to its port 7 that provably leaves over the Wi-Fi
 * network holding this phone's serving address, never over whatever the default network is.
 *
 * The cast's control and media sockets stay on the Wi-Fi network they were opened on, but
 * an unbound socket follows the default network, which moves to mobile data when Wi-Fi
 * loses validation and into a VPN when one comes up. Probed that way, a film playing fine
 * read as a TV that had stopped answering.
 *
 * [EchoMode.BOUND] binds the socket to the Wi-Fi network. Under a secure VPN netd refuses
 * that to an app that cannot protect sockets (`EPERM`), while the cast itself still works:
 * the VPN leaves the LAN out of its routes and netd falls through to Wi-Fi. So
 * [EchoMode.ROUTED] connects unbound, and counts only when the kernel's source address
 * toward the TV is the serving address both before and after (see [EchoProbeRules]).
 *
 * Libcore reports a connect that failed after it was started as a `ConnectException` (or,
 * for `ETIMEDOUT`, a `SocketTimeoutException`) whose cause is the `ErrnoException`; a
 * synchronous `EHOSTUNREACH` arrives as a causeless `NoRouteToHostException`, which cannot
 * be told from `EADDRNOTAVAIL` and is therefore no information.
 */
internal object WifiEchoProbe {

    data class Result(val outcome: EchoOutcome, val mode: EchoMode)

    private const val ECHO_PORT = 7

    private val errnos = DirectLinkEchoPolicy.ConnectErrnos(
        refused = OsConstants.ECONNREFUSED,
        timedOut = OsConstants.ETIMEDOUT,
        hostUnreachable = OsConstants.EHOSTUNREACH,
    )

    private val unknown = Result(EchoOutcome.UNKNOWN, EchoMode.NONE)

    suspend fun probe(context: Context, tvHost: String, localIp: String?, timeoutMs: Int): Result =
        withContext(Dispatchers.IO) {
            if (localIp == null) return@withContext unknown
            val target = InetSocketAddress(tvHost, ECHO_PORT)
            // Creating the socket is where the bind is refused; only connect() is classified.
            val bound = wifiNetworkHolding(context, localIp)?.let { network ->
                runCatching { network.socketFactory.createSocket() }.getOrNull()
            }
            if (bound != null) return@withContext Result(connectOutcome(bound, target, timeoutMs), EchoMode.BOUND)
            val before = sourceToward(target)
            if (!EchoProbeRules.routedPossible(before, localIp)) return@withContext unknown
            val raw = connectOutcome(Socket(), target, timeoutMs)
            Result(EchoProbeRules.routedOutcome(before, raw, sourceToward(target), localIp), EchoMode.ROUTED)
        }

    private fun connectOutcome(socket: Socket, target: InetSocketAddress, timeoutMs: Int): EchoOutcome = try {
        socket.use { it.connect(target, timeoutMs) }
        EchoOutcome.ANSWERED
    } catch (e: SocketTimeoutException) {
        EchoOutcome.MISSED
    } catch (e: Exception) {
        val errno = (e.cause as? ErrnoException)?.errno
        if (errno == null) EchoOutcome.UNKNOWN else DirectLinkEchoPolicy.outcomeForErrno(errno, errnos)
    }

    /** The source address the kernel picks for an unbound socket to [target]; a UDP connect sends nothing. */
    private fun sourceToward(target: InetSocketAddress): String? = runCatching {
        DatagramSocket().use { socket ->
            socket.connect(target)
            socket.localAddress?.hostAddress
        }
    }.getOrNull()

    /** The non-VPN Wi-Fi network whose link addresses include [localIp], or null. */
    @Suppress("DEPRECATION")
    private fun wifiNetworkHolding(context: Context, localIp: String): Network? = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        manager.allNetworks.firstOrNull { network ->
            val capabilities = manager.getNetworkCapabilities(network) ?: return@firstOrNull false
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                manager.getLinkProperties(network)?.linkAddresses?.any { it.address.hostAddress == localIp } == true
        }
    }.getOrNull()
}
