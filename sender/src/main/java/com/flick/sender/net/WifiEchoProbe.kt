package com.flick.sender.net

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
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
 * the VPN leaves the LAN out of its routes and netd falls through to Wi-Fi. So when that
 * bind is refused, [EchoMode.ROUTED] connects unbound, and counts only when the kernel's
 * source address toward the TV is the serving address both before and after (see
 * [EchoProbeRules]). With no Wi-Fi network holding the serving address there is no probe.
 *
 * Libcore reports a connect that failed after it was started as a `ConnectException` (or,
 * for `ETIMEDOUT`, a `SocketTimeoutException`) whose cause is the `ErrnoException`; a
 * synchronous `EHOSTUNREACH` arrives as a causeless `NoRouteToHostException`, which cannot
 * be told from `EADDRNOTAVAIL` and is therefore no information.
 *
 * The Wi-Fi network is remembered between probes while [watchNetworks] is open (see
 * [EchoNetworkCache]); a probe outside one resolves it every time.
 */
internal object WifiEchoProbe {

    data class Result(val outcome: EchoOutcome, val mode: EchoMode)

    private const val ECHO_PORT = 7

    // NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED: a system API absent from the public SDK.
    private const val NET_CAPABILITY_NOT_VCN_MANAGED = 28

    private val errnos = DirectLinkEchoPolicy.ConnectErrnos(
        refused = OsConstants.ECONNREFUSED,
        timedOut = OsConstants.ETIMEDOUT,
        hostUnreachable = OsConstants.EHOSTUNREACH,
    )

    private val unknown = Result(EchoOutcome.UNKNOWN, EchoMode.NONE)

    private val networks = EchoNetworkCache<Network>()

    suspend fun probe(context: Context, tvHost: String, localIp: String?, timeoutMs: Int): Result =
        withContext(Dispatchers.IO) {
            if (localIp == null) return@withContext unknown
            val target = InetSocketAddress(tvHost, ECHO_PORT)
            // The last network [resolve] returned, when the callbacks cover it.
            var covered: Network? = null
            val resolve = { ip: String ->
                wifiNetworkHolding(context, ip)?.let { (network, capabilities) ->
                    covered = network.takeIf { coveredByCallbacks(capabilities) }
                    network
                }
            }
            val cacheable = { network: Network -> network == covered }
            // Serving from no Wi-Fi network — the phone's own hotspot, for one — leaves no
            // direct link to watch.
            var lookup = networks.lookup(localIp, resolve, cacheable) ?: return@withContext unknown
            // Creating the socket is where the bind is refused; only connect() is classified.
            var bound = boundSocket(lookup.network)
            if (bound == null && lookup.cached) {
                // A cached network may be one that has just gone, which a fresh lookup would
                // not have offered; only a fresh one may send the probe down the routed path.
                lookup = networks.refresh(localIp, resolve, cacheable) ?: return@withContext unknown
                bound = boundSocket(lookup.network)
            }
            if (bound != null) {
                val outcome = connectOutcome(bound, target, timeoutMs)
                // A miss through a cached network counts only if a fresh lookup still finds
                // that network: through one that went away in the meantime it says nothing
                // about the TV.
                if (outcome == EchoOutcome.MISSED && lookup.cached) {
                    val fresh = networks.refresh(localIp, resolve, cacheable) ?: return@withContext unknown
                    if (fresh.network != lookup.network) return@withContext Result(EchoOutcome.UNKNOWN, EchoMode.BOUND)
                }
                return@withContext Result(outcome, EchoMode.BOUND)
            }
            val before = sourceToward(target)
            if (!EchoProbeRules.routedPossible(before, localIp)) return@withContext unknown
            val raw = connectOutcome(Socket(), target, timeoutMs)
            Result(EchoProbeRules.routedOutcome(before, raw, sourceToward(target), localIp), EchoMode.ROUTED)
        }

    /**
     * Remembers the probe's network until [AutoCloseable.close]. Any Wi-Fi network arriving,
     * leaving or changing its addresses, and the default network moving, forgets it. Null
     * when the callbacks cannot be registered, and the probe then resolves every time.
     */
    fun watchNetworks(context: Context): AutoCloseable? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val wifi = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = networks.invalidate()
            override fun onLost(network: Network) = networks.invalidate()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = networks.invalidate()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                networks.onCapabilities(network, qualifies(capabilities))
        }
        val defaultNetwork = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = networks.invalidate()
            override fun onLost(network: Network) = networks.invalidate()
        }
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        val registered = runCatching {
            manager.registerNetworkCallback(request, wifi)
            runCatching { manager.registerDefaultNetworkCallback(defaultNetwork) }
                .onFailure { runCatching { manager.unregisterNetworkCallback(wifi) } }
                .getOrThrow()
        }.isSuccess
        if (!registered) return null
        // Armed only once both callbacks are in: a cache nothing invalidates must not answer.
        networks.arm()
        return object : AutoCloseable {
            private var closed = false

            @Synchronized
            override fun close() {
                if (closed) return
                closed = true
                // Disarmed first, so no probe answers from a cache whose callbacks are going.
                networks.disarm()
                runCatching { manager.unregisterNetworkCallback(wifi) }
                runCatching { manager.unregisterNetworkCallback(defaultNetwork) }
            }
        }
    }

    private fun boundSocket(network: Network): Socket? = runCatching { network.socketFactory.createSocket() }.getOrNull()

    private fun qualifies(capabilities: NetworkCapabilities): Boolean =
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)

    /**
     * Whether [watchNetworks]' request reports this network's changes. Its builder's default
     * capabilities are what a plain app can ask for, and a qualifying network outside them
     * (restricted, untrusted, a VPN's, a VCN's) gets no callbacks, so it is never cached.
     */
    @SuppressLint("WrongConstant")
    private fun coveredByCallbacks(capabilities: NetworkCapabilities): Boolean =
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
            (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    capabilities.hasCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
                )

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

    /** The non-VPN Wi-Fi network whose link addresses include [localIp], with its capabilities, or null. */
    @Suppress("DEPRECATION")
    private fun wifiNetworkHolding(context: Context, localIp: String): Pair<Network, NetworkCapabilities>? = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        manager.allNetworks.firstNotNullOfOrNull { network ->
            val capabilities = manager.getNetworkCapabilities(network) ?: return@firstNotNullOfOrNull null
            val holds = qualifies(capabilities) &&
                manager.getLinkProperties(network)?.linkAddresses?.any { it.address.hostAddress == localIp } == true
            if (holds) network to capabilities else null
        }
    }.getOrNull()
}
