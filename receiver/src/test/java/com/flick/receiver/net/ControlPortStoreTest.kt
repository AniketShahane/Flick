package com.flick.receiver.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlPortStoreTest {

    @Test fun `no persisted port starts at the fixed default`() {
        val candidates = controlPortCandidates(null)
        assertEquals(DEFAULT_CONTROL_PORT, candidates.first())
    }

    @Test fun `persisted port is tried before the default`() {
        val candidates = controlPortCandidates(50123)
        assertEquals(listOf(50123, DEFAULT_CONTROL_PORT), candidates.take(2))
    }

    @Test fun `persisted default is not duplicated`() {
        val candidates = controlPortCandidates(DEFAULT_CONTROL_PORT)
        assertEquals(candidates.size, candidates.distinct().size)
        assertEquals(DEFAULT_CONTROL_PORT, candidates.first())
    }

    @Test fun `ladder covers the fixed range and ends ephemeral`() {
        val candidates = controlPortCandidates(null)
        assertEquals((DEFAULT_CONTROL_PORT..DEFAULT_CONTROL_PORT + 9).toList(), candidates.dropLast(1))
        assertEquals(0, candidates.last())
    }

    @Test fun `out of range persisted value is ignored`() {
        assertEquals(controlPortCandidates(null), controlPortCandidates(70000))
        assertEquals(controlPortCandidates(null), controlPortCandidates(0))
    }

    @Test fun `ephemeral is the last resort only`() {
        val candidates = controlPortCandidates(null)
        assertEquals(1, candidates.count { it == 0 })
        assertTrue(candidates.indexOf(0) == candidates.lastIndex)
    }

    @Test fun `tier names the winning rung`() {
        assertEquals("persisted", controlPortTier(50123, 50123))
        assertEquals("default", controlPortTier(DEFAULT_CONTROL_PORT, null))
        assertEquals("ladder", controlPortTier(DEFAULT_CONTROL_PORT + 3, null))
        assertEquals("ephemeral", controlPortTier(51234, null))
    }
}
