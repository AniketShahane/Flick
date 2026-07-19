package com.flick.sender.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.flick.sender.model.DiscoveredTv
import com.flick.sender.model.TvAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

/**
 * Browses `_flick._tcp` and resolves entries into the S1 device list
 * (control-channel.md §2). Discovery is best-effort — if NSD yields nothing (some
 * routers block mDNS) the caller falls back to manual address entry; NSD never
 * dead-ends. Resolves are serialized because [NsdManager] rejects a second
 * concurrent resolve with "listener already in use".
 */
class NsdDiscovery(context: Context) {

    private val nsd = context.applicationContext
        .getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _devices = MutableStateFlow<List<DiscoveredTv>>(emptyList())
    val devices: StateFlow<List<DiscoveredTv>> = _devices.asStateFlow()

    private val lock = Any()
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun start() {
        synchronized(lock) {
            if (discoveryListener != null) return
            val listener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
                override fun onDiscoveryStarted(serviceType: String?) {}
                override fun onDiscoveryStopped(serviceType: String?) {}
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (serviceInfo.serviceType?.contains(SERVICE_TYPE_MATCH) == true) {
                        enqueueResolve(serviceInfo)
                    }
                }
                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    removeByName(serviceInfo.serviceName)
                }
            }
            discoveryListener = listener
            runCatching {
                nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            discoveryListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
            discoveryListener = null
            pending.clear()
            resolving = false
        }
    }

    private fun enqueueResolve(info: NsdServiceInfo) {
        synchronized(lock) {
            pending.addLast(info)
            pumpResolve()
        }
    }

    private fun pumpResolve() {
        synchronized(lock) {
            if (resolving) return
            val next = pending.pollFirst() ?: return
            resolving = true
            val listener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    synchronized(lock) { resolving = false; pumpResolve() }
                }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    onResolved(serviceInfo)
                    synchronized(lock) { resolving = false; pumpResolve() }
                }
            }
            runCatching { nsd.resolveService(next, listener) }
                .onFailure { synchronized(lock) { resolving = false; pumpResolve() } }
        }
    }

    @Suppress("DEPRECATION")
    private fun onResolved(info: NsdServiceInfo) {
        val host = info.host?.hostAddress ?: return
        val attrs = info.attributes ?: emptyMap()
        fun attr(key: String): String? = attrs[key]?.let { String(it) }
        val tv = DiscoveredTv(
            name = info.serviceName ?: "TV",
            host = host,
            port = info.port,
            model = attr("model"),
            state = when (attr("state")?.lowercase()) {
                "ready" -> TvAvailability.READY
                "sleeping" -> TvAvailability.SLEEPING
                else -> TvAvailability.UNKNOWN
            },
        )
        _devices.value = (_devices.value.filter { it.host != tv.host } + tv)
            .sortedByDescending { it.state == TvAvailability.READY }
    }

    private fun removeByName(name: String?) {
        if (name == null) return
        _devices.value = _devices.value.filter { it.name != name }
    }

    private companion object {
        const val SERVICE_TYPE = "_flick._tcp."
        const val SERVICE_TYPE_MATCH = "_flick._tcp"
    }
}
