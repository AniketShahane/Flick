package com.flick.sender.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StillVerdictTest {

    @Test fun aFrameThatStillPassesIsNotStale() {
        assertFalse(StillVerdict.Searched(1_000L, passed = true).stale(lit))
    }

    @Test fun aPassingFrameThatNowJudgesBlankIsStale() {
        // The file was re-encoded under the memo: MediaStore bumped its revision, the image
        // cache refetched under a fresh key, and the second that held a scene holds black.
        // Nothing else can explain a position that passed once and does not now.
        assertTrue(StillVerdict.Searched(1_000L, passed = true).stale(blank))
    }

    @Test fun aBestEffortFrameIsNeverStale() {
        // A film that is dark throughout settles on a frame that judges blank on purpose.
        // Re-deciding it would run the whole four-decode search on every cache miss for the
        // rest of the process, which is a worse failure than the tile it would fix.
        assertFalse(StillVerdict.Searched(1_000L, passed = false).stale(blank))
        assertFalse(StillVerdict.Searched(1_000L, passed = false).stale(lit))
    }

    @Test fun aFrameThatCouldNotBeJudgedOverrulesNothing() {
        // A hardware-backed bitmap throws on any pixel access, and "could not look" must
        // never reach the verdict "looked and found nothing".
        assertFalse(StillVerdict.Searched(1_000L, passed = true).stale(null))
        assertFalse(StillVerdict.Searched(1_000L, passed = false).stale(null))
    }

    private val blank = frameStats(IntArray(SampleRows * SampleColumns) { 0 })

    private val lit = frameStats(IntArray(SampleRows * SampleColumns) { if (it % 2 == 0) 40 else 200 })
}
