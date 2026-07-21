package com.flick.receiver.net

import org.junit.Assert.assertEquals
import org.junit.Test

class LabelNormalizationTest {
    @Test fun canonicalizesControlsFormatsAndUnicodeWhitespace() {
        assertEquals("Demo Phone", normalizeLabel("\u200e Demo\n\tPhone \u200f", 80))
    }

    @Test fun capsAtCodePointBoundaryNotUtf16Boundary() {
        assertEquals("A😀", normalizeLabel("A😀B", 2))
    }
}
