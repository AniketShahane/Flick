package com.flick.sender.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HyperspaceTest {

    @Test
    fun aLifeFadesInFromNothingAndOutToNothing() {
        assertEquals(0f, hyperspaceFade(0f), EPS)
        assertEquals(0f, hyperspaceFade(1f), EPS)
        assertTrue(hyperspaceFade(0.3f) > 0.5f)
    }

    @Test
    fun theLoopPointIsSeamless() {
        // The clock snaps from 1 back to 0; every ring and streak must be in the same pose
        // on both sides of that snap.
        for (index in 0 until Hyperspace.RingCount) {
            assertEquals("ring $index", ringPhase(0f, index), ringPhase(1f, index), EPS)
        }
        for (index in 0 until Hyperspace.StreakCount) {
            assertEquals("streak $index", streakPhase(0f, index), streakPhase(1f, index), EPS)
        }
    }

    @Test
    fun theRingsAreEvenlyStaggered() {
        val phases = (0 until Hyperspace.RingCount).map { ringPhase(0f, it) }.sorted()
        val step = 1f / Hyperspace.RingCount
        phases.zipWithNext().forEach { (a, b) -> assertEquals(step, b - a, EPS) }
    }

    @Test
    fun aRingIsBornAtTheVanishingPoint() {
        assertEquals(0f, ringScale(0f), EPS)
        assertEquals(1f, ringScale(1f), EPS)
        assertTrue(ringScale(0.5f) < 0.5f)
    }

    @Test
    fun theAmberRingIsOneOfTheRings() {
        assertTrue(Hyperspace.AmberRing in 0 until Hyperspace.RingCount)
    }
}

private const val EPS = 1e-4f
