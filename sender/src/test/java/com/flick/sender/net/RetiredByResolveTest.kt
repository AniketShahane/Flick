package com.flick.sender.net

import com.flick.sender.model.DiscoveredTv
import com.flick.sender.model.TvAvailability
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which names a resolve retires, and therefore whose record subscription has to be
 * unregistered with them.
 *
 * Measured on real hardware before this existed: renaming the TV from one name to
 * another left the old name's subscription registered, re-querying every ~20 s for a
 * name nothing on the LAN answered to. It had sent over 2,200 queries across 14 hours
 * and was still going.
 */
class RetiredByResolveTest {

    private fun tv(name: String, host: String = "192.168.42.17") =
        DiscoveredTv(name, host, 47654, "ABEiM0RVZneImaq7zN3u_w", 2, null, TvAvailability.READY)

    @Test fun aRenameRetiresTheNameItReplaced() {
        assertEquals(
            listOf("R1"),
            retiredByResolve(listOf(tv("R1")), name = "R2", host = "192.168.42.17"),
        )
    }

    /** A re-registration is not a rename, and keeps the subscription it already has. */
    @Test fun theSameNameResolvingAgainRetiresNothing() {
        assertEquals(
            emptyList<String>(),
            retiredByResolve(listOf(tv("R1")), name = "R1", host = "192.168.42.17"),
        )
    }

    /** A second TV keeps its own name; only the one sharing this host was renamed. */
    @Test fun anotherTvAtAnotherAddressIsUntouched() {
        assertEquals(
            listOf("R1"),
            retiredByResolve(
                listOf(tv("R1"), tv("Bedroom", host = "192.168.42.31")),
                name = "R2",
                host = "192.168.42.17",
            ),
        )
    }

    /**
     * The same TV renamed twice while the phone was away leaves two dead names on one
     * host, and both subscriptions have to go.
     */
    @Test fun everyNameStrandedOnThisHostIsRetired() {
        assertEquals(
            listOf("R1", "R1b"),
            retiredByResolve(listOf(tv("R1"), tv("R1b")), name = "R2", host = "192.168.42.17"),
        )
    }

    @Test fun anEmptyListRetiresNothing() {
        assertEquals(
            emptyList<String>(),
            retiredByResolve(emptyList(), name = "R2", host = "192.168.42.17"),
        )
    }

    /**
     * A TV that moved address rather than name: the record under the OLD host is not
     * retired here. The loss path still owns that one, and taking it here on a host
     * match that did not happen would unregister a name that is still live.
     */
    @Test fun aRecordAtADifferentAddressIsLeftToTheLossPath() {
        assertEquals(
            emptyList<String>(),
            retiredByResolve(listOf(tv("R1", host = "192.168.42.31")), name = "R2", host = "192.168.42.17"),
        )
    }
}
