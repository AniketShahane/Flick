package com.flick.receiver.ui.components

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RenameLabelInputTest {

    /** The seeded value as the TV IME leaves it: selection collapsed to the end. */
    private fun collapsed(name: String) = TextFieldValue(name, TextRange(name.length))

    private fun selectedWhole(name: String) = TextFieldValue(name, TextRange(0, name.length))

    @Test
    fun typingOverACollapsedSelectionReplacesTheWholeName() {
        val typed = firstRenameLabelEdit(
            current = collapsed("Living Room TV"),
            next = TextFieldValue("Living Room TVZ", TextRange(15)),
        )

        assertEquals("Z", typed.text)
        assertEquals(TextRange(1), typed.selection)
    }

    @Test
    fun deletingOverACollapsedSelectionClearsTheWholeName() {
        val typed = firstRenameLabelEdit(
            current = collapsed("Living Room TV"),
            next = TextFieldValue("Living Room T", TextRange(13)),
        )

        assertEquals("", typed.text)
    }

    @Test
    fun aComposedRunReplacingTheLastWordStillReplacesTheWholeName() {
        val typed = firstRenameLabelEdit(
            current = collapsed("Living Room TV"),
            next = TextFieldValue("Living Room Den", TextRange(15)),
        )

        assertEquals("Den", typed.text)
    }

    @Test
    fun anIntactSelectionHasAlreadyReplacedTheNameAndIsPassedThrough() {
        val typed = firstRenameLabelEdit(
            current = selectedWhole("Living Room TV"),
            next = TextFieldValue("Den TV", TextRange(6)),
        )

        assertEquals("Den TV", typed.text)
    }

    @Test
    fun movingTheCaretIsNotAnEdit() {
        val moved = TextFieldValue("Living Room TV", TextRange(14))

        val typed = firstRenameLabelEdit(current = selectedWhole("Living Room TV"), next = moved)

        assertEquals(moved, typed)
    }

    @Test
    fun replacingOneEmojiNeverSplitsItsSurrogatePair() {
        val typed = firstRenameLabelEdit(
            current = collapsed("😀"),
            next = TextFieldValue("😁", TextRange(2)),
        )

        assertEquals("😁", typed.text)
    }

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
