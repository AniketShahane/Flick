package com.flick.receiver.ui.theme

import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleAmbientGeometryTest {

    @Test fun driftingFootprintStaysBeyondTheRadialAtBothExtrema() {
        val viewports = listOf(
            Size(1_920f, 1_080f),
            Size(3_840f, 2_160f),
            Size(1_280f, 960f),
        )

        for (viewport in viewports) {
            for (phase in listOf(-1f, 0f, 1f)) {
                val radial = idleAmbientBounds(viewport, phase, footprint = false)
                val footprint = idleAmbientBounds(viewport, phase, footprint = true)

                assertTrue(footprint.left < radial.left)
                assertTrue(footprint.top < radial.top)
                assertTrue(footprint.right > radial.right)
                assertTrue(footprint.bottom > radial.bottom)
            }
        }
    }

    @Test fun restingFootprintIsNotClampedBeforeItMoves() {
        val viewport = Size(1_920f, 1_080f)
        val footprint = idleAmbientBounds(viewport, phase = 0f, footprint = true)

        assertTrue(footprint.left < 0f)
        assertTrue(footprint.top < 0f)
        assertTrue(footprint.right > viewport.width)
        assertTrue(footprint.bottom > viewport.height)
    }
}
