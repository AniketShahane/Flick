package com.flick.receiver.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every guard here exists to stop a terminal screen appearing over a healthy film,
 * which would be a worse bug than the silence this replaces — so each is asserted to
 * suppress on its own, at a frozen count well past the threshold.
 */
class PictureProgressWatchdogTest {

    private val wellPastThreshold = PICTURE_WEDGED_SAMPLES * 3

    @Test fun aPlayerThatIsNotReadyIsNeverJudged() {
        assertEquals(
            PictureVerdict.HEALTHY,
            pictureVerdict(
                frozenSamples = wellPastThreshold,
                ready = false,
                playing = true,
                positionAdvancing = true,
                hasRenderedAFrame = true,
                framesExpected = true,
                rePrepared = false,
            ),
        )
    }

    @Test fun aPausedFilmIsNeverJudged() {
        assertEquals(
            PictureVerdict.HEALTHY,
            pictureVerdict(
                frozenSamples = wellPastThreshold,
                ready = true,
                playing = false,
                positionAdvancing = true,
                hasRenderedAFrame = true,
                framesExpected = true,
                rePrepared = false,
            ),
        )
    }

    /**
     * The mandatory guard. `videoTrackShortfall` returns null for a container that
     * declares no video group at all, so an audio-only file plays perfectly happily with
     * a counter frozen at zero — and a picture that never existed cannot have stopped.
     */
    @Test fun aFilmThatNeverPaintedAPictureIsNeverJudged() {
        assertEquals(
            PictureVerdict.HEALTHY,
            pictureVerdict(
                frozenSamples = wellPastThreshold,
                ready = true,
                playing = true,
                positionAdvancing = true,
                hasRenderedAFrame = false,
                framesExpected = true,
                rePrepared = true,
            ),
        )
    }

    /** A still frame under an hour of audio is a legal file, not a stopped picture. */
    @Test fun aFilmWithNoFramesLeftToExpectIsNeverJudged() {
        assertEquals(
            PictureVerdict.HEALTHY,
            pictureVerdict(
                frozenSamples = wellPastThreshold,
                ready = true,
                playing = true,
                positionAdvancing = true,
                hasRenderedAFrame = true,
                framesExpected = false,
                rePrepared = true,
            ),
        )
    }

    @Test fun oneSampleShortOfTheThresholdIsStillHealthy() {
        assertEquals(
            PictureVerdict.HEALTHY,
            pictureVerdict(
                frozenSamples = PICTURE_FROZEN_SAMPLES - 1,
                ready = true,
                playing = true,
                positionAdvancing = true,
                hasRenderedAFrame = true,
                framesExpected = true,
                rePrepared = false,
            ),
        )
    }

    @Test fun theThresholdItselfEarnsTheOneSilentRePrepare() {
        assertEquals(
            PictureVerdict.REPREPARE,
            pictureVerdict(
                frozenSamples = PICTURE_FROZEN_SAMPLES,
                ready = true,
                playing = true,
                positionAdvancing = true,
                hasRenderedAFrame = true,
                framesExpected = true,
                rePrepared = false,
            ),
        )
    }

    @Test fun onlyASecondFreezeAfterTheRePrepareIsAnnounced() {
        assertEquals(
            PictureVerdict.ANNOUNCE,
            pictureVerdict(
                frozenSamples = PICTURE_FROZEN_SAMPLES,
                ready = true,
                playing = true,
                positionAdvancing = true,
                hasRenderedAFrame = true,
                framesExpected = true,
                rePrepared = true,
            ),
        )
    }

    @Test fun aRecoveredCounterGoesStraightBackToHealthy() {
        assertEquals(
            PictureVerdict.HEALTHY,
            pictureVerdict(
                frozenSamples = 0,
                ready = true,
                playing = true,
                positionAdvancing = true,
                hasRenderedAFrame = true,
                framesExpected = true,
                rePrepared = true,
            ),
        )
    }

    // --- The wedged pipeline: frames AND clock frozen -------------------------

    /**
     * The mode nothing watched: an audio playback head that stalls without raising a
     * write error consumes nothing, so the buffer never drains, the state never leaves
     * READY and the position — driven by that same clock — freezes with the counter.
     */
    @Test fun aFrozenClockIsJudgedTooOnTheLongerThreshold() {
        assertEquals(
            PictureVerdict.REPREPARE,
            pictureVerdict(
                frozenSamples = PICTURE_WEDGED_SAMPLES,
                ready = true,
                playing = true,
                positionAdvancing = false,
                hasRenderedAFrame = true,
                framesExpected = true,
                rePrepared = false,
            ),
        )
    }

    /** A seek, a masked clock and a rounded audio timestamp all look like this briefly. */
    @Test fun aFrozenClockIsNotJudgedOnTheOrdinaryThreshold() {
        for (samples in listOf(PICTURE_FROZEN_SAMPLES, PICTURE_WEDGED_SAMPLES - 1)) {
            assertEquals(
                "frozenSamples=$samples",
                PictureVerdict.HEALTHY,
                pictureVerdict(
                    frozenSamples = samples,
                    ready = true,
                    playing = true,
                    positionAdvancing = false,
                    hasRenderedAFrame = true,
                    framesExpected = true,
                    rePrepared = false,
                ),
            )
        }
    }

    @Test fun aWedgedPipelineThatSurvivedTheRePrepareIsAnnounced() {
        assertEquals(
            PictureVerdict.ANNOUNCE,
            pictureVerdict(
                frozenSamples = PICTURE_WEDGED_SAMPLES,
                ready = true,
                playing = true,
                positionAdvancing = false,
                hasRenderedAFrame = true,
                framesExpected = true,
                rePrepared = true,
            ),
        )
    }

    // --- Whether frames were due at all ---------------------------------------

    @Test fun anOrdinaryFilmsPaceIsJudgeable() {
        assertTrue(framesAreExpected(23.976f))
        assertTrue(framesAreExpected(60f))
        assertTrue(framesAreExpected(MIN_JUDGED_FRAME_RATE))
    }

    /** Format.NO_VALUE is -1: a single-sample video track declares no rate at all. */
    @Test fun aContainerThatDeclaresNoPaceIsNotJudged() {
        assertFalse(framesAreExpected(-1f))
        assertFalse(framesAreExpected(Float.NaN))
    }

    @Test fun aSlideshowIsNotJudged() {
        assertFalse(framesAreExpected(0.2f))
        assertFalse(framesAreExpected(0f))
    }

    /** Six seconds at the 500 ms cadence `snapshot()` already samples on, then twelve. */
    @Test fun theThresholdsAreSecondsOfTheExistingSamplingCadence() {
        assertEquals(6_000, PICTURE_FROZEN_SAMPLES * 500)
        assertEquals(12_000, PICTURE_WEDGED_SAMPLES * 500)
    }
}
