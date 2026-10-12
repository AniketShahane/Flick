package com.flick.sender.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogoInkTest {

    @Test
    fun aBlackWordmarkIsDark() {
        val luma = LogoInk.meanOpaqueLuma(intArrayOf(BLACK, BLACK, CLEAR, CLEAR))
        assertEquals(0f, luma!!, EPS)
        assertTrue(LogoInk.isDark(luma))
    }

    @Test
    fun aWhiteWordmarkIsNot() {
        val luma = LogoInk.meanOpaqueLuma(intArrayOf(WHITE, CLEAR, WHITE))
        assertEquals(1f, luma!!, EPS)
        assertFalse(LogoInk.isDark(luma))
    }

    @Test
    fun transparentPixelsDoNotCount() {
        // A white logo on a sheet of transparent black: only the ink is judged.
        val pixels = IntArray(100) { if (it < 5) WHITE else CLEAR }
        assertFalse(LogoInk.isDark(LogoInk.meanOpaqueLuma(pixels)))
    }

    @Test
    fun nothingOpaqueIsNoVerdict() {
        val luma = LogoInk.meanOpaqueLuma(intArrayOf(CLEAR, CLEAR))
        assertNull(luma)
        assertFalse(LogoInk.isDark(luma))
    }

    @Test
    fun onlyTheCountedSamplesAreRead() {
        val luma = LogoInk.meanOpaqueLuma(intArrayOf(WHITE, BLACK, BLACK), count = 1)
        assertEquals(1f, luma!!, EPS)
    }

    private companion object {
        const val EPS = 1e-4f
        val BLACK = 0xFF000000.toInt()
        val WHITE = 0xFFFFFFFF.toInt()
        const val CLEAR = 0x00000000
    }
}
