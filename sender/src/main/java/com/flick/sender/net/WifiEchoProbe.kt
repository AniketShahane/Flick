package com.flick.sender.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.system.ErrnoException
import android.system.OsConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * One echo probe of the TV: a TCP connect to its port 7, made through the Wi-Fi network
 * that holds this phone's serving address — never the default network.
 *
 * The cast's control and media sockets stay on the Wi-Fi network they were opened on, but
 * an unbound socket follows the default network, which moves to mobile data when Wi-Fi
 * loses validation and into a VPN when one comes up. Probed that way, a film playing fine
 * read as a TV that had stopped answering.
 *
 * Libcore reports a connect that failed after it was started as a `ConnectException` (or,
 * for `ETIMEDOUT`, a `SocketTimeoutException`) whose cause is the `ErrnoException`; a
 * synchronous `EHOSTUNREACH` arrives as a causeless `NoRouteToHostException`, which cannot
 * be told from `EADDRNOTAVAIL` and is therefore no information.
 */
internal object WifiEchoProbe {

    private const val ECHO_PORT = 7

    private val errnos = DirectLinkEchoPolicy.ConnectErrnos(
        refused = OsConstants.ECONNREFUSED,
        timedOut = OsConstants.ETIMEDOUT,
        hostUnreachable = OsConstants.EHOSTUNREACH,
    )

    suspend fun probe(context: Context, tvHost: String, localIp: String?, timeoutMs: Int): EchoOutcome =
        withContext(Dispatchers.IO) {
            val network = localIp?.let { wifiNetworkHolding(context, it) } ?: return@withContext EchoOutcome.UNKNOWN
            try {
                network.socketFactory.createSocket().use { socket ->
                    socket.connect(InetSocketAddress(tvHost, ECHO_PORT), timeoutMs)
                }
                EchoOutcome.ANSWERED
            } catch (e: SocketTimeoutException) {
                EchoOutcome.MISSED
            } catch (e: Exception) {
                val errno = (e.cause as? ErrnoException)?.errno
                if (errno == null) EchoOutcome.UNKNOWN else DirectLinkEchoPolicy.outcomeForErrno(errno, errnos)
            }
        }

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
