package com.flick.receiver.summon

import android.app.Activity
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.withFrameNanos
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import com.flick.receiver.BuildConfig
import com.flick.receiver.session.ForegroundRequest
import com.flick.receiver.util.FlickLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Open when you cast": brings MainActivity to the front for a cast adopted while
 * it is stopped, and keeps [CastReadyService] running while the feature can work.
 *
 * Composition-scoped and main-thread only. Every decision is [SummonPolicy] or
 * [SummonLedger]; this class reads the platform and applies their verdicts.
 *
 * Off (the default) must be indistinguishable from a TV without the feature: no
 * service, no watchers, no wake lock, and no binder call on the cast path.
 */
class ForegroundSummoner(
    private val appContext: Context,
    private val lifecycle: Lifecycle,
    private val scope: CoroutineScope,
    private val addressCurrent: () -> Boolean,
) : ForegroundRequest {

    private val store = OpenForCastsStore(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val keyguardManager = appContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    private val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val appOps = appContext.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    private val packageManager = appContext.packageManager
    private val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        .apply { setReferenceCounted(false) }

    private val ledger: SummonLedger
    private var access = OverlayAccess.Unavailable

    /** False for this process once the overlay Settings screen has failed to open twice. */
    private var settingsResolvable = true
    private var screenOff = false
    private var lowPowerStandby = false
    private var started = false
    private var watchersRegistered = false
    private var serviceRequested = false
    private var runningJob: Job? = null
    private var awaitedId = 0L

    /** elapsedRealtime − uptimeMillis at the newest hold; the growth of that gap is time spent suspended. */
    private var sleepBaseMs: Long? = null

    private val resumedListener: (Long) -> Unit = ::onWakeResumed

    private val _armed = MutableStateFlow(false)
    private val _idleMayRest = MutableStateFlow(false)
    private val _backgroundReady = MutableStateFlow(false)
    private val _row = MutableStateFlow(OpenForCastsRow.Hidden)

    /** The service should run and does: a cast arriving now can open Flick. */
    val armed: StateFlow<Boolean> = _armed

    /** Armed, and a TV that sleeps now can still be reached by the next cast. */
    val idleMayRest: StateFlow<Boolean> = _idleMayRest

    /** Whether a stopped Flick may advertise `ready`. */
    val backgroundReady: StateFlow<Boolean> = _backgroundReady

    val row: StateFlow<OpenForCastsRow> = _row

    // Binder thread.
    private val opListener = AppOpsManager.OnOpChangedListener { _, _ ->
        mainHandler.post { onOverlayOpChanged() }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> screenOff = true
                Intent.ACTION_SCREEN_ON -> screenOff = false
                PowerManager.ACTION_LOW_POWER_STANDBY_ENABLED_CHANGED -> lowPowerStandby = lowPowerStandbyEnabled()
                else -> return
            }
            publish()
        }
    }

    init {
        val strikeRecordStale = !SummonStrikePolicy.blockStillValid(
            store.strikeFingerprint,
            store.strikeVersion,
            Build.FINGERPRINT,
            BuildConfig.VERSION_CODE.toLong(),
        )
        if ((store.strikes > 0 || store.blocked) && strikeRecordStale) store.clearStrikes()
        ledger = SummonLedger(store.strikes, store.blocked)
        recomputeAccess()
        publish()
    }

    fun start() {
        started = true
        SummonSignals.onResumed = resumedListener
        runningJob?.cancel()
        runningJob = scope.launch { CastReadyService.running.collect { publish() } }
        syncWatchers()
        publish()
    }

    fun stop(changingConfigurations: Boolean) {
        started = false
        runningJob?.cancel()
        runningJob = null
        syncWatchers()
        if (SummonSignals.onResumed === resumedListener) SummonSignals.onResumed = null
        if (wakeLock.isHeld) wakeLock.release()
        sleepBaseMs = null
        // A configuration relaunch rebuilds the composition in this same process;
        // stopping here would drop it out of foreground-service priority in between.
        if (!changingConfigurations) {
            CastReadyService.stop(appContext)
            serviceRequested = false
        }
    }

    /** First thing in ON_START. */
    fun onForeground() {
        ledger.foreground()
        recomputeAccess()
        // Back from Settings without the grant, or the grant revoked since: off, so
        // a grant made later for any other reason never arms it unasked.
        if (access == OverlayAccess.Grantable && store.enabled) store.enabled = false
        reconcileService()
        publish()
        FlickLog.i(
            "summon",
            "access=$access enabled=${store.enabled} blocked=${ledger.blocked} armed=${_armed.value}",
        )
    }

    /** In ON_STOP, after the teardown terminal and before the NSD advert is chosen. */
    fun onBackground(castLive: Boolean, beforeReady: Boolean) {
        if (!store.enabled) return
        if (!SummonPolicy.latchesOnStop(castLive, powerManager.isInteractive)) return
        val reason = ledger.leftDuringCast(now(), beforeReady)
        FlickLog.i("summon", "latched reason=${reason.wire}")
        publish()
    }

    fun onRowPressed(activity: Activity) {
        val from = _row.value
        when (from) {
            OpenForCastsRow.Off -> store.enabled = true
            OpenForCastsRow.On -> store.enabled = false
            OpenForCastsRow.Blocked -> {
                ledger.unblock()
                persistStrikes()
            }
            // Saved before the trip to Settings, so a process killed on the way still comes back on.
            OpenForCastsRow.NeedsAccess -> store.enabled = true
            OpenForCastsRow.Hidden -> return
        }
        FlickLog.i("summon", "row pressed from=${from.name}")
        reconcileService()
        publish()
        if (from == OpenForCastsRow.NeedsAccess) openOverlaySettings(activity)
    }

    override fun holdAwake(): Long {
        if (!store.enabled) return 0L
        recomputeAccess()
        val hold = SummonPolicy.holdAwakeForProbe(
            enabled = true,
            access = access,
            blocked = ledger.blocked,
            missedLatch = ledger.missedLatch,
        )
        if (!hold) return 0L
        val token = ledger.takeHold()
        sleepBaseMs = suspendedGapMs()
        runCatching { wakeLock.acquire(SummonPolicy.WAKE_LOCK_TIMEOUT_MS) }
        return token
    }

    override fun release(token: Long) {
        if (!ledger.releaseHold(token)) return
        if (wakeLock.isHeld) wakeLock.release()
        sleepBaseMs = null
    }

    override fun request(): Boolean {
        if (!store.enabled) {
            FlickLog.i("summon", "refused reason=${SummonRefusal.Off.wire}")
            return false
        }
        recomputeAccess()
        val refusal = SummonPolicy.refusal(
            enabled = true,
            access = access,
            blocked = ledger.blocked,
            addressCurrent = addressCurrent(),
            deviceLocked = keyguardManager.isDeviceLocked,
            missedLatch = ledger.missedLatch,
        )
        if (refusal != null) {
            FlickLog.i("summon", "refused reason=${refusal.wire}")
            return false
        }

        val attempt = ledger.issue(now(), powerManager.isInteractive)
        SummonSignals.issue(attempt.id)
        FlickLog.i(
            "summon",
            "request attempt=${attempt.id} sdk=${Build.VERSION.SDK_INT} " +
                "interactive=${attempt.interactiveAtIssue} svc=${CastReadyService.running.value} sleptMs=${sleptMs()}",
        )
        // CLEAR_TASK only ever clears the trampoline's own task (its own affinity),
        // replacing a previous WakeActivity that is still waiting.
        val launched = runCatching {
            appContext.startActivity(
                Intent(appContext, WakeActivity::class.java)
                    .putExtra(SummonSignals.EXTRA_ATTEMPT, attempt.id)
                    .putExtra(SummonSignals.EXTRA_ISSUED_AT, attempt.issuedAtMs)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION,
                    ),
            )
        }.isSuccess
        if (!launched) {
            ledger.abandon(attempt.id)
            SummonSignals.abandon(attempt.id)
            FlickLog.i("summon", "refused reason=start_threw")
            return false
        }
        // A silently blocked launch also lands here; the caller's Lifecycle check decides.
        return true
    }

    override suspend fun awaitStarted(timeoutMs: Long): Boolean {
        val id = ledger.inFlight?.id ?: return false
        awaitedId = id
        try {
            val reached = withTimeoutOrNull(timeoutMs) {
                lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.STARTED) }
            } != null
            // STARTED can land in the same main-thread turn as the timeout; the
            // registry's own state is set before its observers run.
            if (!reached && !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return false
            ledger.opened(id, now())?.let { waited ->
                persistStrikes()
                FlickLog.i("summon", "opened attempt=$id waitedMs=$waited")
            }
            // One composed frame before playback starts, so the connecting screen and
            // the video surface exist; the clock ON_START resumed drives it.
            if (currentCoroutineContext()[MonotonicFrameClock] != null) {
                withTimeoutOrNull(SummonPolicy.FRAME_SETTLE_MS) { withFrameNanos { } }
            }
            return true
        } catch (e: CancellationException) {
            if (ledger.abandon(id)) {
                SummonSignals.abandon(id)
                FlickLog.i("summon", "abandoned attempt=$id")
            }
            throw e
        }
    }

    override fun missed() {
        val verdict = ledger.missed(awaitedId, now(), wakeResumed = SummonSignals.resumed(awaitedId)) ?: return
        persistStrikes()
        if (verdict.blockedNow) {
            reconcileService()
            FlickLog.i("summon", "blocked strikes=${verdict.strikes}")
        }
        FlickLog.i(
            "summon",
            "missed attempt=${verdict.attempt.id} kind=${verdict.kind.wire} waitedMs=${verdict.waitedMs} " +
                "strikes=${verdict.strikes} sleptMs=${sleptMs()}",
        )
        publish()
    }

    /** A trampoline scored Blocked resumed after all; the service restarts only at the next ON_START. */
    private fun onWakeResumed(attemptId: Long) {
        val correction = ledger.wakeResumed(attemptId) ?: return
        persistStrikes()
        FlickLog.i("summon", "corrected attempt=${correction.attemptId} kind=late unblocked=${correction.unblocked}")
        if (correction.unblocked) reconcileService()
        publish()
    }

    private fun syncWatchers() {
        val want = started && store.enabled && access != OverlayAccess.Unavailable
        if (want == watchersRegistered) return
        if (want) registerWatchers() else unregisterWatchers()
    }

    private fun registerWatchers() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, appContext.packageName, opListener)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                addAction(PowerManager.ACTION_LOW_POWER_STANDBY_ENABLED_CHANGED)
            }
        }
        ContextCompat.registerReceiver(appContext, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        watchersRegistered = true
        screenOff = !powerManager.isInteractive
        lowPowerStandby = lowPowerStandbyEnabled()
    }

    private fun unregisterWatchers() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { appOps.stopWatchingMode(opListener) }
        }
        runCatching { appContext.unregisterReceiver(screenReceiver) }
        watchersRegistered = false
    }

    private fun onOverlayOpChanged() {
        if (!watchersRegistered) return
        val before = access
        recomputeAccess()
        reconcileService()
        publish()
        if (access != before) FlickLog.i("summon", "access changed access=$access")
    }

    /** Stops may happen in any state; a connectedDevice service may only start while STARTED. */
    private fun reconcileService() {
        val should = SummonPolicy.serviceShouldRun(store.enabled, access, ledger.blocked)
        val running = CastReadyService.running.value
        if (should && !running && started && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            if (CastReadyService.start(appContext)) {
                serviceRequested = true
                FlickLog.i("summon", "service start")
            }
        } else if (!should && (running || serviceRequested)) {
            CastReadyService.stop(appContext)
            serviceRequested = false
            FlickLog.i("summon", "service stop reason=${stopReason()}")
        }
    }

    private fun stopReason(): String = when {
        !store.enabled -> "off"
        !SummonPolicy.accessAllowsSummon(access) -> "no_access"
        else -> "blocked"
    }

    private fun openOverlaySettings(activity: Activity) {
        // No NEW_TASK: in Flick's own task, Back returns to Flick even when TV
        // Settings already has a task with a back stack of its own. Some TVs ignore
        // the package data and open the full list; some do not resolve it at all.
        val general = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        val forFlick = Intent(general).setData(Uri.parse("package:${appContext.packageName}"))
        val opened = runCatching { activity.startActivity(forFlick) }.isSuccess ||
            runCatching { activity.startActivity(general) }.isSuccess
        if (opened) return
        settingsResolvable = false
        store.enabled = false
        recomputeAccess()
        publish()
        FlickLog.i("summon", "settings unavailable")
    }

    private fun persistStrikes() {
        store.saveStrikes(ledger.strikes, ledger.blocked, Build.FINGERPRINT, BuildConfig.VERSION_CODE.toLong())
    }

    private fun recomputeAccess() {
        access = SummonPolicy.overlayAccess(
            sdkInt = Build.VERSION.SDK_INT,
            canDrawOverlays = canDrawOverlays(),
            lowRamDevice = activityManager.isLowRamDevice,
            settingsResolvable = settingsResolvable && overlaySettingsResolves(),
            fireTv = packageManager.hasSystemFeature(FIRE_TV_FEATURE),
        )
    }

    /**
     * Some builds report a stale canDrawOverlays right after the op changes; the
     * op's own mode is authoritative whenever it is explicit.
     */
    private fun canDrawOverlays(): Boolean {
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, Process.myUid(), appContext.packageName)
            }.getOrDefault(AppOpsManager.MODE_DEFAULT)
        } else {
            AppOpsManager.MODE_DEFAULT
        }
        return when (mode) {
            AppOpsManager.MODE_ALLOWED -> true
            AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED -> false
            else -> Settings.canDrawOverlays(appContext)
        }
    }

    private fun overlaySettingsResolves(): Boolean {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            packageManager.resolveActivity(intent, 0)
        }
        return resolved != null
    }

    private fun publish() {
        syncWatchers()
        val shouldRun = SummonPolicy.serviceShouldRun(store.enabled, access, ledger.blocked)
        val armed = SummonPolicy.armed(shouldRun, CastReadyService.running.value)
        _armed.value = armed
        _idleMayRest.value = SummonPolicy.idleMayRest(armed, lowPowerStandby)
        _backgroundReady.value = SummonPolicy.backgroundReady(armed, ledger.missedLatch, screenOff, lowPowerStandby)
        _row.value = SummonPolicy.openForCastsRow(store.enabled, access, ledger.blocked)
    }

    private fun lowPowerStandbyEnabled(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && powerManager.isLowPowerStandbyEnabled

    private fun suspendedGapMs(): Long = SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()

    private fun sleptMs(): Long = sleepBaseMs?.let { suspendedGapMs() - it } ?: -1L

    private fun now(): Long = SystemClock.elapsedRealtime()

    private companion object {
        const val WAKE_LOCK_TAG = "flick:summon"
        const val FIRE_TV_FEATURE = "amazon.hardware.fire_tv"
    }
}
