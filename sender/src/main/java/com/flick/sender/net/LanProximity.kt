package com.flick.sender.net

/**
 * An address this phone dialed, carrying the one fact the /24 comparison cannot supply:
 * whether the phone learned the address on the network it is on NOW.
 */
data class DialedHost(val host: String, val liveVerified: Boolean)

/**
 * Two facts the phone can prove about its own position on the network, and the honesty
 * limits on each.
 *
 * A non-null site-local address does NOT prove Wi-Fi: `NetworkUtils.getSiteLocalIpv4`'s
 * fallback branch accepts a cellular rmnet 10/8 address. Only the null case may say "not
 * on Wi-Fi", and any positive Wi-Fi claim has to be paired with `getWifiLinkInfo != null`.
 *
 * A shared /24 does NOT prove one broadcast domain — a VLAN can split one. The claim this
 * supports is "on the same network", never "the network is fine".
 */
object LanProximity {
    fun sameSubnet(ownIp: String?, targetIp: String?): Boolean {
        if (ownIp == null || targetIp == null) return false
        if (!PairLaunch.isCanonicalIpv4(ownIp) || !PairLaunch.isCanonicalIpv4(targetIp)) return false
        return ownIp.substringBeforeLast('.') == targetIp.substringBeforeLast('.')
    }

    /**
     * The same comparison as something the copy may state — or null, where it may not.
     *
     * A remembered address is measured against nothing. 192.168.1.0/24 is the commonest
     * consumer-router default, so a phone that paired at home and has since joined a hotel
     * or a hotspot on that same range matches its stored TV address by pure coincidence —
     * and the copy would then rule out the very Wi-Fi that IS the fault, and send the user
     * to their router settings instead of onto the right network. Only an address this
     * phone met on the network it is on now may carry the claim.
     */
    fun sameSubnetClaim(ownIp: String?, dialed: DialedHost?): Boolean? {
        val target = dialed?.takeIf { it.liveVerified }?.host ?: return null
        val own = ownIp ?: return null
        return sameSubnet(own, target)
    }
}
