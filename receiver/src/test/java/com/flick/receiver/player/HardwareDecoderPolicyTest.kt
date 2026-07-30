package com.flick.receiver.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareDecoderPolicyTest {

    @Test fun theSoftwareAndReferenceNamespacesAreRejected() {
        for (name in listOf(
            "OMX.google.h264.decoder",
            "OMX.google.hevc.decoder",
            "c2.android.avc.decoder",
            "c2.android.hevc.decoder",
            "c2.android.av1.decoder",
            "c2.google.av1.decoder",
            "OMX.ffmpeg.h264.decoder",
            "OMX.SEC.avc.sw.dec",
            "OMX.qcom.video.decoder.hevcswvdec",
        )) {
            assertTrue(name, HardwareDecoderPolicy.isSoftwareOnlyCodecName(name))
            assertFalse(name, HardwareDecoderPolicy.hasHardwareVideoCodec(listOf(name)))
        }
    }

    @Test fun everyVendorNamespaceIsAcceptedAsHardware() {
        // The old MediaTek-only allow-list refused all of these, which meant Flick
        // would not play at all on most of the API 26-28 Android TV installed base.
        for (name in listOf(
            "c2.mtk.avc.decoder",
            "c2.mtk.dvhe.sth.decoder",
            "OMX.MTK.VIDEO.DECODER.HEVC",
            "c2.amlogic.hevc.decoder",
            "OMX.amlogic.hevc.decoder.awesome",
            "c2.rtk.hevc.decoder",
            "OMX.realtek.video.decoder.hevc",
            "c2.qti.hevc.decoder",
            "OMX.qcom.video.decoder.hevc",
            "OMX.SEC.hevc.dec",
            "OMX.Exynos.HEVC.Decoder",
            "OMX.brcm.video.hevc.hw.decoder",
            "c2.vendor.hevc.decoder",
            "arc.h264.decoder",
        )) {
            assertFalse(name, HardwareDecoderPolicy.isSoftwareOnlyCodecName(name))
            assertTrue(name, HardwareDecoderPolicy.hasHardwareVideoCodec(listOf(name)))
        }
    }

    @Test fun samsungHardwareIsKeptWhileOnlyItsSwVariantIsRejected() {
        // OMX.SEC is Samsung HARDWARE; only the names carrying `.sw.` are software.
        assertFalse(HardwareDecoderPolicy.isSoftwareOnlyCodecName("OMX.SEC.avc.dec"))
        assertTrue(HardwareDecoderPolicy.isSoftwareOnlyCodecName("OMX.SEC.avc.sw.dec"))
    }

    @Test fun aNameInNoPlatformNamespaceFailsClosed() {
        // Not omx./c2. at all: not a platform decoder namespace, so it is not
        // evidence of hardware and direct-play declines it.
        assertTrue(HardwareDecoderPolicy.isSoftwareOnlyCodecName("vendor.unknown.avc.decoder"))
        assertTrue(HardwareDecoderPolicy.isSoftwareOnlyCodecName("libstagefright.h264"))
        assertFalse(HardwareDecoderPolicy.hasHardwareVideoCodec(listOf("vendor.unknown.hevc.decoder")))
    }

    @Test fun classificationIsCaseInsensitive() {
        assertTrue(HardwareDecoderPolicy.isSoftwareOnlyCodecName("omx.GOOGLE.h264.decoder"))
        assertTrue(HardwareDecoderPolicy.isSoftwareOnlyCodecName("C2.Android.AVC.Decoder"))
        assertFalse(HardwareDecoderPolicy.isSoftwareOnlyCodecName("C2.MTK.DVHE.STH.DECODER"))
    }

    @Test fun suppliedPlatformEvidenceOverridesTheNameEntirely() {
        // Media3's own verdict wins wherever it is supplied — it folds in the API 29
        // platform flag and the device workarounds Media3 maintains below it.
        assertFalse(HardwareDecoderPolicy.hasHardwareVideoCodec(listOf("c2.mtk.avc.decoder"), listOf(false)))
        assertTrue(HardwareDecoderPolicy.hasHardwareVideoCodec(listOf("c2.android.avc.decoder"), listOf(true)))
        assertTrue(HardwareDecoderPolicy.isHardwareVideoCodec("anything", hardwareAccelerated = true))
        assertFalse(HardwareDecoderPolicy.isHardwareVideoCodec("anything", hardwareAccelerated = false))
    }

    @Test fun oneHardwareCandidateAmongSoftwareOnesIsEnough() {
        assertTrue(
            HardwareDecoderPolicy.hasHardwareVideoCodec(
                listOf("c2.android.hevc.decoder", "OMX.google.hevc.decoder", "c2.amlogic.hevc.decoder"),
            ),
        )
        assertFalse(
            HardwareDecoderPolicy.hasHardwareVideoCodec(
                listOf("c2.android.hevc.decoder", "OMX.google.hevc.decoder"),
            ),
        )
    }

    @Test fun flagsShorterThanTheCodecListFallBackToTheNameForTheRemainder() {
        // The zip is positional and the flag list may run out; the tail must still
        // be classified rather than silently accepted.
        assertFalse(
            HardwareDecoderPolicy.hasHardwareVideoCodec(
                listOf("c2.mtk.avc.decoder", "c2.android.avc.decoder"),
                listOf(false),
            ),
        )
        assertTrue(
            HardwareDecoderPolicy.hasHardwareVideoCodec(
                listOf("c2.android.avc.decoder", "c2.amlogic.avc.decoder"),
                listOf(false),
            ),
        )
    }

    @Test fun anEmptyCandidateListIsNeverHardware() {
        assertFalse(HardwareDecoderPolicy.hasHardwareVideoCodec(emptyList()))
    }
}
