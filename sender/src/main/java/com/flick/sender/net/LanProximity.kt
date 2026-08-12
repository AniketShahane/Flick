package com.flick.sender.net

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
}
