package com.flick.sender.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlFrameSchemaTest {
    private val castId = "MDEyMzQ1Njc4OWFiY2RlZg"
    private val tvId = "ABEiM0RVZneImaq7zN3u_w"
    private val keyId = "AQIDBAUGBwgJCgsMDQ4PEA"
    private val nonce = "ERITFBUWFxgZGhscHR4fIA"
    private val proof = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"

    @Test fun exactPreAuthSchemasRejectExtraFieldsAndReorderedCapabilities() {
        val negotiated = mapOf("t" to "negotiated", "v" to 2, "clientNonce" to nonce, "serverNonce" to nonce, "tvId" to tvId, "cap" to ControlProtocolV2.capabilities)
        assertTrue(ControlFrameSchema.preAuth(negotiated))
        assertFalse(ControlFrameSchema.preAuth(negotiated + ("host" to "192.168.42.88")))
        assertFalse(ControlFrameSchema.preAuth(negotiated + ("cap" to ControlProtocolV2.capabilities.reversed())))
        assertFalse(ControlFrameSchema.preAuth(negotiated + ("v" to "2")))
    }

    @Test fun startupAndTerminalEventsRequireExactFieldsAndTypes() {
        assertTrue(ControlFrameSchema.event(mapOf("t" to "loadReady", "v" to 2, "castId" to castId, "probeLatencyMs" to 42, "startupMs" to 1380)))
        assertFalse(ControlFrameSchema.event(mapOf("t" to "loadReady", "v" to 2, "castId" to castId, "probeLatencyMs" to 42.5, "startupMs" to 1380)))
        assertFalse(ControlFrameSchema.event(mapOf("t" to "loadReady", "v" to 2, "castId" to castId, "probeLatencyMs" to 42, "startupMs" to 1380, "url" to "leak")))
        assertTrue(ControlFrameSchema.event(mapOf("t" to "loadFailed", "v" to 2, "castId" to castId, "code" to "http_rejected", "retryable" to true, "httpStatus" to 503)))
        assertFalse(ControlFrameSchema.event(mapOf("t" to "loadFailed", "v" to 2, "castId" to castId, "code" to "raw exception", "retryable" to true)))
        assertFalse(ControlFrameSchema.event(mapOf("t" to "loadFailed", "v" to 2, "castId" to castId, "code" to "http_rejected", "retryable" to "true")))
    }

    @Test fun stateSchemaRejectsOutOfRangeAndNonFiniteValues() {
        val state = mapOf("t" to "state", "v" to 2, "castId" to castId, "posMs" to 0, "durationMs" to 123000, "playing" to true, "bufferedMs" to 9000, "phase" to "playing", "volume" to 1.0, "seq" to 8)
        assertTrue(ControlFrameSchema.event(state))
        assertFalse(ControlFrameSchema.event(state + ("volume" to Double.NaN)))
        assertFalse(ControlFrameSchema.event(state + ("durationMs" to 604_800_001L)))
        assertFalse(ControlFrameSchema.event(state + ("phase" to "error")))
        assertFalse(ControlFrameSchema.event(state + ("seq" to 8.0)))
        assertFalse(ControlFrameSchema.event(state + ("v" to 2.0)))
    }

    @Test fun strictTransportParserRejectsSemanticDuplicatesAndTrailingBytes() {
        assertFalse(StrictControlJson.hasUniqueTopLevelObject("{\"t\":\"denied\",\"\\u0074\":\"denied\",\"v\":2}"))
        assertFalse(StrictControlJson.hasUniqueTopLevelObject("{\"t\":\"denied\",\"v\":2} trailing"))
        assertTrue(StrictControlJson.hasUniqueTopLevelObject("{\"t\":\"denied\",\"v\":2}"))
    }

    @Test fun resumeSchemaNeverAllowsKeyOnProofFrames() {
        val resumed = mapOf("t" to "resumed", "v" to 2, "tv" to "Demo TV", "tvId" to tvId, "keyId" to keyId, "clientNonce" to nonce, "serverNonce" to "ISIjJCUmJygpKissLS4vMA", "peerIp" to "192.168.42.17", "serverHost" to "192.168.42.88", "serverPort" to 42421, "cap" to ControlProtocolV2.capabilities, "proof" to proof)
        assertTrue(ControlFrameSchema.preAuth(resumed))
        assertFalse(ControlFrameSchema.preAuth(resumed + ("key" to proof)))
    }

    @Test fun deniedAcceptsBothTheLegacyPairAndTheDiagnosticReasonAndNothingElse() {
        assertTrue(ControlFrameSchema.preAuth(mapOf("t" to "denied", "v" to 2)))
        ControlFrameSchema.deniedReasons.forEach {
            assertTrue(it, ControlFrameSchema.preAuth(mapOf("t" to "denied", "v" to 2, "reason" to it)))
        }
        assertFalse(ControlFrameSchema.preAuth(mapOf("t" to "denied", "v" to 2, "reason" to "wrong_code")))
        assertFalse(ControlFrameSchema.preAuth(mapOf("t" to "denied", "v" to 2, "reason" to 3)))
        assertFalse(ControlFrameSchema.preAuth(mapOf("t" to "denied", "v" to 2, "reason" to "code", "hint" to "x")))
        assertFalse(ControlFrameSchema.preAuth(mapOf("t" to "denied", "v" to 3, "reason" to "code")))
    }

    @Test fun sessionBusyIsValidWithoutCastIdButCannotCarryOne() {
        assertTrue(ControlFrameSchema.event(mapOf("t" to "busy", "v" to 2, "reason" to "active_cast")))
        assertFalse(ControlFrameSchema.event(mapOf("t" to "busy", "v" to 2, "reason" to "active_cast", "castId" to castId)))
    }
}
