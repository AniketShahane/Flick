package com.flick.sender.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.flick.sender.model.DiscoveredTv
import com.flick.sender.model.TvAvailability
import com.flick.sender.util.FlickLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.ArrayDeque

/**
 * Browses `_flick._tcp` and resolves entries into the S1 device list
 * (control-channel.md §2). Discovery is best-effort — if NSD yields nothing (some
 * routers block mDNS) the caller falls back to manual address entry; NSD never
 * dead-ends. Resolves are serialized because [NsdManager] rejects a second
 * concurrent resolve with "listener already in use".
 *
 * The cache is keyed by serviceName and every successful resolve is stamped with a
 * monotonic sequence: a receiver that re-registers to flip its TXT `state` looks
 * like lost-then-found, and a late `onServiceLost` carrying an older stamp must
 * never delete the record a newer resolve just wrote.
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
    private val retryScope = CoroutineScope(Dispatchers.Main.immediate)
    private var retryJob: Job? = null
    private val retryGate = NsdRetryGate()

    // serviceName -> the browse record last seen for it, so a loss (or a pre-connect
    // freshness check) can re-enqueue a resolve instead of guessing.
    private val lastInfoByName = HashMap<String, NsdServiceInfo>()
    // serviceName -> the resolve sequence that produced the currently held record.
    private val resolvedAt = HashMap<String, Long>()
    private val _resolveRevision = MutableStateFlow(0L)

    fun start() {
        synchronized(lock) {
            if (!retryGate.begin()) return
            val listener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                    FlickLog.w("nsd", "discovery failed code=$errorCode")
                    discoveryFailed { retryGate.startFailed() }
                }
                override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                    FlickLog.w("nsd", "discovery failed code=$errorCode")
                    discoveryFailed { retryGate.stopFailed() }
                }
                override fun onDiscoveryStarted(serviceType: String?) { FlickLog.i("nsd", "discovery started") }
                override fun onDiscoveryStopped(serviceType: String?) = discoveryFailed { retryGate.stopped() }
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (serviceInfo.serviceType?.contains(SERVICE_TYPE_MATCH) == true) {
                        enqueueResolve(serviceInfo)
                    }
                }
                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    onLost(serviceInfo.serviceName)
                }
            }
            discoveryListener = listener
            runCatching {
                nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            }.onFailure {
                discoveryListener = null
                if (retryGate.startFailed()) scheduleRetry()
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            discoveryListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
            discoveryListener = null
            pending.clear()
            resolving = false
            retryJob?.cancel(); retryJob = null
            retryGate.stopRequested()
        }
    }

    /**
     * Re-resolves the cached record for [tvId] just before it is dialed.
     * `NsdManager.resolveService` is known to answer from a cached SRV on several
     * Android versions, so the port held here can be stale. Falls back to the cached
     * record when the fresh resolve does not land inside [timeoutMs].
     */
    suspend fun refresh(tvId: String, timeoutMs: Long = REFRESH_TIMEOUT_MS): DiscoveredTv? {
        val cached = _devices.value.firstOrNull { it.tvId == tvId } ?: return null
        val before = synchronized(lock) {
            val info = lastInfoByName[cached.name] ?: return cached
            pending.addLast(info)
            pumpResolve()
            _resolveRevision.value
        }
        withTimeoutOrNull(timeoutMs) { _resolveRevision.first { it > before } }
        return _devices.value.firstOrNull { it.tvId == tvId } ?: cached
    }

    private fun enqueueResolve(info: NsdServiceInfo) {
        synchronized(lock) {
            info.serviceName?.let { lastInfoByName[it] = info }
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
                    FlickLog.w("nsd", "resolve failed code=$errorCode")
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
        val name = info.serviceName ?: "TV"
        val tv = DiscoveredTv(
            name = name,
            host = host,
            port = info.port,
            tvId = attr("id")?.takeIf { ControlProtocolV2.id(it) },
            protocolVersion = attr("v")?.toIntOrNull(),
            model = attr("model"),
            state = when (attr("state")?.lowercase()) {
                "ready" -> TvAvailability.READY
                "sleeping" -> TvAvailability.SLEEPING
                else -> TvAvailability.UNKNOWN
            },
        )
        FlickLog.i(
            "nsd",
            "resolved name=$name ${tv.host}:${tv.port} v=${tv.protocolVersion} state=${tv.state.name.lowercase()}",
        )
        synchronized(lock) {
            val stamp = _resolveRevision.value + 1L
            resolvedAt[name] = stamp
            // Drop the same name (a re-registration) and any other record squatting the
            // same host (a rename), so neither key can leave a duplicate behind.
            _devices.value = (_devices.value.filter { it.name != name && it.host != tv.host } + tv)
                .sortedByDescending { it.state == TvAvailability.READY }
            _resolveRevision.value = stamp
        }
    }

    /**
     * A loss is treated as "re-resolve me", not "delete me": contract C4 flips TXT
     * `state` by re-registering the same name, which surfaces here as a loss. The
     * record is dropped only if no newer resolve lands inside the grace window.
     */
    private fun onLost(name: String?) {
        if (name == null) return
        synchronized(lock) {
            if (_devices.value.none { it.name == name }) return
            val stampAtLoss = resolvedAt[name] ?: 0L
            FlickLog.i("nsd", "lost name=$name — re-resolving")
            lastInfoByName[name]?.let { pending.addLast(it); pumpResolve() }
            retryScope.launch {
                delay(LOSS_GRACE_MS)
                synchronized(lock) {
                    // A newer stamp means the re-resolve landed: the loss was stale.
                    if ((resolvedAt[name] ?: 0L) <= stampAtLoss) {
                        FlickLog.i("nsd", "dropped name=$name")
                        resolvedAt.remove(name)
                        lastInfoByName.remove(name)
                        _devices.value = _devices.value.filter { it.name != name }
                    }
                }
            }
        }
    }

    private fun scheduleRetry() {
        if (retryJob?.isActive == true) return
        retryJob = retryScope.launch {
            delay(1_000)
            synchronized(lock) {
                retryJob = null
                if (discoveryListener == null && retryGate.retryFired()) start()
            }
        }
    }

    private fun discoveryFailed(retry: () -> Boolean) {
        synchronized(lock) {
            // Intentional stop clears the listener first, so its asynchronous callback
            // cannot resurrect discovery behind the UI's back.
            if (discoveryListener == null) return
            discoveryListener = null
            if (retry()) scheduleRetry()
        }
    }

    private companion object {
        const val SERVICE_TYPE = "_flick._tcp."
        const val SERVICE_TYPE_MATCH = "_flick._tcp"
        const val LOSS_GRACE_MS = 2_500L
        const val REFRESH_TIMEOUT_MS = 900L
    }
}
