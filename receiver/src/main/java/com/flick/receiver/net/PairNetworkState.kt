package com.flick.receiver.net

/**
 * What the pair screen may say about this TV's network.
 *
 * `networkReady` was one flag for three unrelated states, so a TV that had just
 * returned a site-local address and then failed every bind was told to "Connect this TV
 * to your home network" — the one thing it had provably already done.
 */
enum class PairNetworkFace { READY, NO_ADDRESS, NOT_SITE_LOCAL, NO_BIND }

/**
 * [boundPort] is the real bound port and not a Boolean derived from it, because the
 * whole point of [PairNetworkFace.NO_BIND] is the case where an address exists and a
 * port does not.
 */
fun pairNetworkFace(hasSiteLocalIpv4: Boolean, hasAnyIpv4: Boolean, boundPort: Int): PairNetworkFace = when {
    !hasSiteLocalIpv4 && hasAnyIpv4 -> PairNetworkFace.NOT_SITE_LOCAL
    !hasSiteLocalIpv4 -> PairNetworkFace.NO_ADDRESS
    boundPort <= 0 -> PairNetworkFace.NO_BIND
    else -> PairNetworkFace.READY
}
