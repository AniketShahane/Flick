package com.flick.sender.net

import com.flick.sender.model.DiscoveredTv

/** Candidate policy is pure so discovery hints cannot poison a stored endpoint. */
internal object ResumeCandidates {
    /**
     * [discovered] is provenance, not rank: it says this address arrived in an
     * advertisement that crossed the network this phone is on NOW, rather than out of a
     * record written on a network it may since have left. Nothing may claim the two
     * devices share a network off a merely remembered address — see
     * [LanProximity.sameSubnetClaim].
     */
    data class Endpoint(val host: String, val port: Int, val discovered: Boolean) {
        /**
         * Identity for the queue below, deliberately narrower than the value: a late
         * advertisement corroborating an endpoint already dialed is the same endpoint,
         * and must not spend a second slot in a bounded sweep.
         */
        val address: Pair<String, Int> get() = host to port
    }

    fun ordered(lastHost: String, lastPort: Int, tvId: String, discovered: List<DiscoveredTv>): List<Endpoint> {
        val live = discovered.filter { it.tvId == tvId && PairLaunch.isCanonicalIpv4(it.host) && it.port in 1..65535 }
        // The stored endpoint is a memory UNLESS a live advertisement names it exactly,
        // which is the receiver saying it is at that address on this network right now.
        val last = Endpoint(lastHost, lastPort, live.any { it.host == lastHost && it.port == lastPort })
        val candidates = live.asSequence()
            .map { Endpoint(it.host, it.port, discovered = true) }
            .filter { it.address != last.address }
            .distinct()
            .sortedWith(compareBy<Endpoint> { it.host }.thenBy { it.port })
            .take(3)
            .toList()
        // A live advertisement at the SAME address is the receiver saying it rebound to
        // a new port, so trying the stored port first would burn a full connect timeout.
        // A candidate at a DIFFERENT address stays behind the stored endpoint, so a
        // rogue advertiser can never jump ahead of the endpoint we actually verified.
        val (rebound, elsewhere) = candidates.partition { it.host == lastHost }
        return rebound + last + elsewhere
    }
}

/** Bounded, deduplicated candidate queue that can absorb a late NSD resolution. */
internal class ResumeCandidateQueue(
    private val lastHost: String,
    private val lastPort: Int,
    private val tvId: String,
    private val maximum: Int = 4,
) {
    private val tried = LinkedHashSet<Pair<String, Int>>()
    fun next(discovered: List<DiscoveredTv>): ResumeCandidates.Endpoint? =
        ResumeCandidates.ordered(lastHost, lastPort, tvId, discovered).firstOrNull { it.address !in tried && tried.size < maximum }
            ?.also { tried += it.address }
    fun hasCapacity() = tried.size < maximum
    fun hasNext(discovered: List<DiscoveredTv>) =
        tried.size < maximum && ResumeCandidates.ordered(lastHost, lastPort, tvId, discovered).any { it.address !in tried }
}
