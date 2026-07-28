package com.flick.receiver.ui.components

import androidx.compose.ui.graphics.Color
import com.flick.receiver.ui.theme.FlickColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two things the repainted finder patterns must never get wrong: where they
 * are, and whether a scanner reads them as dark.
 */
class QrSymbolTest {

    /** The white plate and the ink the data modules take — the symbol as shipped. */
    private val plate = Color.White
    private val moduleInk = FlickColor.OnLight

    @Test fun everyFinderEyeBinarizesDark() {
        // Asserted through the guard the renderer itself calls, not against the
        // brand constant: a test that only measures a colour goes on passing while
        // a call site hands the eyes a different one.
        assertEquals(QrEyeInk, qrEyeInk(QrEyeInk, moduleInk, plate))
        assertTrue(qrLuma(QrEyeInk) < qrBinarizerThreshold(plate, moduleInk))
    }

    @Test fun anEyeTintedForTheBrandRatherThanForAScannerIsRefused() {
        // The shipped defect, now unreachable however the eyes are asked for:
        // amber's luma is ABOVE the threshold, so an amber eye core binarized
        // white and broke the finder pattern's mandatory 1:1:3:1:1 run.
        assertTrue(qrLuma(FlickColor.Spark) > qrBinarizerThreshold(plate, moduleInk))
        assertEquals(moduleInk, qrEyeInk(FlickColor.Spark, moduleInk, plate))
        assertEquals(moduleInk, qrEyeInk(FlickColor.SparkLight, moduleInk, plate))
        assertEquals(moduleInk, qrEyeInk(plate, moduleInk, plate))
    }

    @Test fun theThreeFindersPinTheSymbolCorners() {
        // A 25-module symbol whose grid starts four modules in from the matrix edge.
        val eyes = qrFinderOrigins(intArrayOf(4, 4, 25, 25))
        assertEquals(
            listOf(
                QrFinder(4, 4),
                QrFinder(22, 4),
                QrFinder(4, 22),
            ),
            eyes,
        )
    }

    @Test fun theFindersFollowANonSquareBoundingBox() {
        val eyes = qrFinderOrigins(intArrayOf(0, 2, 21, 29))
        assertEquals(
            listOf(
                QrFinder(0, 2),
                QrFinder(14, 2),
                QrFinder(0, 24),
            ),
            eyes,
        )
    }

    @Test fun anImplausibleBoundingBoxRepaintsNothing() {
        assertTrue(qrFinderOrigins(null).isEmpty())
        assertTrue(qrFinderOrigins(intArrayOf(0, 0)).isEmpty())
        // Smaller than one finder pattern: repainting would corrupt the grid.
        assertTrue(qrFinderOrigins(intArrayOf(0, 0, 6, 25)).isEmpty())
        assertTrue(qrFinderOrigins(intArrayOf(0, 0, 25, 6)).isEmpty())
    }
}
