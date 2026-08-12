package com.flick.sender.net

import com.flick.sender.model.DiscoveredTv
import com.flick.sender.model.TvAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeCandidatesTest {
    private val tvId = "ABEiM0RVZneImaq7zN3u_w"

    @Test fun lastVerifiedEndpointWinsAndUnrelatedDiscoveryCannotPoisonCandidates() {
        val candidates = ResumeCandidates.ordered(
            "192.168.42.88", 42421, tvId,
            listOf(
                tv("192.168.42.91", 42421), tv("192.168.42.89", 42421),
                tv("192.168.42.88", 42421), tv("192.168.42.92", 42421, "ERITFBUWFxgZGhscHR4fIA"),
            ),
        )
        assertEquals(
            listOf("192.168.42.88", "192.168.42.89", "192.168.42.91"),
            candidates.map { it.host },
        )
    }

    @Test fun aLiveAdvertisementAtTheStoredAddressIsTriedBeforeTheStoredPort() {
        // A same-host rebind is the receiver telling us the port moved; the stored
        // port would otherwise burn a full connect timeout first.
        val candidates = ResumeCandidates.ordered(
            "192.168.42.88", 42421, tvId,
            listOf(tv("192.168.42.88", 47654), tv("192.168.42.89", 47654)),
        )
        assertEquals(
            listOf("192.168.42.88" to 47654, "192.168.42.88" to 42421, "192.168.42.89" to 47654),
            candidates.map { it.host to it.port },
        )
    }

    // The stored endpoint is where the TV was last SEEN. Nothing about a record written on
    // one network says the phone is still on it, so it carries no provenance of its own.
    @Test fun onlyDiscoveredCandidatesAreMarkedLive() {
        val candidates = ResumeCandidates.ordered(
            "192.168.42.88", 42421, tvId,
            listOf(tv("192.168.42.88", 47654), tv("192.168.42.89", 47654)),
        )
        assertEquals(listOf(true, false, true), candidates.map { it.discovered })
    }

    @Test fun anAdvertisementAtTheStoredEndpointCorroboratesItWithoutDuplicatingIt() {
        val candidates = ResumeCandidates.ordered(
            "192.168.42.88", 42421, tvId,
            listOf(tv("192.168.42.88", 42421)),
        )
        assertEquals(listOf("192.168.42.88" to 42421), candidates.map { it.host to it.port })
        assertEquals(listOf(true), candidates.map { it.discovered })
    }

    // Provenance must not become identity: a queue that re-offered an endpoint because a
    // late advertisement corroborated it would spend a second slot on the same address.
    @Test fun aLateAdvertisementAtAnAlreadyTriedEndpointIsNotOfferedAgain() {
        val queue = ResumeCandidateQueue("192.168.42.88", 42421, tvId)
        assertEquals(false, queue.next(emptyList())?.discovered)
        val late = listOf(tv("192.168.42.88", 42421))
        assertEquals(false, queue.hasNext(late))
        assertEquals(null, queue.next(late))
    }

    @Test fun lateMatchingNsdEndpointIsTriedOnceAfterTheStoredPortFails() {
        val queue = ResumeCandidateQueue("192.168.42.88", 42421, tvId)
        assertEquals("192.168.42.88", queue.next(emptyList())?.host)
        assertEquals(null, queue.next(emptyList()))
        val delayed = listOf(tv("192.168.42.93", 42422))
        assertTrue(queue.hasNext(delayed))
        assertEquals("192.168.42.93", queue.next(delayed)?.host)
        assertEquals(null, queue.next(delayed))
    }

    private fun tv(host: String, port: Int, id: String = tvId) =
        DiscoveredTv("TV", host, port, id, 2, null, TvAvailability.READY)
}
