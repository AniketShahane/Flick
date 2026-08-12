package com.flick.receiver.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.flick.receiver.util.FlickLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Reports the site-local IPv4 the TV currently owns — an ADDRESS, not an event.
 *
 * Callers must not treat a connectivity callback as a rebind signal. A default
 * network emits `onCapabilitiesChanged` for RSSI, link speed, validation and
 * NOT_SUSPENDED transitions on a link that never changed address; those updates
 * carry no address at all (addresses live in LinkProperties), so a callback-count
 * flow is indistinguishable from a real DHCP change and made the control server
 * rebind — onto a new ephemeral port — seconds apart on a healthy Wi-Fi link.
 *
 * Every callback here is only a trigger to RE-SAMPLE. [address] is distinct, so a
 * capability burst on an unchanged address produces no emission at all.
 */
class LanBindingMonitor(
    context: Context,
    private val sample: () -> String? = LanAddress::current,
) {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val _address = MutableStateFlow<String?>(null)

    /**
     * Current site-local IPv4, or null when the TV has none. StateFlow conflates
     * equal values, so re-sampling the same address emits nothing — the explicit
     * `distinctUntilChanged()` this contract calls for is already inherent here
     * (and is an error-level deprecation on StateFlow).
     */
    val address: StateFlow<String?> = _address

    /** Latest sampled address without collecting. */
    val current: String? get() = _address.value

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = resample("available")
        override fun onLost(network: Network) = resample("lost")
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = resample("capabilities")
    }

    fun start() {
        resample(LAN_CALLBACK_START)
        runCatching { manager.registerDefaultNetworkCallback(callback) }
    }

    fun stop() {
        runCatching { manager.unregisterNetworkCallback(callback) }
    }

    private fun resample(callback: String) {
        val next = sample()
        val changed = next != _address.value
        if (logsLanResample(callback, changed)) {
            FlickLog.v("lan", "callback=$callback addr=${next ?: "null"} changed=$changed")
        }
        _address.value = next
    }
}

internal const val LAN_CALLBACK_START = "start"

/**
 * Whether a re-sample is worth a line.
 *
 * The monitor above already knows these callbacks are mostly noise — it is the entire reason
 * it re-samples an ADDRESS rather than counting events. That reasoning was applied to what it
 * DOES and never to what it SAYS, so an idle TV on a healthy link wrote a line every few
 * seconds to report that nothing had changed.
 *
 * [FlickLog] records every level into a 200-entry ring, `v` included, so roughly twenty
 * minutes of sitting still evicts a whole cast; and on a release build `v` never reaches
 * logcat, so those lines are at once the only thing IN the ring and invisible outside it.
 * Observed on the verified TV: a five-hour-old process whose entire buffer was this one line
 * repeated, with the pairing and the playback it was opened to explain long since gone.
 *
 * So speak when the answer moved. A re-sample agreeing with the last one is the normal case
 * and describes nothing. [LAN_CALLBACK_START] always speaks, because a first sample of null
 * is a real answer about this TV rather than agreement with a value never taken.
 */
internal fun logsLanResample(callback: String, changed: Boolean): Boolean =
    changed || callback == LAN_CALLBACK_START
