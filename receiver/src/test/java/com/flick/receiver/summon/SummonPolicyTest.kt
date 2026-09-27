package com.flick.receiver.summon

import com.flick.receiver.net.NsdAdvertiser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SummonPolicyTest {

    // --- Access ---------------------------------------------------------------

    private fun access(
        sdk: Int = 34,
        canDraw: Boolean = false,
        lowRam: Boolean = false,
        resolvable: Boolean = true,
        fireTv: Boolean = false,
    ) = SummonPolicy.overlayAccess(sdk, canDraw, lowRam, resolvable, fireTv)

    @Test fun beforeAndroid10NothingIsNeeded() {
        assertEquals(OverlayAccess.NotNeeded, access(sdk = 28))
        assertEquals(OverlayAccess.NotNeeded, access(sdk = 26))
        assertEquals(OverlayAccess.Grantable, access(sdk = 29))
    }

    @Test fun lowRamHidesTheFeatureOnlyWhereThePermissionIsNeeded() {
        assertEquals(OverlayAccess.NotNeeded, access(sdk = 28, lowRam = true))
        assertEquals(OverlayAccess.Unavailable, access(sdk = 29, lowRam = true))
    }

    @Test fun anUnresolvableSettingsScreenOrAFireTvIsUnavailable() {
        assertEquals(OverlayAccess.Unavailable, access(resolvable = false))
        assertEquals(OverlayAccess.Unavailable, access(fireTv = true))
    }

    @Test fun aGrantBeatsEveryReasonToHideIt() {
        assertEquals(OverlayAccess.Granted, access(canDraw = true))
        assertEquals(OverlayAccess.Granted, access(canDraw = true, lowRam = true))
        assertEquals(OverlayAccess.Granted, access(canDraw = true, fireTv = true))
        assertEquals(OverlayAccess.Granted, access(canDraw = true, resolvable = false))
    }

    @Test fun onlyNotNeededAndGrantedAllowASummon() {
        assertTrue(SummonPolicy.accessAllowsSummon(OverlayAccess.NotNeeded))
        assertTrue(SummonPolicy.accessAllowsSummon(OverlayAccess.Granted))
        assertFalse(SummonPolicy.accessAllowsSummon(OverlayAccess.Grantable))
        assertFalse(SummonPolicy.accessAllowsSummon(OverlayAccess.Unavailable))
    }

    // --- Refusal --------------------------------------------------------------

    private fun refusal(
        enabled: Boolean = true,
        access: OverlayAccess = OverlayAccess.Granted,
        blocked: Boolean = false,
        addressCurrent: Boolean = true,
        deviceLocked: Boolean = false,
        missedLatch: Boolean = false,
    ) = SummonPolicy.refusal(enabled, access, blocked, addressCurrent, deviceLocked, missedLatch)

    @Test fun anArmedCurrentUnlockedUnlatchedTvMayRequest() {
        assertNull(refusal())
        assertNull(refusal(access = OverlayAccess.NotNeeded))
    }

    @Test fun eachRefusalAloneNamesItself() {
        assertEquals(SummonRefusal.Off, refusal(enabled = false))
        assertEquals(SummonRefusal.NoAccess, refusal(access = OverlayAccess.Grantable))
        assertEquals(SummonRefusal.NoAccess, refusal(access = OverlayAccess.Unavailable))
        assertEquals(SummonRefusal.Blocked, refusal(blocked = true))
        assertEquals(SummonRefusal.StaleAddress, refusal(addressCurrent = false))
        assertEquals(SummonRefusal.Locked, refusal(deviceLocked = true))
        assertEquals(SummonRefusal.Latched, refusal(missedLatch = true))
    }

    @Test fun refusalsFollowTheirPrecedence() {
        assertEquals(SummonRefusal.Off, refusal(false, OverlayAccess.Grantable, true, false, true, true))
        assertEquals(SummonRefusal.NoAccess, refusal(true, OverlayAccess.Grantable, true, false, true, true))
        assertEquals(SummonRefusal.Blocked, refusal(true, OverlayAccess.Granted, true, false, true, true))
        assertEquals(SummonRefusal.StaleAddress, refusal(true, OverlayAccess.Granted, false, false, true, true))
        assertEquals(SummonRefusal.Locked, refusal(true, OverlayAccess.Granted, false, true, true, true))
        assertEquals(SummonRefusal.Latched, refusal(true, OverlayAccess.Granted, false, true, false, true))
    }

    @Test fun refusalLogTokensAreStable() {
        assertEquals(
            listOf("off", "no_access", "blocked", "stale_address", "locked", "latched"),
            SummonRefusal.entries.map { it.wire },
        )
    }

    // --- Hold, service, armed -------------------------------------------------

    @Test fun theProbeIsHeldAwakeOnlyWhenASummonCouldFollow() {
        assertTrue(SummonPolicy.holdAwakeForProbe(true, OverlayAccess.Granted, blocked = false, missedLatch = false))
        assertTrue(SummonPolicy.holdAwakeForProbe(true, OverlayAccess.NotNeeded, blocked = false, missedLatch = false))
        assertFalse(SummonPolicy.holdAwakeForProbe(false, OverlayAccess.Granted, blocked = false, missedLatch = false))
        assertFalse(SummonPolicy.holdAwakeForProbe(true, OverlayAccess.Grantable, blocked = false, missedLatch = false))
        assertFalse(SummonPolicy.holdAwakeForProbe(true, OverlayAccess.Unavailable, blocked = false, missedLatch = false))
        assertFalse(SummonPolicy.holdAwakeForProbe(true, OverlayAccess.Granted, blocked = true, missedLatch = false))
        assertFalse(SummonPolicy.holdAwakeForProbe(true, OverlayAccess.Granted, blocked = false, missedLatch = true))
    }

    @Test fun theServiceRunsOnlyWhenEnabledAllowedAndUnblocked() {
        for (enabled in listOf(true, false)) {
            for (access in OverlayAccess.entries) {
                for (blocked in listOf(true, false)) {
                    val expected = enabled && !blocked &&
                        (access == OverlayAccess.NotNeeded || access == OverlayAccess.Granted)
                    assertEquals(
                        "enabled=$enabled access=$access blocked=$blocked",
                        expected,
                        SummonPolicy.serviceShouldRun(enabled, access, blocked),
                    )
                }
            }
        }
    }

    @Test fun armedNeedsTheServiceToBothBeWantedAndActuallyRun() {
        assertTrue(SummonPolicy.armed(serviceShouldRun = true, serviceRunning = true))
        assertFalse(SummonPolicy.armed(serviceShouldRun = true, serviceRunning = false))
        assertFalse(SummonPolicy.armed(serviceShouldRun = false, serviceRunning = true))
        assertFalse(SummonPolicy.armed(serviceShouldRun = false, serviceRunning = false))
    }

    // --- Budget ---------------------------------------------------------------

    @Test fun theWaitLeavesTheFirstFrameItsReserve() {
        assertEquals(8_000L, SummonWaitPolicy.budgetMs(nowMs = 0L, startupDeadlineMs = 18_000L))
        assertEquals(4_000L, SummonWaitPolicy.budgetMs(nowMs = 6_000L, startupDeadlineMs = 18_000L))
        assertEquals(0L, SummonWaitPolicy.budgetMs(nowMs = 10_500L, startupDeadlineMs = 18_000L))
        assertEquals(0L, SummonWaitPolicy.budgetMs(nowMs = 20_000L, startupDeadlineMs = 18_000L))
    }

    @Test fun theTrampolineOutlastsTheWaitAndTheWakeLockOutlastsBoth() {
        assertEquals(10_000L, SummonPolicy.WAKE_GIVE_UP_MS)
        assertTrue(SummonPolicy.WAKE_LOCK_TIMEOUT_MS > SummonWaitPolicy.MAX_WAIT_MS + SummonPolicy.FRAME_SETTLE_MS)
    }

    // --- Advertising ----------------------------------------------------------

    @Test fun anArmedAwakeUnlatchedTvIsReady() {
        assertTrue(SummonPolicy.backgroundReady(armed = true, missedLatch = false, screenOff = false, lowPowerStandby = false))
        assertFalse(SummonPolicy.backgroundReady(armed = false, missedLatch = false, screenOff = false, lowPowerStandby = false))
        assertFalse(SummonPolicy.backgroundReady(armed = true, missedLatch = true, screenOff = false, lowPowerStandby = false))
    }

    @Test fun lowPowerStandbyMattersOnlyWhileTheScreenIsOff() {
        assertTrue(SummonPolicy.backgroundReady(armed = true, missedLatch = false, screenOff = false, lowPowerStandby = true))
        assertFalse(SummonPolicy.backgroundReady(armed = true, missedLatch = false, screenOff = true, lowPowerStandby = true))
        assertTrue(
            SummonPolicy.backgroundReady(
                armed = true,
                missedLatch = false,
                screenOff = true,
                lowPowerStandby = false,
                readyWhileScreenOff = true,
            ),
        )
    }

    @Test fun withoutTheScreenOffGateAnyScreenOffIsSleeping() {
        assertFalse(
            SummonPolicy.backgroundReady(
                armed = true,
                missedLatch = false,
                screenOff = true,
                lowPowerStandby = false,
                readyWhileScreenOff = false,
            ),
        )
        assertTrue(
            SummonPolicy.backgroundReady(
                armed = true,
                missedLatch = false,
                screenOff = false,
                lowPowerStandby = false,
                readyWhileScreenOff = false,
            ),
        )
    }

    // --- Resting and latching ------------------------------------------------

    @Test fun idleRestsOnlyWhenASleepingTvStillHearsTheNextCast() {
        assertTrue(SummonPolicy.idleMayRest(armed = true, lowPowerStandby = false, readyWhileScreenOff = true))
        assertFalse(SummonPolicy.idleMayRest(armed = false, lowPowerStandby = false, readyWhileScreenOff = true))
        assertFalse(SummonPolicy.idleMayRest(armed = true, lowPowerStandby = true, readyWhileScreenOff = true))
        assertFalse(SummonPolicy.idleMayRest(armed = true, lowPowerStandby = false, readyWhileScreenOff = false))
    }

    @Test fun onlyAViewerLeavingALiveCastLatches() {
        assertTrue(SummonPolicy.latchesOnStop(castLive = true, interactive = true))
        assertFalse(SummonPolicy.latchesOnStop(castLive = false, interactive = true))
        // Sleep, CEC standby or the sleep timer stopping a film mid-play.
        assertFalse(SummonPolicy.latchesOnStop(castLive = true, interactive = false))
    }

    @Test fun theStoppedAdvertIsReadyOnlyWhenReadyAndOnTheBoundAddress() {
        assertEquals(NsdAdvertiser.STATE_READY, SummonPolicy.stoppedAdvertState(backgroundReady = true, addressCurrent = true))
        assertEquals(NsdAdvertiser.STATE_SLEEPING, SummonPolicy.stoppedAdvertState(backgroundReady = true, addressCurrent = false))
        assertEquals(NsdAdvertiser.STATE_SLEEPING, SummonPolicy.stoppedAdvertState(backgroundReady = false, addressCurrent = true))
        assertEquals(NsdAdvertiser.STATE_SLEEPING, SummonPolicy.stoppedAdvertState(backgroundReady = false, addressCurrent = false))
    }

    // --- Settings row ---------------------------------------------------------

    @Test fun theRowFollowsAccessThenIntentThenTheBlock() {
        assertEquals(OpenForCastsRow.Hidden, SummonPolicy.openForCastsRow(true, OverlayAccess.Unavailable, blocked = false))
        assertEquals(OpenForCastsRow.Hidden, SummonPolicy.openForCastsRow(false, OverlayAccess.Unavailable, blocked = true))
        assertEquals(OpenForCastsRow.NeedsAccess, SummonPolicy.openForCastsRow(true, OverlayAccess.Grantable, blocked = false))
        assertEquals(OpenForCastsRow.NeedsAccess, SummonPolicy.openForCastsRow(false, OverlayAccess.Grantable, blocked = false))
        assertEquals(OpenForCastsRow.Off, SummonPolicy.openForCastsRow(false, OverlayAccess.Granted, blocked = false))
        assertEquals(OpenForCastsRow.Off, SummonPolicy.openForCastsRow(false, OverlayAccess.NotNeeded, blocked = true))
        assertEquals(OpenForCastsRow.Blocked, SummonPolicy.openForCastsRow(true, OverlayAccess.Granted, blocked = true))
        assertEquals(OpenForCastsRow.On, SummonPolicy.openForCastsRow(true, OverlayAccess.Granted, blocked = false))
        assertEquals(OpenForCastsRow.On, SummonPolicy.openForCastsRow(true, OverlayAccess.NotNeeded, blocked = false))
    }

    // --- Misses and strikes ---------------------------------------------------

    @Test fun aTrampolineThatResumedIsLateWhetherOrNotTheTvWasAsleep() {
        assertEquals(SummonMiss.Late, SummonPolicy.classifyMiss(wakeResumed = true, interactiveAtIssue = true))
        assertEquals(SummonMiss.Late, SummonPolicy.classifyMiss(wakeResumed = true, interactiveAtIssue = false))
    }

    @Test fun onlyAnAwakeTvThatNeverResumedIsBlocked() {
        assertEquals(SummonMiss.Blocked, SummonPolicy.classifyMiss(wakeResumed = false, interactiveAtIssue = true))
        assertEquals(SummonMiss.Asleep, SummonPolicy.classifyMiss(wakeResumed = false, interactiveAtIssue = false))
    }

    @Test fun everyMissMapsToItsOutcome() {
        assertEquals(SummonOutcome.Blocked, SummonPolicy.outcomeOf(SummonMiss.Blocked))
        assertEquals(SummonOutcome.Late, SummonPolicy.outcomeOf(SummonMiss.Late))
        assertEquals(SummonOutcome.Asleep, SummonPolicy.outcomeOf(SummonMiss.Asleep))
    }

    private fun strikesAfter(vararg outcomes: SummonOutcome): Int =
        outcomes.fold(0) { strikes, outcome -> SummonStrikePolicy.afterOutcome(strikes, outcome) }

    @Test fun twoBlockedInARowDisarm() {
        assertTrue(SummonStrikePolicy.blockedNow(strikesAfter(SummonOutcome.Blocked, SummonOutcome.Blocked)))
        assertFalse(SummonStrikePolicy.blockedNow(strikesAfter(SummonOutcome.Blocked)))
    }

    @Test fun aLateMissBetweenTwoBlockedResetsTheCount() {
        assertFalse(
            SummonStrikePolicy.blockedNow(strikesAfter(SummonOutcome.Blocked, SummonOutcome.Late, SummonOutcome.Blocked)),
        )
    }

    @Test fun anOpenedSummonResetsAndAnAsleepMissLeavesTheCountAlone() {
        assertEquals(0, SummonStrikePolicy.afterOutcome(1, SummonOutcome.Opened))
        assertEquals(1, SummonStrikePolicy.afterOutcome(1, SummonOutcome.Asleep))
        assertEquals(0, SummonStrikePolicy.afterOutcome(0, SummonOutcome.Asleep))
        assertTrue(
            SummonStrikePolicy.blockedNow(strikesAfter(SummonOutcome.Blocked, SummonOutcome.Asleep, SummonOutcome.Blocked)),
        )
    }

    @Test fun theStrikeRecordBelongsToOneOsBuildAndOneAppVersion() {
        assertTrue(SummonStrikePolicy.blockStillValid("fp", 7L, "fp", 7L))
        assertFalse(SummonStrikePolicy.blockStillValid("fp", 7L, "fp2", 7L))
        assertFalse(SummonStrikePolicy.blockStillValid("fp", 7L, "fp", 8L))
        // A record written before any strike carries no build at all.
        assertFalse(SummonStrikePolicy.blockStillValid("", 0L, "fp", 7L))
    }
}
