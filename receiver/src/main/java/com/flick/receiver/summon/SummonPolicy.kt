package com.flick.receiver.summon

import com.flick.receiver.net.NsdAdvertiser

/** Whether this TV may launch an Activity from the background, and what it would take. */
enum class OverlayAccess { NotNeeded, Granted, Grantable, Unavailable }

/** Why a cast adopted in the background is failed now instead of opening Flick. [wire] is a log token only. */
enum class SummonRefusal(val wire: String) {
    Off("off"),
    NoAccess("no_access"),
    Blocked("blocked"),
    StaleAddress("stale_address"),
    Locked("locked"),
    Latched("latched"),
}

/** What the "Open when you cast" Settings row shows. */
enum class OpenForCastsRow { Hidden, Off, NeedsAccess, On, Blocked }

/** Why a summon ended without STARTED. [wire] is a log token only. */
enum class SummonMiss(val wire: String) { Blocked("blocked"), Late("late"), Asleep("asleep") }

enum class SummonOutcome { Opened, Late, Blocked, Asleep }

/** [wire] is a log token only. */
enum class LatchReason(val wire: String) {
    Missed("missed"),
    LeftDuringCast("left_during_cast"),
    Bounced("bounced"),
}

/**
 * Every decision "Open when you cast" makes, free of Android types so the whole
 * ladder can be exercised on the JVM. Called qualified, never imported by name:
 * [armed] and friends would otherwise shadow the summoner's properties.
 */
object SummonPolicy {
    /** Bounded so a composition whose frame clock never resumes cannot hold a started cast back. */
    const val FRAME_SETTLE_MS = 500L

    /**
     * A backstop only. The hold starts at the probe, and the wait ends by the startup
     * deadline less its first-frame reserve (T0 + 10 s); this adds the settle and margin.
     */
    const val WAKE_LOCK_TIMEOUT_MS = 12_000L

    /** Measured from the request, not from the trampoline's onCreate, so a slow wake cannot stretch it. */
    const val WAKE_GIVE_UP_MS = SummonWaitPolicy.MAX_WAIT_MS + 2_000L

    /** An ON_STOP this soon after a summon opened Flick, before the first frame, is the TV sending it back. */
    const val BOUNCE_WINDOW_MS = 5_000L

    /** Ship gate G1: false makes every screen-off advertise `sleeping`. */
    const val ADVERTISE_READY_WHILE_SCREEN_OFF = true

    /** Android 10 introduced the background-activity-launch restriction; the overlay grant is what lifts it. */
    fun overlayAccess(
        sdkInt: Int,
        canDrawOverlays: Boolean,
        lowRamDevice: Boolean,
        settingsResolvable: Boolean,
        fireTv: Boolean,
    ): OverlayAccess = when {
        sdkInt < 29 -> OverlayAccess.NotNeeded
        canDrawOverlays -> OverlayAccess.Granted
        // Fire OS 8 may resolve the intent to a screen that cannot grant it.
        lowRamDevice || !settingsResolvable || fireTv -> OverlayAccess.Unavailable
        else -> OverlayAccess.Grantable
    }

    fun accessAllowsSummon(access: OverlayAccess): Boolean =
        access == OverlayAccess.NotNeeded || access == OverlayAccess.Granted

    fun serviceShouldRun(enabled: Boolean, access: OverlayAccess, blocked: Boolean): Boolean =
        enabled && accessAllowsSummon(access) && !blocked

    fun armed(serviceShouldRun: Boolean, serviceRunning: Boolean): Boolean =
        serviceShouldRun && serviceRunning

    /** Precedence Off > NoAccess > Blocked > StaleAddress > Locked > Latched; null = may request. */
    fun refusal(
        enabled: Boolean,
        access: OverlayAccess,
        blocked: Boolean,
        addressCurrent: Boolean,
        deviceLocked: Boolean,
        missedLatch: Boolean,
    ): SummonRefusal? = when {
        !enabled -> SummonRefusal.Off
        !accessAllowsSummon(access) -> SummonRefusal.NoAccess
        blocked -> SummonRefusal.Blocked
        // ON_START's reconcile would tear the cast down with no_compatible_lan.
        !addressCurrent -> SummonRefusal.StaleAddress
        // The TV is never woken to a secure lock screen.
        deviceLocked -> SummonRefusal.Locked
        missedLatch -> SummonRefusal.Latched
        else -> null
    }

    fun holdAwakeForProbe(enabled: Boolean, access: OverlayAccess, blocked: Boolean, missedLatch: Boolean): Boolean =
        enabled && accessAllowsSummon(access) && !blocked && !missedLatch

    /**
     * Whether a stopped TV may advertise `ready`. Low Power Standby cuts the network
     * only while the screen is off, so it matters only then.
     */
    fun backgroundReady(
        armed: Boolean,
        missedLatch: Boolean,
        screenOff: Boolean,
        lowPowerStandby: Boolean,
        readyWhileScreenOff: Boolean = ADVERTISE_READY_WHILE_SCREEN_OFF,
    ): Boolean = armed && !missedLatch && !(screenOff && (lowPowerStandby || !readyWhileScreenOff))

    /**
     * Whether an idle Flick may let the screen sleep: only when a sleeping TV still
     * hears the next cast. Low Power Standby cuts the network once the screen is off.
     */
    fun idleMayRest(
        armed: Boolean,
        lowPowerStandby: Boolean,
        readyWhileScreenOff: Boolean = ADVERTISE_READY_WHILE_SCREEN_OFF,
    ): Boolean = armed && readyWhileScreenOff && !lowPowerStandby

    /**
     * Whether an ON_STOP counts as the viewer leaving a cast. A stop the TV's own
     * sleep caused left no app in front for the latch to protect.
     */
    fun latchesOnStop(castLive: Boolean, interactive: Boolean): Boolean = castLive && interactive

    fun stoppedAdvertState(backgroundReady: Boolean, addressCurrent: Boolean): String =
        if (backgroundReady && addressCurrent) NsdAdvertiser.STATE_READY else NsdAdvertiser.STATE_SLEEPING

    /** Grantable reads NeedsAccess whatever `enabled` says: the press saves the intent before the grant lands. */
    fun openForCastsRow(enabled: Boolean, access: OverlayAccess, blocked: Boolean): OpenForCastsRow = when {
        access == OverlayAccess.Unavailable -> OpenForCastsRow.Hidden
        access == OverlayAccess.Grantable -> OpenForCastsRow.NeedsAccess
        !enabled -> OpenForCastsRow.Off
        blocked -> OpenForCastsRow.Blocked
        else -> OpenForCastsRow.On
    }

    /**
     * A trampoline that resumed proves the launch was allowed, so its miss is never
     * the platform's block. A TV that was asleep may block only while asleep, which
     * is not evidence against the next wake.
     */
    fun classifyMiss(wakeResumed: Boolean, interactiveAtIssue: Boolean): SummonMiss = when {
        wakeResumed -> SummonMiss.Late
        !interactiveAtIssue -> SummonMiss.Asleep
        else -> SummonMiss.Blocked
    }

    fun outcomeOf(miss: SummonMiss): SummonOutcome = when (miss) {
        SummonMiss.Blocked -> SummonOutcome.Blocked
        SummonMiss.Late -> SummonOutcome.Late
        SummonMiss.Asleep -> SummonOutcome.Asleep
    }
}

/** How long a stopped cast may wait for the Activity, against the 18 s startup deadline. */
object SummonWaitPolicy {
    const val MAX_WAIT_MS = 8_000L

    /** Left for the first frame, so STARTUP_TIMEOUT can never beat the tv_backgrounded verdict. */
    const val FIRST_FRAME_RESERVE_MS = 8_000L

    fun budgetMs(nowMs: Long, startupDeadlineMs: Long): Long =
        minOf(MAX_WAIT_MS, startupDeadlineMs - nowMs - FIRST_FRAME_RESERVE_MS).coerceAtLeast(0L)
}

/**
 * Consecutive launches the platform silently refused while the TV was awake. At
 * [LIMIT] the feature disarms itself rather than make every cast wait out the budget.
 */
object SummonStrikePolicy {
    const val LIMIT = 2

    fun afterOutcome(strikes: Int, outcome: SummonOutcome): Int = when (outcome) {
        SummonOutcome.Blocked -> strikes + 1
        SummonOutcome.Opened, SummonOutcome.Late -> 0
        SummonOutcome.Asleep -> strikes
    }

    fun blockedNow(strikes: Int): Boolean = strikes >= LIMIT

    /** A restored backup, an OS update or an app update is a different platform, so the record starts over. */
    fun blockStillValid(storedFingerprint: String, storedVersion: Long, fingerprint: String, version: Long): Boolean =
        storedFingerprint == fingerprint && storedVersion == version
}
