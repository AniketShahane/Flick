package com.flick.sender.net

import com.flick.sender.model.DiscoveredTv

/** Candidate policy is pure so discovery hints cannot poison a stored endpoint. */
internal object ResumeCandidates {
    data class Endpoint(val host: String, val port: Int)

    fun ordered(lastHost: String, lastPort: Int, tvId: String, discovered: List<DiscoveredTv>): List<Endpoint> {
        val last = Endpoint(lastHost, lastPort)
        val candidates = discovered.asSequence()
            .filter { it.tvId == tvId && PairLaunch.isCanonicalIpv4(it.host) && it.port in 1..65535 }
            .map { Endpoint(it.host, it.port) }
            .filter { it != last }
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
    private val tried = LinkedHashSet<ResumeCandidates.Endpoint>()
    fun next(discovered: List<DiscoveredTv>): ResumeCandidates.Endpoint? =
        ResumeCandidates.ordered(lastHost, lastPort, tvId, discovered).firstOrNull { it !in tried && tried.size < maximum }
            ?.also(tried::add)
    fun hasCapacity() = tried.size < maximum
    fun hasNext(discovered: List<DiscoveredTv>) =
        tried.size < maximum && ResumeCandidates.ordered(lastHost, lastPort, tvId, discovered).any { it !in tried }
}
