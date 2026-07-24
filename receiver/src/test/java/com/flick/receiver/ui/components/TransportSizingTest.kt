package com.flick.receiver.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transport metrics from receiver-expressive-spec.md §5.3 row 3, plus the
 * constraint they exist to protect: every D-pad target stays at or above the
 * 48 dp TV minimum, and each glyph keeps at least half its button to read in.
 */
class TransportSizingTest {

    @Test fun transportMatchesTheExpressiveMetrics() {
        assertEquals(52.dp, SecondaryTransportTargetSize)
        assertEquals(66.dp, PrimaryTransportTargetSize)
        assertEquals(26.dp, TransportGlyphSize)
        assertEquals(35.dp, PrimaryTransportGlyphSize)
    }

    @Test fun everyTransportTargetClearsTheTvMinimum() {
        assertTrue(SecondaryTransportTargetSize >= 48.dp)
        assertTrue(PrimaryTransportTargetSize >= 48.dp)
    }

    @Test fun glyphsStayReadableInsideTheirTargets() {
        assertTrue(TransportGlyphSize >= SecondaryTransportTargetSize / 2f)
        assertTrue(PrimaryTransportGlyphSize >= PrimaryTransportTargetSize / 2f)
        assertTrue(TransportGlyphSize < SecondaryTransportTargetSize)
        assertTrue(PrimaryTransportGlyphSize < PrimaryTransportTargetSize)
    }
}
