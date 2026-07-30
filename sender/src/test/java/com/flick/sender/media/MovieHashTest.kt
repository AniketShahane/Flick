package com.flick.sender.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The OpenSubtitles hash arithmetic, against buffers whose sum can be worked out by hand.
 *
 * Nothing here computes the expected value a second way: a test that re-implements the
 * function it is checking agrees with its own bug. Every window below is chosen so the
 * answer is arithmetic anyone can follow — a size, one word, and nothing else.
 *
 * The hash is what tells the server WHICH release is being cast, so the failure this
 * guards against is not a crash. It is a plausible-looking hash that belongs to somebody
 * else's file, which comes back as a confidently wrong, out-of-sync subtitle.
 */
class MovieHashTest {

    private val window = 64 * 1024
    private val minimumSize = 128L * 1024L

    private fun zeros() = ByteArray(window)

    /** [bytes] written at the start of an otherwise empty window. */
    private fun windowStarting(vararg bytes: Int) = zeros().apply {
        bytes.forEachIndexed { index, value -> this[index] = value.toByte() }
    }

    @Test fun theSizeAloneIsTheHashOfAnEmptyFile() {
        // 0x20000 == 131072, and the answer is zero-padded to the 16 digits the API takes.
        assertEquals("0000000000020000", MovieHash.of(minimumSize, zeros(), zeros()))
    }

    @Test fun wordsAreReadLittleEndian() {
        // {0x01,0x02} at the head of a window is the word 0x0201, never 0x0102000000000000.
        val head = windowStarting(0x01, 0x02)
        assertEquals("0000000000020201", MovieHash.of(minimumSize, head, zeros()))
    }

    @Test fun everyWordInBothWindowsCounts() {
        // Byte 8 opens the second word, so this is 0x0201 in word 0 plus 0x03 in word 1,
        // and the same again out of the tail window.
        val head = windowStarting(0x01, 0x02, 0, 0, 0, 0, 0, 0, 0x03)
        assertEquals("0000000000020408", MovieHash.of(minimumSize, head, head))
    }

    @Test fun theSumWrapsAtSixtyFourBits() {
        // Two words of 2^63 sum to 2^64, which wraps to zero and leaves just the size.
        val half = windowStarting(0, 0, 0, 0, 0, 0, 0, 0x80)
        assertEquals("0000000000020000", MovieHash.of(minimumSize, half, half))
        // One of them alone must NOT wrap, or the test above would pass for the wrong reason.
        assertEquals("8000000000020000", MovieHash.of(minimumSize, half, zeros()))
    }

    @Test fun aWrappedSumIsRenderedUnsigned() {
        // 0xFFFF…FF is -1 as a Long; the hash is the unsigned form of the total, so
        // 131072 - 1 has to read as 0x1ffff and never as a minus sign.
        val minusOne = zeros().apply { for (index in 0 until 8) this[index] = 0xFF.toByte() }
        assertEquals("000000000001ffff", MovieHash.of(minimumSize, minusOne, zeros()))
    }

    @Test fun aFileUnderTwoWindowsHasNoHash() {
        // Below 128 KiB the two windows would overlap, so there is no hash to compute —
        // and the caller must fall back to the text query rather than send a made-up one.
        assertNull(MovieHash.of(minimumSize - 1, zeros(), zeros()))
        assertNull(MovieHash.of(0L, zeros(), zeros()))
        // -1 is what a provider that will not state a size reports; same answer.
        assertNull(MovieHash.of(-1L, zeros(), zeros()))
        assertEquals(minimumSize, MovieHash.MinBytes)
    }

    @Test fun aShortWindowIsRefusedRatherThanPadded() {
        assertNull(MovieHash.of(minimumSize, ByteArray(window - 1), zeros()))
        assertNull(MovieHash.of(minimumSize, zeros(), ByteArray(window - 8)))
        assertNull(MovieHash.of(minimumSize, ByteArray(0), ByteArray(0)))
        // A longer window is equally wrong: it is not the window the format names.
        assertNull(MovieHash.of(minimumSize, ByteArray(window + 8), zeros()))
    }

    @Test fun onlyTheApisOwnFormIsWellFormed() {
        assertTrue(MovieHash.isWellFormed("8e245d9679d31e12"))
        assertTrue(MovieHash.isWellFormed("0000000000020000"))
        // Uppercase, short, long, and non-hex are all values this object never produced.
        assertFalse(MovieHash.isWellFormed("8E245D9679D31E12"))
        assertFalse(MovieHash.isWellFormed("8e245d9679d31e1"))
        assertFalse(MovieHash.isWellFormed("8e245d9679d31e123"))
        assertFalse(MovieHash.isWellFormed("8e245d9679d31g12"))
        assertFalse(MovieHash.isWellFormed(""))
        assertFalse(MovieHash.isWellFormed(" 8e245d9679d31e12"))
    }
}
