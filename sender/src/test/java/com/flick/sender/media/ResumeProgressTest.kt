package com.flick.sender.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeProgressTest {

    private val fingerprint = "media-fingerprint"

    private fun stored(positionMs: Long): PlaybackProgressState.Ready =
        PlaybackProgressState.Ready(mapOf(fingerprint to PlaybackCheckpoint(positionMs, 1_700L)))

    @Test fun nothingIsOfferedBeforeTheStoreHasRead() {
        assertNull(resumePositionMs(PlaybackProgressState.Loading, fingerprint, 7_200_000L))
        assertNull(resumeProgress(PlaybackProgressState.Loading, fingerprint, 7_200_000L))
    }

    @Test fun nothingIsOfferedForAFileWithNoCheckpoint() {
        val state = stored(600_000L)
        assertNull(resumePositionMs(state, "another-file", 7_200_000L))
        assertNull(resumeProgress(state, "another-file", 7_200_000L))
    }

    @Test fun theOfferedPositionIsTheResumePolicysOwn() {
        // The head and the tail the policy suppresses, at their exact boundaries.
        assertNull(resumePositionMs(stored(9_999L), fingerprint, 120_000L))
        assertEquals(10_000L, resumePositionMs(stored(10_000L), fingerprint, 120_000L))
        assertNull(resumePositionMs(stored(90_000L), fingerprint, 120_000L))
        assertEquals(89_999L, resumePositionMs(stored(89_999L), fingerprint, 120_000L))
    }

    @Test fun theLineIsDrawnExactlyWhenAResumeIsOffered() {
        // The whole point of one rule behind both: a tile drawing a line the detail sheet
        // has no resume for — or the reverse — is what this equivalence forbids.
        val durationMs = 120_000L
        (0L..durationMs step 1_000L).forEach { position ->
            val state = stored(position)
            val offered = resumePositionMs(state, fingerprint, durationMs)
            val drawn = resumeProgress(state, fingerprint, durationMs)
            assertTrue("disagreed at $position", (offered != null) == (drawn != null))
            if (offered != null) assertEquals(offered, drawn!!.positionMs)
        }
    }

    @Test fun anUnmeasuredDurationOffersAResumeAndStillDrawsNoLine() {
        // MediaStore reported no duration. The checkpoint is a position and needs none,
        // so the sheet still offers the resume — but a fraction has no denominator, and
        // the tile withholds the line rather than inventing one.
        val state = stored(600_000L)
        assertNotNull(resumePositionMs(state, fingerprint, 0L))
        assertNull(resumeProgress(state, fingerprint, 0L))
    }

    @Test fun theFractionIsTheWatchedShareOfTheFile() {
        val progress = resumeProgress(stored(1_800_000L), fingerprint, 7_200_000L)
        assertEquals(1_800_000L, progress!!.positionMs)
        assertEquals(0.25f, progress.fraction, 0.0001f)
    }

    @Test fun theFractionNeverLeavesTheTrack() {
        // The end window means a drawn line can never actually reach the right edge — a
        // file watched that far has no resume at all, so it has no line either. The clamp
        // inside the fraction is there for a checkpoint written against a container
        // duration MediaStore later disagreed with, which cannot be reached from here.
        (0L..7_200_000L step 60_000L).forEach { position ->
            val fraction = resumeProgress(stored(position), fingerprint, 7_200_000L)?.fraction
            if (fraction != null) assertTrue("$position drew $fraction", fraction in 0f..1f)
        }
        assertNull(resumeProgress(stored(7_200_000L), fingerprint, 7_200_000L))
    }
}
