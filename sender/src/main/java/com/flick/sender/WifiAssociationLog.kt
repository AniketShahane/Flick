package com.flick.sender

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import com.flick.sender.util.FlickLog

/**
 * Every Wi-Fi association this phone makes, as edges on the monotonic clock.
 *
 * It exists to settle one question nothing else in the app can. The home-LAN fault
 * (research/03) is a router that stops carrying traffic between this phone and the TV and
 * starts again by itself minutes later, and no unprivileged app can shorten it — so the
 * only thing left worth knowing is what ENDS one. If a new [Network] object appears within
 * half a minute of a block clearing, the block was ended by this phone re-associating, and
 * no app-side action could ever have mattered. If none appears, every explanation that
 * turns on a station-inactivity timer dies with it. Either answer is decisive, which is why
 * this records edges rather than state, and why the timestamps are elapsed-realtime: a
 * wall clock that steps mid-outage would invent or erase the very interval being measured.
 *
 * NEVER the SSID or the BSSID. This repository is public, both need a location permission
 * this app does not hold, and neither is what the question is about — the association
 * EPOCH is, and it is a counter derived here that says nothing about the network at all.
 * [wifiAssociationLine] has no parameter either of them could arrive in.
 */
class WifiAssociationMonitor(context: Context) {

    private val appContext = context.applicationContext
    private val ledger = WifiAssociationLedger()

    /**
     * Registered for the life of the process and deliberately never unregistered: this is
     * application-scoped, the question is about intervals that begin before any screen is
     * open, and a callback torn down between them would miss the edge that answers it.
     */
    fun start() {
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                ledger.available(network.networkHandle)?.let { record("available", it) }
            }

            override fun onLost(network: Network) {
                ledger.lost(network.networkHandle)?.let { record("lost", it) }
            }
        }
        runCatching { manager.registerNetworkCallback(request, callback) }
            .onFailure { FlickLog.w("lan", "wifi-assoc unavailable err=${it.javaClass.simpleName}") }
    }

    private fun record(edge: String, epoch: Int) {
        FlickLog.i(
            "lan",
            wifiAssociationLine(
                edge = edge,
                epoch = epoch,
                atMs = SystemClock.elapsedRealtime(),
                link = NetworkUtils.getWifiLinkInfo(appContext),
            ),
        )
    }
}

/**
 * Which association each [Network] belongs to.
 *
 * The platform never reuses a Network object, so a handle this has not seen IS a new
 * association — and a repeat for one it has must not open an epoch it did not earn. Losses
 * answer with the epoch they close rather than the current one, because a handover reports
 * the new network's arrival before the old one's departure and reading the counter live
 * would file that departure under the association that replaced it.
 *
 * Bounded, because it is fed by a callback that outlives every screen.
 */
internal class WifiAssociationLedger(private val limit: Int = 8) {

    private val epochs = LinkedHashMap<Long, Int>()
    private var epoch = 0

    /** The epoch [handle] opens, or null when it is one already recorded. */
    @Synchronized
    fun available(handle: Long): Int? {
        if (epochs.containsKey(handle)) return null
        epoch += 1
        epochs[handle] = epoch
        while (epochs.size > limit) epochs.remove(epochs.keys.first())
        return epoch
    }

    /** The epoch [handle] closes, or null for a network this never saw arrive. */
    @Synchronized
    fun lost(handle: Long): Int? = epochs[handle]
}

/**
 * One edge, in the shape the question is grepped in.
 *
 * `wifi-assoc` is the anchor: one token, present on every line and on no other, so a whole
 * diagnostics ring filters down to the association history in one pass. A null [link] is
 * the phone holding no readable Wi-Fi link at that instant — which is itself a reading at a
 * loss edge, and is stated as an absence rather than as zeros that would average in.
 */
internal fun wifiAssociationLine(edge: String, epoch: Int, atMs: Long, link: WifiLinkInfo?): String =
    "wifi-assoc edge=$edge epoch=$epoch atMs=$atMs " + if (link == null) {
        "link=none"
    } else {
        "band=${link.band.name.lowercase()} freqMhz=${link.frequencyMhz} " +
            "linkMbps=${link.linkSpeedMbps} rssiDbm=${link.rssiDbm}"
    }
