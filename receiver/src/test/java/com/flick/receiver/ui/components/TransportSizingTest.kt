package com.flick.receiver.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class TransportSizingTest {

    @Test fun compactTransportPreservesReadableGlyphsAndTvTargets() {
        assertEquals(32.dp, TransportGlyphSize)
        assertEquals(48.dp, SecondaryTransportTargetSize)
        assertEquals(56.dp, PrimaryTransportTargetSize)
    }
}
