package com.flick.receiver.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RenameLabelInputTest {

    @Test
    fun codePointLimitDoesNotSplitSurrogatePairs() {
        val value = "A".repeat(79) + "😀" + "discarded"

        val limited = limitRenameLabelInput(value)

        assertEquals(80, limited.codePointCount(0, limited.length))
        assertEquals("😀", limited.takeLast(2))
    }

    @Test
    fun canonicalNameMatchesTheWireContract() {
        assertEquals("Living Room TV", normalizedRenameLabel("  Living\nRoom\tTV  "))
        assertEquals("Demo TV", normalizedRenameLabel("\u200eDemo TV\u200f"))
    }

    @Test
    fun blankCanonicalNameCannotSave() {
        assertNull(normalizedRenameLabel(" \n\t\u200e "))
    }
}
