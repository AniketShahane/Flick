package com.flick.receiver.summon

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SummonSignals is process-wide, so every test uses ids no other test issues and
 * leaves [SummonSignals.onResumed] cleared.
 */
class SummonSignalsTest {

    @After fun clearListener() {
        SummonSignals.onResumed = null
    }

    @Test fun `only the newest issued attempt is wanted`() {
        SummonSignals.issue(101)
        SummonSignals.issue(102)
        assertFalse(SummonSignals.wanted(101))
        assertTrue(SummonSignals.wanted(102))
    }

    @Test fun `an abandoned attempt is never wanted again`() {
        SummonSignals.issue(201)
        SummonSignals.abandon(201)
        assertFalse(SummonSignals.wanted(201))
    }

    @Test fun `abandoning an older attempt leaves the newest wanted`() {
        SummonSignals.issue(301)
        SummonSignals.issue(302)
        SummonSignals.abandon(301)
        assertTrue(SummonSignals.wanted(302))
    }

    @Test fun `a launch without an attempt extra is never wanted`() {
        assertFalse(SummonSignals.wanted(-1L))
        assertFalse(SummonSignals.wanted(0L))
        assertFalse(SummonSignals.resumed(-1L))
    }

    @Test fun `resumed is per attempt and reaches the listener`() {
        val heard = mutableListOf<Long>()
        SummonSignals.onResumed = { heard += it }
        SummonSignals.issue(401)
        SummonSignals.markResumed(401)
        assertTrue(SummonSignals.resumed(401))
        assertFalse(SummonSignals.resumed(402))
        assertEquals(listOf(401L), heard)
    }

    @Test fun `a reused id from a new composition starts clean`() {
        SummonSignals.issue(501)
        SummonSignals.markResumed(501)
        SummonSignals.abandon(501)
        SummonSignals.issue(501)
        assertTrue(SummonSignals.wanted(501))
        assertFalse(SummonSignals.resumed(501))
    }
}
