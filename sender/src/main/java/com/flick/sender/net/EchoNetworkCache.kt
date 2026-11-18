package com.flick.sender.net

/**
 * The Wi-Fi network an echo probe leaves over, remembered between probes so a probe a
 * second does not walk every network over binder to find the same one again.
 *
 * Only safe while something reports the changes that could move the answer, so it caches
 * only while [armed]: between [arm] and [disarm] a network callback calls [invalidate] on
 * every arrival, loss and link change. A disarmed cache resolves every time, as an
 * uncached probe always did.
 *
 * A callback lands on another thread a moment after the change it reports, so a cached
 * answer can be stale for that moment. The probe therefore never lets one decide anything
 * a fresh lookup would not have: a bound socket that cannot be made on a cached network,
 * and a miss through one, are both checked with [refresh] before they count (see
 * [WifiEchoProbe]). An invalidation that lands while a lookup is in flight wins: the
 * generation stamp keeps that lookup's answer out of the cache. No answer (null) is never
 * cached, so a network that comes to hold the address is found on the next probe whether
 * or not a callback saw it arrive.
 *
 * [N] is the platform's `Network` in production; anything with value equality in tests.
 */
internal class EchoNetworkCache<N : Any> {

    /** A lookup's answer; [cached] is true when no resolve was spent on it. */
    data class Lookup<N : Any>(val network: N, val cached: Boolean)

    private var arms = 0
    private var generation = 0L
    private var cachedFor: String? = null
    private var cached: N? = null

    @Synchronized
    fun arm() {
        arms += 1
        forget()
    }

    @Synchronized
    fun disarm() {
        if (arms > 0) arms -= 1
        forget()
    }

    @Synchronized
    fun invalidate() = forget()

    /**
     * A capability change on [network]. Transports are what the lookup reads and the
     * platform never changes them for a network, so a change that leaves the cached
     * network still qualifying is a signal-strength step and keeps the cache.
     */
    @Synchronized
    fun onCapabilities(network: N, qualifies: Boolean) {
        if (network == cached && !qualifies) forget()
    }

    /** The network for [localIp], from the cache while armed and holding it, else from [resolve]. */
    fun lookup(localIp: String, resolve: (String) -> N?): Lookup<N>? {
        val stamp = synchronized(this) {
            val held = cached
            if (arms > 0 && held != null && cachedFor == localIp) return Lookup(held, cached = true)
            generation
        }
        return store(localIp, stamp, resolve(localIp))
    }

    /** Resolves [localIp] afresh, replacing whatever was cached; never answers from the cache. */
    fun refresh(localIp: String, resolve: (String) -> N?): Lookup<N>? {
        val stamp = synchronized(this) { generation }
        return store(localIp, stamp, resolve(localIp))
    }

    private fun store(localIp: String, stamp: Long, fresh: N?): Lookup<N>? {
        synchronized(this) {
            if (stamp == generation) {
                cached = fresh
                cachedFor = fresh?.let { localIp }
            }
        }
        return fresh?.let { Lookup(it, cached = false) }
    }

    private fun forget() {
        generation += 1
        cached = null
        cachedFor = null
    }
}
