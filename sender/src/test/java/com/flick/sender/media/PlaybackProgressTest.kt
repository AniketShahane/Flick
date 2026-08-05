package com.flick.sender.media

import com.flick.sender.model.PlaybackPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackProgressTest {
    @Test fun resumeEligibilityRejectsNoiseAndFinishedPlayback() {
        assertNull(PlaybackResumePolicy.eligiblePosition(9_999L, 120_000L))
        assertEquals(10_000L, PlaybackResumePolicy.eligiblePosition(10_000L, 120_000L))
        assertNull(PlaybackResumePolicy.eligiblePosition(90_000L, 120_000L))
        assertEquals(90_000L, PlaybackResumePolicy.eligiblePosition(90_000L, 0L))
        assertEquals(130_000L, PlaybackResumePolicy.eligiblePosition(130_000L, 200_000L))
    }

    @Test fun earlyEndedAndNearEndFramesClearOlderProgress() {
        assertEquals(
            PlaybackProgressMutation.Clear,
            PlaybackResumePolicy.mutation(2_000L, 120_000L, PlaybackPhase.PLAYING),
        )
        assertEquals(
            PlaybackProgressMutation.Clear,
            PlaybackResumePolicy.mutation(40_000L, 120_000L, PlaybackPhase.ENDED),
        )
        assertEquals(
            PlaybackProgressMutation.Clear,
            PlaybackResumePolicy.mutation(91_000L, 120_000L, PlaybackPhase.PLAYING),
        )
    }

    @Test fun fingerprintIsOpaqueAndChangesWithEverySourceRevisionField() {
        val base = fingerprint()
        assertFalse(base.contains("content://media/external/video/media/42"))
        assertNotEquals(base, fingerprint(uri = "content://media/external/video/media/43"))
        assertNotEquals(base, fingerprint(size = 8_001L))
        assertNotEquals(base, fingerprint(modified = 124L))
        assertNotEquals(base, fingerprint(duration = 181_000L))
        assertNotEquals(base, fingerprint(generation = 10L))
        assertNotEquals(base, fingerprint(version = "v2"))
    }

    @Test fun checkpointCodecRejectsMalformedOrOutOfRangeValues() {
        val checkpoint = PlaybackCheckpoint(42_000L, 123_456L)
        assertEquals(checkpoint, PlaybackCheckpointCodec.decode(PlaybackCheckpointCodec.encode(checkpoint)))
        assertNull(PlaybackCheckpointCodec.decode("not-a-record"))
        assertNull(PlaybackCheckpointCodec.decode("-1:123"))
        assertNull(PlaybackCheckpointCodec.decode("${PlaybackResumePolicy.MAX_POSITION_MS + 1}:123"))
        assertNull(PlaybackCheckpointCodec.decode("42:-1"))
    }

    @Test fun recorderCoalescesPlayingFramesButFlushesPauseAndCleanup() {
        val recorder = PlaybackProgressRecorder()
        assertNull(recorder.activate(CAST, FINGERPRINT, startOver = false))
        val first = recorder.onConfirmed(CAST, 10_000L, 120_000L, PlaybackPhase.PLAYING)!!
        assertEquals(
            PlaybackProgressMutation.Save(10_000L),
            first.mutation,
        )
        assertNull(recorder.onConfirmed(CAST, 12_000L, 120_000L, PlaybackPhase.PLAYING))
        recorder.acknowledge(first, success = true)
        assertNull(recorder.onConfirmed(CAST, 12_000L, 120_000L, PlaybackPhase.PLAYING))
        val paused = recorder.onConfirmed(CAST, 12_000L, 120_000L, PlaybackPhase.PAUSED)!!
        assertEquals(
            PlaybackProgressMutation.Save(12_000L),
            paused.mutation,
        )
        recorder.acknowledge(paused, success = true)
        recorder.onConfirmed(CAST, 14_000L, 120_000L, PlaybackPhase.PLAYING)
        assertEquals(
            PlaybackProgressMutation.Save(14_000L),
            recorder.finish(CAST)?.mutation,
        )
    }

    @Test fun recorderClearsOnlyAfterStartOverBecomesActive() {
        val recorder = PlaybackProgressRecorder()
        assertEquals(
            PlaybackProgressMutation.Clear,
            recorder.activate(CAST, FINGERPRINT, startOver = true)?.mutation,
        )
        assertEquals(PlaybackProgressMutation.Clear, recorder.finish(CAST)?.mutation)
    }

    @Test fun seekingBackBelowResumeThresholdClearsAnOlderCheckpoint() {
        val recorder = PlaybackProgressRecorder()
        recorder.activate(CAST, FINGERPRINT, startOver = false)
        val saved = recorder.onConfirmed(CAST, 45_000L, 120_000L, PlaybackPhase.PLAYING)!!
        recorder.acknowledge(saved, success = true)
        assertEquals(
            PlaybackProgressMutation.Clear,
            recorder.onConfirmed(CAST, 3_000L, 120_000L, PlaybackPhase.PLAYING)?.mutation,
        )
    }

    @Test fun failedSaveAndClearAreRetriedByLaterFrames() {
        val recorder = PlaybackProgressRecorder()
        recorder.activate(CAST, FINGERPRINT, startOver = false)
        val save = recorder.onConfirmed(CAST, 20_000L, 120_000L, PlaybackPhase.PLAYING)!!
        recorder.acknowledge(save, success = false)
        assertEquals(
            PlaybackProgressMutation.Save(20_000L),
            recorder.onConfirmed(CAST, 20_000L, 120_000L, PlaybackPhase.PLAYING)?.mutation,
        )

        val clearRecorder = PlaybackProgressRecorder()
        val clear = clearRecorder.activate(CAST, FINGERPRINT, startOver = true)!!
        clearRecorder.acknowledge(clear, success = false)
        assertEquals(
            PlaybackProgressMutation.Clear,
            clearRecorder.onConfirmed(CAST, 2_000L, 120_000L, PlaybackPhase.PLAYING)?.mutation,
        )
    }

    @Test fun cleanupEnqueuesFreshestPositionEvenWhileAnOlderWriteIsPending() {
        val recorder = PlaybackProgressRecorder()
        recorder.activate(CAST, FINGERPRINT, startOver = false)
        recorder.onConfirmed(CAST, 20_000L, 120_000L, PlaybackPhase.PLAYING)
        assertNull(recorder.onConfirmed(CAST, 50_000L, 120_000L, PlaybackPhase.PLAYING))
        assertEquals(
            PlaybackProgressMutation.Save(50_000L),
            recorder.finish(CAST)?.mutation,
        )
    }

    @Test fun callbackFromAnOlderGenerationCannotAcknowledgeTheNewCast() {
        val recorder = PlaybackProgressRecorder()
        recorder.activate("cast-old", "old", startOver = false)
        val old = recorder.onConfirmed("cast-old", 20_000L, 120_000L, PlaybackPhase.PLAYING)!!
        recorder.activate("cast-new", "new", startOver = false)
        recorder.acknowledge(old, success = true)
        assertEquals(
            PlaybackProgressMutation.Save(20_000L),
            recorder.onConfirmed("cast-new", 20_000L, 120_000L, PlaybackPhase.PLAYING)?.mutation,
        )
    }

    // --- The drain's acknowledge-exactly-once contract ------------------------

    @Test fun aThrowingPersistStillAcknowledgesAndTheDrainKeepsGoing() = runTest {
        val writes = Channel<PlaybackStoreWrite>(Channel.UNLIMITED)
        val answers = mutableListOf<Pair<String, Boolean>>()
        listOf("a", "b", "c").forEach { name ->
            writes.trySend(
                PlaybackStoreWrite(name, PlaybackProgressMutation.Clear) { answers += name to it },
            )
        }
        writes.close()

        drainStoreWrites(writes) { write ->
            if (write.fingerprint == "b") error("non-IO failure inside dataStore.edit")
            true
        }

        assertEquals(listOf("a" to true, "b" to false, "c" to true), answers)
    }

    @Test fun cancellationStillAnswersTheWriteItWasCarrying() = runTest {
        val writes = Channel<PlaybackStoreWrite>(Channel.UNLIMITED)
        val answers = mutableListOf<Boolean>()
        writes.trySend(PlaybackStoreWrite(FINGERPRINT, PlaybackProgressMutation.Clear) { answers += it })

        assertThrows(CancellationException::class.java) {
            runBlocking {
                drainStoreWrites(writes) { throw CancellationException("scope died") }
            }
        }

        assertEquals(listOf(false), answers)
    }

    /**
     * The store must not be left silently frozen after a transient read error, so the
     * backoff has to stay finite — a permanently unreadable store settles at one read
     * a minute rather than either spinning or giving up forever.
     */
    @Test fun readBackoffDoublesThenSettlesAtTheCap() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L), (0L..5L).map(::readRetryDelayMs))
        assertEquals(60_000L, readRetryDelayMs(6L))
        assertEquals(60_000L, readRetryDelayMs(7L))
        assertEquals(60_000L, readRetryDelayMs(Long.MAX_VALUE))
    }

    private fun fingerprint(
        uri: String = "content://media/external/video/media/42",
        size: Long = 8_000L,
        modified: Long = 123L,
        duration: Long = 180_000L,
        generation: Long? = 9L,
        version: String? = "v1",
    ) = PlaybackMediaFingerprint.of(uri, size, modified, duration, generation, version)

    private companion object {
        const val CAST = "cast-a"
        const val FINGERPRINT = "fingerprint-a"
    }
}
