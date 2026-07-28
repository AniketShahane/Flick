package com.flick.receiver.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The bug this exists to keep fixed: every 2.39:1 film on the drive was labelled
 * "720p SDR" on the TV.
 *
 * Scope material is encoded with the letterbox baked out — 1920 × 804 — so a
 * classifier that reads HEIGHT alone finds 804, clears the 720 rung and stops.
 * The phone, looking at the same file, said "1080p · 1920 x 804". Both ends now
 * read the frame the same way, from one function.
 */
class VideoResolutionClassTest {

    @Test fun scopeFilmsAreClassifiedByTheirWidthNotTheirLetterbox() {
        // The exact geometry confirmed on device: logged res=1920x804, displayed 720p.
        assertEquals(VideoResolutionClass.Fhd, videoResolutionClass(1920, 804))
        // 2.35:1, the other common scope crop.
        assertEquals(VideoResolutionClass.Fhd, videoResolutionClass(1920, 816))
        assertEquals(VideoResolutionClass.Fhd, videoResolutionClass(1920, 1080))
    }

    @Test fun uhdScopeIsStill4kWithHalfItsLinesCroppedAway() {
        assertEquals(VideoResolutionClass.Uhd, videoResolutionClass(3840, 1608))
        assertEquals(VideoResolutionClass.Uhd, videoResolutionClass(3840, 2160))
    }

    @Test fun genuineHdIsNotPromoted() {
        assertEquals(VideoResolutionClass.Hd, videoResolutionClass(1280, 720))
        // Portrait phone capture. Height is 1280 here, and reading height first
        // would call this 1080p — the failure mode the width rule must not simply
        // trade for another one.
        assertEquals(VideoResolutionClass.Hd, videoResolutionClass(720, 1280))
    }

    @Test fun portraitAndRotatedFramesAreReadOnTheirLongEdgeToo() {
        assertEquals(VideoResolutionClass.Fhd, videoResolutionClass(1080, 1920))
        assertEquals(VideoResolutionClass.Uhd, videoResolutionClass(2160, 3840))
    }

    @Test fun unmeasuredFramesClaimNothing() {
        assertEquals(VideoResolutionClass.Unknown, videoResolutionClass(0, 0))
        assertEquals(VideoResolutionClass.Unknown, videoResolutionClass(1920, 0))
        assertEquals(VideoResolutionClass.Unknown, videoResolutionClass(0, 1080))
        assertEquals(VideoResolutionClass.Unknown, videoResolutionClass(-1, -1))
    }

    @Test fun theRungsBetweenAreWhereTheyShouldBe() {
        assertEquals(VideoResolutionClass.Qhd, videoResolutionClass(2560, 1440))
        // Ultrawide 1440p scope: still classified on the long edge.
        assertEquals(VideoResolutionClass.Qhd, videoResolutionClass(2560, 1080))
        // DCI 2K and DCI 4K, both scope-cropped.
        assertEquals(VideoResolutionClass.Fhd, videoResolutionClass(2048, 858))
        assertEquals(VideoResolutionClass.Uhd, videoResolutionClass(4096, 1716))
        assertEquals(VideoResolutionClass.Sd, videoResolutionClass(720, 480))
        assertEquals(VideoResolutionClass.Sd, videoResolutionClass(640, 360))
    }

    @Test fun standardDefinitionIsNamedByItsShortEdge() {
        // The only rung with no name of its own falls back to "<lines>p", and the
        // lines of a frame are its short edge — 480, not 720.
        assertEquals(480, videoResolutionLines(720, 480))
        assertEquals(360, videoResolutionLines(640, 360))
        assertEquals(480, videoResolutionLines(480, 720))
    }
}
