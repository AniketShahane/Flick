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
    fun theSurgeRunsFromTheFlashToRest() {
        assertEquals(0f, LightSpeed.surge(0f), EPS)
        assertEquals(0f, LightSpeed.surge(Hyperspace.JumpFlashAtMs), EPS)
        assertEquals(1f, LightSpeed.surge(Hyperspace.JumpFlashAtMs + Hyperspace.SurgeMs), EPS)
        assertEquals(1f, LightSpeed.surge(Hyperspace.JumpMs.toFloat()), EPS)
        // Fastest at the start: more than half the extra distance in the first quarter.
        assertTrue(LightSpeed.surge(Hyperspace.JumpFlashAtMs + Hyperspace.SurgeMs / 4f) > 0.5f)
    }

    @Test
    fun theFlashPeaksAndIsGoneBeforeTheJumpEnds() {
        assertEquals(0f, LightSpeed.flash(0f), EPS)
        assertEquals(0f, LightSpeed.flash(Hyperspace.JumpFlashAtMs), EPS)
        assertEquals(Hyperspace.FlashPeak, LightSpeed.flash(Hyperspace.JumpFlashAtMs + Hyperspace.FlashRiseMs), EPS)
        assertEquals(0f, LightSpeed.flash(Hyperspace.JumpMs.toFloat()), EPS)
    }

    @Test
    fun everyJumpTrackIsAtRestWhenTheJumpEnds() {
        val end = Hyperspace.JumpMs.toFloat()
        assertEquals(1f, LightSpeed.field(end), EPS)
        assertEquals(1f, LightSpeed.copy(end), EPS)
        assertTrue(Hyperspace.JumpFlashAtMs + Hyperspace.SurgeMs <= end)
        assertTrue(Hyperspace.JumpCopyDelayMs + Hyperspace.JumpCopyInMs <= end)
        assertTrue(Hyperspace.JumpFlashAtMs + Hyperspace.FlashRiseMs + Hyperspace.FlashFallMs <= end)
    }

    @Test
    fun theAmberRingIsOneOfTheRings() {
        assertTrue(Hyperspace.AmberRing in 0 until Hyperspace.RingCount)
    }
}

private const val EPS = 1e-4f
