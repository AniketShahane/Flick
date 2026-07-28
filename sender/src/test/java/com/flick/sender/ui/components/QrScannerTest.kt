package com.flick.sender.ui.components

import com.flick.sender.net.PairLaunch
import com.flick.sender.net.PairLaunchParseResult
import com.google.mlkit.vision.barcode.common.Barcode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

class QrPayloadTest {
    private val pairing = "flick://pair?v=3&h=10.0.0.2&p=47654"

    @Test fun onlyQrResultsAreEverRouted() {
        assertEquals(pairing, QrPayload.of(Barcode.FORMAT_QR_CODE, pairing))
        // The detector is asked for QR only; if that ever changes, pairing still refuses
        // to act on the formats it was never designed around.
        listOf(
            Barcode.FORMAT_AZTEC,
            Barcode.FORMAT_DATA_MATRIX,
            Barcode.FORMAT_PDF417,
            Barcode.FORMAT_CODE_128,
            Barcode.FORMAT_EAN_13,
            Barcode.FORMAT_UNKNOWN,
        ).forEach { assertNull(QrPayload.of(it, pairing)) }
    }

    @Test fun aQrCarryingNoTextIsNotSomethingToRoute() {
        // A binary-mode symbol decodes with a null raw value, and a frame full of those
        // must fall through to the next result rather than waking the gate.
        assertNull(QrPayload.of(Barcode.FORMAT_QR_CODE, null))
        assertNull(QrPayload.of(Barcode.FORMAT_QR_CODE, ""))
        assertNull(QrPayload.of(Barcode.FORMAT_QR_CODE, "   \n"))
    }

    @Test fun theRawValueReachesTheGateExactlyAsTheTvEncodedIt() {
        val padded = "  $pairing  "
        assertEquals(padded, QrPayload.of(Barcode.FORMAT_QR_CODE, padded))
        // Trimming is the gate's job, and validation is the parser's — neither happens
        // here, so nothing between the camera and PairLaunch rewrites the payload.
        assertEquals(
            PairLaunchParseResult.Valid("10.0.0.2", 47654),
            PairLaunch.parse(QrScanGate().accept(QrPayload.of(Barcode.FORMAT_QR_CODE, padded)!!, 0L)!!),
        )
    }
}
