package com.flick.receiver.ui.components

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DisplayCadenceTest {

    @Test fun cadenceIsCompatibleOnlyOnAWholeNumberOfRefreshesPerFrame() {
        assertFalse(cadenceCompatible(60f, 23.976f))
        assertTrue(cadenceCompatible(23.976f, 23.976f))
        assertFalse(cadenceCompatible(60f, 29.97f))
        assertTrue(cadenceCompatible(60f, 30f))
        assertTrue(cadenceCompatible(50f, 25f))
        assertFalse(cadenceCompatible(24f, 23.976f))
        assertFalse(cadenceCompatible(0f, 24f))
        assertFalse(cadenceCompatible(60f, 0f))
        assertFalse(cadenceCompatible(Float.NaN, 24f))
        assertFalse(cadenceCompatible(60f, Float.NaN))
    }

    @Test fun sameCadenceTellsTheNtscRatesApart() {
        assertTrue(sameCadence(60f, 60f))
        assertTrue(sameCadence(23.976f, 23.976f))
        assertFalse(sameCadence(59.94f, 60f))
        assertFalse(sameCadence(24f, 23.976f))
        assertFalse(sameCadence(23.976f, 60f))
    }

    @Test fun aSwitchBlanksOnlyWhenThePlatformWillMakeANonSeamlessOne() {
        val modes = floatArrayOf(60f, 50f, 23.976f)
        val none = FloatArray(0)
        assertFalse("match-content never", switchBlanks(none, modes, NEVER, 23.976f))
        assertFalse("no compatible mode", switchBlanks(none, floatArrayOf(60f, 50f), ALWAYS, 23.976f))
        assertFalse("seamless alternative", switchBlanks(floatArrayOf(23.976f), modes, ALWAYS, 23.976f))
        assertFalse("seamless only", switchBlanks(none, modes, SEAMLESS_ONLY, 23.976f))
        assertTrue("always", switchBlanks(none, modes, ALWAYS, 23.976f))
        assertTrue("preference unreadable", switchBlanks(none, modes, null, 23.976f))
    }

    @Test fun theHoldWaitsOutAnObservedSwitchAndNothingElse() {
        assertEquals(StageHold(0L, HoldReason.NoSwitch), stageHold(0L, pending = false, lastChangeElapsedMs = null))
        assertEquals(StageHold(500L, HoldReason.NoChangeSeen), stageHold(0L, pending = true, lastChangeElapsedMs = null))
        assertEquals(StageHold(400L, HoldReason.Settled), stageHold(200L, pending = true, lastChangeElapsedMs = 100L))
        assertEquals(StageHold(300L, HoldReason.Settled), stageHold(0L, pending = true, lastChangeElapsedMs = -200L))
        assertEquals(StageHold(0L, HoldReason.Cap), stageHold(2_500L, pending = true, lastChangeElapsedMs = null))
        assertEquals(StageHold(100L, HoldReason.Settled), stageHold(2_400L, pending = true, lastChangeElapsedMs = 2_400L))
    }

    @Test fun aReducedCapCountsTheTimeTheSeamAlreadyWaited() {
        // A reveal that spent 600 ms waiting for its cadence has 1 900 ms of the cap left.
        val left = STAGE_HOLD_CAP_MS - RATE_KNOWN_WAIT_MS
        assertEquals(StageHold(500L, HoldReason.NoChangeSeen), stageHold(0L, pending = true, lastChangeElapsedMs = null, capMs = left))
        assertEquals(StageHold(0L, HoldReason.Cap), stageHold(left, pending = true, lastChangeElapsedMs = 100L, capMs = left))
        assertEquals(StageHold(100L, HoldReason.Settled), stageHold(1_800L, pending = true, lastChangeElapsedMs = 1_700L, capMs = left))
        assertEquals(StageHold(200L, HoldReason.NoChangeSeen), stageHold(0L, pending = true, lastChangeElapsedMs = null, capMs = 200L))
        assertEquals(StageHold(0L, HoldReason.NoSwitch), stageHold(0L, pending = false, lastChangeElapsedMs = null, capMs = 0L))
        assertTrue(RATE_KNOWN_WAIT_MS > SWITCH_EXPECT_WINDOW_MS)
    }

    @Test fun aSettledHoldStillOwesASwitchOnlyWhileThePanelCannotShowTheRate() {
        assertTrue("released to rest, pin still to come", switchStillOwed(60f, 23.976f) { true })
        assertFalse("pinned", switchStillOwed(23.976f, 23.976f) { true })
        assertFalse("rest already fits", switchStillOwed(60f, 30f) { true })
        assertFalse("the pin will not blank", switchStillOwed(60f, 23.976f) { false })
        assertFalse("no rate", switchStillOwed(60f, 0f) { true })
    }

    @Test fun anOwedSwitchIsWaitedForThenWaitedOut() = runTest {
        val cadence = FakeCadence()
        val clock = { currentTime }
        val held = async { awaitOwedSwitch(cadence, clock, emptyFlow(), capMs = 2_000L) }
        advanceTimeBy(300L)
        runCurrent()
        cadence.change(currentTime)
        advanceTimeBy(RESYNC_GRACE_MS + 1L)
        assertEquals(StageHold(300L + RESYNC_GRACE_MS, HoldReason.Settled), held.await())
    }

    @Test fun anOwedSwitchThatNeverComesIsNotWaitedForLong() = runTest {
        val held = async { awaitOwedSwitch(FakeCadence(), { currentTime }, emptyFlow(), capMs = 2_000L) }
        advanceTimeBy(SWITCH_EXPECT_WINDOW_MS + 1L)
        assertEquals(StageHold(SWITCH_EXPECT_WINDOW_MS, HoldReason.NoChangeSeen), held.await())
    }

    @Test fun anOwedSwitchKeepsTheCap() = runTest {
        val cadence = FakeCadence()
        val held = async { awaitOwedSwitch(cadence, { currentTime }, emptyFlow(), capMs = 400L) }
        advanceTimeBy(300L)
        runCurrent()
        cadence.change(currentTime)
        advanceTimeBy(RESYNC_GRACE_MS)
        assertEquals(StageHold(400L, HoldReason.Cap), held.await())
    }

    @Test fun aKeyEndsAnOwedWait() = runTest {
        val keys = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val held = async { awaitOwedSwitch(FakeCadence(), { currentTime }, keys, capMs = 2_000L) }
        advanceTimeBy(100L)
        runCurrent()
        keys.tryEmit(Unit)
        runCurrent()
        assertEquals(StageHold(100L, HoldReason.KeyPressed), held.await())
    }

    private class FakeCadence : DisplayCadenceSource {
        override val physicalHz = 60f
        override val restHz = 60f
        override var lastChangeAtMs: Long? = null
        private val events = MutableSharedFlow<Long>(extraBufferCapacity = 8)
        override val changes: Flow<Long> = events
        override fun switchWouldBlank(requestedHz: Float) = true

        fun change(atMs: Long) {
            lastChangeAtMs = atMs
            events.tryEmit(atMs)
        }
    }

    private companion object {
        // DisplayManager.MATCH_CONTENT_FRAMERATE_* values, which are API 31.
        const val NEVER = 0
        const val SEAMLESS_ONLY = 1
        const val ALWAYS = 2
    }
}
