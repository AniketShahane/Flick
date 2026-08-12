package com.flick.sender.net

/** What the device list under "NEARBY" is actually saying. */
enum class DiscoveryFace { NO_NETWORK, SEARCH_UNAVAILABLE, SEARCHING, NOTHING_FOUND, FOUND }

/**
 * What the platform has said about this app's browse, which is three answers and not two.
 *
 * [PENDING] is the one that had to exist: `NsdManager.discoverServices` is acknowledged on
 * an asynchronous binder callback, so between the request and [RUNNING] there is a window —
 * milliseconds normally, the whole retry backoff after a failed start — in which the search
 * has neither started nor been refused. Rendering that as [UNAVAILABLE] made the Connect
 * screen accuse Android of blocking a search that was about to run, on the first screen a
 * new install opens.
 */
enum class BrowseState { PENDING, RUNNING, UNAVAILABLE }

/**
 * [hasLanAddress] false is proof, not a guess: `NetworkUtils.getSiteLocalIpv4` returns
 * null only when this phone holds no site-local address at all, and there is then
 * nothing to search. [BrowseState.UNAVAILABLE] is the platform having refused the request
 * and every retry for it, which is a different fact again — and one this build could not
 * previously state, so the manual-entry fallback the discovery class documents was never
 * offered.
 *
 * [settleMs] exists because mDNS answers are not instant and a list that said "nothing
 * found" in its first second would be wrong far more often than right.
 */
fun discoveryFace(
    hasLanAddress: Boolean,
    browse: BrowseState,
    deviceCount: Int,
    elapsedMs: Long,
    settleMs: Long = 12_000L,
): DiscoveryFace = when {
    !hasLanAddress -> DiscoveryFace.NO_NETWORK
    // A found device outranks a failed browse: rows already on screen are real, whatever
    // the platform has since said about the search that produced them.
    deviceCount > 0 -> DiscoveryFace.FOUND
    browse == BrowseState.UNAVAILABLE -> DiscoveryFace.SEARCH_UNAVAILABLE
    elapsedMs < settleMs -> DiscoveryFace.SEARCHING
    else -> DiscoveryFace.NOTHING_FOUND
}
