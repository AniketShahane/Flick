package com.flick.receiver.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareDecoderPolicyTest {
    @Test fun api26FailsClosedUnlessTheDecoderIsOnTheVerifiedHardwareAllowlist() {
        assertFalse(HardwareDecoderPolicy.hasHardwareVideoCodec(listOf("OMX.google.h264.decoder", "c2.android.avc.decoder"), 26))
        assertTrue(HardwareDecoderPolicy.hasHardwareVideoCodec(listOf("c2.mtk.dvhe.sth.decoder"), 26))
        assertFalse(HardwareDecoderPolicy.hasHardwareVideoCodec(listOf("vendor.unknown.avc.decoder"), 26))
        assertFalse(HardwareDecoderPolicy.hasHardwareVideoCodec(listOf("vendor.unknown.hevc.decoder"), 28))
    }

    @Test fun api29UsesPlatformHardwareEvidence() {
        assertFalse(HardwareDecoderPolicy.hasHardwareVideoCodec(listOf("vendor.decoder"), 29, listOf(false)))
        assertTrue(HardwareDecoderPolicy.hasHardwareVideoCodec(listOf("vendor.decoder"), 29, listOf(true)))
    }
}
