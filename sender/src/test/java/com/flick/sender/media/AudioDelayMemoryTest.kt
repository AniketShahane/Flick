package com.flick.sender.media

import com.flick.sender.net.AudioDelayPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// `advanceTimeBy(Long)` and `runCurrent()` drive the scheduler directly, which is the
// only way to test a window that must NOT have elapsed yet; both are still opt-in.
@OptIn(ExperimentalCoroutinesApi::class)
class AudioDelayMemoryTest {

    // --- What a record is, and what is refused as one --------------------------

    @Test fun aRecordRoundTripsThroughTheEncodingItIsFiledIn() {
        val record = AudioDelayRecord(-450, 1_712_000_000_000L)
        assertEquals("-450:1712000000000", AudioDelayCodec.encode(record))
        assertEquals(record, AudioDelayCodec.decode(AudioDelayCodec.encode(record)))
        assertEquals(AudioDelayRecord(5_000, 0L), AudioDelayCodec.decode("5000:0"))
    }

    @Test fun aStoredValueOutsideTheWireRangeIsDiscardedRatherThanClampedIntoOne() {
        // The receiver refuses anything outside the range, so a wider stored value could
        // not be re-applied anyway — but the reason to drop it rather than pull it to the
        // bound is the viewer: a bound they never chose, silently in force on a film they
        // have no reason to suspect, is worse than no memory at all.
        assertNull(AudioDelayCodec.decode("${AudioDelayPolicy.MAX_MS + 25}:1"))
        assertNull(AudioDelayCodec.decode("${AudioDelayPolicy.MIN_MS - 25}:1"))
        assertNull(AudioDelayCodec.decode("2147483648:1"))
    }

    @Test fun aStoredValueOffTheStepGridIsDiscarded() {
        // Nothing this app can do produces one: every path into the session goes through
        // AudioDelayPolicy.clamp. So a value between two steps is a record it did not
        // write, and the honest reading of that is that the film has no memory.
        assertNull(AudioDelayCodec.decode("30:1"))
        assertNull(AudioDelayCodec.decode("-30:1"))
        assertNull(AudioDelayCodec.decode("4999:1"))
        assertEquals(AudioDelayRecord(4_975, 1L), AudioDelayCodec.decode("4975:1"))
    }

    @Test fun aStoredInSyncRecordReadsAsNoMemoryAtAll() {
        // Nothing writes one — in-sync removes the record instead — so its presence is
        // corruption, and reading it as absent is the same behaviour with no special case.
        assertNull(AudioDelayCodec.decode("0:1"))
        assertNull(AudioDelayCodec.decode("-0:1"))
    }

    @Test fun aMalformedRecordIsDiscardedRatherThanGuessedAt() {
        assertNull(AudioDelayCodec.decode("75"))
        assertNull(AudioDelayCodec.decode("75:1:2"))
        assertNull(AudioDelayCodec.decode("not-a-record"))
        assertNull(AudioDelayCodec.decode(""))
        assertNull(AudioDelayCodec.decode("75:-1"))
        assertNull(AudioDelayCodec.decode("75:later"))
    }

    // --- Which film a memory belongs to ---------------------------------------

    @Test fun theMemoryIsFiledUnderTheSameIdentityTheResumeCheckpointIs() {
        val fingerprint = fingerprint()
        val state = AudioDelayMemoryState.Ready(mapOf(fingerprint to AudioDelayRecord(75, 5L)))
        assertEquals(75, rememberedAudioDelayMs(state, fingerprint))
        // Every field that moves the resume checkpoint moves this too, which is the point:
        // the offset was dialled in against the mux that file had, and a re-encode or a
        // re-download is a different mux with its own error.
        assertNotEquals(fingerprint, fingerprint(size = 9_000L))
        assertNull(rememberedAudioDelayMs(state, fingerprint(size = 9_000L)))
        // A film nobody has nudged, and a store that has not finished reading yet, are
        // both "no memory" — neither is a reason to put the picture anywhere.
        assertNull(rememberedAudioDelayMs(AudioDelayMemoryState.Ready(emptyMap()), fingerprint))
        assertNull(rememberedAudioDelayMs(AudioDelayMemoryState.Loading, fingerprint))
    }

    // --- What is worth writing ------------------------------------------------

    @Test fun takingTheNudgeBackOutRemovesTheRecordRatherThanSavingAZero() {
        assertEquals(
            AudioDelayMutation.Clear,
            AudioDelayMemoryPolicy.mutation(AudioDelayPolicy.IN_SYNC_MS),
        )
        assertEquals(AudioDelayMutation.Save(25), AudioDelayMemoryPolicy.mutation(25))

        val recorder = AudioDelayRecorder()
        recorder.activate(CAST, FINGERPRINT, 500)
        val write = recorder.settled(AudioDelayPolicy.IN_SYNC_MS)!!
        assertEquals(AudioDelayMutation.Clear, write.mutation)
        recorder.acknowledge(write, success = true)
        assertNull(recorder.finish(CAST, AudioDelayPolicy.IN_SYNC_MS))
    }

    @Test fun aFilmThatWasNeverNudgedLeavesNoTraceAtAll() {
        val recorder = AudioDelayRecorder()
        recorder.activate(CAST, FINGERPRINT, AudioDelayPolicy.IN_SYNC_MS)
        assertNull(recorder.settled(AudioDelayPolicy.IN_SYNC_MS))
        assertNull(recorder.finish(CAST, AudioDelayPolicy.IN_SYNC_MS))
    }

    @Test fun anOutOfRangeValueIsMadeLegalBeforeItIsEverFiled() {
        // The session clamps everything it publishes, so this is belt and braces — but a
        // record the codec would then refuse to read back is a memory silently lost.
        val recorder = AudioDelayRecorder()
        recorder.activate(CAST, FINGERPRINT, AudioDelayPolicy.IN_SYNC_MS)
        assertEquals(AudioDelayMutation.Save(AudioDelayPolicy.MAX_MS), recorder.settled(9_000)?.mutation)
    }

    @Test fun aFilmIsCastBackAtTheOffsetItWasLeftAtAndRewritesNothingForIt() {
        val first = AudioDelayRecorder()
        first.activate(CAST, FINGERPRINT, AudioDelayPolicy.IN_SYNC_MS)
        val write = first.settled(-450)!!
        assertEquals(AudioDelayMutation.Save(-450), write.mutation)
        first.acknowledge(write, success = true)
        assertNull(first.finish(CAST, -450))

        val remembered = rememberedAudioDelayMs(
            AudioDelayMemoryState.Ready(mapOf(FINGERPRINT to AudioDelayRecord(-450, 1L))),
            FINGERPRINT,
        )
        assertEquals(-450, remembered)

        // The later cast starts where the last one ended. Re-applying that is not the
        // viewer choosing it again, so it must not cost a write — otherwise every cast of
        // every nudged film rewrites the whole store to say what it already says.
        val later = AudioDelayRecorder()
        later.activate("cast-b", FINGERPRINT, remembered!!)
        assertNull(later.settled(-450))
        assertNull(later.finish("cast-b", -450))

        val moved = AudioDelayRecorder()
        moved.activate("cast-c", FINGERPRINT, remembered)
        assertEquals(AudioDelayMutation.Save(-475), moved.settled(-475)?.mutation)
    }

    @Test fun aValueThatArrivesWithNoCastBeingDrivenBelongsToNoFilm() {
        val recorder = AudioDelayRecorder()
        assertNull(recorder.settled(250))

        recorder.activate(CAST, FINGERPRINT, AudioDelayPolicy.IN_SYNC_MS)
        val write = recorder.settled(250)!!
        recorder.acknowledge(write, success = true)
        assertNull(recorder.finish(CAST, 250))
        // The teardown that ends a cast republishes in-sync a moment later. It is the
        // session being emptied, not the viewer cancelling the nudge on the film that
        // just ended, and reading it as the second would erase the memory every time.
        assertNull(recorder.settled(AudioDelayPolicy.IN_SYNC_MS))
        assertNull(recorder.finish(CAST, AudioDelayPolicy.IN_SYNC_MS))
    }

    @Test fun oneWriteIsHeldInFlightAndTheEndOfTheCastSpendsTheFreshestValue() {
        val recorder = AudioDelayRecorder()
        recorder.activate(CAST, FINGERPRINT, AudioDelayPolicy.IN_SYNC_MS)
        assertEquals(AudioDelayMutation.Save(100), recorder.settled(100)?.mutation)
        assertNull(recorder.settled(200))
        assertEquals(AudioDelayMutation.Save(200), recorder.finish(CAST, 200)?.mutation)
    }

    @Test fun aNudgeTheViewerWalkedAwayFromIsStillWrittenByTheEndOfTheCast() {
        // Stopping the cast is exactly the gesture that outruns the settle window, so the
        // teardown's value is the one that counts and it has never been settled at all.
        val recorder = AudioDelayRecorder()
        recorder.activate(CAST, FINGERPRINT, AudioDelayPolicy.IN_SYNC_MS)
        assertEquals(AudioDelayMutation.Save(-75), recorder.finish(CAST, -75)?.mutation)
    }

    @Test fun aFailedWriteIsOfferedAgainRatherThanAssumedToHaveLanded() {
        val recorder = AudioDelayRecorder()
        recorder.activate(CAST, FINGERPRINT, AudioDelayPolicy.IN_SYNC_MS)
        val write = recorder.settled(-100)!!
        recorder.acknowledge(write, success = false)
        assertEquals(AudioDelayMutation.Save(-100), recorder.settled(-100)?.mutation)
    }

    @Test fun anAcknowledgementFromAnOlderCastCannotSpeakForTheNewOne() {
        val recorder = AudioDelayRecorder()
        recorder.activate("cast-old", "old", AudioDelayPolicy.IN_SYNC_MS)
        val old = recorder.settled(75)!!
        recorder.activate("cast-new", "new", AudioDelayPolicy.IN_SYNC_MS)
        recorder.acknowledge(old, success = true)
        assertEquals(AudioDelayMutation.Save(75), recorder.settled(75)?.mutation)
    }

    // --- When it is worth writing ---------------------------------------------

    @Test fun aWalkIsOneWriteForTheValueItLandedOnAndNoneForTheFortyItPassedThrough() = runTest {
        val values = MutableStateFlow(AudioDelayPolicy.MIN_MS)
        val settled = mutableListOf<Int>()
        backgroundScope.launch { collectSettledAudioDelay(values) { settled += it } }

        // The widest move there is, walked exactly as PlaybackSession walks it: 250 ms of
        // picture every 40 ms, bound to bound. One of those forty values is the one the
        // viewer asked for; a write per value would be 25 whole-file rewrites a second.
        var hops = 0
        while (values.value != AudioDelayPolicy.MAX_MS) {
            values.value = AudioDelayPolicy.approach(values.value, AudioDelayPolicy.MAX_MS)
            advanceTimeBy(AudioDelayPolicy.WALK_INTERVAL_MS)
            runCurrent()
            hops++
        }
        assertEquals(
            (AudioDelayPolicy.MAX_MS - AudioDelayPolicy.MIN_MS) / AudioDelayPolicy.MAX_JUMP_MS,
            hops,
        )
        assertEquals(emptyList<Int>(), settled)

        letTheWindowElapse()
        assertEquals(listOf(AudioDelayPolicy.MAX_MS), settled)

        // Quiet is quiet: nothing further is owed until the value moves again.
        advanceTimeBy(10 * AudioDelayMemoryPolicy.SETTLE_QUIET_MS)
        runCurrent()
        assertEquals(listOf(AudioDelayPolicy.MAX_MS), settled)

        values.value = AudioDelayPolicy.MAX_MS - AudioDelayPolicy.STEP_MS
        letTheWindowElapse()
        assertEquals(
            listOf(AudioDelayPolicy.MAX_MS, AudioDelayPolicy.MAX_MS - AudioDelayPolicy.STEP_MS),
            settled,
        )
    }

    @Test fun aValueTheViewerOnlyDraggedThroughIsNeverTheOneWritten() = runTest {
        val values = MutableStateFlow(AudioDelayPolicy.IN_SYNC_MS)
        val settled = mutableListOf<Int>()
        backgroundScope.launch { collectSettledAudioDelay(values) { settled += it } }

        // A finger on the blade, reporting a value per pointer sample: the one it rests
        // on is the only one the store ever hears about.
        listOf(-100, -225, -400, -375, -350).forEach {
            values.value = it
            advanceTimeBy(16L)
            runCurrent()
        }
        letTheWindowElapse()
        assertEquals(listOf(-350), settled)
    }

    @Test fun theSettleWindowOutlastsTheGapsInTheMoveItSitsThrough() {
        // Every value restarts the window, so what it has to outlast is one hop of a walk
        // and not the whole 1,560 ms walk. Ten hops of margin, for the hops that a busy
        // main thread stretches — a window that fired in one of those would write a value
        // the walk was only passing through.
        assertEquals(10 * AudioDelayPolicy.WALK_INTERVAL_MS, AudioDelayMemoryPolicy.SETTLE_QUIET_MS)
        assertTrue(AudioDelayMemoryPolicy.SETTLE_QUIET_MS > AudioDelayPolicy.WALK_INTERVAL_MS)
    }

    // --- What a full store gives up -------------------------------------------

    @Test fun aFullStoreDropsTheFilmNobodyHasTouchedInLongest() {
        val records = (1..AudioDelayMemoryPolicy.MAX_RECORDS).map { "delay_$it" to it.toLong() * 1_000L }
        assertNull(AudioDelayMemoryPolicy.evicted(emptyList()))
        assertNull(AudioDelayMemoryPolicy.evicted(records.dropLast(1)))
        assertEquals("delay_1", AudioDelayMemoryPolicy.evicted(records))
        // Preferences hand their contents back in no particular order, so the oldest has
        // to be found rather than assumed to be first.
        assertEquals("delay_1", AudioDelayMemoryPolicy.evicted(records.shuffled()))
        // Dropped by when the nudge was last settled, not by when the record first
        // appeared: a film nudged again this evening is the last one to give up.
        val touchedTonight = records.toMutableList().apply { this[0] = "delay_1" to Long.MAX_VALUE }
        assertEquals("delay_2", AudioDelayMemoryPolicy.evicted(touchedTonight))
    }

    /**
     * Let the settle window run out. Never `advanceUntilIdle()`: idleness in `runTest` is
     * judged on the test coroutine alone, and the collector under test deliberately lives
     * in `backgroundScope`, so that call would return with the window still queued and
     * would pass whatever the window did.
     */
    private fun TestScope.letTheWindowElapse() {
        advanceTimeBy(AudioDelayMemoryPolicy.SETTLE_QUIET_MS + 1L)
        runCurrent()
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
