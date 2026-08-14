package com.flick.sender.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.ext.SdkExtensions
import androidx.annotation.RequiresExtension
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
 *
 * A browse alone does not keep that TXT true. `onServiceFound` fires once per service
 * and [NsdManager] never re-resolves it, so a receiver that changes `state` in place —
 * or whose goodbye never reaches this phone — leaves a resolution describing a state
 * the TV has left, and the row goes on telling the user to wake a TV they have already
 * woken. Two things converge it: where the platform ships it, each held record is
 * subscribed to with [NsdManager.ServiceInfoCallback], which reports its own changes;
 * everywhere else, and for anything that subscription refuses, [SWEEP_PERIOD_MS]
 * re-resolves the names nothing is watching. Both write through [onResolved], so a
 * stale answer can only ever be replaced by a newer one and the row cannot flap
 * between the two.
 */
class NsdDiscovery(context: Context) {

    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _devices = MutableStateFlow<List<DiscoveredTv>>(emptyList())
    val devices: StateFlow<List<DiscoveredTv>> = _devices.asStateFlow()

    /**
     * Where this app's browse stands with the platform.
     *
     * [BrowseState.UNAVAILABLE] is the platform having refused this app's search and every
     * retry for it — which is a different sentence from "no TV answered", and one the
     * screen previously had no way to tell. This class's own doc already promises a
     * manual-entry fallback; without this nothing ever told the user it existed.
     *
     * The seed is [BrowseState.PENDING] rather than a refusal, because nothing has refused
     * anything before the first [start]: `discoverServices` is acknowledged on an async
     * binder callback, and a screen composed inside that window used to accuse Android of
     * blocking a search that had just been requested.
     */
    private val _browse = MutableStateFlow(BrowseState.PENDING)
    val browse: StateFlow<BrowseState> = _browse.asStateFlow()

    /**
     * Services that answered the browse and have not produced a usable record.
     *
     * A found-but-unresolved name is proof a `_flick._tcp` service exists, which is the
     * one thing an empty list cannot say for itself.
     */
    private val _unresolvedNames = MutableStateFlow(0)
    val unresolvedNames: StateFlow<Int> = _unresolvedNames.asStateFlow()

    private val awaitingResolve = HashSet<String>()

    private val lock = Any()
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val retryScope = CoroutineScope(Dispatchers.Main.immediate)
    private var retryJob: Job? = null
    private var sweepJob: Job? = null
    private var sweepsLeft = 0
    private val retryGate = NsdRetryGate()

    // serviceName -> the browse record last seen for it, so a loss (or a pre-connect
    // freshness check) can re-enqueue a resolve instead of guessing.
    private val lastInfoByName = HashMap<String, NsdServiceInfo>()
    // serviceName -> the resolve sequence that produced the currently held record.
    private val resolvedAt = HashMap<String, Long>()
    // serviceName -> its live API 34+ record subscription, while one is registered.
    private val monitors = HashMap<String, NsdManager.ServiceInfoCallback>()
    private val _resolveRevision = MutableStateFlow(0L)

    fun start() {
        synchronized(lock) {
            // Every caller of start() is a moment of attention — the app coming up, the
            // device list being opened, a rescan — which is what the sweep budget is
            // spent on. Nobody is reading a row hours later, so nothing is re-queried then.
            sweepsLeft = SWEEP_BUDGET
            if (!retryGate.begin()) {
                // Already browsing, so there is no second discovery to open — but the
                // caller asked to look again, and what the browse cannot notice on its
                // own is a TXT `state` that changed under a record already held.
                sweepStale()
                startSweep()
                return
            }
            val listener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                    FlickLog.w("nsd", "discovery failed code=$errorCode")
                    discoveryFailed { retryGate.startFailed() }
                }
                override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                    FlickLog.w("nsd", "discovery failed code=$errorCode")
                    discoveryFailed { retryGate.stopFailed() }
                }
                override fun onDiscoveryStarted(serviceType: String?) {
                    FlickLog.i("nsd", "discovery started")
                    _browse.value = BrowseState.RUNNING
                }
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
            // A request that has left this app and not yet been answered is a search in
            // flight, not a refused one: only `onStartDiscoveryFailed` and a thrown
            // request below are refusals, and both are handled where they happen.
            _browse.value = BrowseState.PENDING
            runCatching {
                nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            }.onFailure {
                discoveryListener = null
                if (retryGate.startFailed()) scheduleRetry() else _browse.value = BrowseState.UNAVAILABLE
            }
            if (discoveryListener != null) startSweep()
        }
    }

    fun stop() {
        synchronized(lock) {
            discoveryListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
            discoveryListener = null
            pending.clear()
            resolving = false
            retryJob?.cancel(); retryJob = null
            sweepJob?.cancel(); sweepJob = null
            monitors.keys.toList().forEach(::dropMonitor)
            awaitingResolve.clear()
            _unresolvedNames.value = 0
            // Back to pending, never to unavailable: this app asked for the browse to end,
            // and a screen that reads this next is reading it after the next start().
            _browse.value = BrowseState.PENDING
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
            info.serviceName?.let {
                lastInfoByName[it] = info
                if (_devices.value.none { device -> device.name == it }) {
                    awaitingResolve += it
                    _unresolvedNames.value = awaitingResolve.size
                }
            }
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

    /** Re-resolves every held record no subscription is watching. Callers hold [lock]. */
    private fun sweepStale() {
        // A pass already in flight is the freshest answer there is, and queueing a second
        // one behind it would let a stalled resolve grow the queue once per period.
        if (resolving || pending.isNotEmpty()) return
        var queued = false
        for ((name, info) in lastInfoByName) {
            if (monitors.containsKey(name)) continue
            pending.addLast(info)
            queued = true
        }
        if (queued) pumpResolve()
    }

    /**
     * Ends with the budget rather than idling on it: a timer that outlived its last pass
     * would go on waking the main looper every [SWEEP_PERIOD_MS] for the life of the
     * process to decide it has nothing to do. Every [start] re-arms the budget and starts
     * it again, which is the same moment of attention the budget is spent on.
     */
    private fun startSweep() {
        if (sweepJob?.isActive == true) return
        sweepJob = retryScope.launch {
            while (true) {
                delay(SWEEP_PERIOD_MS)
                synchronized(lock) {
                    if (discoveryListener == null || sweepsLeft <= 0) return@launch
                    sweepsLeft--
                    sweepStale()
                }
            }
        }
    }

    /**
     * Subscribes to one service's own record so its TXT changes arrive without a browse
     * event. Bounded because the platform caps concurrent NSD requests and a home LAN
     * that answers with more receivers than this has nothing more to tell us.
     */
    private fun ensureMonitor(name: String) {
        // SDK_INT alone does not prove the subscription exists: it lives in the
        // Connectivity mainline module, so a device can report 34 and still lack the
        // class — instantiating ServiceMonitor there is a NoClassDefFoundError, not a
        // rejected registration. Below the extension, the sweep is the whole fallback.
        if (Build.VERSION.SDK_INT < MONITOR_API) return
        if (SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) < MONITOR_EXT) return
        if (monitors.containsKey(name) || monitors.size >= MAX_MONITORS) return
        val info = lastInfoByName[name] ?: return
        val monitor = ServiceMonitor(name)
        monitors[name] = monitor
        runCatching { nsd.registerServiceInfoCallback(info, appContext.mainExecutor, monitor) }
            .onFailure {
                FlickLog.w("nsd", "monitor rejected name=$name")
                monitors.remove(name)
            }
    }

    private fun dropMonitor(name: String) {
        if (Build.VERSION.SDK_INT < MONITOR_API) return
        if (SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) < MONITOR_EXT) return
        val monitor = monitors.remove(name) ?: return
        runCatching { nsd.unregisterServiceInfoCallback(monitor) }
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
            val retired = retiredByResolve(_devices.value, name, tv.host)
            _devices.value = (_devices.value.filter { it.name != name && it.host != tv.host } + tv)
                .sortedByDescending { it.state == TvAvailability.READY }
            _resolveRevision.value = stamp
            if (awaitingResolve.remove(name)) _unresolvedNames.value = awaitingResolve.size
            // A renamed TV retires its old name HERE and nowhere else: the line above is
            // the only place that name leaves [_devices], and [onLost] — the one path that
            // tears a name down — returns early on a name already gone from it. Left as it
            // was, the retired name kept its own `ServiceInfoCallback` registered for the
            // life of the process, re-querying a name nothing on the LAN answers to and
            // holding one of [MAX_MONITORS] against the TVs that still exist.
            retired.forEach { gone ->
                resolvedAt.remove(gone)
                lastInfoByName.remove(gone)
                dropMonitor(gone)
                if (awaitingResolve.remove(gone)) _unresolvedNames.value = awaitingResolve.size
            }
            ensureMonitor(name)
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
                        dropMonitor(name)
                        if (awaitingResolve.remove(name)) _unresolvedNames.value = awaitingResolve.size
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
            // Only an exhausted retry budget may say the search is unavailable: a browse
            // that will be reopened in a second is still, from the user's side, pending.
            if (retry()) {
                _browse.value = BrowseState.PENDING
                scheduleRetry()
            } else {
                _browse.value = BrowseState.UNAVAILABLE
            }
        }
    }

    /**
     * The API 34+ subscription to one service's own record. It survives its own
     * [onServiceLost]: the platform keeps reporting the service if it returns, which is
     * exactly the re-registration a receiver performs to flip its advertised state.
     */
    @RequiresExtension(extension = Build.VERSION_CODES.TIRAMISU, version = MONITOR_EXT)
    private inner class ServiceMonitor(private val name: String) : NsdManager.ServiceInfoCallback {
        override fun onServiceUpdated(serviceInfo: NsdServiceInfo) = onResolved(serviceInfo)

        override fun onServiceLost() = onLost(name)

        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
            FlickLog.w("nsd", "monitor failed name=$name code=$errorCode")
            // Handing the name back to the sweep is the whole recovery: it is the path
            // every pre-34 device already takes.
            synchronized(lock) { if (monitors[name] === this) monitors.remove(name) }
        }

        override fun onServiceInfoCallbackUnregistered() {
            synchronized(lock) { if (monitors[name] === this) monitors.remove(name) }
        }
    }

    private companion object {
        const val SERVICE_TYPE = "_flick._tcp."
        const val SERVICE_TYPE_MATCH = "_flick._tcp"
        const val LOSS_GRACE_MS = 2_500L
        const val REFRESH_TIMEOUT_MS = 900L
        /** Android 14 — the first release with a per-service record subscription. */
        const val MONITOR_API = 34
        /** The T extension SDK that actually ships that subscription's callback class. */
        const val MONITOR_EXT = 7
        const val MAX_MONITORS = 4
        // The ceiling on how long a woken TV can go on being listed as asleep where no
        // subscription is available.
        const val SWEEP_PERIOD_MS = 15_000L
        // Five minutes of re-resolving per moment of attention. Unbounded, this would be
        // one query per held TV every 15 s for as long as the process lives — a browse
        // costs nothing to leave running, but a resolve loop is traffic.
        const val SWEEP_BUDGET = 20
    }
}

/**
 * The names a resolve of [name] at [host] displaces — the old names of a TV that was
 * renamed, which this resolve is about to drop from the device list.
 *
 * A rename is the only way a name leaves that list without passing through the loss
 * path, and the loss path is where a name's record subscription is unregistered. So
 * these names are exactly the ones whose subscription would otherwise outlive them:
 * one mDNS query every ~20 s, for a name nothing on the LAN answers to, for the life
 * of the process — and one of the four subscription slots held against a TV that
 * still exists.
 *
 * Matching is on the host and never on the name: the new name is the only thing known
 * about the rename, and the old one is by definition the value being replaced. A
 * record carrying [name] itself is a re-registration rather than a rename, and keeps
 * its subscription.
 */
internal fun retiredByResolve(devices: List<DiscoveredTv>, name: String, host: String): List<String> =
    devices.filter { it.name != name && it.host == host }.map { it.name }
