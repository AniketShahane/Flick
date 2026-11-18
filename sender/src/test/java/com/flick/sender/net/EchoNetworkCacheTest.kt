package com.flick.sender.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The echo probe's network lookup, remembered between probes only while something reports
 * every change that could move it, and never answering where a fresh lookup would not.
 */
class EchoNetworkCacheTest {

    private val ip = "192.0.2.10"
    private val cache = EchoNetworkCache<String>()
    private var resolves = 0
    private var network: String? = "wifi-1"
    private val resolve = { _: String -> resolves += 1; network }

    @Test fun aDisarmedCacheResolvesEveryTime() {
        repeat(3) { assertEquals(EchoNetworkCache.Lookup("wifi-1", cached = false), cache.lookup(ip, resolve)) }
        assertEquals(3, resolves)
    }

    @Test fun anArmedCacheResolvesOnceUntilInvalidated() {
        cache.arm()
        assertEquals(EchoNetworkCache.Lookup("wifi-1", cached = false), cache.lookup(ip, resolve))
        assertEquals(EchoNetworkCache.Lookup("wifi-1", cached = true), cache.lookup(ip, resolve))
        assertEquals(1, resolves)
        network = "wifi-2"
        cache.invalidate()
        assertEquals(EchoNetworkCache.Lookup("wifi-2", cached = false), cache.lookup(ip, resolve))
        assertEquals(2, resolves)
    }

    @Test fun anotherServingAddressIsResolvedAfresh() {
        cache.arm()
        cache.lookup(ip, resolve)
        assertFalse(cache.lookup("192.0.2.11", resolve)!!.cached)
        assertEquals(2, resolves)
    }

    @Test fun noNetworkIsNeverCached() {
        cache.arm()
        network = null
        assertNull(cache.lookup(ip, resolve))
        network = "wifi-1"
        assertEquals(EchoNetworkCache.Lookup("wifi-1", cached = false), cache.lookup(ip, resolve))
    }

    @Test fun disarmingForgetsAndStopsCaching() {
        cache.arm()
        cache.lookup(ip, resolve)
        cache.disarm()
        assertFalse(cache.lookup(ip, resolve)!!.cached)
        assertFalse(cache.lookup(ip, resolve)!!.cached)
        assertEquals(3, resolves)
    }

    @Test fun rearmingStartsEmpty() {
        cache.arm()
        cache.lookup(ip, resolve)
        cache.disarm()
        cache.arm()
        assertFalse(cache.lookup(ip, resolve)!!.cached)
    }

    @Test fun anOverlappingWatchKeepsTheCacheArmed() {
        cache.arm()
        cache.arm()
        cache.disarm()
        cache.lookup(ip, resolve)
        assertTrue(cache.lookup(ip, resolve)!!.cached)
    }

    /** A change reported while a lookup is in flight wins over that lookup's answer. */
    @Test fun anInvalidationDuringALookupKeepsItsAnswerOutOfTheCache() {
        cache.arm()
        val racing = { _: String -> cache.invalidate(); "wifi-old" }
        assertEquals("wifi-old", cache.lookup(ip, racing)!!.network)
        assertFalse(cache.lookup(ip, resolve)!!.cached)
    }

    @Test fun aSignalStepKeepsTheCacheAndLosingWifiTransportDropsIt() {
        cache.arm()
        cache.lookup(ip, resolve)
        cache.onCapabilities("wifi-1", qualifies = true)
        cache.onCapabilities("wifi-9", qualifies = false)
        assertTrue(cache.lookup(ip, resolve)!!.cached)
        cache.onCapabilities("wifi-1", qualifies = false)
        assertFalse(cache.lookup(ip, resolve)!!.cached)
    }

    @Test fun refreshNeverAnswersFromTheCacheAndReplacesIt() {
        cache.arm()
        cache.lookup(ip, resolve)
        network = "wifi-2"
        assertEquals(EchoNetworkCache.Lookup("wifi-2", cached = false), cache.refresh(ip, resolve))
        assertEquals(EchoNetworkCache.Lookup("wifi-2", cached = true), cache.lookup(ip, resolve))
        network = null
        assertNull(cache.refresh(ip, resolve))
        network = "wifi-3"
        assertEquals(EchoNetworkCache.Lookup("wifi-3", cached = false), cache.lookup(ip, resolve))
    }
}
