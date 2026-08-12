package com.flick.sender.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DiscoveryStateTest {

    private fun face(
        hasLanAddress: Boolean = true,
        browse: BrowseState = BrowseState.RUNNING,
        deviceCount: Int = 0,
        elapsedMs: Long = 0L,
    ): DiscoveryFace = discoveryFace(hasLanAddress, browse, deviceCount, elapsedMs)

    // No address is proof there is nothing to search, whatever the search is doing.
    @Test
    fun `no LAN address outranks everything`() {
        for (browse in BrowseState.entries) {
            for (count in listOf(0, 3)) {
                assertEquals(
                    "browse=$browse count=$count",
                    DiscoveryFace.NO_NETWORK,
                    face(hasLanAddress = false, browse = browse, deviceCount = count),
                )
            }
        }
    }

    // Rows on screen are real whatever the platform has since said about the browse that
    // produced them.
    @Test
    fun `found devices outrank a failed browse`() {
        assertEquals(DiscoveryFace.FOUND, face(browse = BrowseState.UNAVAILABLE, deviceCount = 1))
        assertEquals(DiscoveryFace.FOUND, face(browse = BrowseState.RUNNING, deviceCount = 1, elapsedMs = 60_000L))
    }

    @Test
    fun `a refused browse says so rather than claiming to look`() {
        assertEquals(DiscoveryFace.SEARCH_UNAVAILABLE, face(browse = BrowseState.UNAVAILABLE))
        assertEquals(
            DiscoveryFace.SEARCH_UNAVAILABLE,
            face(browse = BrowseState.UNAVAILABLE, elapsedMs = 60_000L),
        )
    }

    /**
     * The window between `discoverServices` and its binder acknowledgement — normally
     * milliseconds, the whole backoff after a failed start. Nothing has refused anything
     * in it, so nothing may say the platform turned the search down. This is the first
     * screen a new install opens on.
     */
    @Test
    fun `a request not yet acknowledged is searching, not refused`() {
        assertEquals(DiscoveryFace.SEARCHING, face(browse = BrowseState.PENDING))
        assertNotEquals(
            DiscoveryFace.SEARCH_UNAVAILABLE,
            face(browse = BrowseState.PENDING, elapsedMs = 60_000L),
        )
        assertEquals(
            DiscoveryFace.NOTHING_FOUND,
            face(browse = BrowseState.PENDING, elapsedMs = 60_000L),
        )
    }

    @Test
    fun `an empty list inside the settle window is still searching`() {
        assertEquals(DiscoveryFace.SEARCHING, face(elapsedMs = 0L))
        assertEquals(DiscoveryFace.SEARCHING, face(elapsedMs = 11_999L))
    }

    @Test
    fun `an empty list past the settle window has found nothing`() {
        assertEquals(DiscoveryFace.NOTHING_FOUND, face(elapsedMs = 12_000L))
        assertEquals(DiscoveryFace.NOTHING_FOUND, face(elapsedMs = 60_000L))
    }

    @Test
    fun `the settle window is a parameter and not a constant`() {
        assertEquals(
            DiscoveryFace.NOTHING_FOUND,
            discoveryFace(
                hasLanAddress = true,
                browse = BrowseState.RUNNING,
                deviceCount = 0,
                elapsedMs = 1_000L,
                settleMs = 500L,
            ),
        )
    }
}
