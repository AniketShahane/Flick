package com.flick.receiver.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The verified hardware advertises DV, HDR10 and HLG — and no HDR10+. */
private val VerifiedTvHdrTypes = intArrayOf(HDR_TYPE_DOLBY_VISION, HDR_TYPE_HDR10, HDR_TYPE_HLG)

class DisplayCapabilityPolicyTest {

    @Test fun dolbyVisionIsReadOffTheMimeTypeBecauseProfile81CarriesAnHdr10BaseLayer() {
        assertEquals(
            HdrPresentation.DolbyVision,
            hdrPresentationOf(MimeTypes.VIDEO_DOLBY_VISION, C.COLOR_TRANSFER_ST2084),
        )
        // Same transfer, no DV MIME type: HDR10, not Dolby Vision.
        assertEquals(HdrPresentation.Hdr10, hdrPresentationOf(MimeTypes.VIDEO_H265, C.COLOR_TRANSFER_ST2084))
        assertEquals(HdrPresentation.Hlg, hdrPresentationOf(MimeTypes.VIDEO_H265, C.COLOR_TRANSFER_HLG))
        assertEquals(HdrPresentation.Sdr, hdrPresentationOf(MimeTypes.VIDEO_H264, C.COLOR_TRANSFER_SDR))
        // Nothing known yet is not an HDR claim.
        assertEquals(HdrPresentation.Sdr, hdrPresentationOf(null, C.COLOR_TRANSFER_SDR))
    }

    @Test fun theVerifiedHardwarePresentsEverythingItAdvertises() {
        assertTrue(isHdrPresentable(HdrPresentation.DolbyVision, VerifiedTvHdrTypes))
        assertTrue(isHdrPresentable(HdrPresentation.Hdr10, VerifiedTvHdrTypes))
        assertTrue(isHdrPresentable(HdrPresentation.Hlg, VerifiedTvHdrTypes))
        assertTrue(isHdrPresentable(HdrPresentation.Sdr, VerifiedTvHdrTypes))
    }

    @Test fun anHdr10OnlyPanelCannotPresentDolbyVision() {
        val hdr10Only = intArrayOf(HDR_TYPE_HDR10)
        assertFalse(isHdrPresentable(HdrPresentation.DolbyVision, hdr10Only))
        assertFalse(isHdrPresentable(HdrPresentation.Hlg, hdr10Only))
        assertTrue(isHdrPresentable(HdrPresentation.Hdr10, hdr10Only))
        assertTrue(isHdrPresentable(HdrPresentation.Sdr, hdr10Only))
    }

    @Test fun anSdrPanelPresentsSdrAndNothingElse() {
        val sdrPanel = intArrayOf(HDR_TYPE_HDR10_PLUS)
        assertFalse(isHdrPresentable(HdrPresentation.DolbyVision, sdrPanel))
        assertFalse(isHdrPresentable(HdrPresentation.Hdr10, sdrPanel))
        assertTrue(isHdrPresentable(HdrPresentation.Sdr, sdrPanel))
    }

    @Test fun aPanelThatAdvertisesNothingIsGivenTheBenefitOfTheDoubt() {
        // An empty list is a reporting gap far more often than it is a real absence,
        // and this policy may not turn a gap into a claim about the hardware.
        for (presentation in HdrPresentation.entries) {
            assertTrue(presentation.name, isHdrPresentable(presentation, intArrayOf()))
        }
    }

    @Test fun aStreamTallerThanThePanelsModeIsNotNative() {
        assertFalse(isResolutionNative(3840, 2160, 1920, 1080))
        // The verified TV's own physical mode presents every line of 4K.
        assertTrue(isResolutionNative(3840, 2160, 3840, 2160))
        assertTrue(isResolutionNative(1920, 1080, 1920, 1080))
        assertTrue(isResolutionNative(1280, 720, 1920, 1080))
    }

    @Test fun theDisplayModesReportedOrientationDoesNotDecideTheAnswer() {
        assertTrue(isResolutionNative(3840, 2160, 2160, 3840))
        assertFalse(isResolutionNative(3840, 2160, 1080, 1920))
    }

    @Test fun anAbsentReadingIsNeverAShortfall() {
        assertTrue(isResolutionNative(0, 0, 1920, 1080))
        assertTrue(isResolutionNative(-1, -1, 1920, 1080))
        assertTrue(isResolutionNative(3840, 2160, 0, 0))
        assertTrue(isResolutionNative(3840, 2160, -1, -1))
    }

    @Test fun theVerifiedHardwareReportsNoShortfallForItsProvenContent() {
        // 4K DV profile 8.1 on the verified TV at its physical mode: nothing to say.
        assertEquals(
            emptyList<String>(),
            presentationShortfalls(
                videoWidth = 3840,
                videoHeight = 2160,
                videoMimeType = MimeTypes.VIDEO_DOLBY_VISION,
                colorTransfer = C.COLOR_TRANSFER_ST2084,
                supportedHdrTypes = VerifiedTvHdrTypes,
                displayWidth = 3840,
                displayHeight = 2160,
            ),
        )
    }

    @Test fun bothShortfallsAreReportedTogetherRatherThanTheFirstOnly() {
        val shortfalls = presentationShortfalls(
            videoWidth = 3840,
            videoHeight = 2160,
            videoMimeType = MimeTypes.VIDEO_DOLBY_VISION,
            colorTransfer = C.COLOR_TRANSFER_ST2084,
            supportedHdrTypes = intArrayOf(HDR_TYPE_HDR10),
            displayWidth = 1920,
            displayHeight = 1080,
        )
        assertEquals(listOf("hdr:DolbyVision", "downscaled:3840x2160"), shortfalls)
    }

    @Test fun anHdrPlusStreamIsNotFlaggedOnAnHdr10Panel() {
        // HDR10+ dynamic metadata rides in an HEVC SEI the format does not expose, so
        // the stream reports as HDR10 — which is what such a panel will present.
        assertEquals(
            emptyList<String>(),
            presentationShortfalls(
                videoWidth = 3840,
                videoHeight = 2160,
                videoMimeType = MimeTypes.VIDEO_H265,
                colorTransfer = C.COLOR_TRANSFER_ST2084,
                supportedHdrTypes = intArrayOf(HDR_TYPE_HDR10),
                displayWidth = 3840,
                displayHeight = 2160,
            ),
        )
    }
}
