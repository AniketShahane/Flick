package com.flick.sender.ui.components

import com.flick.sender.net.PairLaunch
import com.flick.sender.net.PairLaunchParseResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrScanGateTest {
    @Test fun aHeldUpCodeIsRoutedOnceNotOncePerFrame() {
        val gate = QrScanGate(quietMs = 2_000L)
        val payload = "flick://pair?v=3&h=192.0.2.10&p=47654"
        assertEquals(payload, gate.accept(payload, 0L))
        assertNull(gate.accept(payload, 16L))
        assertNull(gate.accept(payload, 1_999L))
        // The sheet stays open on an unusable code, so the same symbol has to be able
        // to report itself again rather than going silent for the rest of the session.
        assertEquals(payload, gate.accept(payload, 2_000L))
    }

    @Test fun aDifferentSymbolIsNeverDelayedByTheQuietWindow() {
        val gate = QrScanGate(quietMs = 2_000L)
        assertEquals("first", gate.accept("first", 0L))
        assertEquals("second", gate.accept("second", 5L))
        assertNull(gate.accept("second", 6L))
        assertEquals("first", gate.accept("first", 7L))
    }

    @Test fun onlyTransportWhitespaceIsStrippedAndEmptyFramesAreDropped() {
        val gate = QrScanGate()
        assertNull(gate.accept("", 0L))
        assertNull(gate.accept("   \n", 1L))
        assertEquals("flick://pair?v=2", gate.accept("  flick://pair?v=2\n", 2L))
    }

    @Test fun theScannedStringIsHandedToTheSameParserTheDeepLinkUses() {
        val gate = QrScanGate()
        val scanned = gate.accept("flick://pair?v=3&h=10.0.0.2&p=47654\n", 0L)
        assertEquals(PairLaunchParseResult.Valid("10.0.0.2", 47654), PairLaunch.parse(scanned!!))
        // Anything else is a QR the pairing screen must reject, not widen for — including a
        // well-formed v3 envelope whose host sits outside the private ranges.
        listOf(
            "flick://pair?v=3&h=192.0.2.10&p=47654",
            "https://example.test/pair",
            "flick://other?v=3",
            "1234",
        ).forEach {
            assertEquals(PairLaunchParseResult.Invalid, PairLaunch.parse(gate.accept(it, 1L)!!))
        }
    }
}

class CameraPermissionTest {
    @Test fun theThreeRefusalStatesAreToldApartByWhetherFlickHasAskedYet() {
        assertEquals(CameraAccess.UNREQUESTED, CameraPermission.state(false, showRationale = false, requested = false))
        assertEquals(CameraAccess.DENIED, CameraPermission.state(false, showRationale = true, requested = true))
        // No rationale after an ask is the platform's "don't ask again".
        assertEquals(CameraAccess.BLOCKED, CameraPermission.state(false, showRationale = false, requested = true))
        assertEquals(CameraAccess.GRANTED, CameraPermission.state(true, showRationale = false, requested = true))
        assertEquals(CameraAccess.GRANTED, CameraPermission.state(true, showRationale = true, requested = false))
    }
}

class QrInkTest {
    // Row-major columns of the fixture, two pixels per chroma sample: the white card,
    // the amber finder eye, the blue finder eyes, and a black module.
    private val lumaByColumn = intArrayOf(255, 255, 186, 186, 69, 69, 16, 16)
    private val cbByBlock = intArrayOf(128, 40, 220, 128)
    private val width = 8
    private val height = 2
    private val lumaRowStride = 10
    private val chromaRowStride = 12
    private val chromaPixelStride = 2

    private fun luma(): ByteArray = ByteArray(lumaRowStride * height).also { plane ->
        for (row in 0 until height) {
            for (col in 0 until width) plane[row * lumaRowStride + col] = lumaByColumn[col].toByte()
        }
    }

    private fun chroma(): ByteArray = ByteArray(chromaRowStride * ((height + 1) / 2)).also { plane ->
        for (block in cbByBlock.indices) plane[block * chromaPixelStride] = cbByBlock[block].toByte()
    }

    private fun ByteArray.sample(row: Int, col: Int): Int = this[row * lumaRowStride + col].toInt() and 0xFF

    @Test fun amberOnlyBinarizesAsInkOnceTheBlueChannelIsFoldedIn() {
        val luma = luma()
        val midpoint = (lumaByColumn[0] + lumaByColumn[6]) / 2
        // The reason the fold exists: on luminance alone the amber eye sits on the
        // white side of any threshold between the card and the modules.
        assertTrue(luma.sample(0, 2) > midpoint)

        val out = ByteArray(luma.size)
        QrInk.fold(luma, lumaRowStride, chroma(), chromaRowStride, chromaPixelStride, width, height, out)
        assertTrue(out.sample(0, 2) < midpoint)
        assertEquals(29, out.sample(0, 2))
    }

    @Test fun neutralsAndBlueEyesComeThroughUntouchedOnEveryRow() {
        val out = ByteArray(lumaRowStride * height)
        QrInk.fold(luma(), lumaRowStride, chroma(), chromaRowStride, chromaPixelStride, width, height, out)
        for (row in 0 until height) {
            assertEquals(255, out.sample(row, 0))
            assertEquals(69, out.sample(row, 4))
            assertEquals(16, out.sample(row, 6))
        }
    }

    @Test fun aFrameWithNoUsableChromaDegradesToPlainLuminance() {
        val luma = luma()
        val out = ByteArray(luma.size)
        QrInk.fold(luma, lumaRowStride, ByteArray(0), chromaRowStride, chromaPixelStride, width, height, out)
        for (row in 0 until height) {
            for (col in 0 until width) assertEquals(luma.sample(row, col), out.sample(row, col))
        }
    }
}
