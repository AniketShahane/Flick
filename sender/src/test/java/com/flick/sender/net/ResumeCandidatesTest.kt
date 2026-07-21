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
