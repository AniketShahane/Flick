package com.flick.sender.ui.theme

import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionTest {

    @Test
    fun orSnapPassesTheSpecThroughWhileAnimatorsAreOn() {
        val spec = spring<Float>()
        assertSame(spec, Motion.orSnap(reduceMotion = false, spec = spec))
    }

    @Test
    fun orSnapReplacesTheSpecWhenAnimatorsAreOff() {
        assertTrue(Motion.orSnap(reduceMotion = true, spec = spring<Float>()) is SnapSpec)
    }

    @Test
    fun cardMorphRetimesTheActualSchemeSpringAndPreservesItsCharacter() {
        val threshold = 0.004f
        val base = spring(
            dampingRatio = 0.8f,
            stiffness = 380f,
            visibilityThreshold = threshold,
        )

        val retimed = Motion.cardMorphSpec(base) as SpringSpec<Float>

        assertEquals(base.dampingRatio, retimed.dampingRatio, 0f)
        assertEquals(380f / (0.75f * 0.75f), retimed.stiffness, 0.001f)
        assertEquals(threshold, retimed.visibilityThreshold ?: error("threshold lost"), 0f)
        assertEquals(450, Motion.CardMorphHoldMs)
        assertEquals(675L, Motion.CardMorphLatchMs)
        assertEquals(135L, Motion.CardMorphBarHandoffMs)
    }

    @Test
    fun cardMorphLeavesAFutureNonSpringSchemeAlone() {
        val spec = tween<Float>(durationMillis = 240)
        assertSame(spec, Motion.cardMorphSpec(spec))
    }

    @Test
    fun pressRadiusTravelsBetweenTheRestAndPressedCorners() {
        assertEquals(20f, pressRadius(20.dp, 8.dp, 0f).value, 0f)
        assertEquals(14f, pressRadius(20.dp, 8.dp, 0.5f).value, 0f)
        assertEquals(8f, pressRadius(20.dp, 8.dp, 1f).value, 0f)
    }

    @Test
    fun pressRadiusClampsWhatTheSpringOvershoots() {
        // The spatial spring rings past both ends of its range by design. A radius past
        // either is a corner the surface never has, and a negative one is not a shape.
        assertEquals(8f, pressRadius(20.dp, 8.dp, 1.18f).value, 0f)
        assertEquals(20f, pressRadius(20.dp, 8.dp, -0.12f).value, 0f)
    }

    @Test
    fun pressRadiusAlsoRunsOutwards() {
        // Nothing in the app morphs outward today, but the helper decides the corner for
        // every press morph and must not assume the pressed radius is the smaller one.
        assertEquals(16f, pressRadius(8.dp, 24.dp, 0.5f).value, 0f)
    }
}
