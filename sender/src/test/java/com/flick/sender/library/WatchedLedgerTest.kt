package com.flick.sender.library

import com.flick.sender.media.PlaybackCheckpoint
import com.flick.sender.model.PlaybackPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchedLedgerTest {

    @Test
    fun endedOrStoppedInTheFinishZoneIsWatched() {
        assertTrue(WatchedLedgerPolicy.finishedAt(0L, 0L, PlaybackPhase.ENDED))
        assertTrue(WatchedLedgerPolicy.finishedAt(5_970_000L, 6_000_000L, PlaybackPhase.PAUSED))
        assertTrue(WatchedLedgerPolicy.finishedAt(6_100_000L, 6_000_000L, PlaybackPhase.PLAYING))
        // The last 5 % of a 100-minute film is five minutes of end credits.
        assertTrue(WatchedLedgerPolicy.finishedAt(5_700_000L, 6_000_000L, PlaybackPhase.PAUSED))
        assertFalse(WatchedLedgerPolicy.finishedAt(5_699_999L, 6_000_000L, PlaybackPhase.PLAYING))
        // A short video falls back to the 30 s window.
        assertTrue(WatchedLedgerPolicy.finishedAt(90_000L, 120_000L, PlaybackPhase.PAUSED))
        assertFalse(WatchedLedgerPolicy.finishedAt(89_999L, 120_000L, PlaybackPhase.PAUSED))
        // No duration: nothing can be said about the end.
        assertFalse(WatchedLedgerPolicy.finishedAt(5_990_000L, 0L, PlaybackPhase.PAUSED))
        assertFalse(WatchedLedgerPolicy.finishedAt(0L, 20_000L, PlaybackPhase.BUFFERING))
    }

    @Test
    fun aFinishZoneCheckpointIsBackfilledOnceAtItsOwnTime() {
        val credits = PlaybackCheckpoint(positionMs = 5_750_000L, updatedAtEpochMs = 1_700_000_000_000L)
        assertEquals(1_700_000_000_000L, WatchedLedgerPolicy.backfillAtMs(credits, 6_000_000L, recorded = false))
        // The ledger already knows: no second write, and its own time stands.
        assertNull(WatchedLedgerPolicy.backfillAtMs(credits, 6_000_000L, recorded = true))
        // Mid-film, or nothing stored, or no duration to place the zone: not a finish.
        val midFilm = PlaybackCheckpoint(positionMs = 5_699_999L, updatedAtEpochMs = 1L)
        assertNull(WatchedLedgerPolicy.backfillAtMs(midFilm, 6_000_000L, recorded = false))
        assertNull(WatchedLedgerPolicy.backfillAtMs(null, 6_000_000L, recorded = false))
        assertNull(WatchedLedgerPolicy.backfillAtMs(credits, 0L, recorded = false))
    }

    @Test
    fun theLedgerKeepsTheNewest5000() {
        val records = (0 until 5_002).associate { "fp$it" to it.toLong() }
        assertEquals(listOf("fp0", "fp1"), WatchedLedgerPolicy.overflow(records))
        assertEquals(emptyList<String>(), WatchedLedgerPolicy.overflow(records.filterKeys { it != "fp0" && it != "fp1" }))
        assertEquals(listOf("a"), WatchedLedgerPolicy.overflow(mapOf("b" to 1L, "a" to 1L, "c" to 2L), max = 2))
    }

    @Test
    fun reachingEndedRecordsOnce() {
        val recorder = WatchedRecorder()
        assertNull(recorder.onConfirmed("cast", 1L, 2L, PlaybackPhase.ENDED))
        recorder.activate("cast", "fp")
        assertNull(recorder.onConfirmed("other", 6_000_000L, 6_000_000L, PlaybackPhase.ENDED))
        assertNull(recorder.onConfirmed("cast", 60_000L, 6_000_000L, PlaybackPhase.PLAYING))
        assertEquals("fp", recorder.onConfirmed("cast", 6_000_000L, 6_000_000L, PlaybackPhase.ENDED))
        assertNull(recorder.onConfirmed("cast", 6_000_000L, 6_000_000L, PlaybackPhase.ENDED))
        assertNull(recorder.finish("cast"))
    }

    @Test
    fun stoppingNearTheEndRecordsAtTeardown() {
        val recorder = WatchedRecorder()
        recorder.activate("cast", "fp")
        recorder.onConfirmed("cast", 5_750_000L, 6_000_000L, PlaybackPhase.PAUSED)
        assertNull(recorder.finish("other"))
        assertEquals("fp", recorder.finish("cast"))
        assertNull(recorder.finish("cast"))
    }

    @Test
    fun stoppingMidFilmRecordsNothing() {
        val recorder = WatchedRecorder()
        recorder.activate("cast", "fp")
        recorder.onConfirmed("cast", 3_000_000L, 6_000_000L, PlaybackPhase.PAUSED)
        assertNull(recorder.finish("cast"))
        // A new cast starts with nothing carried over.
        recorder.activate("next", "fp2")
        assertNull(recorder.finish("next"))
    }
}
